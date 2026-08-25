package com.blazemeter.jmeter.http2.control.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.core.SampleClock;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The concurrent embedded-resource drain loop in {@code HTTP2Sampler.downloadPageResources} shares
 * the busy-wait shape of the controller's queue: it inspects {@code samplers.get(0)} but only
 * removes it on the success path. When the response-timeout escape fires instead, the same sampler is
 * re-selected with an unchanged deadline, so the timeout fires again immediately and another error
 * sub-result is appended, without bound.
 *
 * <p>Reproduced against a local socket that completes the TCP handshake and then never writes a
 * response, which is what a stalled or half-dead peer looks like. Nothing leaves the loopback
 * interface and no HTTP server is involved.
 */
public class EmbeddedResourceDrainLoopTest extends HTTP2TestBase {

  private static final long WATCHDOG_MILLIS = 15_000L;
  private static final int RESPONSE_TIMEOUT_MILLIS = 200;
  private static final int SUB_RESULT_SCAN_CAP = 20_000;

  private StallingPeer peer;

  @Before
  public void setUp() throws Exception {
    peer = new StallingPeer();
    // Cold Jetty/QUIC client init can take several seconds on a loaded CI agent. Warm it on this
    // thread so the drain worker is measured against the timeout logic, not first-time startup.
    warmJettyHttpClient();
  }

  private void warmJettyHttpClient() throws Exception {
    HTTP2Sampler warmer = new HTTP2Sampler();
    warmer.setName("jetty-warmup");
    warmer.setProtocol("http");
    warmer.setDomain(peer.host());
    warmer.setPort(peer.port());
    warmer.setPath("/warmup");
    warmer.setMethod(HTTPConstants.GET);
    warmer.setSyncRequest(false);
    warmer.setResponseTimeout(String.valueOf(RESPONSE_TIMEOUT_MILLIS));
    warmer.setConnectTimeout(String.valueOf(RESPONSE_TIMEOUT_MILLIS));
    // Fire-and-forget: we only need the client stack constructed. Cancel so the stalling peer does
    // not keep the warmup request around for the rest of the test.
    warmer.sample();
    HTTP2FutureResponseListener listener = warmer.getFutureResponseListener();
    if (listener != null) {
      listener.cancel(true);
    }
  }

  @After
  public void tearDown() {
    peer.close();
  }

