package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

import java.util.Locale;

/**
 * Number formatting for the table, the ruler and the tooltips.
 *
 * <p>Everything here is deliberately allocation-light and locale-independent. These methods run
 * once per visible cell on every repaint, and a waterfall is scrolled and zoomed constantly; a
 * {@code DecimalFormat} per call would show up in a profile. Locale is pinned to {@code ROOT} so a
 * decimal comma never turns "1.5 s" into something a reader has to decode.
 */
public final class WaterfallFormat {

  private static final long KILOBYTE = 1024L;

  private WaterfallFormat() {
  }

  /**
   * Formats a byte count the way a network panel does.
   *
   * @param bytes the count
   * @return for instance {@code 0 B}, {@code 812 B}, {@code 41.3 kB} or {@code 2.10 MB}
   */
  public static String size(long bytes) {
    if (bytes < KILOBYTE) {
      return bytes + " B";
    }
    double kilobytes = bytes / (double) KILOBYTE;
    if (kilobytes < KILOBYTE) {
      return trim(kilobytes) + " kB";
    }
    double megabytes = kilobytes / KILOBYTE;
    if (megabytes < KILOBYTE) {
      return trim(megabytes) + " MB";
    }
    return trim(megabytes / KILOBYTE) + " GB";
  }

  /**
   * Formats a duration, switching to seconds once milliseconds stop being readable.
   *
   * @param millis the duration in milliseconds
   * @return for instance {@code 0 ms}, {@code 348 ms}, {@code 1.35 s} or {@code 1 m 12 s}
   */
  public static String duration(long millis) {
    if (millis < 1000) {
      return millis + " ms";
    }
    if (millis < 60_000) {
      return String.format(Locale.ROOT, "%.2f s", millis / 1000d);
    }
    long minutes = millis / 60_000;
    long seconds = (millis % 60_000) / 1000;
    return minutes + " m " + seconds + " s";
  }

  /**
   * Formats a tick on the time ruler, where space is tight and the reader is comparing labels to
   * each other rather than reading an absolute value.
   *
   * @param millis the offset from the start of the timeline
   * @return a compact label such as {@code 0}, {@code 250ms}, {@code 1.5s} or {@code 2m}
   */
  public static String tick(long millis) {
    if (millis == 0) {
      return "0";
    }
    if (millis < 1000) {
      return millis + "ms";
    }
    if (millis < 60_000) {
      double seconds = millis / 1000d;
      return (seconds == Math.rint(seconds) ? String.valueOf((long) seconds) : trim(seconds)) + "s";
    }
    long minutes = millis / 60_000;
    long remainder = (millis % 60_000) / 1000;
    return remainder == 0 ? minutes + "m" : minutes + "m" + remainder + "s";
  }

  /**
   * Renders a value with two decimals, dropping a trailing {@code .00} so a whole number does not
   * gain noise.
   *
   * @param value the value
   * @return the trimmed representation
   */
  private static String trim(double value) {
    String text = String.format(Locale.ROOT, "%.2f", value);
    if (text.endsWith(".00")) {
      return text.substring(0, text.length() - 3);
    }
    if (text.endsWith("0")) {
      return text.substring(0, text.length() - 1);
    }
    return text;
  }

  /**
   * Formats a percentage for the Timing tab.
   *
   * @param part  the part
   * @param total the whole
   * @return for instance {@code 42.1%}, or an empty string when the whole is zero
   */
  public static String percent(long part, long total) {
    if (total <= 0) {
      return "";
    }
    return String.format(Locale.ROOT, "%.1f%%", part * 100d / total);
  }
}
