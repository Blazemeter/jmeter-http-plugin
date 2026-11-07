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
import com.blazemeter.jmeter.http2.core.ProtocolErrorException;
import com.blazemeter.jmeter.http2.core.SampleClock;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Result;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * An async request that fails is collected by the async controller on a later turn, after the
 * requests queued ahead of it, possibly long after it failed. Its sample must still say how long
 * the request took - from dispatch to the failure - not how long it waited to be collected: a
 * refused connection reported as taking as long as the slowest request ahead of it is a response
 * time that never happened, and it lands in the label's average and percentiles.
 *
 * <p>"The failure" is the last attempt's: when the request is sent again after failing (an
 * HTTP/1.1 fallback) and fails again, that time is part of the request too.
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

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @After
  public void tearDown() {
    if (sampler != null) {
      // Stops the Jetty client this thread cached for the sampler, so it does not outlive the test.
      sampler.threadFinished();
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
