package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.Assume.assumeTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Field;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.Test;

/**
 * A sample's start and end must come from one clock.
 *
 * <p>A {@link SampleResult} derives every stamp of its own from {@code System.nanoTime()} plus an
 * offset it captures at construction, while {@link HTTP2FutureResponseListener} records the exchange
 * on the wall clock as well. Taking a sample's start from {@code sampleStart()} and its end from the
 * listener's wall-clock reading therefore reported the distance between those two clocks as part of
 * the duration: single-digit milliseconds in a healthy run, but bounded only by how stale that
 * offset is. See {@link SampleClock}.
 *
 * <p>The distance is made deterministic here by moving the result's own offset - the same thing a
 * stale refresh does at runtime, only by an amount worth asserting on.
 */
public class SampleClockTest extends HTTP2TestBase {

  private static final long SKEW_MILLIS = 250L;
  private static final long EXCHANGE_MILLIS = 60L;
  /** The exchange is simulated with a sleep, so its duration is a lower bound, not an equality. */
  private static final long SCHEDULING_SLACK_MILLIS = 400L;
  /** Two truncations to whole milliseconds, plus the gap between reading the two clocks. */
  private static final long TRANSLATION_SLACK_MILLIS = 5L;

  @Test
  public void endTakenFromTheListenerMustNotAbsorbTheDistanceBetweenTheClocks() throws Exception {
    HTTPSampleResult result = new HTTPSampleResult();
    assumeSampleClockMovedBy(result, SKEW_MILLIS);
    result.sampleStart();

    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(-1);
    Thread.sleep(EXCHANGE_MILLIS);
    listener.setEnd();

    assertThat(listener.getResponseEnd() - result.getStartTime())
        .as("the raw wall-clock reading the sample used to be stamped with sits before its own "
            + "start here, which is what corrupted the duration")
        .isNegative();

    result.setEndTime(listener.getResponseEndOn(result));

    assertThat(result.getTime())
        .as("the duration must be the exchange, not the exchange shifted by %d ms of clock "
            + "distance", SKEW_MILLIS)
        .isBetween(EXCHANGE_MILLIS, EXCHANGE_MILLIS + SCHEDULING_SLACK_MILLIS);
  }

  @Test
  public void endTakenFromTheListenerMustNotBeInflatedByTheDistanceBetweenTheClocks()
      throws Exception {
    HTTPSampleResult result = new HTTPSampleResult();
    assumeSampleClockMovedBy(result, -SKEW_MILLIS);
    result.sampleStart();

    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(-1);
    Thread.sleep(EXCHANGE_MILLIS);
    listener.setEnd();

    assertThat(listener.getResponseEnd() - result.getStartTime())
        .as("the raw wall-clock reading is %d ms ahead of this result's clock, so it used to "
            + "inflate the sample by that much", SKEW_MILLIS)
        .isGreaterThan(SKEW_MILLIS);

    result.setEndTime(listener.getResponseEndOn(result));

    assertThat(result.getTime())
        .isBetween(EXCHANGE_MILLIS, EXCHANGE_MILLIS + SCHEDULING_SLACK_MILLIS);
  }

  @Test
  public void anExchangeThatNeverCompletedStaysUnstampedInsteadOfBecomingAClockReading() {
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(-1);

    assertThat(listener.getResponseEnd()).as("no completion yet").isZero();
    assertThat(listener.getResponseEndOn(new HTTPSampleResult()))
        .as("an absent stamp must stay absent, so callers can tell it apart from a real end and "
            + "fall back to sampleEnd() rather than stamping a zero")
        .isZero();
  }

  /**
   * The winner of a protocol race is reported through the listener of the attempt that lost, so the
   * stamps and the monotonic readings they are translated with have to travel together: translating
   * the winner's interval with the loser's readings would put the sample wherever the two attempts
   * happened to be apart.
   */
  @Test
  public void adoptingARaceWinnerKeepsItsIntervalOnOneClock() throws Exception {
    HTTP2FutureResponseListener winner = new HTTP2FutureResponseListener(-1);
    Thread.sleep(EXCHANGE_MILLIS);
    winner.setEnd();
    HTTP2FutureResponseListener reporter = new HTTP2FutureResponseListener(-1);

    reporter.completeWith(null, winner);

    HTTPSampleResult result = new HTTPSampleResult();
    assumeSampleClockMovedBy(result, SKEW_MILLIS);
    assertThat(reporter.getResponseEndOn(result) - reporter.getResponseStartOn(result))
        .as("the reported interval must be the winner's exchange")
        .isBetween(EXCHANGE_MILLIS - TRANSLATION_SLACK_MILLIS,
            EXCHANGE_MILLIS + SCHEDULING_SLACK_MILLIS);
    assertThat(reporter.getResponseEndOn(result) - winner.getResponseEndOn(result))
        .as("both listeners must translate the same instant to the same time")
        .isBetween(-TRANSLATION_SLACK_MILLIS, TRANSLATION_SLACK_MILLIS);
    assertThat(reporter.isDone()).isTrue();
  }

