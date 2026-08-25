package com.blazemeter.jmeter.http2.visualizers.waterfall.details;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the header blocks JMeter stores on a sample.
 *
 * <p>JMeter keeps request and response headers as one newline-separated string rather than as a
 * structured collection, and the response block starts with the status line. That format is what
 * survives into a {@code .jtl}, so parsing it here - rather than expecting a sampler to hand over
 * something richer - is what lets the details panel work identically for a live run and for a file
 * loaded after the fact.
 *
 * <p>Values are split on the first colon only, because a value can legally contain one: a date, a
 * URL in a {@code Location} header, or a {@code Set-Cookie} with an expiry.
 */
public final class HttpHeaders {

  private HttpHeaders() {
  }

  /**
   * Splits a header block into name and value pairs.
   *
   * @param block         the raw block, may be {@code null}
   * @param skipFirstLine {@code true} for a response block, whose first line is the status line
   * @return the headers in the order they appeared, each entry being a name followed by a value
   */
  public static List<String[]> parse(String block, boolean skipFirstLine) {
    List<String[]> headers = new ArrayList<>();
    if (block == null || block.isEmpty()) {
      return headers;
    }
    String[] lines = block.split("\n");
    for (int i = skipFirstLine ? 1 : 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        headers.add(new String[] {line, ""});
      } else {
        headers.add(new String[] {line.substring(0, colon).trim(),
            line.substring(colon + 1).trim()});
      }
    }
    return headers;
  }

  /**
   * The status line of a response block.
   *
   * @param responseHeaders the raw response header block, may be {@code null}
   * @return the first line, or an empty string when there is none
   */
  public static String statusLine(String responseHeaders) {
    if (responseHeaders == null || responseHeaders.isEmpty()) {
      return "";
    }
    int newline = responseHeaders.indexOf('\n');
    return (newline < 0 ? responseHeaders : responseHeaders.substring(0, newline)).trim();
  }

  /**
   * Every value of a named header, case-insensitively.
   *
   * @param block         the raw block, may be {@code null}
   * @param skipFirstLine {@code true} for a response block
   * @param name          the header name to look for
   * @return the values, in order
   */
  public static List<String> valuesOf(String block, boolean skipFirstLine, String name) {
    List<String> values = new ArrayList<>();
    String wanted = name.toLowerCase(Locale.ROOT);
    for (String[] header : parse(block, skipFirstLine)) {
      if (header[0].toLowerCase(Locale.ROOT).equals(wanted)) {
        values.add(header[1]);
      }
    }
    return values;
  }
}
