package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.LowLevelDebugLog.lowLevelDebug;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CRL;
import java.security.cert.X509Certificate;
import java.util.Collection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.SSLManager;
import org.apache.jmeter.util.keystore.JmeterKeyStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class JMeterJettySslContextFactory extends SslContextFactory.Client {

  private final JmeterKeyStore keys;

  public JMeterJettySslContextFactory() {
    setTrustAll(true);
    setValidatePeerCerts(false);
    String keyStorePath = System.getProperty("javax.net.ssl.keyStore");
    if (keyStorePath != null && !keyStorePath.isEmpty()) {
      if (SslStorePathResolver.isFileBasedStoreLocation(keyStorePath)) {
        String jettyKeyStoreUri = SslStorePathResolver.toJettyFileUri(keyStorePath);
        String keyStoreType = SslStorePathResolver.resolveKeyStoreType(keyStorePath);
        lowLevelDebug(
            "SSL keyStore path resolved: javax.net.ssl.keyStore='{}' -> jettyUri='{}'",
            keyStorePath, jettyKeyStoreUri);
        lowLevelDebug(
            "SSL keyStore type resolved: javax.net.ssl.keyStoreType='{}' -> jettyType='{}'",
            System.getProperty("javax.net.ssl.keyStoreType"), keyStoreType);
        setKeyStorePath(jettyKeyStoreUri);
        setKeyStoreType(keyStoreType);
      }
      keys = getKeyStore((JsseSSLManager) SSLManager.getInstance());
      /*
       we need to set password after getting keystore since getKeystore may ask the user for the
       password.
      */
      setKeyStorePassword(System.getProperty("javax.net.ssl.keyStorePassword"));
    } else {
      keys = null;
    }

    String truststore = System.getProperty("javax.net.ssl.trustStore");
    if (truststore != null && !truststore.isEmpty()) {
      if (SslStorePathResolver.isFileBasedStoreLocation(truststore)) {
        String jettyTrustStoreUri = SslStorePathResolver.toJettyFileUri(truststore);
        String trustStoreType = SslStorePathResolver.resolveTrustStoreType(truststore);
        lowLevelDebug(
            "SSL trustStore path resolved: javax.net.ssl.trustStore='{}' -> jettyUri='{}'",
            truststore, jettyTrustStoreUri);
        lowLevelDebug(
            "SSL trustStore type resolved: javax.net.ssl.trustStoreType='{}' -> jettyType='{}'",
            System.getProperty("javax.net.ssl.trustStoreType"), trustStoreType);
        setTrustStorePath(jettyTrustStoreUri);
        setTrustStoreType(trustStoreType);
      }
      getTrustStore((JsseSSLManager) SSLManager.getInstance());
      /*
       we need to set password after getting truststore since getTrustStore may ask the user for the
       password.
      */
      setTrustStorePassword(System.getProperty("javax.net.ssl.trustStorePassword"));
    }
  }

  private JmeterKeyStore getKeyStore(JsseSSLManager sslManager) {
    try {
      Method keystoreMethod = SSLManager.class.getDeclaredMethod("getKeyStore");
      keystoreMethod.setAccessible(true);
      return (JmeterKeyStore) keystoreMethod.invoke(sslManager);
    } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  private KeyStore getTrustStore(JsseSSLManager sslManager) {
    try {
      Method trustStoreMethod = SSLManager.class.getDeclaredMethod("getTrustStore");
      trustStoreMethod.setAccessible(true);
      return (KeyStore) trustStoreMethod.invoke(sslManager);
    } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  // Overwritten to avoid warning logging
  @Override
  protected void checkTrustAll() {
  }

  // Overwritten to avoid warning logging
  @Override
  protected void checkEndPointIdentificationAlgorithm() {
  }

  // JMeter HTTP uses CustomX509TrustManager which does not validate server certificates.
  // Jetty still runs PKIX when a keyStore is configured unless trust managers are overridden.
  @Override
  protected TrustManager[] getTrustManagers(KeyStore trustStore,
      Collection<? extends CRL> crls) throws Exception {
    if (isTrustAll()) {
      lowLevelDebug("SSL trust managers: using TRUST_ALL_CERTS (JMeter HTTP parity)");
      return TRUST_ALL_CERTS;
    }
    return super.getTrustManagers(trustStore, crls);
  }

  // Overwritten to provide jmeter SSLManager configured keyManagers
  @Override
  protected KeyManager[] getKeyManagers(KeyStore keyStore) throws Exception {
    // based in logic extracted from JsseSSLManager.createContext
    KeyManager[] ret = super.getKeyManagers(keyStore);
    if (keys == null) {
      return ret;
    }
    for (int i = 0; i < ret.length; i++) {
      if (ret[i] instanceof X509KeyManager) {
        ret[i] = new WrappedX509KeyManager((X509KeyManager) ret[i], keys);
      }
    }
    return ret;
  }

  // based in logic extracted from JsseSSLManager.WrappedX509KeyManager
  private static class WrappedX509KeyManager extends X509ExtendedKeyManager {

    private final X509KeyManager manager;
    private final JmeterKeyStore store;

    private WrappedX509KeyManager(X509KeyManager parent, JmeterKeyStore ks) {
      this.manager = parent;
      this.store = ks;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
      return store.getClientAliases(keyType, issuers);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
      return manager.getServerAliases(keyType, issuers);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
      return store.getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
      return store.getPrivateKey(alias);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
      return store.getAlias();
    }

    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers,
                                          SSLEngine engine) {
      return store.getAlias();
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
      return this.manager.chooseServerAlias(keyType, issuers, socket);
    }

    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
      return manager instanceof X509ExtendedKeyManager
          ? ((X509ExtendedKeyManager) manager).chooseEngineServerAlias(keyType, issuers, engine)
          : null;
    }

  }

}
