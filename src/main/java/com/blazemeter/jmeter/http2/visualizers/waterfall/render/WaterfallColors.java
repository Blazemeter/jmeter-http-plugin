package com.blazemeter.jmeter.http2.visualizers.waterfall.render;

import java.awt.Color;
import javax.swing.UIManager;

/**
 * The palette: one phase colour per waterfall segment, plus the status and chrome colours the
 * table needs.
 *
 * <p>Two variants of each colour exist because JMeter ships both a light and a dark look and feel,
 * and a saturated bar that reads well on white turns into a glare on a dark panel. Which variant
 * applies is decided from the current look and feel's own panel background rather than from a
 * setting, so switching theme in JMeter switches the waterfall with it - including a third-party
 * theme this code has never heard of.
 *
 * <p>The phase colours follow the convention browsers established, because the point of a
 * waterfall is that someone who has read one in DevTools can read this one without a legend: grey
 * queueing, orange connect, green waiting, blue download. There is no separate handshake colour,
 * because JMeter reports no separate handshake time - the TLS handshake is inside the orange.
 */
public final class WaterfallColors {

  private static final Color IDLE_LIGHT = new Color(0xB4B4B4);
  private static final Color IDLE_DARK = new Color(0x6E6E6E);
  private static final Color CONNECT_LIGHT = new Color(0xF0A030);
  private static final Color CONNECT_DARK = new Color(0xD99A3C);
  private static final Color TTFB_LIGHT = new Color(0x3FA84A);
  private static final Color TTFB_DARK = new Color(0x5CB85C);
  private static final Color DOWNLOAD_LIGHT = new Color(0x2F80ED);
  private static final Color DOWNLOAD_DARK = new Color(0x5A9BF0);
  private static final Color UNBROKEN_LIGHT = new Color(0x7A8CA0);
  private static final Color UNBROKEN_DARK = new Color(0x8798AC);

  private static final Color OK_LIGHT = new Color(0x1E7B32);
  private static final Color OK_DARK = new Color(0x7ED08C);
  private static final Color REDIRECT_LIGHT = new Color(0xA07400);
  private static final Color REDIRECT_DARK = new Color(0xDFC060);
  private static final Color ERROR_LIGHT = new Color(0xC1272D);
  private static final Color ERROR_DARK = new Color(0xF08A8A);

  private static final Color GRID_LIGHT = new Color(0x000000);
  private static final Color GRID_DARK = new Color(0xFFFFFF);
  private static final int GRID_ALPHA = 28;

  /** How far a group heading's background is nudged away from the table background. */
  private static final int GROUP_SHIFT = 14;

  /** Luminance above which the look and feel counts as light, on a 0..255 scale. */
  private static final int DARK_THRESHOLD = 128;

  private WaterfallColors() {
  }

  /**
   * Whether the current look and feel is a dark one.
   *
   * @return {@code true} when the panel background is darker than mid grey
   */
  public static boolean isDark() {
    Color background = UIManager.getColor("Panel.background");
    if (background == null) {
      return false;
    }
    return luminance(background) < DARK_THRESHOLD;
  }

  private static int luminance(Color color) {
    return (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) / 1000;
  }

  private static Color pick(Color light, Color dark) {
    return isDark() ? dark : light;
  }

  /**
   * Queueing and idle time.
   *
   * @return the grey used for the first segment
   */
  public static Color idle() {
    return pick(IDLE_LIGHT, IDLE_DARK);
  }

  /**
   * Connection setup.
   *
   * @return the orange used for the connect segment
   */
  public static Color connect() {
    return pick(CONNECT_LIGHT, CONNECT_DARK);
  }

  /**
   * Waiting for the first byte.
   *
   * @return the green used for the latency segment
   */
  public static Color ttfb() {
    return pick(TTFB_LIGHT, TTFB_DARK);
  }

  /**
   * Reading the response body.
   *
   * @return the blue used for the download segment
   */
  public static Color download() {
    return pick(DOWNLOAD_LIGHT, DOWNLOAD_DARK);
  }

  /**
   * A sample whose elapsed time could not be split into phases.
   *
   * @return the slate used for the single-segment bar
   */
  public static Color unbroken() {
    return pick(UNBROKEN_LIGHT, UNBROKEN_DARK);
  }

  /**
   * The colour for a response code, by status class.
   *
   * @param statusClass the leading digit, {@code 0} for a non-numeric code
   * @param success     whether JMeter considered the sample successful, which is what decides the
   *                    colour when there is no numeric code to go by
   * @return the foreground colour for the Status cell
   */
  public static Color status(int statusClass, boolean success) {
    switch (statusClass) {
      case 2:
        return pick(OK_LIGHT, OK_DARK);
      case 3:
        return pick(REDIRECT_LIGHT, REDIRECT_DARK);
      case 4:
      case 5:
        return pick(ERROR_LIGHT, ERROR_DARK);
      default:
        return success ? foreground() : pick(ERROR_LIGHT, ERROR_DARK);
    }
  }

  /**
   * The failure colour, for the error count on a group heading.
   *
   * @return the red used for errors
   */
  public static Color error() {
    return pick(ERROR_LIGHT, ERROR_DARK);
  }

  /**
   * The vertical grid lines in the waterfall column and the ruler ticks.
   *
   * @return a translucent line colour that works over either background
   */
  public static Color grid() {
    Color base = pick(GRID_LIGHT, GRID_DARK);
    return new Color(base.getRed(), base.getGreen(), base.getBlue(), GRID_ALPHA);
  }

  /**
   * The default text colour of the look and feel.
   *
   * @return the table foreground, falling back to black
   */
  public static Color foreground() {
    Color color = UIManager.getColor("Table.foreground");
    return color != null ? color : Color.BLACK;
  }

  /**
   * A muted text colour, for URLs and secondary values.
   *
   * @return the foreground blended halfway into the background
   */
  public static Color mutedForeground() {
    return blend(foreground(), background(), 0.45f);
  }

  /**
   * The table background of the look and feel.
   *
   * @return the table background, falling back to white
   */
  public static Color background() {
    Color color = UIManager.getColor("Table.background");
    return color != null ? color : Color.WHITE;
  }

  /**
   * The background of a group heading row, distinct from a sample row without being loud.
   *
   * @return the heading background
   */
  public static Color groupBackground() {
    Color base = background();
    int shift = isDark() ? GROUP_SHIFT : -GROUP_SHIFT;
    return new Color(clampChannel(base.getRed() + shift), clampChannel(base.getGreen() + shift),
        clampChannel(base.getBlue() + shift));
  }

  private static int clampChannel(int value) {
    return Math.min(255, Math.max(0, value));
  }

  private static Color blend(Color from, Color to, float ratio) {
    float keep = 1f - ratio;
    return new Color(Math.round(from.getRed() * keep + to.getRed() * ratio),
        Math.round(from.getGreen() * keep + to.getGreen() * ratio),
        Math.round(from.getBlue() * keep + to.getBlue() * ratio));
  }
}
