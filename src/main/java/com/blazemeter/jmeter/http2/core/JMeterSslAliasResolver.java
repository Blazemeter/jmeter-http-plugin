package com.blazemeter.jmeter.http2.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.jmeter.util.SSLManager;
import org.apache.jmeter.util.keystore.JmeterKeyStore;

/**
 * Resolves the client certificate alias on the current JMeter thread, where
 * {@link JMeterContextService} variables are available.
 */
public final class JMeterSslAliasResolver {

  private JMeterSslAliasResolver() {
  }

  /**
   * Returns the alias to use for the next HTTPS client authentication, or {@code null} when no
   * client keystore is configured.
   */
  public static String resolveForRequest() {
    String keyStorePath = System.getProperty(SSLManager.JAVAX_NET_SSL_KEY_STORE);
    if (keyStorePath == null || keyStorePath.isEmpty()) {
      return null;
    }
    return getKeyStore().getAlias();
  }

  private static JmeterKeyStore getKeyStore() {
    try {
      Method keystoreMethod = SSLManager.class.getDeclaredMethod("getKeyStore");
      keystoreMethod.setAccessible(true);
      return (JmeterKeyStore) keystoreMethod.invoke(SSLManager.getInstance());
    } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
      throw new IllegalStateException("Could not access JMeter keystore", e);
    }
  }

}
