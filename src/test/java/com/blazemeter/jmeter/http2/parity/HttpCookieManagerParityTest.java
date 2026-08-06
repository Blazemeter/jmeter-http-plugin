package com.blazemeter.jmeter.http2.parity;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.core.ServerBuilder;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URL;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Port of cookie jar behaviour covered by Apache {@code TestHC4CookieManager} scenarios. */
public class HttpCookieManagerParityTest extends HTTP2TestBase {

  private TeardownableServer server;
  private HTTP2JettyClient client;
  private int serverPort;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    server = new ServerBuilder().withHTTP1().withSSL().buildServer();
    server.start();
    serverPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("cookie-parity");
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void setCookieAndEchoMatchesHttpClient4() throws Exception {
    CookieManager cookieManager = new CookieManager();
    cookieManager.testStarted(ServerBuilder.HOST_NAME);

    HTTP2Sampler setCookies = baseSampler(ServerBuilder.SERVER_PATH_SET_COOKIES);
    setCookies.setCookieManager(cookieManager);
    URL setUrl = new URL("https", ServerBuilder.HOST_NAME, serverPort,
        ServerBuilder.SERVER_PATH_SET_COOKIES);
    HttpClient4PluginParitySupport.assertCoreParity(
        HttpClient4PluginParitySupport.sampleHttpClient4(setCookies, setUrl),
        HttpClient4PluginParitySupport.samplePlugin(client, setCookies, setUrl),
        "set cookies");

    HTTP2Sampler useCookies = baseSampler(ServerBuilder.SERVER_PATH_USE_COOKIES);
    useCookies.setCookieManager(cookieManager);
    URL useUrl = new URL("https", ServerBuilder.HOST_NAME, serverPort,
        ServerBuilder.SERVER_PATH_USE_COOKIES);
    HTTPSampleResult reference = HttpClient4PluginParitySupport.sampleHttpClient4(useCookies, useUrl);
    HTTPSampleResult plugin = HttpClient4PluginParitySupport.samplePlugin(client, useCookies, useUrl);

    HttpClient4PluginParitySupport.assertCoreParity(reference, plugin, "echo cookies");
    HttpClient4PluginParitySupport.assertResponseBodyParity(reference, plugin, "cookie body");
  }

  private HTTP2Sampler baseSampler(String path) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setProtocol("https");
    sampler.setDomain(ServerBuilder.HOST_NAME);
    sampler.setPort(serverPort);
    sampler.setPath(path);
    sampler.setUseKeepAlive(true);
    sampler.setFollowRedirects(true);
    return sampler;
  }
}
