package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClientTestIsolation;
import com.blazemeter.jmeter.http2.core.ProtocolErrorException;
import com.blazemeter.jmeter.http2.core.RequestsSentByThread;
import com.blazemeter.jmeter.http2.core.SampleClock;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Result;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * An async request that fails is collected by the async controller on a later turn, after the
 * requests queued ahead of it, possibly long after it failed. Its sample must still say how long
 * the request took - from dispatch to the failure - not how long it waited to be collected: a
 * refused connection reported as taking as long as the slowest request ahead of it is a response
 * time that never happened, and it lands in the label's average and percentiles.
 *
 * <p>"The failure" is the last attempt's: when collecting the request sends it again after it
 * failed (a GOAWAY retry, an HTTP/1.1 fallback) and that fails too, the time is part of the
 * request. Each path that sends it again is covered here by what the sample reports, not by how
 * that path tells the sampler, so a fallback added later is held to the same rule.
 */
public class AsyncFailureElapsedTimeTest extends HTTP2TestBase {

  private static final long COLLECTED_LATE_BY_MILLIS = 400;
  private static final long FAILED_FALLBACK_MILLIS = 300;
  /**
   * A time read on the wall clock and one translated from a sample's clock can disagree by the
   * truncation of each reading to whole milliseconds. See {@link SampleClock}.
   */
  private static final long CLOCK_TOLERANCE_MILLIS = 2;

  private HTTP2Sampler sampler;
  private HTTP2JettyClient realClient;
  private HttpClient probeClient;
  private StallingServer server;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  /**
   * A protocol property another class left behind ({@code httpJettyClient.enableHttp2=false}) makes
   * the client skip the h2c upgrade these tests depend on, so the 426 never comes and no fallback is
   * sent; a cached origin on a recycled port does the same.
   */
  @Before
  public void resetSharedClientState() throws Exception {
    HTTP2JettyClientTestIsolation.resetSharedClientState();
  }

