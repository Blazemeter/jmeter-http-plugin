package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_WITH_BODY;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * When HTTP/3 is attempted against an origin that does not speak QUIC, the handshake has to fail
 * fast and the request has to be served over HTTP/2 instead.
 *
 * <p>The test server is TCP-only, so every HTTP/3 attempt against it can only fail to connect.
 * HTTP/3 is forced with prior knowledge, which is also what makes this an HTTP/3-only attempt with
 * no competing HTTP/2 request (see {@code shouldUseHappyEyeballs}) - the shape a POST would take if
 * first-contact exploration were extended to requests that cannot be raced.
 *
 * <p>{@code getContent} is supposed to turn the connect timeout into "mark the origin broken and
 * retry without HTTP/3". These tests pin whether that actually happens, for a plain GET and for a
 * request carrying a body.
 */
public class Http3ConnectTimeoutFallbackTest extends HTTP2TestBase {

  private static final String PRIOR_KNOWLEDGE_PROP = "httpJettyClient.http3PriorKnowledge";
  private static final String HANDSHAKE_TIMEOUT_PROP =
      "httpJettyClient.http3HandshakeTimeoutMs";

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
    // Force HTTP/3 to be attempted without any Alt-Svc knowledge, and keep the handshake deadline
    // short so a blocked QUIC path is reported quickly instead of stalling the test.
    JMeterUtils.getJMeterProperties().setProperty(PRIOR_KNOWLEDGE_PROP, "true");
    JMeterUtils.getJMeterProperties().setProperty(HANDSHAKE_TIMEOUT_PROP, "500");

    // Same combination the other client tests use: without withHTTP2C the HTTP/2 retry is answered
    // with frame_size_error, which looks like a broken fallback when it is a server limitation.
    server = new ServerBuilder().withHTTP2().withALPN().withHTTP2C().withSSL().buildServer();
    server.start();
    port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

    sampler = new HTTP2Sampler();
    sampler.setDomain(HOST_NAME);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(port);

    client = new HTTP2JettyClient();
    client.loadProperties();
    client.start();
  }

  @After
  public void tearDown() throws Exception {
    JMeterUtils.getJMeterProperties().remove(PRIOR_KNOWLEDGE_PROP);
    JMeterUtils.getJMeterProperties().remove(HANDSHAKE_TIMEOUT_PROP);
    if (client != null) {
      client.stop();
    }
    if (server != null && server.isStarted()) {
      server.stop();
    }
  }

  @Test
  public void shouldFallBackToHttp2WhenHttp3HandshakeFailsOnGet() throws Exception {
    HTTPSampleResult result = sample(SERVER_PATH_200, HTTPConstants.GET);

    assertThat(result.isSuccessful())
        .as("HTTP/3 cannot connect here, so the GET must be served over HTTP/2")
        .isTrue();
  }

  /**
   * The case that blocks extending first-contact exploration to POST: a request with a body cannot
   * be raced, so it would be an HTTP/3-only attempt and the fallback is the only thing that can
   * save it.
   */
  @Test
  public void shouldFallBackToHttp2WhenHttp3HandshakeFailsOnPostWithBody() throws Exception {
    sampler.setMethod(HTTPConstants.POST);
    sampler.addArgument("test1", "value1");

    HTTPSampleResult result = sample(SERVER_PATH_200_WITH_BODY, HTTPConstants.POST);

    assertThat(result.isSuccessful())
        .as("HTTP/3 cannot connect here, so the POST must be served over HTTP/2")
        .isTrue();
    assertThat(result.getResponseDataAsString())
        .as("the body must survive the fallback, not be sent truncated")
        .contains("value1");
  }

  /**
   * The handshake deadline is what makes the fallback fast, and it is easy to lose: it has to be
   * applied to the client that performs the HTTP/3 attempt, because setting it on the QUIC
   * connector has no effect at all. Unbounded, this same sample measured ~10s (twice Jetty's 5s
   * default connect timeout); bounded at 500ms it measures ~1s.
   */
  @Test
  public void shouldBoundHttp3HandshakeByConfiguredDeadline() throws Exception {
    long startedAt = System.nanoTime();

    HTTPSampleResult result = sample(SERVER_PATH_200, HTTPConstants.GET);

    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    assertThat(result.isSuccessful()).as("the sample still has to succeed over HTTP/2").isTrue();
    assertThat(elapsedMs)
        .as("a 500ms handshake deadline must abandon HTTP/3 quickly, not wait for the default")
        .isLessThan(5000);
  }

  /**
   * A connect timeout configured for the sampler must not stretch the handshake deadline: the
   * HTTP/3 client keeps whichever of the two is shorter.
   */
  @Test
  public void shouldKeepHandshakeDeadlineWhenSamplerConnectTimeoutIsLonger() throws Exception {
    sampler.setConnectTimeout("8000");
    long startedAt = System.nanoTime();

    HTTPSampleResult result = sample(SERVER_PATH_200, HTTPConstants.GET);

    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    assertThat(result.isSuccessful()).as("the sample still has to succeed over HTTP/2").isTrue();
    assertThat(elapsedMs)
        .as("the shorter handshake deadline must win over the longer sampler connect timeout")
        .isLessThan(5000);
  }

  private HTTPSampleResult sample(String path, String method) throws Exception {
    client.loadProperties();
    URL url = createUrl(path);
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(url);
    result.setHTTPMethod(method);
    result.setSampleLabel(url.toString());
    return client.sample(sampler, result, false, 0);
  }

  private URL createUrl(String path) throws MalformedURLException {
    try {
      return new URI(HTTPConstants.PROTOCOL_HTTPS, null, HOST_NAME, port, path, null, null).toURL();
    } catch (URISyntaxException e) {
      MalformedURLException ex = new MalformedURLException(e.getMessage());
      ex.initCause(e);
      throw ex;
    }
  }
}
