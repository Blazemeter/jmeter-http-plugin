package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CRL;
import java.util.Collection;
import javax.net.ssl.TrustManager;
import org.apache.jmeter.util.SSLManager;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Test;

public class JMeterJettySslContextFactoryTest extends HTTP2TestBase {

  private String previousKeyStore;
  private String previousKeyStorePassword;
  private Path tempKeystore;

  @After
  public void restoreSystemProperties() throws IOException {
    restoreProperty("javax.net.ssl.keyStore", previousKeyStore);
    restoreProperty("javax.net.ssl.keyStorePassword", previousKeyStorePassword);
    SSLManager.reset();
    if (tempKeystore != null) {
      Files.deleteIfExists(tempKeystore);
      tempKeystore = null;
    }
  }

  @Test
  public void shouldConstructWithFileBasedKeyStorePath() throws IOException {
    tempKeystore = Files.createTempFile("jmeter-jetty-ssl", ".p12");
    try (InputStream in = getClass().getResourceAsStream("keystore.p12")) {
      if (in == null) {
        throw new IllegalStateException("classpath resource keystore.p12 not found");
      }
      in.transferTo(Files.newOutputStream(tempKeystore));
    }

    previousKeyStore = System.getProperty("javax.net.ssl.keyStore");
    previousKeyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");

    SSLManager.reset();
    System.setProperty("javax.net.ssl.keyStore", tempKeystore.toString());
    System.setProperty("javax.net.ssl.keyStorePassword", ServerBuilder.KEYSTORE_PASSWORD);

    assertThatCode(JMeterJettySslContextFactory::new).doesNotThrowAnyException();
  }

  @Test
  public void shouldUseTrustAllManagersWhenKeyStoreIsConfigured() throws Exception {
    tempKeystore = Files.createTempFile("jmeter-jetty-ssl-trust", ".p12");
    try (InputStream in = getClass().getResourceAsStream("keystore.p12")) {
      if (in == null) {
        throw new IllegalStateException("classpath resource keystore.p12 not found");
      }
      in.transferTo(Files.newOutputStream(tempKeystore));
    }

    previousKeyStore = System.getProperty("javax.net.ssl.keyStore");
    previousKeyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");

    SSLManager.reset();
    System.setProperty("javax.net.ssl.keyStore", tempKeystore.toString());
    System.setProperty("javax.net.ssl.keyStorePassword", ServerBuilder.KEYSTORE_PASSWORD);

    TestableJMeterJettySslContextFactory factory = new TestableJMeterJettySslContextFactory();
    TrustManager[] trustManagers = factory.getTrustManagersForTest(null, null);

    assertThat(trustManagers).isSameAs(SslContextFactory.TRUST_ALL_CERTS);
  }

  @Test
  public void shouldConstructWhenKeyStoreIsPkcs11None() {
    previousKeyStore = System.getProperty("javax.net.ssl.keyStore");
    previousKeyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");

    SSLManager.reset();
    System.setProperty("javax.net.ssl.keyStore",
        SslStorePathResolver.NON_FILE_KEYSTORE_LOCATION);

    assertThatCode(JMeterJettySslContextFactory::new).doesNotThrowAnyException();
  }

  private static final class TestableJMeterJettySslContextFactory
      extends JMeterJettySslContextFactory {

    TrustManager[] getTrustManagersForTest(java.security.KeyStore trustStore,
        Collection<? extends CRL> crls) throws Exception {
      return getTrustManagers(trustStore, crls);
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