  @Test
  public void anEmbeddedResourceThatNeverAnswersMustNotSpinAppendingErrorSubResults()
      throws Exception {
    ProbeSampler sampler = new ProbeSampler();
    sampler.setName("main-page");
    sampler.setProtocol("http");
    sampler.setDomain(peer.host());
    sampler.setPort(peer.port());
    sampler.setPath("/");
    sampler.setMethod(HTTPConstants.GET);
    sampler.setConcurrentDwn(true);
    sampler.setConcurrentPool("2");
    sampler.setImageParser(true);
    sampler.setResponseTimeout(String.valueOf(RESPONSE_TIMEOUT_MILLIS));

    HTTPSampleResult page = new HTTPSampleResult();
    page.setSampleLabel("main-page");
    page.setContentType("text/html; charset=UTF-8");
    page.setDataType(SampleResult.TEXT);
    page.setURL(new java.net.URL("http", peer.host(), peer.port(), "/"));
    page.sampleStart();
    page.setResponseData(
        "<html><body><img src=\"/stalls-forever.png\"></body></html>",
        StandardCharsets.UTF_8.name());
    page.setResponseCodeOK();
    page.setSuccessful(true);
    page.sampleEnd();

    AtomicReference<HTTPSampleResult> drained = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker = new Thread(() -> {
      try {
        drained.set(sampler.drainEmbeddedResources(page));
      } catch (Throwable t) {
        failure.set(t);
      }
    }, "embedded-resource-drain");
    worker.setDaemon(true);
    worker.start();
    worker.join(WATCHDOG_MILLIS);
    boolean stillRunning = worker.isAlive();
    if (stillRunning) {
      worker.interrupt();
      worker.join(2_000L);
    }

    HTTPSampleResult container = drained.get() == null ? page : drained.get();
    int directSubResults = container.getSubResults() == null ? 0 : container.getSubResults().length;
    System.out.println("\n=== embedded resource that never answers ==="
        + "\n  responseTimeout=" + RESPONSE_TIMEOUT_MILLIS + "ms"
        + " watchdog=" + WATCHDOG_MILLIS + "ms"
        + "\n  still running after watchdog=" + stillRunning
        + "\n  direct sub-results=" + directSubResults
        + " reachable nodes=" + describeReachable(container)
        + "\n  failure=" + failure.get());

    assertThat(stillRunning)
        .as("the drain loop must give up on a resource that timed out instead of re-selecting the "
            + "same queue head forever; with a %d ms response timeout it should finish in well "
            + "under %d ms, but it was still running and had appended %d direct sub-results",
            RESPONSE_TIMEOUT_MILLIS, WATCHDOG_MILLIS, directSubResults)
        .isFalse();
    assertThat(directSubResults)
        .as("one timed-out embedded resource must produce one error sub-result, not one per poll")
        .isLessThanOrEqualTo(2);
    assertThat(failure.get())
        .as("timeout handling must not throw (including StackOverflowError from a cyclic "
            + "sub-result graph)")
        .isNull();
    assertThatCode(() -> recursiveWalkWithoutIdentitySet(container))
        .as("timeout error sub-results must not form a self-referential graph that overflows a "
            + "naive tree walk (View Results Tree)")
        .doesNotThrowAnyException();

    SampleResult[] subs = container.getSubResults();
    assertThat(subs).isNotNull();
    HTTPSampleResult timedOutChild = null;
    for (SampleResult sub : subs) {
      HTTPSampleResult httpSub = (HTTPSampleResult) sub;
      if (httpSub.getURL() != null
          && httpSub.getURL().getPath().contains("stalls-forever.png")) {
        timedOutChild = httpSub;
        break;
      }
    }
    assertThat(timedOutChild)
        .as("the timed-out embedded resource must be reported under its own URL, not as a clone "
            + "of the parent page (JMeter HC4 socket-timeout shape)")
        .isNotNull();
    assertThat(timedOutChild.isSuccessful()).isFalse();
    assertThat(timedOutChild.getResponseMessage())
        .as("timeout should surface like JMeter's socket read timeout")
        .containsIgnoringCase("timed out");
    assertThat(container.isSuccessful())
        .as("the page container must be marked failed when an embedded resource times out")
        .isFalse();
    assertThat(container.getResponseMessage())
        .as("parent message should aggregate failed embedded URLs like JMeter")
        .contains("Embedded resource download error:")
        .contains("stalls-forever.png");
    assertThat(timedOutChild.getURL().getPath())
        .isNotEqualTo(page.getURL().getPath());
    assertThat(timedOutChild.getTime())
        .as("timed-out child must report the wait until give-up, not a ~0ms sampleStart/sampleEnd "
            + "pair; that elapsed end time is what pushes the parent container's clock forward")
        .isGreaterThanOrEqualTo(RESPONSE_TIMEOUT_MILLIS);
    // On the container's own clock, the way addSubResult itself compares the two (Bug 51855):
    // each SampleResult captures its nano offset when it is constructed, so two results built on
    // either side of a refresh of that offset are a millisecond apart on the raw stamps alone.
    assertThat(container.getEndTime())
        .as("parent end time must advance to cover the embedded timeout wait (addSubResult takes "
            + "max of child end times)")
        .isGreaterThanOrEqualTo(
            SampleClock.fromResultClock(container, timedOutChild, timedOutChild.getEndTime()));
    assertThat(timedOutChild.getConnectTime())
        .as("a poll-aborted attempt never completed the handshake/response, so connect stays 0 "
            + "rather than copying the parent's connect")
        .isZero();
    assertThat(timedOutChild.getLatency()).isZero();
    assertThat(timedOutChild.getSampleLabel())
        .as("timeout child must not keep the default sampler class name; addSubResult may rename "
            + "to parent-N when SampleResult.isRenameSampleLabel() is on")
        .doesNotContain("bzm - HTTP Sampler");
    assertThat(timedOutChild.getUrlAsString()).contains("stalls-forever.png");
  }

