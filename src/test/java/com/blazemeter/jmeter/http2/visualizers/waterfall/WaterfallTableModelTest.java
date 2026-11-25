package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.GroupMode;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallColumn;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallRow;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallStore;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallTableModel;
import java.util.ArrayList;
import java.util.List;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.Before;
import org.junit.Test;

public class WaterfallTableModelTest {

  private WaterfallStore store;
  private WaterfallTableModel model;

  private static SampleResult sample(String label, String thread, long start, long elapsed,
      String code) {
    return SampleResultBuilder.http()
        .label(label)
        .thread(thread)
        .code(code)
        .timing(start, elapsed)
        .build();
  }

  @Before
  public void setUp() {
    store = new WaterfallStore(-1);
    model = new WaterfallTableModel(store);
  }

  private void offerAndRefresh(SampleResult... samples) {
    for (SampleResult sample : samples) {
      store.offer(sample);
    }
    store.drainPending(1000);
    model.refresh();
  }

  private List<String> labels() {
    List<String> labels = new ArrayList<>();
    for (int i = 0; i < model.getRowCount(); i++) {
      WaterfallRow row = model.getRow(i);
      labels.add(row.isGroup() ? "[" + row.getGroupName() + "]" : row.getRecord().getLabel());
    }
    return labels;
  }

  @Test
  public void showsSamplesInArrivalOrderByDefault() {
    offerAndRefresh(sample("a", "TG 1-1", 1000, 10, "200"),
        sample("b", "TG 1-2", 900, 10, "200"));

    assertThat(labels()).containsExactly("a", "b");
  }

  /**
   * The timeline starts exactly at the first sample but ends a little past the last one: without
   * that headroom the final bar is drawn flush against the edge of the column and reads as clipped.
   */
  @Test
  public void boundsCoverEverySampleOnScreenWithHeadroomAfterTheLast() {
    offerAndRefresh(sample("a", "TG 1-1", 1000, 500, "200"),
        sample("b", "TG 1-2", 2000, 300, "200"));

    assertThat(model.getAxis().getBoundsStart()).isEqualTo(1000);
    assertThat(model.getAxis().getBoundsEnd())
        .isGreaterThan(2300)
        .isLessThan(2300 + (2300 - 1000) / 10);
  }

  @Test
  public void arrivingSamplesAreAppendedRatherThanRebuildingTheWholeList() {
    List<TableModelEvent> events = new ArrayList<>();
    TableModelListener listener = events::add;
    offerAndRefresh(sample("a", "TG 1-1", 1000, 10, "200"));
    model.addTableModelListener(listener);

    offerAndRefresh(sample("b", "TG 1-1", 1100, 10, "200"));

    assertThat(events).hasSize(1);
    assertThat(events.get(0).getType()).isEqualTo(TableModelEvent.INSERT);
    assertThat(events.get(0).getFirstRow()).isEqualTo(1);
  }

  @Test
  public void refreshReportsNoChangeWhenNothingArrived() {
    offerAndRefresh(sample("a", "TG 1-1", 1000, 10, "200"));

    assertThat(model.refresh()).isFalse();
  }

  @Test
  public void filteringHidesRowsAndTheCountReflectsIt() {
    offerAndRefresh(sample("home", "TG 1-1", 1000, 10, "200"),
        sample("checkout", "TG 1-1", 1100, 10, "500"));

    model.getFilter().setText("checkout");
    model.filterChanged();

    assertThat(labels()).containsExactly("checkout");
    assertThat(model.getVisibleSampleCount()).isEqualTo(1);
    assertThat(store.getRecords()).hasSize(2);
  }

  @Test
  public void sortingCyclesThroughAscendingDescendingAndBack() {
    offerAndRefresh(sample("b", "TG 1-1", 1000, 300, "200"),
        sample("a", "TG 1-1", 1100, 100, "200"));

    model.cycleSort(WaterfallColumn.TIME);
    assertThat(labels()).containsExactly("a", "b");
    assertThat(model.getSortColumn()).isEqualTo(WaterfallColumn.TIME);
    assertThat(model.isSortAscending()).isTrue();

    model.cycleSort(WaterfallColumn.TIME);
    assertThat(labels()).containsExactly("b", "a");
    assertThat(model.isSortAscending()).isFalse();

    model.cycleSort(WaterfallColumn.TIME);
    assertThat(model.getSortColumn()).isNull();
    assertThat(labels()).containsExactly("b", "a");
  }

  @Test
  public void theWaterfallColumnCannotBeSorted() {
    offerAndRefresh(sample("a", "TG 1-1", 1000, 10, "200"));

    model.cycleSort(WaterfallColumn.WATERFALL);

    assertThat(model.getSortColumn()).isNull();
  }

  @Test
  public void groupingByThreadGroupInsertsHeadings() {
    offerAndRefresh(sample("a", "Shoppers 1-1", 1000, 10, "200"),
        sample("b", "Admins 1-1", 1100, 10, "200"),
        sample("c", "Shoppers 1-2", 1200, 10, "200"));

    model.setGroupMode(GroupMode.THREAD_GROUP);

    assertThat(labels()).containsExactly("[Shoppers]", "a", "c", "[Admins]", "b");
  }

  @Test
  public void aGroupHeadingAggregatesItsSamples() {
    offerAndRefresh(
        SampleResultBuilder.http().label("a").thread("TG 1-1").code("200").bytes(100)
            .timing(1000, 200).build(),
        SampleResultBuilder.http().label("b").thread("TG 1-2").code("500").success(false)
            .bytes(50).timing(1500, 300).build());

    model.setGroupMode(GroupMode.THREAD_GROUP);
    WaterfallRow heading = model.getRow(0);

    assertThat(heading.isGroup()).isTrue();
    assertThat(heading.getSampleCount()).isEqualTo(2);
    assertThat(heading.getErrorCount()).isEqualTo(1);
    assertThat(heading.getStartTime()).isEqualTo(1000);
    assertThat(heading.getEndTime()).isEqualTo(1800);
    assertThat(heading.getBytes()).isEqualTo(150);
  }