  @After
  public void tearDown() throws Exception {
    if (sampler != null) {
      // Stops the Jetty client this thread cached for the sampler, so it does not outlive the test.
      sampler.threadFinished();
    }
    if (realClient != null) {
      realClient.stop();
    }
    if (probeClient != null) {
      probeClient.stop();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  public void failedAsyncRequestMustReportTheTimeToItsFailureNotToItsCollection()
      throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    sampler = new HTTP2Sampler();
    sampler.setName("refused");
    sampler.setSyncRequest(false);
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("127.0.0.1");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
    sampler.setPort(closedPort);
    sampler.setPath("/");
    URL url = new URL("http://127.0.0.1:" + closedPort + "/");

    assertThat(sampler.sample(url, HTTPConstants.GET, false, 0))
        .as("the dispatch turn reports nothing").isNull();
    HTTP2FutureResponseListener listener = sampler.getFutureResponseListener();
    assertThat(listener).as("the request went out").isNotNull();
    long deadline = System.currentTimeMillis() + 10_000;
    while (!listener.isDone() && System.currentTimeMillis() < deadline) {
      Thread.sleep(5);
    }
    assertThat(listener.isDone()).as("the connection was refused").isTrue();
    long failedAt = System.currentTimeMillis();
    // Collected late, as when the controller first collects the slower requests queued ahead.
    Thread.sleep(COLLECTED_LATE_BY_MILLIS);
    HTTPSampleResult result = sampler.sample(url, HTTPConstants.GET, false, 0);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(result.isSuccessful()).as("the refused request is an error").isFalse();
    // failedAt is a wall-clock reading and the sample keeps its own clock: compare on one clock.
    softly.assertThat(SampleClock.toWallClock(result, result.getEndTime()))
        .as("the sample ends when the request failed, not when it was collected")
        .isLessThanOrEqualTo(failedAt + CLOCK_TOLERANCE_MILLIS);
    softly.assertThat(result.getTime())
        .as("the elapsed time does not include the wait to be collected")
        .isLessThan(COLLECTED_LATE_BY_MILLIS);
    softly.assertThat(result.getTime()).as("and is not negative").isNotNegative();
    softly.assertThat(result.getIdleTime())
        .as("nothing was sent again, so the sample simply ends at the failure, with no idle time")
        .isZero();
    softly.assertAll();
  }

  @Test
  public void failedFallbackAfterAsyncFailureMustCountInTheElapsedTime() throws Exception {
    HTTP2JettyClient client = mockClient();
    when(client.dispatchAsync(any(), any(), any())).thenAnswer(invocation -> {
      HTTPSampleResult dispatched = invocation.getArgument(1);
      HTTP2FutureResponseListener listener = invocation.getArgument(2);
      dispatched.sampleStart();
      Request request = mock(Request.class);
      listener.setRequest(request);
      // The first attempt fails at once, with an error the sampler retries over HTTP/1.1.
      listener.onComplete(new Result(request, new ProtocolErrorException("protocol_error"), null));
      return request;
    });
    when(client.sampleFromListener(any(), any(), anyBoolean(), anyInt(), any()))
        .thenThrow(new ExecutionException(new ProtocolErrorException("protocol_error")));
    when(client.isProtocolErrorFallbackEnabled()).thenReturn(true);
    when(client.retryWithHTTP11Only(any(), any())).thenAnswer(invocation -> {
      // What the real client's request listener records when the fallback goes out.
      RequestsSentByThread.recordSent();
      Thread.sleep(FAILED_FALLBACK_MILLIS);
      throw new IOException("the HTTP/1.1 fallback failed too");
    });
    URL url = new URL("https://example.com/");

    assertThat(sampler.sample(url, HTTPConstants.GET, false, 0)).isNull();
    HTTPSampleResult result = sampler.sample(url, HTTPConstants.GET, false, 0);

    assertThat(result.isSuccessful()).as("both attempts failed").isFalse();
    assertThat(result.getTime())
        .as("the request failed when its fallback did, not when its first attempt did")
        .isGreaterThanOrEqualTo(FAILED_FALLBACK_MILLIS - CLOCK_TOLERANCE_MILLIS);
  }

  /**
   * The client's HTTP/1.1 fallback, sent while collecting, gets an HTTP error back: the response
   * ends the result, and the client still rethrows the first attempt's failure. The end the
   * response set must not carry the wait to be collected into the elapsed time.
   */
  @Test
  public void fallbackAnsweredWithAnHttpErrorWhileCollectingMustLeaveOutTheQueueWait()
      throws Exception {
    HTTP2JettyClient client = mockClientFailingAtOnceWithAProtocolError();
    when(client.sampleFromListener(any(), any(), anyBoolean(), anyInt(), any()))
        .thenAnswer(invocation -> {
          answerFallbackWithHttpError(invocation.getArgument(1));
          throw new ExecutionException(new ProtocolErrorException("protocol_error"));
        });
    assertHttpErrorFallbackTimeCountedAndQueueWaitLeftOut();
  }

  /** The same, with the HTTP/1.1 fallback the sampler sends itself once the client gives up. */
  @Test
  public void samplerFallbackAnsweredWithAnHttpErrorMustLeaveOutTheQueueWait() throws Exception {
    HTTP2JettyClient client = mockClientFailingAtOnceWithAProtocolError();
    when(client.sampleFromListener(any(), any(), anyBoolean(), anyInt(), any()))
        .thenThrow(new ExecutionException(new ProtocolErrorException("protocol_error")));
    when(client.isProtocolErrorFallbackEnabled()).thenReturn(true);
    when(client.retryWithHTTP11Only(any(), any())).thenAnswer(invocation -> {
      HTTPSampleResult result = invocation.getArgument(1);
      answerFallbackWithHttpError(result);
      return result;
    });
    assertHttpErrorFallbackTimeCountedAndQueueWaitLeftOut();
  }

  private HTTP2JettyClient mockClientFailingAtOnceWithAProtocolError() throws Exception {
    HTTP2JettyClient client = mockClient();
    when(client.dispatchAsync(any(), any(), any())).thenAnswer(invocation -> {
      HTTPSampleResult dispatched = invocation.getArgument(1);
      HTTP2FutureResponseListener listener = invocation.getArgument(2);
      dispatched.sampleStart();
      Request request = mock(Request.class);
      listener.setRequest(request);
      listener.onComplete(new Result(request, new ProtocolErrorException("protocol_error"), null));
      return request;
    });
    return client;
  }

  /**
   * What the real client does with a fallback that gets an HTTP error: the request listener counts
   * it, and the response sets the result's code and ends it, as {@code postContentResponse} does.
   */
  private static void answerFallbackWithHttpError(HTTPSampleResult result)
      throws InterruptedException {
    RequestsSentByThread.recordSent();
    Thread.sleep(FAILED_FALLBACK_MILLIS);
    result.setResponseCode("500");
    result.setSuccessful(false);
    result.sampleEnd();
  }

  private void assertHttpErrorFallbackTimeCountedAndQueueWaitLeftOut() throws Exception {
    URL url = new URL("https://example.com/");
    assertThat(sampler.sample(url, HTTPConstants.GET, false, 0)).isNull();
    Thread.sleep(COLLECTED_LATE_BY_MILLIS);
    long collectionStartedAt = System.nanoTime();
    HTTPSampleResult result = sampler.sample(url, HTTPConstants.GET, false, 0);
    long collectionMillis =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - collectionStartedAt);
    long collectionEndedAt = System.currentTimeMillis();

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(result.isSuccessful()).as("the fallback got an HTTP error").isFalse();
    softly.assertThat(result.getTime())
        .as("the elapsed time counts the fallback the collection sent")
        .isGreaterThanOrEqualTo(FAILED_FALLBACK_MILLIS - CLOCK_TOLERANCE_MILLIS);
    softly.assertThat(result.getTime())
        .as("and not the %d ms the request waited to be collected", COLLECTED_LATE_BY_MILLIS)
        .isLessThanOrEqualTo(collectionMillis + CLOCK_TOLERANCE_MILLIS);
    softly.assertThat(SampleClock.toWallClock(result, result.getEndTime()))
        .as("the sample keeps the end the fallback's response gave it")
        .isLessThanOrEqualTo(collectionEndedAt + CLOCK_TOLERANCE_MILLIS);
    softly.assertThat(result.getIdleTime())
        .as("and the wait to be collected is held as idle time, which JMeter leaves out")
        .isGreaterThanOrEqualTo(COLLECTED_LATE_BY_MILLIS - CLOCK_TOLERANCE_MILLIS);
    softly.assertAll();
  }

