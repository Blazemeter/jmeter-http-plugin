package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import java.net.ServerSocket;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.assertj.core.api.SoftAssertions;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * An async request that fails is collected by the async controller on a later turn, after the
 * requests queued ahead of it, possibly long after it failed. Its sample must still say how long
 * the request took - from dispatch to the failure - not how long it waited to be collected: a
 * refused connection reported as taking as long as the slowest request ahead of it is a response
 * time that never happened, and it lands in the label's average and percentiles.
 */
public class AsyncFailureElapsedTimeTest extends HTTP2TestBase {

  private static final long COLLECTED_LATE_BY_MILLIS = 400;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Test
  public void failedAsyncRequestMustReportTheTimeToItsFailureNotToItsCollection()
      throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    HTTP2Sampler sampler = new HTTP2Sampler();
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
    softly.assertThat(result.getEndTime())
        .as("the sample ends when the request failed, not when it was collected")
        .isLessThanOrEqualTo(failedAt);
    softly.assertThat(result.getTime())
        .as("the elapsed time does not include the wait to be collected")
        .isLessThan(COLLECTED_LATE_BY_MILLIS);
    softly.assertThat(result.getTime()).as("and is not negative").isNotNegative();
    softly.assertAll();
  }
}
