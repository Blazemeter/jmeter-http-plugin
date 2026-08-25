package com.blazemeter.jmeter.http2.visualizers.waterfall;

/**
 * The actions the toolbar needs from whatever is hosting the waterfall.
 *
 * <p>An interface rather than a direct reference so the toolbar has no idea whether it is sitting
 * inside JMeter's listener panel or inside the detached window - the two differ in what "expand"
 * means, and in nothing else.
 */
public interface WaterfallHost {

  /**
   * Shows or hides the details panel.
   *
   * @param visible {@code true} to show it
   */
  void setDetailsVisible(boolean visible);

  /**
   * Whether the details panel is showing.
   *
   * @return {@code true} when visible
   */
  boolean isDetailsVisible();

  /** Moves the waterfall into the detached window, or back into the listener panel. */
  void toggleExpandedWindow();

  /**
   * Whether the waterfall is currently in the detached window.
   *
   * @return {@code true} when expanded
   */
  boolean isExpanded();

  /** Discards every sample. */
  void clearResults();
}
