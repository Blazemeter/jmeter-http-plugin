package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.MULTI_IMAGE_HTML_TEMPLATE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_EMBEDDED_MANY;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Regression for HTTP/1.1-only mode with {@linkplain HTTP2Sampler#isConcurrentDwn() concurrent}
 * embedded downloads: Jetty rejects a second in-flight request on the same HTTP/1 connection when
 * the pool is configured with multiplexing {@code > 1} ("Pipelined requests not supported").
 *
 * <p>Reproduce the failure by changing {@code HTTP2JettyClient} so the HTTP/1-only transport uses
 * {@code configureTransport(http1Transport)} (shared multiplex limit) instead of
 * {@code configureTransport(http1Transport, 1)}.
 */
@RunWith(MockitoJUnitRunner.class)
public class Http1OnlyConcurrentEmbeddedResourcesIntegrationTest extends HTTP2TestBase {

  private static final String PROP_ENABLE_HTTP1 = "httpJettyClient.enableHttp1";
  private static final String PROP_ENABLE_HTTP2 = "httpJettyClient.enableHttp2";
  private static final String PROP_ENABLE_HTTP3 = "httpJettyClient.enableHttp3";
  private static final String PROP_SHARED_POOL = "httpJettyClient.sharedThreadPool";

  private String savedEnableHttp1;
  private String savedEnableHttp2;
  private String savedEnableHttp3;
  private String savedSharedPool;

  private TeardownableServer server;
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;
  private int serverPort = -1;

  @Before
  public void setUp() throws Exception {
    JMeterTestUtils.setupJmeterEnv();
    savedEnableHttp1 = JMeterUtils.getProperty(PROP_ENABLE_HTTP1);
    savedEnableHttp2 = JMeterUtils.getProperty(PROP_ENABLE_HTTP2);
    savedEnableHttp3 = JMeterUtils.getProperty(PROP_ENABLE_HTTP3);
    savedSharedPool = JMeterUtils.getProperty(PROP_SHARED_POOL);

    JMeterUtils.setProperty(PROP_ENABLE_HTTP1, "true");
    JMeterUtils.setProperty(PROP_ENABLE_HTTP2, "false");
    JMeterUtils.setProperty(PROP_ENABLE_HTTP3, "false");
    JMeterUtils.setProperty(PROP_SHARED_POOL, "false");

    server = new ServerBuilder()
        .withHTTP1()
        .withSSL()
        .buildServer();
    server.start();
    serverPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

    sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain(HOST_NAME);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(serverPort);
    sampler.setPath("");
    sampler.setImageParser(true);
    sampler.setConcurrentDwn(true);
    sampler.setConcurrentPool("6");
    sampler.setEnableHttp1(true);
    sampler.setEnableHttp2(false);
    sampler.setEnableHttp3(false);

    client = new HTTP2JettyClient();
    client.start();
  }

  @After
  public void tearDown() throws Exception {
    sampler.threadFinished();
    if (client != null) {
      client.stop();
      client = null;
    }
    if (server != null) {
      server.stop();
      server = null;
    }
    serverPort = -1;
    restoreProperty(PROP_ENABLE_HTTP1, savedEnableHttp1);
    restoreProperty(PROP_ENABLE_HTTP2, savedEnableHttp2);
    restoreProperty(PROP_ENABLE_HTTP3, savedEnableHttp3);
    restoreProperty(PROP_SHARED_POOL, savedSharedPool);
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      JMeterUtils.getJMeterProperties().remove(key);
    } else {
      JMeterUtils.setProperty(key, value);
    }
  }

  @Test
  public void http1Only_concurrentEmbeddedDownloads_shouldSucceed() throws Exception {
    client.loadProperties();
    HTTPSampleResult result =
        client.sample(sampler, buildBaseResult(createHttpsUrl(SERVER_PATH_200_EMBEDDED_MANY),
            HTTPConstants.GET), false, 0);

    assertThat(result.isSuccessful())
        .as("main sample (HTML)")
        .isTrue();
    assertThat(result.getResponseDataAsString()).isEqualTo(MULTI_IMAGE_HTML_TEMPLATE);

    assertSampleTreeSuccessful(result);
    List<SampleResult> subs = allNestedSubResults(result);
    assertThat(subs.size())
        .as("expected multiple embedded resource subresults")
        .isGreaterThanOrEqualTo(5);
    assertThat(subs.stream().filter(Http1OnlyConcurrentEmbeddedResourcesIntegrationTest::isImageSub)
        .count())
        .as("same-origin image.png fetches")
        .isGreaterThanOrEqualTo(5);
  }

  private static void assertSampleTreeSuccessful(SampleResult sr) {
    assertThat(sr.isSuccessful())
        .as("sample label=%s url=%s", sr.getSampleLabel(), sr.getUrlAsString())
        .isTrue();
    for (SampleResult ch : sr.getSubResults()) {
      assertSampleTreeSuccessful(ch);
    }
  }

  private static List<SampleResult> allNestedSubResults(SampleResult root) {
    List<SampleResult> out = new ArrayList<>();
    for (SampleResult ch : root.getSubResults()) {
      out.add(ch);
      out.addAll(allNestedSubResults(ch));
    }
    return out;
  }

  private static boolean isImageSub(SampleResult s) {
    String url = s.getUrlAsString();
    return url != null && url.contains("/test/image-");
  }

  private URL createHttpsUrl(String path) throws MalformedURLException {
    try {
      return new URI(HTTPConstants.PROTOCOL_HTTPS, null, HOST_NAME, activePort(), path, null, null)
          .toURL();
    } catch (URISyntaxException e) {
      MalformedURLException ex = new MalformedURLException(e.getMessage());
      ex.initCause(e);
      throw ex;
    }
  }

  private int activePort() {
    return serverPort > 0 ? serverPort : sampler.getPort();
  }

  private static HTTPSampleResult buildBaseResult(URL url, String method) {
    HTTPSampleResult ret = new HTTPSampleResult();
    ret.setURL(url);
    ret.setHTTPMethod(method);
    return ret;
  }
}
