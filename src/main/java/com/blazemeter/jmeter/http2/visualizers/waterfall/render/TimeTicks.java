package com.blazemeter.jmeter.http2.visualizers.waterfall.render;

/**
 * Chooses the gridline spacing for the time axis.
 *
 * <p>The ruler and the bars have to agree on where the lines fall, so both ask this class. Steps
 * are rounded up to a 1-2-5 sequence, which is what makes the labels readable while zooming: the
 * spacing changes in recognisable jumps (100ms, 200ms, 500ms, 1s) instead of landing on values
 * like 137ms that nobody can compare at a glance.
 */
public final class TimeTicks {

  private static final int[] MANTISSAS = {1, 2, 5};

  private TimeTicks() {
  }

  /**
   * The spacing between gridlines.
   *
   * @param spanMs      the visible time span in milliseconds
   * @param width       the width available in pixels
   * @param minSpacing  the smallest gap in pixels that still leaves room for a label
   * @return the step in milliseconds, at least {@code 1}
   */
  public static long step(long spanMs, int width, int minSpacing) {
    if (width <= 0 || spanMs <= 0) {
      return 1;
    }
    double minimum = spanMs * (double) minSpacing / width;
    long magnitude = 1;
    while (magnitude <= Long.MAX_VALUE / 10) {
      for (int mantissa : MANTISSAS) {
        long candidate = mantissa * magnitude;
        if (candidate >= minimum) {
          return candidate;
        }
      }
      magnitude *= 10;
    }
    return magnitude;
  }

  /**
   * The first gridline at or after {@code from}, aligned to the timeline origin so that the lines
   * stay put while panning instead of sliding with the viewport.
   *
   * @param origin the zero of the timeline, in epoch milliseconds
   * @param from   the first visible instant, in epoch milliseconds
   * @param step   the spacing in milliseconds
   * @return the instant of the first gridline, in epoch milliseconds
   */
  public static long firstTick(long origin, long from, long step) {
    if (step <= 0) {
      return from;
    }
    long offset = from - origin;
    long aligned = Math.floorDiv(offset, step) * step;
    if (aligned < offset) {
      aligned += step;
    }
    return origin + aligned;
  }
}
