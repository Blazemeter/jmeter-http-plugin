package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import org.junit.Test;

public class SslStorePathResolverTest {

  private static final String RELATIVE_STORE = "certs/keystore.p12";

  @Test
  public void naiveFilePrefixOnRelativePathTreatsFirstSegmentAsUriAuthority() {
    URI malformed = URI.create("file://" + RELATIVE_STORE);
    assertThat(malformed.getAuthority()).isEqualTo("certs");
    assertThat(malformed.getPath()).isEqualTo("/keystore.p12");
  }

  @Test
  public void naiveFilePrefixOnRelativePathRejectsAuthorityOnUnix() {
    assumeTrue(!isWindows());
    assertThatThrownBy(() -> Paths.get(URI.create("file://" + RELATIVE_STORE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
  }

  @Test
  public void toJettyFileUriMatchesFileToUriForRelativePath() {
    assertThat(SslStorePathResolver.toJettyFileUri(RELATIVE_STORE))
        .isEqualTo(new File(RELATIVE_STORE).getAbsoluteFile().toURI().toString());
  }

  @Test
  public void toJettyFileUriMatchesFileToUriForAbsolutePath() {
    String absolute = new File(System.getProperty("user.dir"), RELATIVE_STORE)
        .getAbsolutePath();
    assertThat(SslStorePathResolver.toJettyFileUri(absolute))
        .isEqualTo(new File(absolute).getAbsoluteFile().toURI().toString());
  }

  @Test
  public void toJettyFileUriHasNoUriAuthorityForRelativePath() {
    URI jettyUri = URI.create(SslStorePathResolver.toJettyFileUri(RELATIVE_STORE));
    assertThat(jettyUri.getAuthority()).isNull();
    assertThat(Paths.get(jettyUri))
        .isEqualTo(new File(RELATIVE_STORE).getAbsoluteFile().toPath());
  }

  @Test
  public void noneIsNotAFileBasedStoreLocation() {
    assertThat(SslStorePathResolver.isFileBasedStoreLocation("NONE")).isFalse();
    assertThat(SslStorePathResolver.isFileBasedStoreLocation("none")).isFalse();
    assertThat(SslStorePathResolver.isFileBasedStoreLocation(RELATIVE_STORE)).isTrue();
  }

  @Test
  public void resolveKeyStoreTypeUsesPropertyWhenSet() {
    String previous = System.getProperty("javax.net.ssl.keyStoreType");
    try {
      System.setProperty("javax.net.ssl.keyStoreType", "PKCS12");
      assertThat(SslStorePathResolver.resolveKeyStoreType("certs/keystore.jks"))
          .isEqualTo("PKCS12");
    } finally {
      restoreProperty("javax.net.ssl.keyStoreType", previous);
    }
  }

  @Test
  public void resolveKeyStoreTypeInfersPkcs12FromP12ExtensionLikeJMeter() {
    String previous = System.getProperty("javax.net.ssl.keyStoreType");
    try {
      System.clearProperty("javax.net.ssl.keyStoreType");
      assertThat(SslStorePathResolver.resolveKeyStoreType(RELATIVE_STORE)).isEqualTo("pkcs12");
      assertThat(SslStorePathResolver.resolveKeyStoreType("certs/keystore.jks")).isEqualTo("JKS");
      assertThat(SslStorePathResolver.resolveKeyStoreType("certs/keystore.pfx")).isEqualTo("JKS");
    } finally {
      restoreProperty("javax.net.ssl.keyStoreType", previous);
    }
  }

  @Test
  public void resolveTrustStoreTypeUsesPropertyWhenSet() {
    String previous = System.getProperty("javax.net.ssl.trustStoreType");
    try {
      System.setProperty("javax.net.ssl.trustStoreType", "JKS");
      assertThat(SslStorePathResolver.resolveTrustStoreType("certs/trust.p12"))
          .isEqualTo("JKS");
    } finally {
      restoreProperty("javax.net.ssl.trustStoreType", previous);
    }
  }

  @Test
  public void resolveTrustStoreTypeInfersPkcs12FromP12OrPfxWhenUnset() {
    String previous = System.getProperty("javax.net.ssl.trustStoreType");
    try {
      System.clearProperty("javax.net.ssl.trustStoreType");
      assertThat(SslStorePathResolver.resolveTrustStoreType("certs/trust.p12"))
          .isEqualTo("pkcs12");
      assertThat(SslStorePathResolver.resolveTrustStoreType("certs/trust.pfx"))
          .isEqualTo("pkcs12");
      assertThat(SslStorePathResolver.resolveTrustStoreType("certs/trust.jks")).isEqualTo("JKS");
    } finally {
      restoreProperty("javax.net.ssl.trustStoreType", previous);
    }
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static boolean isWindows() {
    return File.separatorChar == '\\';
  }

}
