package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jetty.http.HttpHeader;
import org.junit.Test;

public class CustomHttp2HeaderNormalizerTest {

  @Test
  public void normalizesStatusWithReasonPhrase() {
    assertThat(CustomHttp2HeaderNormalizer.normalize(
        HttpHeader.C_STATUS, ":status", "299 Akamai")).isEqualTo("299");
  }

  @Test
  public void leavesNumericStatusUnchanged() {
    assertThat(CustomHttp2HeaderNormalizer.normalize(
        HttpHeader.C_STATUS, ":status", "299")).isEqualTo("299");
  }

  @Test
  public void trimsSoftIllegalWhitespaceOnRegularHeaders() {
    assertThat(CustomHttp2HeaderNormalizer.normalize(
        null, "warning", "299 Akamai ")).isEqualTo("299 Akamai");
  }

  @Test
  public void leavesHardIllegalValuesUntouched() {
    assertThat(CustomHttp2HeaderNormalizer.normalize(
        null, "x-test", "bad\nvalue")).isEqualTo("bad\nvalue");
  }
}