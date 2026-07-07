package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_IMAGE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_EMBEDDED_CROSS_ORIGIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Regression test: embedded resource downloads must inherit the parent sampler Jetty protocol
 * profile so that origins present in the global HTTP/1-only cache still use HTTP/2 when the parent
 * has HTTP/1 disabled (HTTP/2-only mode).
 *
 * <p>Same-origin downloads clear the HTTP/1-only cache on a successful HTTP/2 main document
 * response, so this test serves the HTML from origin B and the embedded PNG from origin A.</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class EmbeddedResourceInheritsSamplerProtocolRegressionTest extends HTTP2TestBase {

  private TeardownableServer serverAssetOrigin;
  private TeardownableServer serverPageOrigin;

  private int assetPort = -1;
  private int pagePort = -1;

  private HTTP2Sampler warmupHttp1OnlySampler;

  @Before
  public void setUp() throws Exception {
    clearHttp1OnlyOriginCache();

    serverAssetOrigin = new ServerBuilder()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .buildServer();
    serverAssetOrigin.start();
    assetPort = ((ServerConnector) serverAssetOrigin.getConnectors()[0]).getLocalPort();

    warmupHttp1OnlySampler = configuredSampler(assetPort);

    warmupHttp1OnlySampler.setEnableHttp3(false);
    warmupHttp1OnlySampler.setEnableHttp1(true);
    warmupHttp1OnlySampler.setEnableHttp2(false);
    invokeSample(warmupHttp1OnlySampler, httpsUrl(assetPort, SERVER_PATH_200));
    warmupHttp1OnlySampler.threadFinished();

    serverPageOrigin = new ServerBuilder()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .withCrossOriginEmbeddedAssetPort(assetPort)
        .buildServer();
    serverPageOrigin.start();
    pagePort = ((ServerConnector) serverPageOrigin.getConnectors()[0]).getLocalPort();
  }

  @After
  public void tearDown() throws Exception {
    if (warmupHttp1OnlySampler != null) {
      warmupHttp1OnlySampler.threadFinished();
    }
    stopQuietly(serverAssetOrigin);
    stopQuietly(serverPageOrigin);
    assetPort = -1;
    pagePort = -1;
  }

  @Test
  public void embeddedDownloadsUseParentProtocolOverridesWhenEmbeddedOriginMarkedHttp11OnlyCached()
      throws Exception {
    HTTP2Sampler h2Sampler = configuredSampler(pagePort);
    h2Sampler.setConcurrentDwn(false);
    h2Sampler.setImageParser(true);
    h2Sampler.setEnableHttp3(false);
    h2Sampler.setEnableHttp1(false);
    h2Sampler.setEnableHttp2(true);
    try {
      HTTPSampleResult pageResult =
          invokeSample(h2Sampler, httpsUrl(pagePort, SERVER_PATH_200_EMBEDDED_CROSS_ORIGIN));
      SampleResult pngSub =
          findSubResultByUrlContains(pageResult, SERVER_IMAGE.substring(1));
      assertThat(pngSub).as("embedded PNG subsample").isNotNull();
      assertThat(pngSub.getResponseHeaders()).as("embedded resource negotiated protocol")
          .startsWith("HTTP/2");
    } finally {
      h2Sampler.threadFinished();
    }
  }

  private static HTTP2Sampler configuredSampler(int port) throws MalformedURLException {
    HTTP2Sampler s = new HTTP2Sampler();
    s.setMethod(HTTPConstants.GET);
    s.setDomain(HOST_NAME);
    s.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    s.setPort(port);
    s.setPath("");
    return s;
  }

  private static URL httpsUrl(int port, String path) throws MalformedURLException {
    try {
      return new URI(HTTPConstants.PROTOCOL_HTTPS, null, HOST_NAME, port, path, null, null)
          .toURL();
    } catch (URISyntaxException e) {
      MalformedURLException ex = new MalformedURLException(e.getMessage());
      ex.initCause(e);
      throw ex;
    }
  }

  private static HTTPSampleResult invokeSample(HTTP2Sampler sampler, URL url)
      throws Exception {
    Method sample = HTTP2Sampler.class.getDeclaredMethod(
        "sample", URL.class, String.class, boolean.class, int.class);
    sample.setAccessible(true);
    return (HTTPSampleResult) sample.invoke(sampler,
        url, HTTPConstants.GET, false, Integer.valueOf(0));
  }

  private static SampleResult findSubResultByUrlContains(SampleResult root, String substring) {
    if (root.getUrlAsString() != null && root.getUrlAsString().contains(substring)) {
      return root;
    }
    for (SampleResult sr : root.getSubResults()) {
      SampleResult found = findSubResultByUrlContains(sr, substring);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static void clearHttp1OnlyOriginCache() throws Exception {
    java.lang.reflect.Field field =
        HTTP2JettyClient.class.getDeclaredField("HTTP1_ONLY_CACHE");
    field.setAccessible(true);
    Map<Object, Object> map = (Map<Object, Object>) field.get(null);
    map.clear();
  }

  private static void stopQuietly(TeardownableServer server) {
    if (server == null || !server.isStarted()) {
      return;
    }
    try {
      server.stop();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
