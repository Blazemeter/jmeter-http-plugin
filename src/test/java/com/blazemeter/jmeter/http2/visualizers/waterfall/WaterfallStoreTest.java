package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallStore;
import java.util.List;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.Test;

public class WaterfallStoreTest {

  private static final int UNBOUNDED = -1;

  @Test
  public void offeredSamplesOnlyAppearOnceDrained() {
    WaterfallStore store = new WaterfallStore(UNBOUNDED);
    store.offer(SampleResultBuilder.http().label("a").build());

    assertThat(store.getRecords()).isEmpty();
    assertThat(store.hasPending()).isTrue();

    assertThat(store.drainPending(100)).isEqualTo(1);
    assertThat(store.getRecords()).hasSize(1);
    assertThat(store.hasPending()).isFalse();
  }

  @Test
  public void drainingIsBoundedSoOneCallCannotMonopoliseTheEventThread() {
    WaterfallStore store = new WaterfallStore(UNBOUNDED);
    for (int i = 0; i < 10; i++) {
      store.offer(SampleResultBuilder.http().label("s" + i).build());
    }

    assertThat(store.drainPending(4)).isEqualTo(4);
    assertThat(store.hasPending()).isTrue();
    assertThat(store.drainPending(100)).isEqualTo(6);
    assertThat(store.hasPending()).isFalse();
  }

  /**
   * Note the labels: JMeter renames a sub-sample to {@code parentLabel-index} unless
   * {@code subresults.disable_renaming} is set, so the row labels here are JMeter's, not the ones
   * the test asked for. What matters is the flattening - depth-first, one row per sample, with the
   * depth that the Name column indents by.
   */
  @Test
  public void subSamplesBecomeRowsOfTheirOwnWithIncreasingDepth() {
    SampleResult grandChild = SampleResultBuilder.http().label("logo.png").timing(1200, 5).build();
    SampleResult child = SampleResultBuilder.http().label("app.css").timing(1100, 20)
        .child(grandChild).build();
    SampleResult parent = SampleResultBuilder.http().label("page").timing(1000, 200)
        .child(child).build();
    WaterfallStore store = new WaterfallStore(UNBOUNDED);
    store.offer(parent);

    assertThat(store.drainPending(100)).isEqualTo(3);

    List<SampleRecord> records = store.getRecords();
    assertThat(records).extracting(SampleRecord::getDepth).containsExactly(0, 1, 2);
    assertThat(records).extracting(SampleRecord::getStartTime).containsExactly(1000L, 1100L, 1200L);
    assertThat(records.get(0).getLabel()).isEqualTo("page");
  }

  @Test
  public void subSamplesCanBeLeftOut() {
    SampleResult parent = SampleResultBuilder.http()
        .label("page")
        .child(SampleResultBuilder.http().label("app.css").build())
        .build();
    WaterfallStore store = new WaterfallStore(UNBOUNDED);
    store.setIncludeSubSamples(false);
    store.offer(parent);

    assertThat(store.drainPending(100)).isEqualTo(1);
    assertThat(store.getRecords()).extracting(SampleRecord::getLabel).containsExactly("page");
  }

  @Test
  public void oldestRecordsAreEvictedOnceTheRetentionBoundIsPassed() {
    WaterfallStore store = new WaterfallStore(10);
    for (int i = 0; i < 12; i++) {
      store.offer(SampleResultBuilder.http().label("s" + i).build());
    }

    store.drainPending(100);

    assertThat(store.getRecords()).hasSize(10);
    assertThat(store.getDroppedCount()).isEqualTo(2);
    assertThat(store.getRecords().get(0).getLabel()).isEqualTo("s2");
  }

  @Test
  public void evictionIsReportedSoTheUiCanSayTheTimelineIsPartial() {
    WaterfallStore store = new WaterfallStore(2);
    for (int i = 0; i < 5; i++) {
      store.offer(SampleResultBuilder.http().label("s" + i).build());
      store.drainPending(100);
    }

    assertThat(store.getRecords()).hasSize(2);
    assertThat(store.getDroppedCount()).isEqualTo(3);
  }

  @Test
  public void sequenceNumbersKeepRisingAcrossDrains() {
    WaterfallStore store = new WaterfallStore(UNBOUNDED);
    store.offer(SampleResultBuilder.http().label("a").build());
    store.drainPending(100);
    store.offer(SampleResultBuilder.http().label("b").build());
    store.drainPending(100);

    assertThat(store.getRecords()).extracting(SampleRecord::getSequence).containsExactly(0, 1);
  }

  @Test
  public void clearForgetsEverythingIncludingTheDroppedCount() {
    WaterfallStore store = new WaterfallStore(1);
    store.offer(SampleResultBuilder.http().label("a").build());
    store.offer(SampleResultBuilder.http().label("b").build());
    store.drainPending(100);

    store.clear();

    assertThat(store.getRecords()).isEmpty();
    assertThat(store.getDroppedCount()).isZero();
    assertThat(store.hasPending()).isFalse();
  }

  @Test
  public void nullSamplesAreIgnored() {
    WaterfallStore store = new WaterfallStore(UNBOUNDED);
    store.offer(null);

    assertThat(store.hasPending()).isFalse();
    assertThat(store.drainPending(100)).isZero();
  }
}
