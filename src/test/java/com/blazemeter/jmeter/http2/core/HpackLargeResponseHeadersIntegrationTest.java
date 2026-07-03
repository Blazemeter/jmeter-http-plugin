package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.LargeResponseHeaderHttpsServer.HEADER_LIST_LIMIT;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import com.blazemeter.jmeter.http2.sampler.TestableHTTP2Sampler;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression guard for oversized HTTP/2 response headers.
 *
 * <p>While the limit is not enforced correctly, Jetty surfaces HPACK-related failures
 * (for example {@code invalid_hpack_block}) which {@link HpackFailureDetector} recognizes.
 * After the production fix, the same scenario should succeed instead.
 */
public class HpackLargeResponseHeadersIntegrationTest extends HTTP2TestBase {

  private String savedSharedPool;
  private String savedProtocolErrorFallback;

  private LargeResponseHeaderHttpsServer server;
  private HTTP2JettyClient client;
  private TestableHTTP2Sampler sampler;
  private int serverPort = -1;

  @Before
  public void setUp() throws Exception {
    JMeterTestUtils.setupJmeterEnv();
    savedSharedPool = JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    savedProtocolErrorFallback = JMeterUtils.getProperty("httpJettyClient.protocolErrorFallbackEnabled");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");
    JMeterUtils.setProperty("httpJettyClient.protocolErrorFallbackEnabled", "false");

    server = LargeResponseHeaderHttpsServer.start();
    serverPort = server.getPort();
  }

  @After
  public void tearDown() throws Exception {
    if (sampler != null) {
      sampler.threadFinished();
      sampler = null;
    }
    if (client != null) {
      client.stop();
      client = null;
    }
    if (server != null) {
      server.stop();
      server = null;
    }
    serverPort = -1;
    restoreProperty("httpJettyClient.sharedThreadPool", savedSharedPool);
    restoreProperty("httpJettyClient.protocolErrorFallbackEnabled", savedProtocolErrorFallback);
  }

  @Test
  public void shouldSucceedWithLargeResponseHeadersUsingDefaultUnlimitedHttp2Limit() throws Exception {
    client = new HTTP2JettyClient(false, "hpack-large-headers-unlimited-it", http2OnlyProfile());
    client.start();

    HTTPSampleResult result = client.sample(buildSampler(), buildBaseResult(), false, 0);

    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseCode()).isEqualTo("200");
  }

  @Test
  public void shouldFailThroughJettyClientWhenResponseHeadersExceedLimit() throws Exception {
    client = createHttp2OnlyClient();
    client.start();

    org.junit.Assert.assertThrows(ExecutionException.class,
        () -> client.sample(buildSampler(), buildBaseResult(), false, 0));
  }

  @Test
  public void shouldDetectHpackRelatedFailureThroughSampler() throws Exception {
    sampler = createHttp2OnlySampler();
    URL url = new URI(HTTPConstants.PROTOCOL_HTTPS, null, HOST_NAME, serverPort, "/", null, null)
        .toURL();

    HTTPSampleResult result = sampler.runSample(url, HTTPConstants.GET);

    assertThat(result.isSuccessful()).isFalse();
    assertThat(HpackFailureDetector.indicatesHpackFailure(
        new Exception(result.getResponseDataAsString()))).isTrue();
  }

  private HTTP2JettyClient createHttp2OnlyClient() throws Exception {
    HTTP2JettyClient jettyClient = new HTTP2JettyClient(false, "hpack-large-headers-it",
        http2OnlyProfile());
    applyResponseHeaderLimit(jettyClient);
    return jettyClient;
  }

  private TestableHTTP2Sampler createHttp2OnlySampler() throws Exception {
    TestableHTTP2Sampler http2Sampler = new TestableHTTP2Sampler(() -> {
      HTTP2JettyClient jettyClient = createHttp2OnlyClient();
      jettyClient.start();
      return jettyClient;
    });
    configureSampler(http2Sampler);
    return http2Sampler;
  }

  private HTTP2Sampler buildSampler() {
    HTTP2Sampler http2Sampler = new HTTP2Sampler();
    configureSampler(http2Sampler);
    return http2Sampler;
  }

  private void configureSampler(HTTP2Sampler http2Sampler) {
    http2Sampler.setMethod(HTTPConstants.GET);
    http2Sampler.setDomain(HOST_NAME);
    http2Sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    http2Sampler.setPort(serverPort);
    http2Sampler.setPath("/");
    http2Sampler.setEnableHttp1(false);
    http2Sampler.setEnableHttp2(true);
    http2Sampler.setEnableHttp3(false);
    http2Sampler.setFallbackEnabled(false);
    http2Sampler.setProtocolErrorFallbackEnabled(false);
  }

  private static HTTP2ClientProfileConfig http2OnlyProfile() {
    return HTTP2ClientProfileConfig.builder()
        .profile("browser-like")
        .enableHttp3(false)
        .enableHttp2(true)
        .enableHttp1(false)
        .alpnEnabled(true)
        .fallbackEnabled(false)
        .protocolErrorFallbackEnabled(false)
        .altSvcCacheEnabled(false)
        .http1OnlyCacheEnabled(false)
        .build();
  }

  private static void applyResponseHeaderLimit(HTTP2JettyClient jettyClient)
      throws ReflectiveOperationException {
    Object httpClient = HTTP2JettyClient.class.getMethod("getHttpClient").invoke(jettyClient);
    invoke(httpClient, "setMaxResponseHeadersSize", int.class, HEADER_LIST_LIMIT);
    invoke(httpClient, "setMaxRequestHeadersSize", int.class, HEADER_LIST_LIMIT);
  }

  private static void invoke(Object target, String methodName, Class<?> paramType, Object arg)
      throws ReflectiveOperationException {
    Method method = target.getClass().getMethod(methodName, paramType);
    method.invoke(target, arg);
  }

  private HTTPSampleResult buildBaseResult() throws Exception {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(new URI(HTTPConstants.PROTOCOL_HTTPS, null, HOST_NAME, serverPort, "/", null, null)
        .toURL());
    result.setHTTPMethod(HTTPConstants.GET);
    return result;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      JMeterUtils.getJMeterProperties().remove(key);
    } else {
      JMeterUtils.setProperty(key, value);
    }
  }
}
