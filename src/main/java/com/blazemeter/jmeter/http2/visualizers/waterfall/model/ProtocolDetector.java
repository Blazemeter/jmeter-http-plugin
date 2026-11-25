package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

import org.apache.jmeter.samplers.SampleResult;

/**
 * Works out which wire protocol served a sample.
 *
 * <p>There is no protocol field on {@code SampleResult} and no protocol column in the JTL
 * formats, but every HTTP sampler - JMeter's own included - writes the response status line as
 * the first line of the response headers, and that line starts with the negotiated version. So
 * the version survives a round trip through a {@code .jtl} as long as response headers were
 * saved, which is why it is read from there rather than from the sampler that produced the
 * result.
 */
public final class ProtocolDetector {

  /** Shown when the sample carries no protocol information at all. */
  public static final String UNKNOWN = "";

  private static final String PREFIX = "HTTP/";

  private ProtocolDetector() {
  }

  /**
   * Reads the protocol out of {@code result}'s response status line.
   *
   * @param result the sample to inspect
   * @return a display string such as {@code HTTP/2}, or {@link #UNKNOWN} when the sample did not
   *     record a status line (a non-HTTP sampler, or a JTL saved without response headers)
   */
  public static String detect(SampleResult result) {
    if (result == null) {
      return UNKNOWN;
    }
    String headers = result.getResponseHeaders();
    if (headers == null || !headers.startsWith(PREFIX)) {
      return UNKNOWN;
    }
    int end = headers.length();
    for (int i = 0; i < headers.length(); i++) {
      char c = headers.charAt(i);
      if (c == ' ' || c == '\r' || c == '\n') {
        end = i;
        break;
      }
    }
    return normalize(headers.substring(0, end));
  }

  /**
   * Collapses the minor version of HTTP/2 and HTTP/3, which is always {@code 0} on the wire and
   * only adds noise to a column that has to stay narrow.
   *
   * @param version the raw version token, for instance {@code HTTP/2.0}
   * @return the display form, for instance {@code HTTP/2}
   */
  private static String normalize(String version) {
    if ("HTTP/2.0".equals(version)) {
      return "HTTP/2";
    }
    if ("HTTP/3.0".equals(version)) {
      return "HTTP/3";
    }
    return version;
  }
}
