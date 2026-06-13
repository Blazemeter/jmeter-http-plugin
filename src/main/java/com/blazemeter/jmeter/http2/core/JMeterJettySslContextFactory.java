package com.blazemeter.jmeter.http2.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.SSLManager;
import org.apache.jmeter.util.keystore.JmeterKeyStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Jetty SSL client wired to JMeter {@link SSLManager}.
 * Keystore/truststore path resolution improvements are tracked in
 * <a href="https://github.com/Blazemeter/jmeter-http-plugin/pull/112">PR #112</a>.
 */
public class JMeterJettySslContextFactory extends SslContextFactory.Client {

  private final JmeterKeyStore keys;
  private final KeyManager[] configuredKeyManagers;

  public JMeterJettySslContextFactory() {
    setTrustAll(true);
    String keyStorePath = System.getProperty("javax.net.ssl.keyStore");
    if (keyStorePath != null && !keyStorePath.isEmpty()) {
      setKeyStorePath(toStoreUri(keyStorePath));
      setKeyStorePassword(System.getProperty("javax.net.ssl.keyStorePassword"));
      configuredKeyManagers = loadKeyManagersFromPath(keyStorePath);
      keys = loadJmeterKeyStoreFromPath(keyStorePath);
    } else {
      configuredKeyManagers = null;
      keys = null;
    }

    String truststore = System.getProperty("javax.net.ssl.trustStore");
    if (truststore != null && !truststore.isEmpty()) {
      setTrustStorePath(toStoreUri(truststore));
      getTrustStore((JsseSSLManager) SSLManager.getInstance());
      /*
       we need to set password after getting truststore since getTrustStore may ask the user for the
       password.
      */
      setTrustStorePassword(System.getProperty("javax.net.ssl.trustStorePassword"));
    }
  }

  private static String toStoreUri(String storePath) {
    if (storePath.regionMatches(true, 0, "file:", 0, 5)) {
      return storePath;
    }
    Path path = Paths.get(storePath);
    return path.toUri().toString();
  }

  private static String resolveKeyStoreType(String keyStorePath) {
    String keyStoreType = System.getProperty("javax.net.ssl.keyStoreType");
    if (keyStoreType != null && !keyStoreType.isEmpty()) {
      return keyStoreType;
    }
    String lowerPath = keyStorePath.toLowerCase(Locale.ENGLISH);
    if (lowerPath.endsWith(".p12") || lowerPath.endsWith(".pfx")) {
      return "PKCS12";
    }
    return KeyStore.getDefaultType();
  }

  private static KeyManager[] loadKeyManagersFromPath(String keyStorePath) {
    File storeFile = new File(keyStorePath);
    if (!storeFile.isFile()) {
      throw new RuntimeException("Keystore file not found: " + keyStorePath);
    }
    String keyStoreType = resolveKeyStoreType(keyStorePath);
    String password = System.getProperty("javax.net.ssl.keyStorePassword", "");
    try {
      KeyStore keyStore = KeyStore.getInstance(keyStoreType);
      try (InputStream in = new FileInputStream(storeFile)) {
        keyStore.load(in, password.toCharArray());
      }
      KeyManagerFactory keyManagerFactory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagerFactory.init(keyStore, password.toCharArray());
      return keyManagerFactory.getKeyManagers();
    } catch (Exception e) {
      throw new RuntimeException("Failed to load key managers from " + keyStorePath, e);
    }
  }

  private static JmeterKeyStore loadJmeterKeyStoreFromPath(String keyStorePath) {
    File storeFile = new File(keyStorePath);
    if (!storeFile.isFile()) {
      throw new RuntimeException("Keystore file not found: " + keyStorePath);
    }
    String keyStoreType = resolveKeyStoreType(keyStorePath);
    String password = System.getProperty("javax.net.ssl.keyStorePassword", "");
    try {
      JmeterKeyStore keyStore = JmeterKeyStore.getInstance(keyStoreType, 0, -1, "");
      try (InputStream in = new FileInputStream(storeFile)) {
        keyStore.load(in, password);
      }
      return keyStore;
    } catch (Exception e) {
      throw new RuntimeException("Failed to load keystore from " + keyStorePath, e);
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

  /**
   * Overwritten to provide JMeter SSLManager configured keyManagers.
   */
  @Override
  protected KeyManager[] getKeyManagers(KeyStore keyStore) throws Exception {
    if (configuredKeyManagers == null) {
      return super.getKeyManagers(keyStore);
    }
    KeyManager[] managers = configuredKeyManagers.clone();
    if (keys != null) {
      for (int i = 0; i < managers.length; i++) {
        if (managers[i] instanceof X509KeyManager) {
          managers[i] = new WrappedX509KeyManager((X509KeyManager) managers[i], keys);
        }
      }
    }
    return managers;
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
      return resolveClientAlias(keyType, issuers);
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers,
                                          SSLEngine engine) {
      return resolveClientAlias(keyType, issuers);
    }

    private String resolveClientAlias(String[] keyTypes, Principal[] issuers) {
      if (keyTypes != null) {
        for (String keyType : keyTypes) {
          String[] aliases = store.getClientAliases(keyType, issuers);
          if (aliases != null && aliases.length > 0) {
            return aliases[0];
          }
        }
      }
      String[] aliases = store.getClientAliases(null, issuers);
      if (aliases != null && aliases.length > 0) {
        return aliases[0];
      }
      if (store.getAliasCount() > 0) {
        return store.getAlias(0);
      }
      if (manager instanceof X509ExtendedKeyManager extendedManager) {
        return extendedManager.chooseEngineClientAlias(keyTypes, issuers, null);
      }
      return manager.chooseClientAlias(keyTypes, issuers, null);
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
