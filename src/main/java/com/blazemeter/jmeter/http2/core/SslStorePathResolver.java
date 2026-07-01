package com.blazemeter.jmeter.http2.core;

import java.io.File;
import java.util.Locale;

/**
 * Converts {@code javax.net.ssl.*} store paths to Jetty {@code file:} URIs.
 */
public final class SslStorePathResolver {

  /** JSSE / JMeter sentinel for non-file keystores (e.g. PKCS#11). */
  public static final String NON_FILE_KEYSTORE_LOCATION = "NONE";

  private static final String KEY_STORE_TYPE_PROPERTY = "javax.net.ssl.keyStoreType";
  private static final String TRUST_STORE_TYPE_PROPERTY = "javax.net.ssl.trustStoreType";
  private static final String PKCS12 = "pkcs12";
  private static final String JKS = "JKS";

  private SslStorePathResolver() {
    // Utility class.
  }

  /**
   * Whether the property points at a filesystem keystore (not PKCS#11 {@code NONE}).
   */
  public static boolean isFileBasedStoreLocation(final String storePath) {
    return storePath != null
        && !storePath.isEmpty()
        && !NON_FILE_KEYSTORE_LOCATION.equalsIgnoreCase(storePath);
  }

  /**
   * Same as JMeter ({@code new File(path)}), then {@link File#toURI()} for Jetty.
   *
   * @param storePath filesystem path from {@code javax.net.ssl.keyStore} or trustStore
   * @return Jetty-compatible {@code file:} URI
   */
  public static String toJettyFileUri(final String storePath) {
    return new File(storePath).getAbsoluteFile().toURI().toString();
  }

  /**
   * Resolves the Jetty key store type from {@link #KEY_STORE_TYPE_PROPERTY}, matching
   * {@link org.apache.jmeter.util.SSLManager} when unset (.p12 → PKCS12, else JKS).
   *
   * @param keyStorePath filesystem path from {@code javax.net.ssl.keyStore}
   * @return type string for {@link org.eclipse.jetty.util.ssl.SslContextFactory#setKeyStoreType}
   */
  public static String resolveKeyStoreType(final String keyStorePath) {
    return resolveStoreType(KEY_STORE_TYPE_PROPERTY, keyStorePath, false);
  }

  /**
   * Resolves the Jetty trust store type from {@link #TRUST_STORE_TYPE_PROPERTY}, or by file
   * extension when unset (.p12 / .pfx → PKCS12, else JKS).
   *
   * @param trustStorePath filesystem path from {@code javax.net.ssl.trustStore}
   * @return type string for {@link org.eclipse.jetty.util.ssl.SslContextFactory#setTrustStoreType}
   */
  public static String resolveTrustStoreType(final String trustStorePath) {
    return resolveStoreType(TRUST_STORE_TYPE_PROPERTY, trustStorePath, true);
  }

  private static String resolveStoreType(final String typeProperty, final String storePath,
                                         final boolean inferPfxAsPkcs12) {
    String explicit = System.getProperty(typeProperty);
    if (explicit != null && !explicit.isEmpty()) {
      return explicit;
    }
    String lower = storePath.toLowerCase(Locale.ENGLISH);
    if (lower.endsWith(".p12") || (inferPfxAsPkcs12 && lower.endsWith(".pfx"))) {
      return PKCS12;
    }
    return JKS;
  }

}
