package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.SSLManager;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SslClientCertAliasSupportTest extends HTTP2TestBase {

  private static final String CERT_ALIAS_VAR = "certAlias";
  private static final String KEY_ALIAS = "client";

  private String previousKeyStore;
  private String previousKeyStorePassword;
  private String previousKeyStoreType;
  private Path clientKeystore;

  @Before
  public void setUpKeystore() throws Exception {
    clientKeystore = TestSslKeyStores.createJks(KEY_ALIAS, "CN=client, O=SslAliasTest, C=US");

    previousKeyStore = System.getProperty("javax.net.ssl.keyStore");
    previousKeyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
    previousKeyStoreType = System.getProperty("javax.net.ssl.keyStoreType");

    SSLManager.reset();
    System.setProperty("javax.net.ssl.keyStore", clientKeystore.toString());
    System.setProperty("javax.net.ssl.keyStorePassword", TestSslKeyStores.PASSWORD);
    System.setProperty("javax.net.ssl.keyStoreType", "JKS");
    SSLManager.getInstance().configureKeystore(true, 0, -1, CERT_ALIAS_VAR);

    JMeterVariables variables = new JMeterVariables();
    variables.put(CERT_ALIAS_VAR, KEY_ALIAS);
    JMeterContextService.getContext().setVariables(variables);
  }

  @After
  public void tearDown() throws IOException {
    restoreProperty("javax.net.ssl.keyStore", previousKeyStore);
    restoreProperty("javax.net.ssl.keyStorePassword", previousKeyStorePassword);
    restoreProperty("javax.net.ssl.keyStoreType", previousKeyStoreType);
    JMeterContextService.getContext().setVariables(null);
    SSLManager.reset();
    if (clientKeystore != null) {
      Files.deleteIfExists(clientKeystore);
      Path parent = clientKeystore.getParent();
      if (parent != null) {
        Files.deleteIfExists(parent);
      }
      clientKeystore = null;
    }
  }

  @Test
  public void shouldResolveAliasOnJMeterThreadWhenVariableIsConfigured() {
    assertThat(JMeterSslAliasResolver.resolveForRequest()).isEqualTo(KEY_ALIAS);
  }

  @Test
  public void shouldInjectAliasIntoConnectionContextFromRequestTag() throws Exception {
    SslClientCertAliasTag tag = new SslClientCertAliasTag(KEY_ALIAS);
    ClientConnectionFactory decorated = tag.apply((endPoint, context) -> null);
    Map<String, Object> context = new HashMap<>();

    decorated.newConnection(null, context);

    assertThat(SslClientCertAliasContext.readAlias(context)).isEqualTo(KEY_ALIAS);
  }

  @Test
  public void shouldChooseBoundAliasOnJettyThreadWithoutJMeterVariables() throws Exception {
    String alias = JMeterSslAliasResolver.resolveForRequest();
    JMeterContextService.getContext().setVariables(null);

    JMeterJettySslContextFactory factory = new JMeterJettySslContextFactory();
    factory.start();
    try {
      Method getKeyManagers = SslContextFactory.class
          .getDeclaredMethod("getKeyManagers", KeyStore.class);
      getKeyManagers.setAccessible(true);
      KeyStore keyStore = KeyStore.getInstance("JKS");
      try (InputStream in = Files.newInputStream(clientKeystore)) {
        keyStore.load(in, TestSslKeyStores.PASSWORD.toCharArray());
      }
      KeyManager keyManager = ((KeyManager[]) getKeyManagers.invoke(factory, keyStore))[0];
      assertThat(keyManager).isInstanceOf(X509ExtendedKeyManager.class);

      SSLEngine engine = factory.getSslContext().createSSLEngine();
      SslClientCertAliasContext.bindEngine(engine, alias);

      assertThatCode(() -> ((X509ExtendedKeyManager) keyManager)
          .chooseEngineClientAlias(new String[] {"RSA"}, new Principal[0], engine))
          .doesNotThrowAnyException();
      assertThat(((X509ExtendedKeyManager) keyManager)
          .chooseEngineClientAlias(new String[] {"RSA"}, new Principal[0], engine))
          .isEqualTo(KEY_ALIAS);
    } finally {
      factory.stop();
    }
  }

  @Test
  public void shouldBindAliasFromSslEngineFactoryContext() throws Exception {
    JMeterJettySslContextFactory factory = new JMeterJettySslContextFactory();
    factory.start();
    try {
      Map<String, Object> context = new HashMap<>();
      context.put(SslClientCertAliasContext.CONNECTION_CONTEXT_KEY, KEY_ALIAS);

      Method method = SslClientConnectionFactory.SslEngineFactory.class
          .getMethod("newSslEngine", String.class, int.class, Map.class);
      SSLEngine engine = (SSLEngine) method.invoke(factory, "localhost", 443, context);

      assertThat(SslClientCertAliasContext.resolveFromEngine(engine)).isEqualTo(KEY_ALIAS);
    } finally {
      factory.stop();
    }
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

}