  @Test
  public void failureTranslatedJustBeforeTheStartMustNotFallBackToCollectionTime()
      throws Exception {
    HTTP2JettyClient client = mockClient();
    when(client.dispatchAsync(any(), any(), any())).thenAnswer(invocation -> {
      HTTPSampleResult dispatched = invocation.getArgument(1);
      HTTP2FutureResponseListener listener = invocation.getArgument(2);
      dispatched.sampleStart();
      // A completion that lands a millisecond before the start once put on the sample's clock, as
      // a sub-millisecond failure can, since each translation truncates two separate readings.
      long justBeforeStart =
          SampleClock.toWallClock(dispatched, dispatched.getStartTime()) - 1;
      listener.completeWith(mock(ContentResponse.class), 0, justBeforeStart);
      return mock(Request.class);
    });
    when(client.sampleFromListener(any(), any(), anyBoolean(), anyInt(), any()))
        .thenThrow(new ExecutionException(new IOException("Connection refused")));
    URL url = new URL("https://example.com/");

    assertThat(sampler.sample(url, HTTPConstants.GET, false, 0)).isNull();
    Thread.sleep(COLLECTED_LATE_BY_MILLIS);
    HTTPSampleResult result = sampler.sample(url, HTTPConstants.GET, false, 0);

    assertThat(result.getTime())
        .as("a failure at the start ends at the start, not at the late collection")
        .isLessThan(COLLECTED_LATE_BY_MILLIS)
        .isNotNegative();
  }

  /**
   * The h2c upgrade is refused with a 426, and the HTTP/1.1 fallback the client sends while
   * collecting the response fails too: the sample counts that fallback, and still leaves out the
   * wait to be collected.
   */
  @Test
  public void failedFallbackAfterARefusedH2cUpgradeMustCountInTheElapsedTime() throws Exception {
    server = new StallingServer(true);
    realClient = new HTTP2JettyClient(true, "refused-h2c-upgrade");
    realClient.start();
    assertFallbackTimeCountedAndQueueWaitLeftOut(realClient);
  }

  /**
   * The connection closes under the request, and the HTTP/1.1 fallback the client sends while
   * collecting the response fails too.
   */
  @Test
  public void failedFallbackAfterAClosedChannelMustCountInTheElapsedTime() throws Exception {
    server = new StallingServer(false);
    probeClient = new HttpClient();
    probeClient.start();
    realClient = new HTTP2JettyClient(false, "closed-channel") {
      @Override
      public Request dispatchAsync(HTTP2Sampler sampler, HTTPSampleResult result,
                                   HTTP2FutureResponseListener listener) {
        result.sampleStart();
        Request request = probeClient.newRequest(URI.create(server.url()));
        listener.setRequest(request);
        // Without a message, so that it is not mistaken for a protocol error and the HTTP/1.1
        // fallback taken while waiting for the response is the one under test.
        listener.onComplete(new Result(request, new ClosedChannelException(), null));
        return request;
      }
    };
    realClient.start();
    assertFallbackTimeCountedAndQueueWaitLeftOut(realClient);
  }