  /** Stamps adopted from elsewhere as plain wall-clock times still land on the result's clock. */
  @Test
  public void adoptedWallClockStampsAreTranslatedIntoTheResultsClock() throws Exception {
    HTTPSampleResult result = new HTTPSampleResult();
    assumeSampleClockMovedBy(result, -SKEW_MILLIS);

    long start = System.currentTimeMillis();
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(-1);
    listener.completeWith(null, start, start + EXCHANGE_MILLIS);

    assertThat(listener.getResponseEndOn(result) - listener.getResponseStartOn(result))
        .as("translating both ends of the same interval must preserve it")
        .isEqualTo(EXCHANGE_MILLIS);
    assertThat(listener.getResponseStartOn(result) - start)
        .as("both stamps must land on the result's clock, %d ms from the wall clock here",
            -SKEW_MILLIS)
        .isBetween(-SKEW_MILLIS - TRANSLATION_SLACK_MILLIS,
            -SKEW_MILLIS + TRANSLATION_SLACK_MILLIS);
  }

  /**
   * {@code SampleResult.setStampAndTime} reads its first argument as the start of the sample or as
   * its end depending on {@code sampleresult.timestamp.start} - JMeter's shipped
   * {@code jmeter.properties} turns that on, the property's own default is off. A caller that
   * assumes either one gets the timestamps of the other backwards by the whole duration, which for
   * a sub-sample also drags the container's end with it. So the choice belongs in one place.
   */
  @Test
  public void stampIntervalReportsTheStartAndTheDurationItWasGiven() {
    SampleResult result = new SampleResult();

    SampleClock.stampInterval(result, 1_000_000L, 250L);

    assertThat(result.getStartTime()).as("start").isEqualTo(1_000_000L);
    assertThat(result.getEndTime()).as("end").isEqualTo(1_000_250L);
    assertThat(result.getTime()).as("duration").isEqualTo(250L);
  }

  /**
   * A sub-sample's times are on its own clock, and JMeter corrects for that when it folds one into
   * its parent - {@code SampleResult.addSubResult} extends the parent's end time by
   * {@code childEnd + parentOffset - childOffset}, its Bug 51855. Reading a child's stamps to
   * measure the parent has to make the same correction, so both must land on the same time.
   */
  @Test
  public void aChildsTimesLandWhereAddSubResultPutsThem() throws Exception {
    SampleResult parent = new SampleResult();
    parent.sampleStart();
    HTTPSampleResult child = new HTTPSampleResult();
    assumeSampleClockMovedBy(child, SKEW_MILLIS);
    child.sampleStart();
    child.sampleEnd();

    parent.addSubResult(child, false);

    assertThat(SampleClock.fromResultClock(parent, child, child.getEndTime()))
        .as("the child's end on the parent's clock must be the end addSubResult stamped")
        .isCloseTo(parent.getEndTime(), within(TRANSLATION_SLACK_MILLIS));
    assertThat(parent.getEndTime() - child.getEndTime())
        .as("and the raw stamp is %d ms away from it, which is what reading it uncorrected costs",
            SKEW_MILLIS)
        .isCloseTo(-SKEW_MILLIS, within(TRANSLATION_SLACK_MILLIS));
  }

  /**
   * Moves {@code result}'s own nano offset by {@code deltaMillis}, so its clock sits a known
   * distance from the wall clock. Skips the test when {@code sampleresult.useNanoTime} is off, since
   * both clocks are then the wall clock and there is no distance to leak.
   */
  private static void assumeSampleClockMovedBy(SampleResult result, long deltaMillis)
      throws ReflectiveOperationException {
    Field nanoTimeOffset = SampleResult.class.getDeclaredField("nanoTimeOffset");
    nanoTimeOffset.setAccessible(true);
    long current = nanoTimeOffset.getLong(result);
    assumeTrue("sampleresult.useNanoTime must be on for the two clocks to differ at all",
        current != Long.MIN_VALUE);
    nanoTimeOffset.setLong(result, current + deltaMillis);
  }
}