  @Test
  public void collapsingAGroupHidesItsSamplesButKeepsTheHeading() {
    offerAndRefresh(sample("a", "Shoppers 1-1", 1000, 10, "200"),
        sample("b", "Shoppers 1-2", 1100, 10, "200"));
    model.setGroupMode(GroupMode.THREAD_GROUP);

    model.toggleGroup("Shoppers");

    assertThat(labels()).containsExactly("[Shoppers]");
    assertThat(model.getRow(0).isCollapsed()).isTrue();

    model.toggleGroup("Shoppers");

    assertThat(labels()).containsExactly("[Shoppers]", "a", "b");
  }

  @Test
  public void groupingByLabelBucketsTheSameRequestAcrossThreads() {
    offerAndRefresh(sample("login", "TG 1-1", 1000, 10, "200"),
        sample("search", "TG 1-1", 1100, 10, "200"),
        sample("login", "TG 1-2", 1200, 10, "200"));

    model.setGroupMode(GroupMode.LABEL);

    assertThat(labels()).containsExactly("[login]", "login", "login", "[search]", "search");
  }

  @Test
  public void sortingAppliesWithinGroups() {
    offerAndRefresh(sample("slow", "TG 1-1", 1000, 900, "200"),
        sample("fast", "TG 1-2", 1100, 20, "200"));
    model.setGroupMode(GroupMode.THREAD_GROUP);

    model.cycleSort(WaterfallColumn.TIME);

    assertThat(labels()).containsExactly("[TG]", "fast", "slow");
  }

  @Test
  public void groupingForcesTheFullRebuildPathForArrivingSamples() {
    offerAndRefresh(sample("a", "Shoppers 1-1", 1000, 10, "200"));
    model.setGroupMode(GroupMode.THREAD_GROUP);

    offerAndRefresh(sample("b", "Admins 1-1", 1100, 10, "200"));

    assertThat(labels()).containsExactly("[Shoppers]", "a", "[Admins]", "b");
  }

  @Test
  public void everyProtocolSeenStaysAvailableToFilterOnEvenWhenFilteredOut() {
    offerAndRefresh(SampleResultBuilder.http().label("h2").thread("TG 1-1")
            .responseHeaders("HTTP/2.0 200 OK\n").timing(1000, 10).build(),
        SampleResultBuilder.http().label("h1").thread("TG 1-1")
            .responseHeaders("HTTP/1.1 200 OK\n").timing(1100, 10).build());

    model.getFilter().setProtocolAccepted("HTTP/2", true);
    model.filterChanged();

    assertThat(model.getKnownProtocols()).contains("HTTP/2", "HTTP/1.1");
    assertThat(labels()).containsExactly("h2");
  }

  @Test
  public void aSampleCanBeFoundAgainAfterARebuildSoTheSelectionSurvives() {
    offerAndRefresh(sample("a", "TG 1-1", 1000, 10, "200"),
        sample("b", "TG 1-1", 1100, 10, "200"));

    model.cycleSort(WaterfallColumn.NAME);

    assertThat(model.indexOf(store.getRecords().get(1))).isEqualTo(1);
    assertThat(model.indexOf(null)).isEqualTo(-1);
  }

  @Test
  public void evictionInTheStoreForcesARebuildInsteadOfAppendingAtStaleIndices() {
    WaterfallStore bounded = new WaterfallStore(2);
    WaterfallTableModel boundedModel = new WaterfallTableModel(bounded);
    for (int i = 0; i < 5; i++) {
      bounded.offer(sample("s" + i, "TG 1-1", 1000 + i, 10, "200"));
    }
    bounded.drainPending(1000);

    assertThat(boundedModel.refresh()).isTrue();
    assertThat(boundedModel.getRowCount()).isEqualTo(2);
    assertThat(boundedModel.getRow(0).getRecord().getLabel()).isEqualTo("s3");
  }

  @Test
  public void hidingAColumnChangesTheColumnSetButNeverEmptiesIt() {
    assertThat(model.isColumnVisible(WaterfallColumn.METHOD)).isTrue();

    model.setColumnVisible(WaterfallColumn.METHOD, false);

    assertThat(model.isColumnVisible(WaterfallColumn.METHOD)).isFalse();
    assertThat(model.getColumnCount()).isGreaterThan(0);
  }

  @Test
  public void aHiddenColumnComesBackInItsDeclaredPosition() {
    model.setColumnVisible(WaterfallColumn.METHOD, false);
    model.setColumnVisible(WaterfallColumn.METHOD, true);

    assertThat(model.getColumn(0)).isEqualTo(WaterfallColumn.NAME);
    assertThat(model.getColumn(1)).isEqualTo(WaterfallColumn.METHOD);
  }

  @Test
  public void clearEmptiesTheTableAndTheAxis() {
    offerAndRefresh(sample("a", "TG 1-1", 1000, 10, "200"));

    model.clear();

    assertThat(model.getRowCount()).isZero();
    assertThat(model.getAxis().isFullExtent()).isTrue();
  }

  @Test
  public void everyColumnReturnsTheRowSoRenderersCanReadWhatTheyNeed() {
    offerAndRefresh(sample("a", "TG 1-1", 1000, 10, "200"));

    for (int column = 0; column < model.getColumnCount(); column++) {
      assertThat(model.getValueAt(0, column)).isInstanceOf(WaterfallRow.class);
      assertThat(model.isCellEditable(0, column)).isFalse();
    }
  }
}
