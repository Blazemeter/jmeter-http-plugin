package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallFormat;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.TimeTicks;
import org.junit.Test;

public class WaterfallFormatTest {

  @Test
  public void sizesStepUpThroughTheUnits() {
    assertThat(WaterfallFormat.size(0)).isEqualTo("0 B");
    assertThat(WaterfallFormat.size(812)).isEqualTo("812 B");
    assertThat(WaterfallFormat.size(2048)).isEqualTo("2 kB");
    assertThat(WaterfallFormat.size(1024 * 1024)).isEqualTo("1 MB");
    assertThat(WaterfallFormat.size(1024L * 1024 * 1024 * 3)).isEqualTo("3 GB");
  }

  @Test
  public void durationsSwitchToSecondsWhenMillisecondsStopBeingReadable() {
    assertThat(WaterfallFormat.duration(0)).isEqualTo("0 ms");
    assertThat(WaterfallFormat.duration(348)).isEqualTo("348 ms");
    assertThat(WaterfallFormat.duration(1350)).isEqualTo("1.35 s");
    assertThat(WaterfallFormat.duration(72_000)).isEqualTo("1 m 12 s");
  }

  @Test
  public void tickLabelsStayCompact() {
    assertThat(WaterfallFormat.tick(0)).isEqualTo("0");
    assertThat(WaterfallFormat.tick(250)).isEqualTo("250ms");
    assertThat(WaterfallFormat.tick(1000)).isEqualTo("1s");
    assertThat(WaterfallFormat.tick(1500)).isEqualTo("1.5s");
    assertThat(WaterfallFormat.tick(120_000)).isEqualTo("2m");
    assertThat(WaterfallFormat.tick(150_000)).isEqualTo("2m30s");
  }

  @Test
  public void percentagesGuardAgainstAZeroWhole() {
    assertThat(WaterfallFormat.percent(50, 200)).isEqualTo("25.0%");
    assertThat(WaterfallFormat.percent(1, 0)).isEmpty();
  }

  @Test
  public void tickSpacingFollowsAOneTwoFiveSequence() {
    assertThat(TimeTicks.step(1000, 1000, 100)).isEqualTo(100);
    assertThat(TimeTicks.step(1000, 1000, 150)).isEqualTo(200);
    assertThat(TimeTicks.step(1000, 1000, 300)).isEqualTo(500);
    assertThat(TimeTicks.step(10_000, 1000, 100)).isEqualTo(1000);
  }

  @Test
  public void tickSpacingIsNeverZero() {
    assertThat(TimeTicks.step(0, 0, 60)).isEqualTo(1);
    assertThat(TimeTicks.step(1, 1000, 60)).isEqualTo(1);
  }

  @Test
  public void gridlinesAreAlignedToTheTimelineOriginSoTheyDoNotSlideWhilePanning() {
    assertThat(TimeTicks.firstTick(1000, 1000, 250)).isEqualTo(1000);
    assertThat(TimeTicks.firstTick(1000, 1100, 250)).isEqualTo(1250);
    assertThat(TimeTicks.firstTick(1000, 1250, 250)).isEqualTo(1250);
    assertThat(TimeTicks.firstTick(1000, 900, 250)).isEqualTo(1000);
  }
}
