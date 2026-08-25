package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.TimeAxis;
import org.junit.Test;

public class TimeAxisTest {

  private static final int WIDTH = 1000;

  private TimeAxis axisOver(long start, long end) {
    TimeAxis axis = new TimeAxis();
    axis.setBounds(start, end);
    return axis;
  }

  @Test
  public void mapsTimeOntoTheColumnAndBack() {
    TimeAxis axis = axisOver(1000, 2000);

    assertThat(axis.xFor(1000, WIDTH)).isEqualTo(0d);
    assertThat(axis.xFor(1500, WIDTH)).isEqualTo(500d);
    assertThat(axis.xFor(2000, WIDTH)).isEqualTo(1000d);
    assertThat(axis.timeAt(250, WIDTH)).isEqualTo(1250);
  }

  @Test
  public void startsAtFullExtentAndFollowsGrowingBounds() {
    TimeAxis axis = axisOver(1000, 2000);
    assertThat(axis.isFullExtent()).isTrue();

    axis.setBounds(1000, 5000);

    assertThat(axis.getViewStart()).isEqualTo(1000);
    assertThat(axis.getViewEnd()).isEqualTo(5000);
  }

  @Test
  public void aZoomedAxisStopsFollowingSoTheRegionUnderInspectionStaysPut() {
    TimeAxis axis = axisOver(1000, 5000);
    axis.setView(2000, 3000);

    axis.setBounds(1000, 20_000);

    assertThat(axis.isFullExtent()).isFalse();
    assertThat(axis.getViewStart()).isEqualTo(2000);
    assertThat(axis.getViewEnd()).isEqualTo(3000);
  }

  @Test
  public void zoomingKeepsTheInstantUnderThePointerInPlace() {
    TimeAxis axis = axisOver(0, 1000);

    axis.zoom(0.5, 0.25);

    assertThat(axis.getViewSpanMs()).isEqualTo(500);
    // The anchor was at 250 ms, a quarter of the way in; it must still be a quarter of the way in.
    assertThat(axis.getViewStart() + axis.getViewSpanMs() / 4).isEqualTo(250);
  }

  @Test
  public void zoomingOutBeyondTheBoundsSnapsBackToFullExtent() {
    TimeAxis axis = axisOver(0, 1000);
    axis.setView(400, 500);

    axis.zoom(100, 0.5);

    assertThat(axis.isFullExtent()).isTrue();
    assertThat(axis.getViewStart()).isZero();
    assertThat(axis.getViewEnd()).isEqualTo(1000);
  }

  @Test
  public void panningStopsAtTheBoundsInsteadOfScrollingIntoEmptySpace() {
    TimeAxis axis = axisOver(0, 1000);
    axis.setView(400, 600);

    axis.pan(-10);

    assertThat(axis.getViewStart()).isZero();
    assertThat(axis.getViewSpanMs()).isEqualTo(200);

    axis.pan(10);

    assertThat(axis.getViewEnd()).isEqualTo(1000);
    assertThat(axis.getViewSpanMs()).isEqualTo(200);
  }

  @Test
  public void panningPreservesTheWindowWidth() {
    TimeAxis axis = axisOver(0, 10_000);
    axis.setView(4000, 5000);

    axis.pan(0.5);

    assertThat(axis.getViewSpanMs()).isEqualTo(1000);
    assertThat(axis.getViewStart()).isEqualTo(4500);
  }

  @Test
  public void fitReturnsToTheWholeTimelineAndResumesFollowing() {
    TimeAxis axis = axisOver(0, 1000);
    axis.setView(100, 200);

    axis.fit();
    axis.setBounds(0, 4000);

    assertThat(axis.isFullExtent()).isTrue();
    assertThat(axis.getViewEnd()).isEqualTo(4000);
  }

  @Test
  public void aViewNarrowerThanTheFloorIsWidenedToIt() {
    TimeAxis axis = axisOver(0, 1000);

    axis.setView(500, 500);

    assertThat(axis.getViewSpanMs()).isGreaterThanOrEqualTo(2);
  }

  @Test
  public void anEmptyTimelineStillHasAUsableSpan() {
    TimeAxis axis = axisOver(0, 0);

    assertThat(axis.getBoundsSpanMs()).isGreaterThan(0);
    assertThat(axis.getViewSpanMs()).isGreaterThan(0);
  }

  @Test
  public void setViewAcceptsAReversedDrag() {
    TimeAxis axis = axisOver(0, 1000);

    axis.setView(800, 300);

    assertThat(axis.getViewStart()).isEqualTo(300);
    assertThat(axis.getViewEnd()).isEqualTo(800);
  }
}