  /**
   * When the concurrent pool is full and the slot wait times out, previously we aborted the page
   * and never dispatched remaining URLs. JMeter keeps scheduling; we must fail only in-flight work
   * and continue the URL list.
   */
  @Test
  public void slotTimeoutMustStillDispatchRemainingEmbeddedUrls() throws Exception {
    ProbeSampler sampler = new ProbeSampler();
    sampler.setName("main-page");
    sampler.setProtocol("http");
    sampler.setDomain(peer.host());
    sampler.setPort(peer.port());
    sampler.setPath("/");
    sampler.setMethod(HTTPConstants.GET);
    sampler.setConcurrentDwn(true);
    // Pool size 1 forces serial mode in downloadPageResources; use 2 so slot waits can fire.
    sampler.setConcurrentPool("2");
    sampler.setImageParser(true);
    sampler.setResponseTimeout(String.valueOf(RESPONSE_TIMEOUT_MILLIS));

    HTTPSampleResult page = new HTTPSampleResult();
    page.setSampleLabel("main-page");
    page.setContentType("text/html; charset=UTF-8");
    page.setDataType(SampleResult.TEXT);
    page.setURL(new java.net.URL("http", peer.host(), peer.port(), "/"));
    page.sampleStart();
    page.setResponseData(
        "<html><body>"
            + "<img src=\"/stall-a.png\">"
            + "<img src=\"/stall-b.png\">"
            + "<img src=\"/stall-c.png\">"
            + "</body></html>",
        StandardCharsets.UTF_8.name());
    page.setResponseCodeOK();
    page.setSuccessful(true);
    page.sampleEnd();

    AtomicReference<HTTPSampleResult> drained = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker = new Thread(() -> {
      try {
        drained.set(sampler.drainEmbeddedResources(page));
      } catch (Throwable t) {
        failure.set(t);
      }
    }, "embedded-slot-timeout");
    worker.setDaemon(true);
    worker.start();
    worker.join(WATCHDOG_MILLIS);
    boolean stillRunning = worker.isAlive();
    if (stillRunning) {
      worker.interrupt();
      worker.join(2_000L);
    }

    assertThat(stillRunning).isFalse();
    assertThat(failure.get()).isNull();
    HTTPSampleResult container = drained.get() == null ? page : drained.get();
    SampleResult[] subs = container.getSubResults();
    assertThat(subs).isNotNull();

    java.util.Set<String> timedOutPaths = new java.util.HashSet<>();
    for (SampleResult sub : subs) {
      HTTPSampleResult httpSub = (HTTPSampleResult) sub;
      if (httpSub.getURL() != null && httpSub.getURL().getPath().startsWith("/stall-")) {
        timedOutPaths.add(httpSub.getURL().getPath());
        assertThat(httpSub.isSuccessful()).isFalse();
      }
    }
    assertThat(timedOutPaths)
        .as("after a slot timeout, remaining undispatched URLs must still be attempted "
            + "(reported as failed children), not abandoned")
        .contains("/stall-a.png", "/stall-b.png", "/stall-c.png");
  }

