package com.blazemeter.jmeter.http2.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.Test;

public class HTTP2ProxyIntegrationTest extends HTTP2TestBase {

  @Test
  public void shouldSendRequestThroughProxyAndCaptureIt() throws Exception {
    String originalSharedThreadPoolProperty =
        JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");

    Server backendServer = new Server();
    Server proxyServer = new Server();
    ServerConnector backendConnector = null;
    ServerConnector proxyConnector = null;

    AtomicBoolean proxyHeaderSeen = new AtomicBoolean(false);
    AtomicReference<String> proxyRequestUri = new AtomicReference<>();

    try {
      HttpConfiguration backendConfig = new HttpConfiguration();
      backendConnector = new ServerConnector(backendServer, new HttpConnectionFactory(backendConfig));
      backendConnector.setPort(0);
      backendServer.addConnector(backendConnector);
      backendServer.setHandler(new Handler.Abstract() {
        @Override
        public boolean handle(Request request, Response response, Callback callback) {
          boolean viaHeaderPresent = request.getHeaders().get(HttpHeader.VIA) != null;
          if (viaHeaderPresent) {
            proxyHeaderSeen.set(true);
          }
          response.setStatus(200);
          response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
          if (viaHeaderPresent) {
            response.getHeaders().put("X-Proxy-Detected", "true");
          }
          Content.Sink.write(response, true, "ok\n", callback);
          return true;
        }
      });
      backendServer.start();

      HttpConfiguration proxyConfig = new HttpConfiguration();
      proxyConnector = new ServerConnector(proxyServer, new HttpConnectionFactory(proxyConfig));
      proxyConnector.setPort(0);
      proxyServer.addConnector(proxyConnector);
      ProxyHandler.Forward proxyHandler = new ProxyHandler.Forward() {
        @Override
        public boolean handle(Request request, Response response, Callback callback) {
          proxyRequestUri.set(request.getHttpURI().toString());
          return super.handle(request, response, callback);
        }
      };
      proxyServer.setHandler(proxyHandler);
      proxyServer.start();

      int backendPort = backendConnector.getLocalPort();
      int proxyPort = proxyConnector.getLocalPort();

      HTTP2Sampler sampler = buildSampler(backendPort, proxyPort);
      URL url = URI.create("http://localhost:" + backendPort + "/proxy-test").toURL();

      HTTP2JettyClient client = new HTTP2JettyClient(false, "IT-Proxy");
      try {
        client.start();
        HTTPSampleResult result = client.sample(sampler, buildBaseResult(url), false, 0);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(proxyRequestUri.get()).isEqualTo(url.toString());
        assertThat(proxyHeaderSeen.get()).isTrue();
      } finally {
        client.stop();
      }
    } finally {
      if (proxyServer.isStarted() || proxyServer.isStarting()) {
        proxyServer.stop();
      }
      if (backendServer.isStarted() || backendServer.isStarting()) {
        backendServer.stop();
      }
      if (originalSharedThreadPoolProperty == null) {
        JMeterUtils.getJMeterProperties().remove("httpJettyClient.sharedThreadPool");
      } else {
        JMeterUtils.setProperty("httpJettyClient.sharedThreadPool",
            originalSharedThreadPoolProperty);
      }
    }
  }

  private HTTP2Sampler buildSampler(int backendPort, int proxyPort) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("localhost");
    sampler.setPort(backendPort);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
    sampler.setPath("/proxy-test");
    sampler.setProxyHost("localhost");
    sampler.setProxyPortInt(String.valueOf(proxyPort));
    sampler.setProxyScheme(HTTPConstants.PROTOCOL_HTTP);
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
