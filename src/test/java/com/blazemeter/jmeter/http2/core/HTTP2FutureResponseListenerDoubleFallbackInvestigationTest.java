package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Result;
import org.junit.After;
import org.junit.Test;

/**
 * Item #14: {@link HTTP2FutureResponseListener} used to resolve a {@code protocol_error} on its
 * own (via a {@code tryHttp11Fallback()}/{@code fallbackHttp1Client} pair), silently, before
 * {@link HTTP2JettyClient} - the single place that should own the HTTP/1.1 fallback decision via
 * {@code shouldFallbackToHttp11AfterTransportFailure}/{@code buildHttp11FallbackRequest} - ever
 * saw the failure. That duplicate fallback path is gone; {@link #get} must now always propagate
 * a {@code protocol_error} to the caller.
 */
public class HTTP2FutureResponseListenerDoubleFallbackInvestigationTest extends HTTP2TestBase {

  private HttpClient probeClient;

  @After
  public void tearDown() throws Exception {
    if (probeClient != null) {
      probeClient.stop();
    }
  }

  @Test
  public void getPropagatesProtocolErrorInsteadOfResolvingItSilently() throws Exception {
    probeClient = new HttpClient();
    probeClient.start();

    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(2 * 1024 * 1024);
    Request originalRequest = probeClient.newRequest(URI.create("http://localhost:1/unused"));
    listener.setRequest(originalRequest);

    ProtocolErrorException simulatedProtocolError =
        new ProtocolErrorException("simulated for regression test");
    listener.onComplete(new Result(originalRequest, simulatedProtocolError, null));

    ExecutionException thrown = org.junit.Assert.assertThrows(ExecutionException.class,
        listener::get);
    assertThat(ProtocolErrorException.isProtocolError(thrown.getCause())).isTrue();
  }
}
