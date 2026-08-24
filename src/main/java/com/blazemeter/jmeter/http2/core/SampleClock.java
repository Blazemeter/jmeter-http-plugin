package com.blazemeter.jmeter.http2.core;

import org.apache.jmeter.samplers.SampleResult;

/**
 * Puts a time taken by the Jetty transport on the clock the {@link SampleResult} it is about to be
 * stamped on keeps its own times in, and stamps an interval that was measured elsewhere.
 *
 * <p>A {@code SampleResult} does not read {@link System#currentTimeMillis()} when
 * {@code sampleresult.useNanoTime} is on, which is JMeter's shipped default: every stamp of its own
 * comes from {@code System.nanoTime() / 1_000_000} plus an offset the instance captures once, at
 * construction, from a value a daemon refreshes every {@code sampleresult.nanoThreadSleep} ms (5 s
 * by default). A wall-clock reading and a {@code SampleResult}-clock reading of the same instant
 * therefore differ by however stale that offset is, and {@link SampleResult#setEndTime} turns the
 * difference straight into reported sample time, since it computes
 * {@code elapsed = end - start - idle}. Taking a sample's start from {@code sampleStart()} and its
 * end from the wall clock is not a rounding error, it is a measurement across two clocks - which is
 * why JMeter's own HTTP samplers only ever stamp through {@code sampleEnd()},
 * {@code latencyEnd()} and {@code connectEnd()}.
 *
 * <p>Every conversion here is expressed as "how far is that clock from this result's clock right
 * now", which is exact when {@code useNanoTime} is off - both clocks are then the same one - and
 * within the 1 ms resolution of the offset when it is on.
 */
public final class SampleClock {

  /**
   * Whether {@link SampleResult#setStampAndTime(long, long)} reads its first argument as the start
   * of the sample. It reads it as the end when {@code sampleresult.timestamp.start} is off, and
   * JMeter's shipped {@code jmeter.properties} turns it on while the property's built-in default is
   * off - so which one a run uses cannot be assumed either way. Measured from the behaviour itself
   * rather than read from the property, so this agrees with whatever {@code SampleResult} settled
   * on when its own class was initialised.
   */
  private static final boolean STAMP_IS_START = probeStampIsStart();

  private SampleClock() {
  }

  /**
   * Converts a {@link System#nanoTime()} reading into {@code result}'s clock.
   *
   * <p>Preferred over {@link #fromWallClock}: a monotonic reading cannot be displaced by an NTP
   * step, or by an operator moving the machine clock, between the moment it was taken and the
   * moment the sample is stamped.
   */
  public static long fromNanoTime(SampleResult result, long nanoTime) {
    return nanoClockMillis(nanoTime)
        + (result.currentTimeInMillis() - nanoClockMillis(System.nanoTime()));
  }

  /**
   * Converts a {@link System#currentTimeMillis()} reading into {@code result}'s clock. For instants
   * no monotonic reading was taken for.
   */
  public static long fromWallClock(SampleResult result, long wallClockMillis) {
    return wallClockMillis + (result.currentTimeInMillis() - System.currentTimeMillis());
  }

  /**
   * Converts a time on {@code result}'s clock back to {@link System#currentTimeMillis()}, for
   * comparing a sample's own stamps against times recorded on the wall clock.
   */
  public static long toWallClock(SampleResult result, long resultClockMillis) {
    return resultClockMillis + (System.currentTimeMillis() - result.currentTimeInMillis());
  }

  /**
   * Converts a time held by {@code source} into {@code target}'s clock. Two results capture their
   * offset from the refreshing one at whatever moment each was constructed, so a sample and a
   * sub-sample of it are on the same clock only as long as no refresh happened in between - which
   * for a transaction holding a think time is not a given.
   *
   * <p>The same correction {@code SampleResult.addSubResult} applies for JMeter's Bug 51855 when it
   * extends a parent's end time with a sub-result's, expressed through the public clock rather than
   * the private offset field the class reads there.
   */
  public static long fromResultClock(SampleResult target, SampleResult source, long time) {
    return time + (target.currentTimeInMillis() - source.currentTimeInMillis());
  }

  /**
   * Stamps a sample that was timed outside of it: {@code start} on the result's own clock (see
   * {@link #fromNanoTime}) and how long it took. Both come out of the sample as given, whichever
   * end of the interval {@code sampleresult.timestamp.start} makes
   * {@link SampleResult#setStampAndTime(long, long)} take as its stamp.
   *
   * @throws IllegalStateException if {@code result} is already stamped, as
   *                               {@code setStampAndTime} does
   */
  public static void stampInterval(SampleResult result, long start, long elapsedMillis) {
    result.setStampAndTime(STAMP_IS_START ? start : start + elapsedMillis, elapsedMillis);
  }

  /** Mirrors {@code SampleResult.sampleNsClockInMs()}, truncation to whole millis included. */
  private static long nanoClockMillis(long nanoTime) {
    return nanoTime / 1_000_000L;
  }

  private static boolean probeStampIsStart() {
    SampleResult probe = new SampleResult();
    probe.setStampAndTime(1_000L, 100L);
    return probe.getStartTime() == 1_000L;
  }
}
