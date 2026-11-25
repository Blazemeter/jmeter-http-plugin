package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.junit.Test;

/**
 * A refused connection to a loopback address fails within the millisecond the sample started in,
 * and the recorder stamps it on the wall clock. The sample's start comes from its own clock and is
 * translated to the wall clock to select the attempts that belong to it; each reading of the
 * translation is truncated to whole milliseconds, so the translated start can land a millisecond
 * after the real one. The attempt must still be selected: dropping it was the intermittent
 * {@code ConnectAttemptVisibilityTest} failure, where the IPv4 refusal - the first and fastest
 * attempt - went missing from the response data.
 */
public class ConnectAttemptsSinceTest {

  @Test
  public void attemptAtTheSampleStartMustBeSelectedDespiteTheTranslationTruncation() {
    SkewedClockResult result = new SkewedClockResult();
    long wallClockAtStart = System.currentTimeMillis();
    // The start is read on the sample's clock while it agrees with the wall clock...
    result.startAt(wallClockAtStart);
    // ...and by the translation, the truncation of the two readings puts the sample's clock a
    // millisecond behind it, which moves the translated start a millisecond later.
    result.skewMillis = -1;

    long since = HTTP2Sampler.connectAttemptsSince(result);

    assertThat(wallClockAtStart)
        .as("an attempt recorded at the very start of the sample belongs to it")
        .isGreaterThanOrEqualTo(since);
  }

  @Test
  public void sampleThatNeverStartedTakesEveryAttemptHeld() {
    assertThat(HTTP2Sampler.connectAttemptsSince(new HTTPSampleResult())).isZero();
  }

  /** A result whose own clock reads the wall clock plus {@link #skewMillis}. */
  private static final class SkewedClockResult extends HTTPSampleResult {

    private long skewMillis;

    private void startAt(long start) {
      setStartTime(start);
    }

    @Override
    public long currentTimeInMillis() {
      return System.currentTimeMillis() + skewMillis;
    }
  }
}
