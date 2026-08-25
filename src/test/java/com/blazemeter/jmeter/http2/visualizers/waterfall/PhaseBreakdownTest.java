package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.PhaseBreakdown;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.Test;

public class PhaseBreakdownTest {

  @Test
  public void splitsConnectWaitingAndDownloadFromCumulativeStamps() {
    SampleResult result = SampleResultBuilder.http()
        .timing(1000, 500)
        .connect(80)
        .latency(300)
        .build();

    PhaseBreakdown phases = PhaseBreakdown.of(result);

    assertThat(phases.getConnectMs()).isEqualTo(80);
    assertThat(phases.getTtfbMs()).isEqualTo(220);
    assertThat(phases.getDownloadMs()).isEqualTo(200);
    assertThat(phases.getUnbrokenMs()).isZero();
    assertThat(phases.isDetailed()).isTrue();
  }

  @Test
  public void segmentsAlwaysSumToTheSpan() {
    SampleResult result = SampleResultBuilder.http()
        .timing(1000, 500)
        .idle(120)
        .connect(80)
        .latency(300)
        .build();

    PhaseBreakdown phases = PhaseBreakdown.of(result);

    assertThat(phases.getIdleMs()).isEqualTo(120);
    assertThat(phases.getElapsedMs()).isEqualTo(500);
    assertThat(phases.getSpanMs()).isEqualTo(620);
  }

  @Test
  public void reusedConnectionHasNoConnectPhase() {
    SampleResult result = SampleResultBuilder.http()
        .timing(1000, 90)
        .connect(0)
        .latency(70)
        .build();

    PhaseBreakdown phases = PhaseBreakdown.of(result);

    assertThat(phases.getConnectMs()).isZero();
    assertThat(phases.getTtfbMs()).isEqualTo(70);
    assertThat(phases.getDownloadMs()).isEqualTo(20);
    assertThat(phases.isDetailed()).isTrue();
  }

  @Test
  public void sampleWithOnlyStartAndElapsedGetsOneUnbrokenSegment() {
    SampleResult result = SampleResultBuilder.http().timing(1000, 250).build();

    PhaseBreakdown phases = PhaseBreakdown.of(result);

    assertThat(phases.getUnbrokenMs()).isEqualTo(250);
    assertThat(phases.getSpanMs()).isEqualTo(250);
    assertThat(phases.isDetailed()).isFalse();
  }

  @Test
  public void latencyBelowConnectIsClampedRatherThanGoingNegative() {
    SampleResult result = SampleResultBuilder.http()
        .timing(1000, 400)
        .connect(300)
        .latency(50)
        .build();

    PhaseBreakdown phases = PhaseBreakdown.of(result);

    assertThat(phases.getConnectMs()).isEqualTo(300);
    assertThat(phases.getTtfbMs()).isZero();
    assertThat(phases.getDownloadMs()).isEqualTo(100);
  }

  @Test
  public void stampsBeyondElapsedAreClampedIntoIt() {
    SampleResult result = SampleResultBuilder.http()
        .timing(1000, 100)
        .connect(5000)
        .latency(9000)
        .build();

    PhaseBreakdown phases = PhaseBreakdown.of(result);

    assertThat(phases.getElapsedMs()).isEqualTo(100);
    assertThat(phases.getConnectMs()).isEqualTo(100);
    assertThat(phases.getTtfbMs()).isZero();
    assertThat(phases.getDownloadMs()).isZero();
  }

  @Test
  public void nullResultYieldsEmptyPhases() {
    PhaseBreakdown phases = PhaseBreakdown.of(null);

    assertThat(phases.getSpanMs()).isZero();
    assertThat(phases.isDetailed()).isFalse();
  }
}
