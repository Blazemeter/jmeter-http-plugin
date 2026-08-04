package com.blazemeter.jmeter.http2.control.async;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
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

  private static final long WATCHDOG_MILLIS = 3_000L;
  private static final int RESPONSE_TIMEOUT_MILLIS = 200;
  private static final int SUB_RESULT_SCAN_CAP = 20_000;

  private StallingPeer peer;

  @Before
  public void setUp() throws IOException {
    peer = new StallingPeer();
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
  }

  /**
   * Walks the sub-result graph with an identity set and a cap. Both are needed: the error sub-results
   * are built with the {@code HTTPSampleResult(HTTPSampleResult)} copy constructor from the container
   * that already holds the previous ones, so the structure is deep and self-referential enough that a
   * plain recursive count blows its own stack, which is itself a symptom.
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

  /** Exposes the protected embedded-resource download so the drain loop can be driven directly. */
  private static final class ProbeSampler extends HTTP2Sampler {

    private static final long serialVersionUID = 1L;

    HTTPSampleResult drainEmbeddedResources(HTTPSampleResult page) {
      return downloadPageResources(page, null, 0);
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
