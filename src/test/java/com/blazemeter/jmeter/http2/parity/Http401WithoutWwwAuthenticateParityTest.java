package com.blazemeter.jmeter.http2.parity;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_401_NO_WWW_AUTHENTICATE;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2ClientProfileConfig;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.core.ServerBuilder;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerFactory;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Side-by-side parity for HTTP 401 responses that omit {@code WWW-Authenticate}.
 *
 * <p>A 401 does not imply HTTP Auth ({@code WWW-Authenticate}). Servers often use JWT, API keys,
 * cookies, or other schemes and still return 401 without that header. Jetty's default handler
 * treats every 401 as an HTTP Auth challenge and fails without the header; JMeter HttpClient4
 * returns a normal 401 sample. {@code CustomWwwAuthenticationProtocolHandler} aligns BlazeMeter
 * HTTP with that HttpClient4 behavior.
 */
public class Http401WithoutWwwAuthenticateParityTest extends HTTP2TestBase {

  private static final String HTTP_CLIENT4 = "HttpClient4";

  private TeardownableServer server;
  private int serverPort;
  private HTTP2JettyClient pluginClient;
  private HTTP2Sampler sampler;
  private String originalSharedThreadPoolProperty;
  private String originalEnableHttp1Property;
  private String originalEnableHttp2Property;
  private String originalEnableHttp3Property;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    originalSharedThreadPoolProperty = JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    originalEnableHttp1Property = JMeterUtils.getProperty("httpJettyClient.enableHttp1");
    originalEnableHttp2Property = JMeterUtils.getProperty("httpJettyClient.enableHttp2");
    originalEnableHttp3Property = JMeterUtils.getProperty("httpJettyClient.enableHttp3");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");
    JMeterUtils.setProperty("httpJettyClient.enableHttp1", "true");
    JMeterUtils.setProperty("httpJettyClient.enableHttp2", "false");
    JMeterUtils.setProperty("httpJettyClient.enableHttp3", "false");

    server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    serverPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

    sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
    sampler.setDomain(HOST_NAME);
    sampler.setPort(serverPort);
    sampler.setPath(SERVER_PATH_401_NO_WWW_AUTHENTICATE);
    sampler.setFollowRedirects(false);
    sampler.setAutoRedirects(false);

    pluginClient = new HTTP2JettyClient(false, "401-parity",
        HTTP2ClientProfileConfig.builder()
            .enableHttp1(true)
            .enableHttp2(false)
            .enableHttp3(false)
            .build());
    pluginClient.start();
  }

  @After
  public void tearDown() throws Exception {
    if (pluginClient != null) {
      pluginClient.stop();
    }
    if (sampler != null) {
      sampler.threadFinished();
    }
    if (server != null) {
      server.stop();
    }
    restoreProperty("httpJettyClient.sharedThreadPool", originalSharedThreadPoolProperty);
    restoreProperty("httpJettyClient.enableHttp1", originalEnableHttp1Property);
    restoreProperty("httpJettyClient.enableHttp2", originalEnableHttp2Property);
    restoreProperty("httpJettyClient.enableHttp3", originalEnableHttp3Property);
  }

  private static void restoreProperty(String key, String originalValue) {
    if (originalValue == null) {
      JMeterUtils.getJMeterProperties().remove(key);
    } else {
      JMeterUtils.setProperty(key, originalValue);
    }
  }

  @Test
  public void pluginShouldMatchHttpClient4For401WithoutWwwAuthenticate() throws Exception {
    URL url = targetUrl();
    HTTPSampleResult reference = sampleHttpClient4(url);
    HTTPSampleResult plugin = samplePlugin(url);

    assertThat(reference.getResponseCode()).isEqualTo("401");
    assertThat(plugin.getResponseCode())
        .as("response code")
        .isEqualTo(reference.getResponseCode());
    assertThat(plugin.isSuccessful())
        .as("success flag")
        .isEqualTo(reference.isSuccessful());
    assertThat(plugin.getResponseDataAsString())
        .as("response body")
        .isEqualTo(reference.getResponseDataAsString());
  }

  private URL targetUrl() throws Exception {
    return URI.create("http://" + HOST_NAME + ":" + serverPort
        + SERVER_PATH_401_NO_WWW_AUTHENTICATE).toURL();
  }

  private HTTPSampleResult sampleHttpClient4(URL url) throws Exception {
    HTTPSamplerBase hc4 = HTTPSamplerFactory.newInstance(HTTP_CLIENT4);
    hc4.setMethod(sampler.getMethod());
    hc4.setProtocol(sampler.getProtocol());
    hc4.setDomain(sampler.getDomain());
    hc4.setPort(sampler.getPort());
    hc4.setPath(sampler.getPath());
    hc4.setFollowRedirects(sampler.getFollowRedirects());
    hc4.setAutoRedirects(sampler.getAutoRedirects());
    hc4.setUseKeepAlive(true);
    Method sample = HTTPSamplerBase.class.getDeclaredMethod(
        "sample", URL.class, String.class, boolean.class, int.class);
    sample.setAccessible(true);
    return (HTTPSampleResult) sample.invoke(hc4, url, sampler.getMethod(), false, 1);
  }

  private HTTPSampleResult samplePlugin(URL url) throws Exception {
    HTTPSampleResult shell = new HTTPSampleResult();
    shell.setURL(url);
    shell.setHTTPMethod(sampler.getMethod());
    shell.setSampleLabel(sampler.getName());
    pluginClient.loadProperties();
    return pluginClient.sample(sampler, shell, false, 0);
  }
}
