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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assume;
import org.junit.Test;

public class HTTP3HappyEyeballsIntegrationTest extends HTTP2TestBase {

  @Test
  public void shouldPreferHttp2WhenNoRecentH3SuccessUsesHalfDelay() throws Exception {
    Assume.assumeTrue("HTTP/3 IT can be disabled with -Dit.http3=false", Boolean.parseBoolean(
        System.getProperty("it.http3", "true")));

    String originalEnableHttp1 = JMeterUtils.getProperty("httpJettyClient.enableHttp1");
    String originalEnableHttp2 = JMeterUtils.getProperty("httpJettyClient.enableHttp2");
    String originalEnableHttp3 = JMeterUtils.getProperty("httpJettyClient.enableHttp3");
    String originalAltSvc = JMeterUtils.getProperty("httpJettyClient.altSvcCacheEnabled");
    String originalH3Prior = JMeterUtils.getProperty("httpJettyClient.http3PriorKnowledge");
    String originalHappyEyeballs = JMeterUtils.getProperty("httpJettyClient.happyEyeballsDelayMs");
    String originalFallback = JMeterUtils.getProperty("httpJettyClient.fallbackEnabled");

    Server h2Server = null;
    Server h3Server = null;

    try {
      clearClientProtocolCaches();
      JMeterUtils.setProperty("httpJettyClient.enableHttp1", "false");
      JMeterUtils.setProperty("httpJettyClient.enableHttp2", "true");
      JMeterUtils.setProperty("httpJettyClient.enableHttp3", "true");
      JMeterUtils.setProperty("httpJettyClient.altSvcCacheEnabled", "true");
      JMeterUtils.setProperty("httpJettyClient.http3PriorKnowledge", "false");
      JMeterUtils.setProperty("httpJettyClient.happyEyeballsDelayMs", "200");
      JMeterUtils.setProperty("httpJettyClient.fallbackEnabled", "true");

      AtomicInteger h2Requests = new AtomicInteger();
      AtomicLong h2DelayMs = new AtomicLong(0L);
      AtomicInteger h3Requests = new AtomicInteger();
      AtomicLong h3DelayMs = new AtomicLong(150L);
      AtomicInteger altSvcPort = new AtomicInteger();

      h2Server = startH2Server(h2Requests, h2DelayMs, altSvcPort);
      int port = ((ServerConnector) h2Server.getConnectors()[0]).getLocalPort();
      altSvcPort.set(port);

      h3Server = startH3Server(port, h3Requests, h3DelayMs);

      HTTP2Sampler sampler = buildSampler(port);
      URL url = URI.create("https://localhost:" + port + "/").toURL();

      HTTP2JettyClient client = new HTTP2JettyClient(false, "IT-HTTP3-HE-NoRecent");
      try {
        client.start();
        HTTPSampleResult first = sample(client, sampler, url);
        assertThat(first.isSuccessful()).isTrue();
        assertThat(first.getResponseHeaders()).startsWith("HTTP/2");

        long effectiveDelay = computeHappyEyeballsDelay(client, url.toURI());
        assertThat(effectiveDelay).isEqualTo(100L);

        HTTPSampleResult second = sample(client, sampler, url);
        assertThat(second.isSuccessful()).isTrue();
        assertThat(second.getResponseHeaders()).startsWith("HTTP/2");
      } finally {
        client.stop();
      }
    } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
      Assume.assumeNoException("HTTP/3 native libraries not available", e);
    } finally {
      stopServer(h3Server);
      stopServer(h2Server);
      restoreProperty("httpJettyClient.enableHttp1", originalEnableHttp1);
      restoreProperty("httpJettyClient.enableHttp2", originalEnableHttp2);
      restoreProperty("httpJettyClient.enableHttp3", originalEnableHttp3);
      restoreProperty("httpJettyClient.altSvcCacheEnabled", originalAltSvc);
      restoreProperty("httpJettyClient.http3PriorKnowledge", originalH3Prior);
      restoreProperty("httpJettyClient.happyEyeballsDelayMs", originalHappyEyeballs);
      restoreProperty("httpJettyClient.fallbackEnabled", originalFallback);
    }
  }

  @Test
  public void shouldPreferHttp3WhenRecentSuccessKeepsBaseDelay() throws Exception {
    Assume.assumeTrue("HTTP/3 IT can be disabled with -Dit.http3=false", Boolean.parseBoolean(
        System.getProperty("it.http3", "true")));

    String originalEnableHttp1 = JMeterUtils.getProperty("httpJettyClient.enableHttp1");
    String originalEnableHttp2 = JMeterUtils.getProperty("httpJettyClient.enableHttp2");
    String originalEnableHttp3 = JMeterUtils.getProperty("httpJettyClient.enableHttp3");
    String originalAltSvc = JMeterUtils.getProperty("httpJettyClient.altSvcCacheEnabled");
    String originalH3Prior = JMeterUtils.getProperty("httpJettyClient.http3PriorKnowledge");
    String originalHappyEyeballs = JMeterUtils.getProperty("httpJettyClient.happyEyeballsDelayMs");
    String originalFallback = JMeterUtils.getProperty("httpJettyClient.fallbackEnabled");

    Server h2Server = null;
    Server h3Server = null;

    try {
      clearClientProtocolCaches();
      JMeterUtils.setProperty("httpJettyClient.enableHttp1", "false");
      JMeterUtils.setProperty("httpJettyClient.enableHttp2", "true");
      JMeterUtils.setProperty("httpJettyClient.enableHttp3", "true");
      JMeterUtils.setProperty("httpJettyClient.altSvcCacheEnabled", "true");
      JMeterUtils.setProperty("httpJettyClient.http3PriorKnowledge", "false");
      JMeterUtils.setProperty("httpJettyClient.happyEyeballsDelayMs", "200");
      JMeterUtils.setProperty("httpJettyClient.fallbackEnabled", "true");

      AtomicInteger h2Requests = new AtomicInteger();
      AtomicLong h2DelayMs = new AtomicLong(0L);
      AtomicInteger h3Requests = new AtomicInteger();
      AtomicLong h3DelayMs = new AtomicLong(0L);
      AtomicInteger altSvcPort = new AtomicInteger();

      h2Server = startH2Server(h2Requests, h2DelayMs, altSvcPort);
      int port = ((ServerConnector) h2Server.getConnectors()[0]).getLocalPort();
      altSvcPort.set(port);

      h3Server = startH3Server(port, h3Requests, h3DelayMs);

      HTTP2Sampler sampler = buildSampler(port);
      URL url = URI.create("https://localhost:" + port + "/").toURL();

      HTTP2JettyClient client = new HTTP2JettyClient(false, "IT-HTTP3-HE-Recent");
      try {
        client.start();
        // Phase A: establish Alt-Svc and force one confirmed H3 success first.
        HTTPSampleResult first = sample(client, sampler, url);
        assertThat(first.isSuccessful()).isTrue();
        assertThat(first.getResponseHeaders()).startsWith("HTTP/2");

        h2DelayMs.set(500L);
        h3DelayMs.set(0L);
        int h3BeforeWarmup = h3Requests.get();
        HTTPSampleResult warmupH3 = null;
        for (int attempt = 0; attempt < 6; attempt++) {
          warmupH3 = sample(client, sampler, url);
          assertThat(warmupH3.isSuccessful()).isTrue();
          if (h3Requests.get() > h3BeforeWarmup
              && warmupH3.getResponseHeaders().contains("HTTP/3")) {
            break;
          }
        }
        assertThat(warmupH3).isNotNull();
        assertThat(warmupH3.getResponseHeaders()).contains("HTTP/3");
        assertThat(h3Requests.get()).isGreaterThan(h3BeforeWarmup);

        // Phase B: recent H3 success must keep base delay and attempt H3 in the race.
        long effectiveDelay = computeHappyEyeballsDelay(client, url.toURI());
        assertThat(effectiveDelay).isEqualTo(200L);

        h2DelayMs.set(40L);
        h3DelayMs.set(150L);
        int h3BeforeRace = h3Requests.get();
        HTTPSampleResult raceAfterRecentSuccess = sample(client, sampler, url);
        assertThat(raceAfterRecentSuccess.isSuccessful()).isTrue();
        assertThat(h3Requests.get()).isGreaterThan(h3BeforeRace);
      } finally {
        client.stop();
      }
    } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
      Assume.assumeNoException("HTTP/3 native libraries not available", e);
    } finally {
      stopServer(h3Server);
      stopServer(h2Server);
      restoreProperty("httpJettyClient.enableHttp1", originalEnableHttp1);
      restoreProperty("httpJettyClient.enableHttp2", originalEnableHttp2);
      restoreProperty("httpJettyClient.enableHttp3", originalEnableHttp3);
      restoreProperty("httpJettyClient.altSvcCacheEnabled", originalAltSvc);
      restoreProperty("httpJettyClient.http3PriorKnowledge", originalH3Prior);
      restoreProperty("httpJettyClient.happyEyeballsDelayMs", originalHappyEyeballs);
      restoreProperty("httpJettyClient.fallbackEnabled", originalFallback);
    }
  }

  @Test
  public void shouldSkipHttp3WhenMarkedBroken() throws Exception {
    Assume.assumeTrue("HTTP/3 IT can be disabled with -Dit.http3=false", Boolean.parseBoolean(
        System.getProperty("it.http3", "true")));

    String originalEnableHttp1 = JMeterUtils.getProperty("httpJettyClient.enableHttp1");
    String originalEnableHttp2 = JMeterUtils.getProperty("httpJettyClient.enableHttp2");
    String originalEnableHttp3 = JMeterUtils.getProperty("httpJettyClient.enableHttp3");
    String originalAltSvc = JMeterUtils.getProperty("httpJettyClient.altSvcCacheEnabled");
    String originalH3Prior = JMeterUtils.getProperty("httpJettyClient.http3PriorKnowledge");
    String originalHappyEyeballs = JMeterUtils.getProperty("httpJettyClient.happyEyeballsDelayMs");
    String originalFallback = JMeterUtils.getProperty("httpJettyClient.fallbackEnabled");
    String originalBrokenCooldown =
        JMeterUtils.getProperty("httpJettyClient.http3BrokenCooldownMs");

    Server h2Server = null;
    Server h3Server = null;

    try {
      clearClientProtocolCaches();
      JMeterUtils.setProperty("httpJettyClient.enableHttp1", "false");
      JMeterUtils.setProperty("httpJettyClient.enableHttp2", "true");
      JMeterUtils.setProperty("httpJettyClient.enableHttp3", "true");
      JMeterUtils.setProperty("httpJettyClient.altSvcCacheEnabled", "true");
      JMeterUtils.setProperty("httpJettyClient.http3PriorKnowledge", "false");
      JMeterUtils.setProperty("httpJettyClient.happyEyeballsDelayMs", "200");
      JMeterUtils.setProperty("httpJettyClient.fallbackEnabled", "true");
      JMeterUtils.setProperty("httpJettyClient.http3BrokenCooldownMs", "5000");

      AtomicInteger h2Requests = new AtomicInteger();
      AtomicLong h2DelayMs = new AtomicLong(0L);
      AtomicInteger h3Requests = new AtomicInteger();
      AtomicLong h3DelayMs = new AtomicLong(0L);
      AtomicInteger altSvcPort = new AtomicInteger();

      h2Server = startH2Server(h2Requests, h2DelayMs, altSvcPort);
      int port = ((ServerConnector) h2Server.getConnectors()[0]).getLocalPort();
      altSvcPort.set(port);

      h3Server = startH3Server(port, h3Requests, h3DelayMs);

      HTTP2Sampler sampler = buildSampler(port);
      URL url = URI.create("https://localhost:" + port + "/").toURL();

      HTTP2JettyClient client = new HTTP2JettyClient(false, "IT-HTTP3-HE-Broken");
      try {
        client.start();
        HTTPSampleResult first = sample(client, sampler, url);
        assertThat(first.isSuccessful()).isTrue();
        assertThat(first.getResponseHeaders()).startsWith("HTTP/2");

        markHttp3Broken(client, url.toURI());

        HTTPSampleResult second = sample(client, sampler, url);
        assertThat(second.isSuccessful()).isTrue();
        assertThat(second.getResponseHeaders()).startsWith("HTTP/2");
        assertThat(h3Requests.get()).isEqualTo(0);
      } finally {
        client.stop();
      }
    } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
      Assume.assumeNoException("HTTP/3 native libraries not available", e);
    } finally {
      stopServer(h3Server);
      stopServer(h2Server);
      restoreProperty("httpJettyClient.enableHttp1", originalEnableHttp1);
      restoreProperty("httpJettyClient.enableHttp2", originalEnableHttp2);
      restoreProperty("httpJettyClient.enableHttp3", originalEnableHttp3);
      restoreProperty("httpJettyClient.altSvcCacheEnabled", originalAltSvc);
      restoreProperty("httpJettyClient.http3PriorKnowledge", originalH3Prior);
      restoreProperty("httpJettyClient.happyEyeballsDelayMs", originalHappyEyeballs);
      restoreProperty("httpJettyClient.fallbackEnabled", originalFallback);
      restoreProperty("httpJettyClient.http3BrokenCooldownMs", originalBrokenCooldown);
    }
  }

    private Server startH2Server(AtomicInteger h2Requests, AtomicLong h2DelayMs,
      AtomicInteger altSvcPort)
      throws Exception {
    Server server = new Server();
    HttpConfiguration httpsConfig = new HttpConfiguration();
    httpsConfig.addCustomizer(new org.eclipse.jetty.server.SecureRequestCustomizer());
    HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
    HttpConnectionFactory http1 = new HttpConnectionFactory(httpsConfig);
    ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
    alpn.setDefaultProtocol(http1.getProtocol());

    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStorePath(getKeyStorePath());
    sslContextFactory.setKeyStorePassword("storepwd");

    ServerConnector connector = new ServerConnector(server,
        new SslConnectionFactory(sslContextFactory, alpn.getProtocol()),
        alpn, h2, http1);
    connector.setPort(0);
    server.addConnector(connector);

    server.setHandler(new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) {
        h2Requests.incrementAndGet();
        long delay = h2DelayMs.get();
        if (delay > 0) {
          try {
            Thread.sleep(delay);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        String altSvcValue = "h3=" + '"' + ":" + altSvcPort.get() + '"' + "; ma=60";
        response.getHeaders().put(HttpHeader.ALT_SVC, altSvcValue);
        Content.Sink.write(response, true, "ok\n", callback);
        return true;
      }
    });

    server.start();
    return server;
  }

  private Server startH3Server(int port, AtomicInteger h3Requests, AtomicLong h3DelayMs)
      throws Exception {
    Server server = new Server();

    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStorePath(getKeyStorePath());
    sslContextFactory.setKeyStorePassword("storepwd");

    Path workDir = Files.createTempDirectory("http3-he-it");
    QuicheServerQuicConfiguration quicConfig =
        HTTP3ServerQuicConfiguration.configure(new QuicheServerQuicConfiguration(workDir));
    HTTP3ServerConnectionFactory h3 = new HTTP3ServerConnectionFactory();
    QuicheServerConnector connector = new QuicheServerConnector(server, sslContextFactory,
        quicConfig, h3);
    connector.setPort(port);
    server.addConnector(connector);

    server.setHandler(new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) {
        h3Requests.incrementAndGet();
        long delay = h3DelayMs.get();
        if (delay > 0) {
          try {
            Thread.sleep(delay);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        Content.Sink.write(response, true, "ok\n", callback);
        return true;
      }
    });

    server.start();
    return server;
  }

  private HTTP2Sampler buildSampler(int port) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPath("/");
    sampler.setConnectTimeout("2000");
    sampler.setResponseTimeout("5000");
    return sampler;
  }

  private HTTPSampleResult sample(HTTP2JettyClient client, HTTP2Sampler sampler, URL url)
      throws Exception {
    HTTPSampleResult baseResult = new HTTPSampleResult();
    baseResult.setURL(url);
    baseResult.setHTTPMethod(HTTPConstants.GET);
    return client.sample(sampler, baseResult, false, 0);
  }

  private void markHttp3Broken(HTTP2JettyClient client, URI uri) throws Exception {
    java.lang.reflect.Method method = HTTP2JettyClient.class.getDeclaredMethod(
        "markHttp3Broken", URI.class);
    method.setAccessible(true);
    method.invoke(client, uri);
  }

  private long computeHappyEyeballsDelay(HTTP2JettyClient client, URI uri) throws Exception {
    java.lang.reflect.Method method = HTTP2JettyClient.class.getDeclaredMethod(
        "computeHappyEyeballsDelayMs", URI.class);
    method.setAccessible(true);
    return (long) method.invoke(client, uri);
  }

  private void clearClientProtocolCaches() throws Exception {
    clearStaticMap("ALT_SVC_CACHE");
    clearStaticMap("HTTP1_ONLY_CACHE");
    clearStaticMap("H2C_CACHE");
  }

  @SuppressWarnings("unchecked")
  private void clearStaticMap(String fieldName) throws Exception {
    java.lang.reflect.Field field = HTTP2JettyClient.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    Map<Object, Object> map = (Map<Object, Object>) field.get(null);
    if (map != null) {
      map.clear();
    }
  }

  private void stopServer(Server server) {
    if (server == null) {
      return;
    }
    try {
      if (server.isRunning()) {
        server.stop();
      }
    } catch (Exception ignored) {
      // best-effort shutdown
    }
  }

  private String getKeyStorePath() {
    try {
      return new File("./").toURI()
          .relativize(getClass().getResource("/com/blazemeter/jmeter/http2/core/keystore.p12")
              .toURI())
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