  private void assertFallbackTimeCountedAndQueueWaitLeftOut(HTTP2JettyClient client)
      throws Exception {
    sampler = new HTTP2Sampler(() -> client);
    sampler.setName("fallback");
    sampler.setSyncRequest(false);
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("127.0.0.1");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
    sampler.setPort(server.port());
    sampler.setPath("/");
    URL url = new URL(server.url());

    long dispatchedAt = System.nanoTime();
    assertThat(sampler.sample(url, HTTPConstants.GET, false, 0)).isNull();
    HTTP2FutureResponseListener listener = sampler.getFutureResponseListener();
    long deadline = System.currentTimeMillis() + 10_000;
    while (!listener.isDone() && System.currentTimeMillis() < deadline) {
      Thread.sleep(5);
    }
    assertThat(listener.isDone()).as("the first attempt ended").isTrue();
    long firstAttemptMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dispatchedAt);
    Thread.sleep(COLLECTED_LATE_BY_MILLIS);
    long collectionStartedAt = System.nanoTime();
    HTTPSampleResult result = sampler.sample(url, HTTPConstants.GET, false, 0);
    long collectionMillis =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - collectionStartedAt);
    long collectionEndedAt = System.currentTimeMillis();

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(result.isSuccessful()).as("every attempt failed").isFalse();
    softly.assertThat(collectionMillis)
        .as("the collection sent the request again, and the server stalled it")
        .isGreaterThanOrEqualTo(FAILED_FALLBACK_MILLIS - CLOCK_TOLERANCE_MILLIS);
    softly.assertThat(result.getTime())
        .as("the elapsed time counts the fallback the collection sent")
        .isGreaterThanOrEqualTo(collectionMillis - CLOCK_TOLERANCE_MILLIS);
    softly.assertThat(result.getTime())
        .as("and not the %d ms the request waited to be collected", COLLECTED_LATE_BY_MILLIS)
        .isLessThanOrEqualTo(firstAttemptMillis + collectionMillis + CLOCK_TOLERANCE_MILLIS);
    // The end is read by whoever spans several samples - the async controller's parent sample ends
    // at its latest child end - so it must be when the fallback really failed, not an end moved
    // back to make the elapsed time come out right.
    softly.assertThat(SampleClock.toWallClock(result, result.getEndTime()))
        .as("the sample ends when the fallback failed")
        .isBetween(collectionEndedAt - collectionMillis - CLOCK_TOLERANCE_MILLIS
                + FAILED_FALLBACK_MILLIS,
            collectionEndedAt + CLOCK_TOLERANCE_MILLIS);
    softly.assertThat(result.getIdleTime())
        .as("and the wait to be collected is held as idle time, which JMeter leaves out")
        .isGreaterThanOrEqualTo(COLLECTED_LATE_BY_MILLIS - CLOCK_TOLERANCE_MILLIS);
    softly.assertAll();
  }

  /**
   * Answers HTTP/1.1 by hand. Every connection is held for {@link #FAILED_FALLBACK_MILLIS} and then
   * closed without a response, so a request sent to it fails that long after it went out - except,
   * when {@code refuseFirstUpgrade}, the first one, which gets a 426 refusing the h2c upgrade.
   */
  private static final class StallingServer implements AutoCloseable {

    private final ServerSocket socket;
    private final Thread acceptor;

    private StallingServer(boolean refuseFirstUpgrade) throws IOException {
      // The address url() and the samplers connect to: the loopback address is ::1 when the JVM
      // prefers IPv6, and the requests would then be refused instead of reaching this server.
      socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
      acceptor = new Thread(() -> {
        boolean refuseNext = refuseFirstUpgrade;
        while (!socket.isClosed()) {
          try {
            Socket connection = socket.accept();
            boolean refuse = refuseNext;
            refuseNext = false;
            Thread handler = new Thread(() -> serve(connection, refuse), "stalling-server-conn");
            handler.setDaemon(true);
            handler.start();
          } catch (IOException closed) {
            return;
          }
        }
      }, "stalling-server");
      acceptor.setDaemon(true);
      acceptor.start();
    }

    private static void serve(Socket connection, boolean refuseUpgrade) {
      try (Socket s = connection) {
        readRequestHead(s.getInputStream());
        if (refuseUpgrade) {
          s.getOutputStream().write(("HTTP/1.1 426 Upgrade Required\r\n"
              + "Content-Length: 0\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
          s.getOutputStream().flush();
        } else {
          Thread.sleep(FAILED_FALLBACK_MILLIS);
        }
      } catch (IOException | InterruptedException ignored) {
        // The connection is closed either way, which is all the request gets.
      }
    }

    private static void readRequestHead(InputStream in) throws IOException {
      int matched = 0;
      byte[] end = {'\r', '\n', '\r', '\n'};
      int b;
      while (matched < end.length && (b = in.read()) != -1) {
        matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
      }
    }

    private int port() {
      return socket.getLocalPort();
    }

    private String url() {
      return "http://127.0.0.1:" + port() + "/";
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  private HTTP2JettyClient mockClient() {
    HTTP2JettyClient client = mock(HTTP2JettyClient.class);
    when(client.getMaxBufferSize()).thenReturn(1024 * 1024);
    when(client.getRequestTimeout()).thenReturn(0);
    sampler = new HTTP2Sampler(() -> client);
    sampler.setName("mocked");
    sampler.setSyncRequest(false);
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("example.com");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPath("/");
    return client;
  }
}
