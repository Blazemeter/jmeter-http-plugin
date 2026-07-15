package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Firefox-inspired HTTP/2 header softening for load-testing clients.
 *
 * <p>Browsers (especially Chrome) reject non-numeric {@code :status} values. This helper is more
 * permissive: it extracts a leading status code (Chrome HTTP/1 {@code ParseStatus} style) and
 * trims soft-illegal field values so sampling can continue while logging the anomaly.
 */
public final class CustomHttp2HeaderNormalizer {

  private static final Logger LOG = LoggerFactory.getLogger(CustomHttp2HeaderNormalizer.class);

  private CustomHttp2HeaderNormalizer() {
  }

  /**
   * Normalizes a decoded header value before Jetty builds/emits the field.
   *
   * @return the value to use (possibly unchanged)
   */
  public static String normalize(HttpHeader header, String name, String value) {
    if (value == null) {
      return null;
    }
    if (isStatusPseudoHeader(header, name)) {
      return normalizeStatus(value);
    }
    return softenFieldValue(name, value);
  }

  static boolean isStatusPseudoHeader(HttpHeader header, String name) {
    if (header == HttpHeader.C_STATUS) {
      return true;
    }
    return name != null && ":status".equalsIgnoreCase(name);
  }

  /**
   * Accepts {@code 299}, {@code 299 Akamai}, or leading spaces before digits.
   * Returns the original value when no leading status code can be recovered.
   */
  static String normalizeStatus(String value) {
    String trimmedLeading = trimLeadingSpaces(value);
    int digits = 0;
    while (digits < trimmedLeading.length()
        && trimmedLeading.charAt(digits) >= '0'
        && trimmedLeading.charAt(digits) <= '9') {
      digits++;
    }
    if (digits == 0) {
      return value;
    }
    String code = trimmedLeading.substring(0, digits);
    if (code.equals(value)) {
      return value;
    }
    String reason = trimmedLeading.substring(digits).trim();
    if (reason.isEmpty() && code.equals(trimmedLeading)) {
      if (!code.equals(value)) {
        LOG.warn("Normalized HTTP/2 :status from [{}] to [{}]", value, code);
      }
      return code;
    }
    LOG.warn("Normalized HTTP/2 :status from [{}] to [{}] (ignored reason [{}])",
        value, code, reason);
    return code;
  }

  /**
   * Softens values that fail only because of leading/trailing SP/HTAB (RFC 9113).
   * Control characters and other illegal octets are left untouched so Jetty can still reject them.
   */
  static String softenFieldValue(String name, String value) {
    if (HttpTokens.isLegalFieldValue(value)) {
      return value;
    }
    String trimmed = trimHttpWhitespace(value);
    if (trimmed.equals(value)) {
      return value;
    }
    if (HttpTokens.isLegalFieldValue(trimmed)) {
      LOG.warn("Trimmed illegal HTTP/2 header value for [{}]: [{}] -> [{}]",
          name, value, trimmed);
      return trimmed;
    }
    return value;
  }

  private static String trimLeadingSpaces(String value) {
    int i = 0;
    while (i < value.length() && value.charAt(i) == ' ') {
      i++;
    }
    return i == 0 ? value : value.substring(i);
  }

  private static String trimHttpWhitespace(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isHttpWhitespace(value.charAt(start))) {
      start++;
    }
    while (end > start && isHttpWhitespace(value.charAt(end - 1))) {
      end--;
    }
    return (start == 0 && end == value.length()) ? value : value.substring(start, end);
  }

  private static boolean isHttpWhitespace(char c) {
    return c == ' ' || c == '\t';
  }
}
