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
import java.util.Map;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.SSLManager;
import org.apache.jmeter.util.keystore.JmeterKeyStore;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMeterJettySslContextFactory extends SslContextFactory.Client
    implements SslClientConnectionFactory.SslEngineFactory {

  private static final Logger LOG = LoggerFactory.getLogger(JMeterJettySslContextFactory.class);

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
        configureKeyStorePathForJetty(keyStorePath, jettyKeyStoreUri, keyStoreType);
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
        configureTrustStorePathForJetty(truststore, jettyTrustStoreUri, trustStoreType);
      }
      getTrustStore((JsseSSLManager) SSLManager.getInstance());
      /*
       we need to set password after getting truststore since getTrustStore may ask the user for the
       password.
      */
      setTrustStorePassword(System.getProperty("javax.net.ssl.trustStorePassword"));
    }
  }

  void configureKeyStorePathForJetty(String originalPath, String jettyUri, String storeType) {
    try {
      setKeyStorePath(jettyUri);
      setKeyStoreType(storeType);
    } catch (RuntimeException e) {
      LOG.warn("Could not set Jetty keyStore path for '{}': {}. "
              + "Client certificate authentication may not work.",
          originalPath, e.getMessage());
      lowLevelDebug("Could not set Jetty keyStore path for '{}'", originalPath, e);
    }
  }

  void configureTrustStorePathForJetty(String originalPath, String jettyUri, String storeType) {
    try {
      setTrustStorePath(jettyUri);
      setTrustStoreType(storeType);
    } catch (RuntimeException e) {
      LOG.warn("Could not set Jetty trustStore path for '{}': {}. "
              + "Trust-all SSL configuration will still be used.",
          originalPath, e.getMessage());
      lowLevelDebug("Could not set Jetty trustStore path for '{}'", originalPath, e);
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

  @Override
  public SSLEngine newSslEngine(String host, int port, Map<String, Object> context) {
    SSLEngine engine = super.newSSLEngine(host, port);
    bindAliasFromContext(engine, context);
    return engine;
  }

  private static void bindAliasFromContext(SSLEngine engine, Map<String, Object> context) {
    String alias = SslClientCertAliasContext.readAlias(context);
    if (alias != null) {
      SslClientCertAliasContext.bindEngine(engine, alias);
    }
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
      return resolveClientAlias(null);
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers,
                                          SSLEngine engine) {
      return resolveClientAlias(engine);
    }

    private String resolveClientAlias(SSLEngine engine) {
      String boundAlias = SslClientCertAliasContext.resolveFromEngine(engine);
      if (boundAlias != null) {
        return boundAlias;
      }
      JMeterVariables variables = JMeterContextService.getContext().getVariables();
      if (variables != null) {
        return store.getAlias();
      }
      return firstConfiguredAlias();
    }

    private String firstConfiguredAlias() {
      String[] aliases = store.getClientAliases("RSA", null);
      if (aliases == null || aliases.length == 0) {
        aliases = store.getClientAliases("EC", null);
      }
      return aliases != null && aliases.length > 0 ? aliases[0] : null;
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
