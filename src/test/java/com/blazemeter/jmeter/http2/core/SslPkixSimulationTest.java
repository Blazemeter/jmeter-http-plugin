package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.SSLManager;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Reproduces BlazeMeter-like PKIX failures: client {@code javax.net.ssl.keyStore} configured
 * (JKS) while the server presents a certificate that is not in the JVM trust store.
 */
public class SslPkixSimulationTest extends HTTP2TestBase {

  private static final String KEY_STORE_PROPERTY = "javax.net.ssl.keyStore";
  private static final String KEY_STORE_PASSWORD_PROPERTY = "javax.net.ssl.keyStorePassword";
  private static final String KEY_STORE_TYPE_PROPERTY = "javax.net.ssl.keyStoreType";

  private String previousKeyStore;
  private String previousKeyStorePassword;
  private String previousKeyStoreType;
  private String previousSharedThreadPoolProperty;

  private Path serverKeystore;
  private Path clientKeystore;
  private TeardownableServer server;
  private int serverPort = -1;
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;

  @Before
  public void setUp() throws Exception {
    JMeterTestUtils.setupJmeterEnv();
    previousKeyStore = System.getProperty(KEY_STORE_PROPERTY);
    previousKeyStorePassword = System.getProperty(KEY_STORE_PASSWORD_PROPERTY);
    previousKeyStoreType = System.getProperty(KEY_STORE_TYPE_PROPERTY);
    previousSharedThreadPoolProperty = JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");

    serverKeystore = TestSslKeyStores.createJks("server", "CN=localhost, O=SslSim, C=US");
    clientKeystore = TestSslKeyStores.createJks("client", "CN=client, O=SslSim, C=US");

    configureClientKeyStoreLikeBlazeMeter(clientKeystore);
    startUntrustedHttpsServer();

    sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain(ServerBuilder.HOST_NAME);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(serverPort);
    sampler.setPath("");
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
      client = null;
    }
    if (sampler != null) {
      sampler.threadFinished();
      sampler = null;
    }
    if (server != null) {
      server.stop();
      server = null;
    }
    restoreProperty(KEY_STORE_PROPERTY, previousKeyStore);
    restoreProperty(KEY_STORE_PASSWORD_PROPERTY, previousKeyStorePassword);
    restoreProperty(KEY_STORE_TYPE_PROPERTY, previousKeyStoreType);
    SSLManager.reset();
    if (previousSharedThreadPoolProperty == null) {
      JMeterUtils.getJMeterProperties().remove("httpJettyClient.sharedThreadPool");
    } else {
      JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", previousSharedThreadPoolProperty);
    }
    deleteIfExists(serverKeystore);
    deleteIfExists(clientKeystore);
  }

  @Test
  public void shouldReproducePkixWhenTrustAllIsSetButTrustManagersAreNotOverridden() {
    TrustAllWithoutTrustManagerOverride factory = new TrustAllWithoutTrustManagerOverride();

    assertThatThrownBy(() -> performTlsHandshake(factory))
        .isInstanceOf(SSLHandshakeException.class)
        .hasMessageContaining("PKIX path building failed");
  }

  @Test
  public void shouldHandshakeWhenJMeterJettySslContextFactoryIsUsed() {
    assertThatCode(() -> performTlsHandshake(new JMeterJettySslContextFactory()))
        .doesNotThrowAnyException();
  }

  @Test
  public void shouldCompleteHttp2RequestWhenClientKeyStoreConfiguredAgainstUntrustedServer()
      throws Exception {
    client = new HTTP2JettyClient();
    client.start();

    HTTPSampleResult result = client.sample(
        sampler,
        buildBaseResult(createUrl(ServerBuilder.SERVER_PATH_200), HTTPConstants.GET),
        false,
        0);

    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseDataAsString()).isEqualTo(ServerBuilder.SERVER_RESPONSE);
  }

  private void startUntrustedHttpsServer() throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .withServerKeyStorePath(serverKeystore.toString(), "JKS")
        .buildServer();
    server.start();
  }

  private void configureClientKeyStoreLikeBlazeMeter(Path keyStorePath) {
    SSLManager.reset();
    System.setProperty(KEY_STORE_PROPERTY, keyStorePath.toString());
    System.setProperty(KEY_STORE_PASSWORD_PROPERTY, TestSslKeyStores.PASSWORD);
    System.setProperty(KEY_STORE_TYPE_PROPERTY, "JKS");
  }

  private void performTlsHandshake(SslContextFactory.Client factory) throws Exception {
    if (serverPort < 0) {
      ServerConnector connector = (ServerConnector) server.getConnectors()[0];
      serverPort = connector.getLocalPort();
    }
    factory.start();
    try {
      try (SSLSocket socket = (SSLSocket) factory.getSslContext().getSocketFactory()
          .createSocket(ServerBuilder.HOST_NAME, serverPort)) {
        socket.setSoTimeout(10_000);
        socket.startHandshake();
      }
    } finally {
      factory.stop();
    }
  }

  private URL createUrl(String path) throws Exception {
    if (serverPort < 0) {
      ServerConnector connector = (ServerConnector) server.getConnectors()[0];
      serverPort = connector.getLocalPort();
    }
    sampler.setPort(serverPort);
    return new URI(HTTPConstants.PROTOCOL_HTTPS, null, ServerBuilder.HOST_NAME, serverPort, path,
        null, null).toURL();
  }

  private static HTTPSampleResult buildBaseResult(URL url, String method) {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(url);
    result.setHTTPMethod(method);
    return result;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static void deleteIfExists(Path path) throws IOException {
    if (path != null) {
      Files.deleteIfExists(path);
    }
  }

  /**
   * Mirrors {@code setTrustAll(true)} plus client keyStore configuration, but without the
   * {@link JMeterJettySslContextFactory#getTrustManagers} override (pre-fix Jetty behaviour).
   */
  private static final class TrustAllWithoutTrustManagerOverride extends SslContextFactory.Client {

    private TrustAllWithoutTrustManagerOverride() {
      setTrustAll(true);
      String keyStorePath = System.getProperty(KEY_STORE_PROPERTY);
      if (keyStorePath != null && !keyStorePath.isEmpty()
          && SslStorePathResolver.isFileBasedStoreLocation(keyStorePath)) {
        setKeyStorePath(SslStorePathResolver.toJettyFileUri(keyStorePath));
        setKeyStoreType(SslStorePathResolver.resolveKeyStoreType(keyStorePath));
        setKeyStorePassword(System.getProperty(KEY_STORE_PASSWORD_PROPERTY));
      }
    }
  }

}