  /**
   * If async dispatch fails before a listener is published, the error result must be attached
   * immediately. Queuing a sampler with a null listener used to drop it silently in the drain.
   */
  @Test
  public void failedEmbeddedDispatchWithoutListenerMustAttachErrorSubResult() throws Exception {
    HTTP2JettyClient failingClient = org.mockito.Mockito.mock(HTTP2JettyClient.class);
    org.mockito.Mockito.when(failingClient.getMaxBufferSize()).thenReturn(1024 * 1024);
    org.mockito.Mockito.when(failingClient.getRequestTimeout()).thenReturn(60_000);
    org.mockito.Mockito.when(failingClient.dispatchAsync(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IOException("forced dispatch failure for regression test"));

    ProbeSampler sampler = new FailingDispatchProbeSampler(failingClient);
    sampler.setName("main-page");
    sampler.setProtocol("http");
    sampler.setDomain(peer.host());
    sampler.setPort(peer.port());
    sampler.setPath("/");
    sampler.setMethod(HTTPConstants.GET);
    sampler.setConcurrentDwn(true);
    sampler.setConcurrentPool("4");
    sampler.setImageParser(true);
    sampler.setResponseTimeout(String.valueOf(RESPONSE_TIMEOUT_MILLIS));

    HTTPSampleResult page = new HTTPSampleResult();
    page.setSampleLabel("main-page");
    page.setContentType("text/html; charset=UTF-8");
    page.setDataType(SampleResult.TEXT);
    page.setURL(new java.net.URL("http", peer.host(), peer.port(), "/"));
    page.sampleStart();
    page.setResponseData(
        "<html><body><img src=\"/never-dispatched.png\"></body></html>",
        StandardCharsets.UTF_8.name());
    page.setResponseCodeOK();
    page.setSuccessful(true);
    page.sampleEnd();

    HTTPSampleResult container = sampler.drainEmbeddedResources(page);
    SampleResult[] subs = container.getSubResults();
    assertThat(subs)
        .as("dispatch failure must produce a visible child sample, not a silent drop")
        .isNotNull()
        .isNotEmpty();
    assertThat(container.isSuccessful()).isFalse();
    boolean sawFailure = false;
    for (SampleResult sub : subs) {
      if (!sub.isSuccessful()) {
        sawFailure = true;
        String detail = (sub.getResponseMessage() == null ? "" : sub.getResponseMessage())
            + " "
            + (sub.getResponseDataAsString() == null ? "" : sub.getResponseDataAsString());
        assertThat(detail).containsIgnoringCase("forced dispatch failure");
      }
    }
    assertThat(sawFailure).isTrue();
  }

  /**
   * Walks the sub-result graph with an identity set and a cap. Both are needed: before the
   * detached-error fix, the error sub-results were built with the
   * {@code HTTPSampleResult(HTTPSampleResult)} copy constructor from the container that already
   * holds the previous ones, so the structure is deep and self-referential enough that a plain
   * recursive count blows its own stack, which is itself a symptom.
   */
  private static String describeReachable(SampleResult result) {
    java.util.Set<SampleResult> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    java.util.Deque<SampleResult> pending = new java.util.ArrayDeque<>();
    pending.push(result);
    boolean capped = false;
    while (!pending.isEmpty()) {
      if (seen.size() >= SUB_RESULT_SCAN_CAP) {
        capped = true;
        break;
      }
      SampleResult current = pending.pop();
      if (!seen.add(current)) {
        continue;
      }
      SampleResult[] subs = current.getSubResults();
      if (subs != null) {
        for (SampleResult sub : subs) {
          pending.push(sub);
        }
      }
    }
    return seen.size() + (capped ? "+ (capped)" : "");
  }

  private static final int RECURSIVE_WALK_DEPTH_CAP = 10_000;

  private static int recursiveWalkWithoutIdentitySet(SampleResult node) {
    return recursiveWalkWithoutIdentitySet(node, 0);
  }

  private static int recursiveWalkWithoutIdentitySet(SampleResult node, int depth) {
    if (depth > RECURSIVE_WALK_DEPTH_CAP) {
      throw new StackOverflowError("sub-result walk exceeded depth " + RECURSIVE_WALK_DEPTH_CAP);
    }
    int count = 1;
    SampleResult[] subs = node.getSubResults();
    if (subs != null) {
      for (SampleResult sub : subs) {
        count += recursiveWalkWithoutIdentitySet(sub, depth + 1);
      }
    }
    return count;
  }

  /** Exposes the protected embedded-resource download so the drain loop can be driven directly. */
  private static class ProbeSampler extends HTTP2Sampler {

    private static final long serialVersionUID = 1L;

    HTTPSampleResult drainEmbeddedResources(HTTPSampleResult page) {
      return downloadPageResources(page, null, 0);
    }
  }

  private static final class FailingDispatchProbeSampler extends ProbeSampler {

    private static final long serialVersionUID = 1L;

    private final HTTP2JettyClient failingClient;

    FailingDispatchProbeSampler(HTTP2JettyClient failingClient) {
      this.failingClient = failingClient;
    }

    @Override
    protected HTTP2Sampler newHttpEmbeddedSampler() {
      return new HTTP2Sampler(() -> failingClient);
    }
  }

  /** Accepts connections and never writes a response, then closes them on shutdown. */
  private static final class StallingPeer implements Closeable {

    private final ServerSocket serverSocket;
    private final List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());
    private final Thread acceptor;
    private volatile boolean running = true;

    StallingPeer() throws IOException {
      serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
      acceptor = new Thread(() -> {
        while (running) {
          try {
            accepted.add(serverSocket.accept());
          } catch (IOException e) {
            return;
          }
        }
      }, "stalling-peer-acceptor");
      acceptor.setDaemon(true);
      acceptor.start();
    }

    String host() {
      return serverSocket.getInetAddress().getHostAddress();
    }

    int port() {
      return serverSocket.getLocalPort();
    }

    @Override
    public void close() {
      running = false;
      synchronized (accepted) {
        for (Socket socket : accepted) {
          try {
            socket.close();
          } catch (IOException e) {
            // shutting down
          }
        }
        accepted.clear();
      }
      try {
        serverSocket.close();
      } catch (IOException e) {
        // shutting down
      }
      acceptor.interrupt();
    }
  }
}
