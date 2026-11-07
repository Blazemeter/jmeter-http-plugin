package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Result;
import org.junit.After;
import org.junit.Test;

/**
 * When collecting an async request finds it failed, the client may send it again (a GOAWAY retry,
 * an HTTP/1.1 fallback). If that fails too, the exchange ended when the last attempt failed, and
 * the listener the sample is ended from has to say so: its own completion is the first attempt's,
 * and a sample ended there leaves out every attempt made after it.
 */
public class FailedRetryExchangeEndTest extends HTTP2TestBase {

  private static final long COLLECTED_AFTER_MILLIS = 100;

  private HttpClient probeClient;
  private HTTP2JettyClient client;

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (probeClient != null) {
      probeClient.stop();
    }
  }

  @Test
  public void failedFallbackMustMoveTheExchangeEndToWhenItFailed() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    URL url = new URL("http://127.0.0.1:" + closedPort + "/");
    probeClient = new HttpClient();
    probeClient.start();
    client = new HTTP2JettyClient(false, "failed-retry-exchange-end");
    client.start();
    assertThat(client.isProtocolErrorFallbackEnabled())
        .as("the HTTP/1.1 fallback is on by default").isTrue();

    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener();
    Request request = probeClient.newRequest(URI.create(url.toString()));
    listener.setRequest(request);
    // The first attempt fails with an error the client retries over HTTP/1.1, which then fails
    // too: nothing listens on the port.
    listener.onComplete(new Result(request, new ProtocolErrorException("protocol_error"), null));
    long firstAttemptEnd = listener.getResponseEnd();
    Thread.sleep(COLLECTED_AFTER_MILLIS);

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(url);
    result.setHTTPMethod(HTTPConstants.GET);
    long collectedAt = System.currentTimeMillis();
    assertThrows(ExecutionException.class,
        () -> client.sampleFromListener(sampler, result, false, 0, listener));

    assertThat(listener.getResponseEnd())
        .as("the exchange ends when the fallback failed, %d ms after the first attempt did",
            COLLECTED_AFTER_MILLIS)
        .isGreaterThanOrEqualTo(collectedAt)
        .isGreaterThan(firstAttemptEnd);
  }
}
