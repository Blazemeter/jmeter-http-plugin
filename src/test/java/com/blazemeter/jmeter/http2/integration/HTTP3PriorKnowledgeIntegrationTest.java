package com.blazemeter.jmeter.http2.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assume;
import org.junit.Test;

public class HTTP3PriorKnowledgeIntegrationTest extends HTTP2TestBase {

  @Test
  public void shouldUseHttp3WithPriorKnowledgeWithoutHttp1Fallback() throws Exception {
    Assume.assumeTrue("HTTP/3 IT can be disabled with -Dit.http3=false", Boolean.parseBoolean(
      System.getProperty("it.http3", "true")));

    String originalEnableHttp1 = JMeterUtils.getProperty("httpJettyClient.enableHttp1");
    String originalEnableHttp2 = JMeterUtils.getProperty("httpJettyClient.enableHttp2");
    String originalEnableHttp3 = JMeterUtils.getProperty("httpJettyClient.enableHttp3");
    String originalAltSvc = JMeterUtils.getProperty("httpJettyClient.altSvcCacheEnabled");
    String originalH3Prior = JMeterUtils.getProperty("httpJettyClient.http3PriorKnowledge");

    Server server = new Server();
    QuicheServerConnector connector = null;

    try {
      JMeterUtils.setProperty("httpJettyClient.enableHttp1", "false");
      JMeterUtils.setProperty("httpJettyClient.enableHttp2", "false");
      JMeterUtils.setProperty("httpJettyClient.enableHttp3", "true");
      JMeterUtils.setProperty("httpJettyClient.altSvcCacheEnabled", "true");
      JMeterUtils.setProperty("httpJettyClient.http3PriorKnowledge", "true");

      SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
      sslContextFactory.setKeyStorePath(getKeyStorePath());
      sslContextFactory.setKeyStorePassword("storepwd");

      Path workDir = Files.createTempDirectory("http3-it");
      QuicheServerQuicConfiguration quicConfig =
          HTTP3ServerQuicConfiguration.configure(new QuicheServerQuicConfiguration(workDir));
      HTTP3ServerConnectionFactory h3 = new HTTP3ServerConnectionFactory();
      connector = new QuicheServerConnector(server, sslContextFactory, quicConfig, h3);
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

      HTTP2Sampler sampler = new HTTP2Sampler();
      sampler.setMethod(HTTPConstants.GET);
      sampler.setDomain("localhost");
      sampler.setPort(connector.getLocalPort());
      sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
      sampler.setPath("/");
      sampler.setConnectTimeout("2000");
      sampler.setResponseTimeout("5000");

      URL url = URI.create("https://localhost:" + connector.getLocalPort() + "/").toURL();
      HTTPSampleResult baseResult = new HTTPSampleResult();
      baseResult.setURL(url);
      baseResult.setHTTPMethod(HTTPConstants.GET);

      HTTP2JettyClient client = new HTTP2JettyClient(false, "IT-HTTP3-Prior");
      try {
        client.start();
        HTTPSampleResult result = client.sample(sampler, baseResult, false, 0);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getResponseHeaders()).contains("HTTP/3");
      } finally {
        client.stop();
      }
    } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
      Assume.assumeNoException("HTTP/3 native libraries not available", e);
    } finally {
      if (connector != null && connector.getServer().isRunning()) {
        connector.getServer().stop();
      }
      restoreProperty("httpJettyClient.enableHttp1", originalEnableHttp1);
      restoreProperty("httpJettyClient.enableHttp2", originalEnableHttp2);
      restoreProperty("httpJettyClient.enableHttp3", originalEnableHttp3);
      restoreProperty("httpJettyClient.altSvcCacheEnabled", originalAltSvc);
      restoreProperty("httpJettyClient.http3PriorKnowledge", originalH3Prior);
    }
  }

  private String getKeyStorePath() {
    try {
      return new File("./").toURI()
          .relativize(getClass().getResource("/com/blazemeter/jmeter/http2/core/keystore.p12").toURI())
          .getPath();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to resolve keystore path", e);
    }
  }

  private void restoreProperty(String key, String value) {
    if (value == null) {
      JMeterUtils.getJMeterProperties().remove(key);
    } else {
      JMeterUtils.setProperty(key, value);
    }
  }
}
