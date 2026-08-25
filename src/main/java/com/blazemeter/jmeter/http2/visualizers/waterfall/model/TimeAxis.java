package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

/**
 * The horizontal time axis shared by the waterfall bars and the ruler above them.
 *
 * <p>Holds two ranges: the bounds, which are the first and last instants present in the rows on
 * screen, and the view, which is the slice of those bounds currently mapped onto the waterfall
 * column. Zooming and panning move the view inside the bounds; the bars and the ruler both ask
 * this object where a millisecond lands, so they cannot drift apart.
 *
 * <p>While a test runs the bounds keep growing. An axis left at full extent follows that growth,
 * which is what makes a live run readable without touching anything; an axis the user has zoomed
 * stays put, because otherwise every arriving sample would yank the region under inspection.
 */
public final class TimeAxis {

  /** Zooming further than this stops being useful and starts producing rounding artefacts. */
  private static final long MIN_WINDOW_MS = 2;

  private long boundsStart;
  private long boundsEnd;
  private long viewStart;
  private long viewEnd;
  private boolean fullExtent = true;

  /**
   * Widens the bounds to cover a row's span, keeping the view in step when it is at full extent.
   *
   * @param start the earliest instant on screen, in epoch milliseconds
   * @param end   the latest instant on screen, in epoch milliseconds
   */
  public void setBounds(long start, long end) {
    boundsStart = start;
    boundsEnd = Math.max(end, start + MIN_WINDOW_MS);
    if (fullExtent) {
      viewStart = boundsStart;
      viewEnd = boundsEnd;
    } else {
      clampView();
    }
  }

  /** Returns the view to the full extent of the bounds, and lets it follow later growth. */
  public void fit() {
    fullExtent = true;
    viewStart = boundsStart;
    viewEnd = boundsEnd;
  }

  /**
   * Whether the view currently spans the whole timeline.
   *
   * @return {@code true} when nothing is zoomed or panned away
   */
  public boolean isFullExtent() {
    return fullExtent;
  }

  /**
   * Zooms about a fixed point, the way a wheel zoom over a chart behaves: the instant under the
   * pointer stays under the pointer.
   *
   * @param factor         below {@code 1} to zoom in, above {@code 1} to zoom out
   * @param anchorFraction where to pin the view, as a fraction of the visible width
   */
  public void zoom(double factor, double anchorFraction) {
    long window = getViewSpanMs();
    long target = Math.max(MIN_WINDOW_MS, Math.round(window * factor));
    long fullSpan = getBoundsSpanMs();
    if (target >= fullSpan) {
      fit();
      return;
    }
    double anchor = Math.min(1, Math.max(0, anchorFraction));
    long anchorTime = viewStart + Math.round(window * anchor);
    fullExtent = false;
    viewStart = anchorTime - Math.round(target * anchor);
    viewEnd = viewStart + target;
    clampView();
  }

  /**
   * Slides the view sideways without changing its width.
   *
   * @param fraction how far to move, as a fraction of the visible width; negative moves earlier
   */
  public void pan(double fraction) {
    long window = getViewSpanMs();
    long shift = Math.round(window * fraction);
    if (shift == 0) {
      return;
    }
    fullExtent = false;
    viewStart += shift;
    viewEnd += shift;
    clampView();
  }

  /**
   * Zooms the view onto an explicit range, used when the user drags a selection on the ruler.
   *
   * @param start the first instant to show, in epoch milliseconds
   * @param end   the last instant to show, in epoch milliseconds
   */
  public void setView(long start, long end) {
    long from = Math.min(start, end);
    long to = Math.max(start, end);
    if (to - from < MIN_WINDOW_MS) {
      to = from + MIN_WINDOW_MS;
    }
    fullExtent = false;
    viewStart = from;
    viewEnd = to;
    clampView();
  }

  /**
   * Keeps the view inside the bounds and no narrower than the floor. Panning to the edge stops
   * there instead of scrolling into empty space, which would leave the user staring at a blank
   * column with no clue which way to go back.
   */
  private void clampView() {
    long window = Math.max(MIN_WINDOW_MS, viewEnd - viewStart);
    long fullSpan = getBoundsSpanMs();
    if (window >= fullSpan) {
      fit();
      return;
    }
    if (viewStart < boundsStart) {
      viewStart = boundsStart;
    }
    if (viewStart + window > boundsEnd) {
      viewStart = boundsEnd - window;
    }
    viewEnd = viewStart + window;
  }

  /**
   * Maps an instant onto the waterfall column.
   *
   * @param timeMs the instant, in epoch milliseconds
   * @param width  the column width in pixels
   * @return the horizontal offset in pixels, possibly outside {@code 0..width}
   */
  public double xFor(long timeMs, int width) {
    return (timeMs - viewStart) * (double) width / getViewSpanMs();
  }

  /**
   * Maps a column offset back to an instant, for tooltips and drag-to-zoom.
   *
   * @param x     the horizontal offset in pixels
   * @param width the column width in pixels
   * @return the instant, in epoch milliseconds
   */
  public long timeAt(double x, int width) {
    if (width <= 0) {
      return viewStart;
    }
    return viewStart + Math.round(x * getViewSpanMs() / width);
  }

  /**
   * The first instant on screen.
   *
   * @return the view start, in epoch milliseconds
   */
  public long getViewStart() {
    return viewStart;
  }

  /**
   * The last instant on screen.
   *
   * @return the view end, in epoch milliseconds
   */
  public long getViewEnd() {
    return viewEnd;
  }

  /**
   * The width of the view.
   *
   * @return the visible span in milliseconds, at least {@link #MIN_WINDOW_MS}
   */
  public long getViewSpanMs() {
    return Math.max(MIN_WINDOW_MS, viewEnd - viewStart);
  }

  /**
   * The first instant present in the rows on screen, which is the zero of the relative Start
   * column.
   *
   * @return the timeline origin, in epoch milliseconds
   */
  public long getBoundsStart() {
    return boundsStart;
  }

  /**
   * The last instant present in the rows on screen.
   *
   * @return the timeline end, in epoch milliseconds
   */
  public long getBoundsEnd() {
    return boundsEnd;
  }

  /**
   * The width of the whole timeline.
   *
   * @return the total span in milliseconds, at least {@link #MIN_WINDOW_MS}
   */
  public long getBoundsSpanMs() {
    return Math.max(MIN_WINDOW_MS, boundsEnd - boundsStart);
  }
}
