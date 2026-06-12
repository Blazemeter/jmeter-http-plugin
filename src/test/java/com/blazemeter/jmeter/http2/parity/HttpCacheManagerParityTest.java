package com.blazemeter.jmeter.http2.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.core.ServerBuilder;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URL;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Port of cache behaviour from Apache {@code TestCacheManagerHC4} (embedded resource caching). */
public class HttpCacheManagerParityTest extends HTTP2TestBase {

  private TeardownableServer server;
  private HTTP2JettyClient client;
  private int serverPort;
  private String previousCacheMode;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    previousCacheMode = JMeterUtils.getProperty("cache_manager.cached_resource_mode");
    JMeterUtils.setProperty("cache_manager.cached_resource_mode", "RETURN_200_CACHE");
    JMeterUtils.setProperty("RETURN_200_CACHE.message", "cached");

    server = new ServerBuilder().withHTTP1().withSSL().buildServer();
    server.start();
    serverPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("cache-parity");
  }

  @After
  public void tearDown() throws Exception {
    if (previousCacheMode != null) {
      JMeterUtils.setProperty("cache_manager.cached_resource_mode", previousCacheMode);
    }
    if (client != null) {
      client.stop();
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void secondEmbeddedFetchUsesCacheLikeHttpClient4() throws Exception {
    CacheManager cacheManager = new CacheManager();
    cacheManager.setUseExpires(true);
    cacheManager.setClearEachIteration(false);
    cacheManager.testIterationStart(null);

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setProtocol("https");
    sampler.setDomain(ServerBuilder.HOST_NAME);
    sampler.setPort(serverPort);
    sampler.setPath(ServerBuilder.SERVER_PATH_200_EMBEDDED);
    sampler.setImageParser(true); // embedded resource download
    sampler.setUseKeepAlive(true);
    sampler.setCacheManager(cacheManager);

    URL url = new URL("https", ServerBuilder.HOST_NAME, serverPort,
        ServerBuilder.SERVER_PATH_200_EMBEDDED);

    HttpClient4PluginParitySupport.sampleHttpClient4(sampler, url);
    HTTPSampleResult refSecond = HttpClient4PluginParitySupport.sampleHttpClient4(sampler, url);

    HttpClient4PluginParitySupport.samplePlugin(client, sampler, url);
    HTTPSampleResult pluginSecond = HttpClient4PluginParitySupport.samplePlugin(client, sampler, url);

    HttpClient4PluginParitySupport.assertCoreParity(refSecond, pluginSecond, "cached embedded");
    assertThat(refSecond.getSubResults()).hasSize(pluginSecond.getSubResults().length);
    if (refSecond.getSubResults().length > 0) {
      assertThat(pluginSecond.getSubResults()[0].getResponseMessage())
          .isEqualTo(refSecond.getSubResults()[0].getResponseMessage());
    }
  }
}
