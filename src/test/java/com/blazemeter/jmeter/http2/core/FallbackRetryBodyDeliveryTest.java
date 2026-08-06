package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_WITH_BODY;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The protocol fallbacks replace a request that already failed, and each one has to hand the body
 * over intact. These tests take that all the way to a real server and assert on what it echoes back,
 * so a body that arrives empty or truncated fails here instead of silently changing what was sent.
 *
 * <p>The mechanics of the hand-over are pinned separately by {@link RetryRequestBodyReuseTest}; what
 * these add is that the request really does reach the server with its body after a retry.
 */
public class FallbackRetryBodyDeliveryTest extends HTTP2TestBase {

  private static final String BODY_MARKER = "value1";

  private TeardownableServer server;
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;
  private int port = -1;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    server = new ServerBuilder().withSSL().withALPN().withHTTP2().withHTTP2C().buildServer();
    server.start();
    port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

    sampler = new HTTP2Sampler();
    sampler.setDomain(HOST_NAME);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(port);
    sampler.setMethod(HTTPConstants.POST);
    sampler.addArgument("test1", BODY_MARKER);
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (server != null && server.isStarted()) {
      server.stop();
    }
  }

  /**
   * The HTTP/1.1 fallback taken for a protocol error, with a body. The HTTP/2 client is built
   * expecting an HTTP/1.1 upgrade against a server that only speaks HTTP/2 over ALPN, which is what
   * makes the first attempt fail as a protocol error and hands the request to the fallback.
   */
  @Test
  public void shouldDeliverBodyThroughHttp11FallbackAfterProtocolError() throws Exception {
    client = new HTTP2JettyClient(true, "fallback-body-delivery");
    client.start();
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener();
    sampler.setFutureResponseListener(listener);
    HTTPSampleResult result = buildResult();

    client.dispatchAsync(sampler, result, listener);
    HTTPSampleResult sampleResult =
        client.sampleFromListener(sampler, result, false, 0, listener);

    assertThat(sampleResult.getResponseDataAsString())
        .as("the body must survive the HTTP/1.1 fallback, not arrive empty or truncated")
        .contains(BODY_MARKER);
  }

  /**
   * Replaying a request whose body a first attempt already consumed, which is what any retry has to
   * do. It drives {@code retryAfterGoAway} because that is the method implementing this replay - but
   * <b>no GOAWAY is involved here</b>, hence the name: this covers the body hand-over only, never the
   * detection of the frame. A real GOAWAY is exercised by {@link GoAwayReconnectTest}.
   */
  @Test
  public void shouldDeliverBodyWhenRetryReplaysRequestWithConsumedBody() throws Exception {
    client = new HTTP2JettyClient(false, "retry-body-delivery");
    client.start();
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener();
    sampler.setFutureResponseListener(listener);
    HTTPSampleResult result = buildResult();

    // A real send first, so the body is consumed exactly as the attempt that got the GOAWAY would
    // have left it. The retry has to rewind it rather than resend nothing.
    client.dispatchAsync(sampler, result, listener);
    client.sampleFromListener(sampler, result, false, 0, listener);
    Request sentRequest = listener.getRequest();

    Method retry = HTTP2JettyClient.class.getDeclaredMethod("retryAfterGoAway", Request.class);
    retry.setAccessible(true);
    ContentResponse retried = (ContentResponse) retry.invoke(client, sentRequest);

    assertThat(retried.getContentAsString())
        .as("the retry after GOAWAY must resend the full body")
        .contains(BODY_MARKER);
  }

  private HTTPSampleResult buildResult() throws MalformedURLException {
    HTTPSampleResult result = new HTTPSampleResult();
    URL url = createUrl();
    result.setURL(url);
    result.setHTTPMethod(HTTPConstants.POST);
    result.setSampleLabel(url.toString());
    return result;
  }

  private URL createUrl() throws MalformedURLException {
    try {
      return new URI(HTTPConstants.PROTOCOL_HTTPS, null, HOST_NAME, port,
          SERVER_PATH_200_WITH_BODY, null, null).toURL();
    } catch (URISyntaxException e) {
      MalformedURLException ex = new MalformedURLException(e.getMessage());
      ex.initCause(e);
      throw ex;
    }
  }
}
