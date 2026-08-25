package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

import org.apache.jmeter.samplers.SampleResult;

/**
 * The timing phases of one sample, in the shape the waterfall bars and the Timing tab need.
 *
 * <p>JMeter reports three cumulative stamps - {@code connectTime}, {@code latency} and
 * {@code elapsed} - all measured from the start of the sample. Turning them into adjacent
 * segments means differencing them, and doing that safely: a sampler that leaves a stamp at
 * {@code 0}, a JTL whose {@code connectTime} column was not saved, or a result whose stamps
 * disagree after a retry must not produce a negative segment. Everything is therefore clamped
 * into the sample's own elapsed time before being differenced.
 *
 * <p>The phases are exactly the ones JMeter reports, and no more. In particular there is no TLS
 * phase: {@code connectTime} covers the whole connection setup, handshake included, and neither the
 * {@code SampleResult} API nor either JTL format carries the handshake separately. A phase that
 * cannot be measured is better absent than shown as zero.
 *
 * <p>The segments always sum to exactly {@link #getSpanMs()}, so a renderer can lay them out
 * left to right without an accumulating rounding drift:
 *
 * <pre>
 *   idle | connect | ttfb | download        (a sample with phase data)
 *   idle | unbroken                         (a sample with only start + elapsed)
 * </pre>
 */
public final class PhaseBreakdown {

  private static final PhaseBreakdown EMPTY = new PhaseBreakdown(0, 0, 0, 0, 0);

  private final long idleMs;
  private final long connectMs;
  private final long ttfbMs;
  private final long downloadMs;
  private final long unbrokenMs;

  private PhaseBreakdown(long idleMs, long connectMs, long ttfbMs, long downloadMs,
      long unbrokenMs) {
    this.idleMs = idleMs;
    this.connectMs = connectMs;
    this.ttfbMs = ttfbMs;
    this.downloadMs = downloadMs;
    this.unbrokenMs = unbrokenMs;
  }

  /**
   * Derives the phases of {@code result}.
   *
   * <p>The only hard requirement is a start time plus an elapsed time (or an end time); any
   * sampler meeting that much gets a bar. Connect time and latency are enrichment: when they are
   * missing the whole elapsed time becomes one "unbroken" segment rather than being attributed to
   * a phase that was never measured.
   *
   * @param result the sample to inspect
   * @return its phases, never {@code null}
   */
  public static PhaseBreakdown of(SampleResult result) {
    if (result == null) {
      return EMPTY;
    }
    long elapsed = Math.max(0, result.getTime());
    long idle = Math.max(0, result.getIdleTime());
    long span = result.getEndTime() - result.getStartTime();
    // A JTL row rebuilt from timeStamp + elapsed can end up with a span that ignores idle time,
    // and a result that was never stamped ends up with a negative one. Both must still add up.
    long idleGap = Math.max(idle, span - elapsed);
    long connect = clamp(result.getConnectTime(), 0, elapsed);
    long latency = clamp(result.getLatency(), connect, elapsed);
    boolean detailed = connect > 0 || latency > 0;
    if (!detailed) {
      return new PhaseBreakdown(idleGap, 0, 0, 0, elapsed);
    }
    return new PhaseBreakdown(idleGap, connect, latency - connect, elapsed - latency, 0);
  }

  private static long clamp(long value, long min, long max) {
    if (max < min) {
      return min;
    }
    return Math.min(Math.max(value, min), max);
  }

  /**
   * Time the sample spent not being measured, drawn first as the grey queueing segment.
   *
   * <p>For a plain request this is zero. It is a transaction controller's think time, or the gap
   * between a sample's wall-clock span and the elapsed time it reports.
   *
   * @return the idle duration in milliseconds
   */
  public long getIdleMs() {
    return idleMs;
  }

  /**
   * Connection setup, as JMeter reports it: the TLS handshake is part of this figure, not a phase
   * of its own.
   *
   * @return the connect duration in milliseconds, zero on a reused connection
   */
  public long getConnectMs() {
    return connectMs;
  }

  /**
   * Time between the connection being usable and the first byte of the response arriving.
   *
   * @return the time to first byte in milliseconds
   */
  public long getTtfbMs() {
    return ttfbMs;
  }

  /**
   * Time spent reading the response body after the first byte.
   *
   * @return the content download duration in milliseconds
   */
  public long getDownloadMs() {
    return downloadMs;
  }

  /**
   * The whole elapsed time as a single segment, used when the sample reported neither a connect
   * time nor a latency and so cannot be split.
   *
   * @return the unattributed duration in milliseconds, zero when phases are known
   */
  public long getUnbrokenMs() {
    return unbrokenMs;
  }

  /**
   * Whether the sample carried enough information to split its elapsed time into phases.
   *
   * @return {@code true} when connect and/or latency were reported
   */
  public boolean isDetailed() {
    return unbrokenMs == 0 && getElapsedMs() > 0;
  }

  /**
   * The measured duration of the sample, excluding idle time.
   *
   * @return the elapsed time in milliseconds
   */
  public long getElapsedMs() {
    return connectMs + ttfbMs + downloadMs + unbrokenMs;
  }

  /**
   * The wall-clock width of the bar: idle time plus elapsed time.
   *
   * @return the total span in milliseconds
   */
  public long getSpanMs() {
    return idleMs + getElapsedMs();
  }
}
