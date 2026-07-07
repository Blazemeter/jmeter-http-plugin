package com.blazemeter.jmeter.http2.integration;

import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.URI;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.Assume;
import org.junit.Test;

/**
 * Integration test for h2c upgrade and cache behavior.
 *
 * Run with: -Dit.h2c=true (default) or disable with -Dit.h2c=false
 */
public class HTTP2H2cUpgradeCacheIT extends HTTP2TestBase {

  @Test
  public void shouldUpgradeAndCacheH2c() throws Exception {
    Assume.assumeTrue(Boolean.parseBoolean(System.getProperty("it.h2c", "true")));
    String originalSharedThreadPoolProperty =
        JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");
    String originalResponseTimeoutProperty =
        JMeterUtils.getProperty("HTTPSampler.response_timeout");
    JMeterUtils.setProperty("HTTPSampler.response_timeout", "5000");
    JMeterUtils.setProperty("httpJettyClient.http2PriorKnowledge", "false");

    Server server = new Server();
    HttpConfiguration config = new HttpConfiguration();
    ServerConnector connector = new ServerConnector(
        server,
        new HttpConnectionFactory(config),
        new HTTP2CServerConnectionFactory(config));
    connector.setPort(0);
    server.addConnector(connector);
    server.setHandler(new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) {
        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        Content.Sink.write(response, true, "ok\n", callback);
        return true;
      }
    });
    server.start();

    int port = connector.getLocalPort();
    HTTP2Sampler sampler = buildSampler(port);
    URL url = URI.create("http://localhost:" + port + "/").toURL();

    HTTP2JettyClient upgradeClient = new HTTP2JettyClient(true, "IT-H2C-Upgrade");
    HTTP2JettyClient cacheClient = new HTTP2JettyClient(false, "IT-H2C-Cache");

    try {
      try {
        upgradeClient.start();
        HTTPSampleResult first = upgradeClient.sample(sampler, buildBaseResult(url), false, 0);
        assertTrue("Expected successful response", first.isSuccessful());
        assertTrue("Expected HTTP/2 response, got: " + first.getResponseHeaders(),
            first.getResponseHeaders().startsWith("HTTP/2"));
      } finally {
        upgradeClient.stop();
      }

      try {
        cacheClient.start();
        HTTPSampleResult second = cacheClient.sample(sampler, buildBaseResult(url), false, 0);
        assertTrue("Expected successful response", second.isSuccessful());
        assertTrue("Expected HTTP/2 response via h2c cache, got: " + second.getResponseHeaders(),
            second.getResponseHeaders().startsWith("HTTP/2"));
      } finally {
        cacheClient.stop();
        server.stop();
      }
    } finally {
      if (originalSharedThreadPoolProperty == null) {
        JMeterUtils.getJMeterProperties().remove("httpJettyClient.sharedThreadPool");
      } else {
        JMeterUtils.setProperty("httpJettyClient.sharedThreadPool",
            originalSharedThreadPoolProperty);
      }
      if (originalResponseTimeoutProperty == null) {
        JMeterUtils.getJMeterProperties().remove("HTTPSampler.response_timeout");
      } else {
        JMeterUtils.setProperty("HTTPSampler.response_timeout",
            originalResponseTimeoutProperty);
      }
    }
  }

  private HTTP2Sampler buildSampler(int port) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
    sampler.setPath("/");
    sampler.setConnectTimeout("2000");
    sampler.setResponseTimeout("5000");
    return sampler;
  }

  private HTTPSampleResult buildBaseResult(URL url) {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(url);
    result.setHTTPMethod(HTTPConstants.GET);
    return result;
  }
}
