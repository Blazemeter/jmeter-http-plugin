package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.conn.HttpHostConnectException;
import org.eclipse.jetty.client.HttpResponseException;
import org.junit.Test;

public class JmeterHttpClientExceptionMapperTest {

  @Test
  public void shouldMapJettyMaxRedirectsToClientProtocolExceptionWhenAutoRedirectsEnabled() {
    Throwable jetty = new ExecutionException(
        new HttpResponseException("Max redirects exceeded 8", null));

    Throwable mapped = JmeterHttpClientExceptionMapper.forSampleResult(jetty, true);

    assertThat(mapped).isInstanceOf(ClientProtocolException.class);
    assertThat(mapped.getMessage()).isNull();
  }

  @Test
  public void shouldKeepExecutionExceptionWhenAutoRedirectsDisabled() {
    Throwable failure = new ExecutionException(
        new HttpResponseException("Max redirects exceeded 8", null));

    Throwable mapped = JmeterHttpClientExceptionMapper.forSampleResult(failure, false);

    assertThat(mapped).isInstanceOf(ExecutionException.class);
  }

  @Test
  public void shouldMapConnectExceptionToHttpHostConnectException() throws Exception {
    Throwable failure = new ExecutionException(
        new ConnectException("Connection refused: getsockopt"));
    URL url = new URL("https://localhost:8082/");

    Throwable mapped = JmeterHttpClientExceptionMapper.forSampleResult(failure, false, url);

    assertThat(mapped).isInstanceOf(HttpHostConnectException.class);
    assertThat(mapped.getCause()).isInstanceOf(ConnectException.class);
    assertThat(mapped.getMessage()).contains("localhost:8082");
  }

  @Test
  public void shouldMapJettyConnectSocketExceptionToHttpHostConnectException() throws Exception {
    SocketException socketFailure =
        new SocketException("Could not connect to localhost/[::1]:8082");
    socketFailure.initCause(new SocketException("Address family not supported by protocol"));
    Throwable failure = new ExecutionException(socketFailure);

    Throwable mapped = JmeterHttpClientExceptionMapper.forSampleResult(failure, false,
        new URL("http://localhost:8082/"));

    assertThat(mapped).isInstanceOf(HttpHostConnectException.class);
    assertThat(mapped.getCause()).isSameAs(socketFailure);
    assertThat(mapped.getMessage()).contains("localhost:8082");
  }
}
