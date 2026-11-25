package com.blazemeter.jmeter.http2.visualizers.waterfall;

import com.blazemeter.jmeter.http2.visualizers.waterfall.details.SampleDetailsPanel;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallRow;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallStore;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallTableModel;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.apache.jmeter.samplers.SampleResult;

/**
 * The waterfall view: toolbar, table, and the details panel below it.
 *
 * <p>This is the whole viewer, and it is deliberately one component that can live in two places.
 * Expanding does not build a second view - it moves this panel out of JMeter's listener slot and
 * into a window of its own, leaving a note behind. So the samples, the filters, the zoom level and
 * the selection are the same objects either way, and switching between embedded and expanded never
 * loses the state someone was in the middle of reading.
 *
 * <p>Samples arrive on sampler threads and are only ever queued there. A timer on the event
 * dispatch thread drains the queue in bounded batches, which is what stops a fast test - or a large
 * {@code .jtl} being read - from turning into thousands of individual table events.
 */
public final class WaterfallPanel extends JPanel implements WaterfallHost {

  private static final long serialVersionUID = 1L;

  /** Often enough to feel live, rarely enough that a fast test does not repaint continuously. */
  private static final int REFRESH_INTERVAL_MS = 250;

  /** Records created per drain, bounding how long one tick occupies the event dispatch thread. */
  private static final int MAX_RECORDS_PER_TICK = 5000;

  private static final int DETAILS_DIVIDER_LOCATION = 320;
  private static final int MIN_TABLE_HEIGHT = 120;
  private static final int MIN_DETAILS_HEIGHT = 140;

  private final WaterfallStore store = new WaterfallStore();
  private final WaterfallTableModel model = new WaterfallTableModel(store);
  private final WaterfallTable table = new WaterfallTable(model);
  private final SampleDetailsPanel details = new SampleDetailsPanel();
  private final JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
  private final WaterfallStatusBar statusBar = new WaterfallStatusBar();
  private final WaterfallToolBar toolBar;
  private final Container embeddedSlot;
  private final Timer refreshTimer;
  private final int dividerSize;
  private transient SampleRecord selectedRecord;
  private WaterfallWindow window;
  private boolean restoringSelection;
  private volatile boolean draining;

  /**
   * Builds the view.
   *
   * @param embeddedSlot the container this panel lives in while not expanded, so it knows where to
   *                     return to
   */
  public WaterfallPanel(Container embeddedSlot) {
    super(new BorderLayout());
    this.embeddedSlot = embeddedSlot;
    this.toolBar = new WaterfallToolBar(model, table, this);
    JScrollPane tableScroll = new JScrollPane(table);
    tableScroll.setMinimumSize(new Dimension(0, MIN_TABLE_HEIGHT));
    details.setMinimumSize(new Dimension(0, MIN_DETAILS_HEIGHT));
    details.setCloseAction(() -> {
      setDetailsVisible(false);
      toolBar.setDetailsSelected(false);
    });
    splitPane.setTopComponent(tableScroll);
    splitPane.setBottomComponent(details);
    splitPane.setResizeWeight(0.65);
    splitPane.setDividerLocation(DETAILS_DIVIDER_LOCATION);
    this.dividerSize = splitPane.getDividerSize();
    add(toolBar, BorderLayout.NORTH);
    add(splitPane, BorderLayout.CENTER);
    add(statusBar, BorderLayout.SOUTH);
    table.getSelectionModel().addListSelectionListener(event -> {
      if (!event.getValueIsAdjusting()) {
        selectionChanged();
      }
    });
    model.addTableModelListener(event -> restoreSelection());
    refreshTimer = new Timer(REFRESH_INTERVAL_MS, event -> pump());
    refreshTimer.setRepeats(true);
  }

  /**
   * Queues a sample. Called from sampler threads, and from the JTL reader.
   *
   * <p>Does no more than enqueue, and wakes the drain timer if it had gone idle. JMeter builds one
   * instance of every listener GUI at startup just to read its menu label and then discards it, so
   * a timer started in the constructor would run for the rest of the session on an object nobody
   * can see; starting on the first sample means the throwaway instance never starts one.
   *
   * @param result the sample
   */
  public void addResult(SampleResult result) {
    store.offer(result);
    if (!draining) {
      SwingUtilities.invokeLater(this::startDraining);
    }
  }

  /** Starts the drain timer if it is not already running. Runs on the event dispatch thread. */
  private void startDraining() {
    if (!draining) {
      draining = true;
      refreshTimer.start();
    }
  }

  /**
   * Drains queued samples into the table, then goes idle again when the queue is empty.
   *
   * <p>Idling matters because a listener sits in a test plan for the whole JMeter session: a timer
   * left ticking forever would wake the event dispatch thread four times a second for a test that
   * finished an hour ago.
   */
  private void pump() {
    boolean drained = store.drainPending(MAX_RECORDS_PER_TICK) > 0;
    boolean changed = model.refresh();
    if (drained || changed) {
      table.repaintTimeline();
      updateStatus();
    }
    if (store.hasPending()) {
      return;
    }
    // Clearing the flag before stopping closes the race with addResult: a sample arriving in the
    // gap either sees the flag already down and schedules a restart, or lands in the queue that
    // the re-check below notices.
    draining = false;
    refreshTimer.stop();
    if (store.hasPending()) {
      startDraining();
    }
  }

  private void updateStatus() {
    statusBar.update(model.getVisibleSampleCount(), store.getRecords().size(),
        store.getDroppedCount());
  }

  private void selectionChanged() {
    if (restoringSelection) {
      return;
    }
    WaterfallRow row = model.getRow(table.getSelectedRow());
    selectedRecord = row == null ? null : row.getRecord();
    details.setRecord(selectedRecord);
  }

  /**
   * Re-selects the previously selected sample after the row list was rebuilt.
   *
   * <p>Filtering, grouping and sorting all replace the row list, and a table event of that kind
   * drops the selection. Without this, changing a filter would close the details panel on the very
   * request being investigated.
   */
  private void restoreSelection() {
    if (selectedRecord == null || restoringSelection) {
      return;
    }
    int index = model.indexOf(selectedRecord);
    if (index < 0 || index == table.getSelectedRow()) {
      return;
    }
    restoringSelection = true;
    try {
      table.setRowSelectionInterval(index, index);
    } finally {
      restoringSelection = false;
    }
  }

  @Override
  public void setDetailsVisible(boolean visible) {
    if (visible == isDetailsVisible()) {
      return;
    }
    if (visible) {
      splitPane.setBottomComponent(details);
      splitPane.setDividerSize(dividerSize);
      splitPane.setDividerLocation(DETAILS_DIVIDER_LOCATION);
    } else {
      splitPane.setBottomComponent(null);
      // A split pane with nothing below it still draws its divider and its drag handles, which
      // reads as a collapsed panel waiting to be dragged open rather than one that is switched off.
      splitPane.setDividerSize(0);
    }
    revalidate();
    repaint();
  }

  @Override
  public boolean isDetailsVisible() {
    return splitPane.getBottomComponent() == details;
  }

  @Override
  public void toggleExpandedWindow() {
    if (window != null) {
      returnToPanel();
    } else {
      expandToWindow();
    }
  }

  @Override
  public boolean isExpanded() {
    return window != null;
  }

  /**
   * Moves this panel into a window of its own, leaving a note in the listener slot.
   *
   * <p>Moving rather than copying is what keeps one set of samples and one selection. The note
   * matters too: an empty listener panel with the data apparently gone is alarming, and JMeter
   * gives no other clue that a window belonging to this listener is open somewhere.
   */
  private void expandToWindow() {
    embeddedSlot.remove(this);
    embeddedSlot.add(buildExpandedNotice(), BorderLayout.CENTER);
    embeddedSlot.revalidate();
    embeddedSlot.repaint();
    window = new WaterfallWindow(this, this::returnToPanel);
    window.open();
  }

  /** Moves this panel back into the listener slot and closes the window. */
  private void returnToPanel() {
    if (window == null) {
      return;
    }
    WaterfallWindow closing = window;
    window = null;
    closing.close();
    embeddedSlot.removeAll();
    embeddedSlot.add(this, BorderLayout.CENTER);
    embeddedSlot.revalidate();
    embeddedSlot.repaint();
    toolBar.updateExpandButton();
  }

  private static JPanel buildExpandedNotice() {
    JPanel notice = new JPanel(new BorderLayout());
    JLabel label = new JLabel("The Waterfall Viewer is open in its own window."
        + " Close that window, or use its Return to panel button, to bring it back here.",
        SwingConstants.CENTER);
    label.setBorder(BorderFactory.createEmptyBorder(20, 12, 20, 12));
    label.setEnabled(false);
    notice.add(label, BorderLayout.CENTER);
    return notice;
  }

  @Override
  public void clearResults() {
    store.clear();
    model.clear();
    selectedRecord = null;
    details.setRecord(null);
    table.fitTimeline();
    updateStatus();
  }

  /**
   * Discards every sample and every filter, for the listener's Clear action.
   *
   * <p>Filters are reset too: JMeter's Clear is understood as "start again", and leaving a filter
   * in place would make a fresh run look empty for reasons nothing on screen explains.
   */
  public void reset() {
    clearResults();
    toolBar.clearFilters();
  }

  /**
   * Stops the refresh timer, for when the listener is discarded.
   *
   * <p>Swing timers are held by a shared queue, so one left running keeps this panel - and every
   * sample it holds - reachable for the rest of the JMeter session.
   */
  public void dispose() {
    refreshTimer.stop();
    if (window != null) {
      returnToPanel();
    }
  }

  /**
   * The toolbar, so the window can keep its expand button in step.
   *
   * @return the toolbar
   */
  WaterfallToolBar getToolBar() {
    return toolBar;
  }

  /**
   * The table model. Package-private for tests and for the screenshot harness, which need to set up
   * a filter or a grouping without going through the toolbar's widgets.
   *
   * @return the model
   */
  WaterfallTableModel getModel() {
    return model;
  }

  /**
   * The table. Package-private for the same reason as {@link #getModel()}.
   *
   * @return the table
   */
  WaterfallTable getTable() {
    return table;
  }

  /**
   * Drains queued samples immediately instead of waiting for the timer.
   *
   * <p>For tests and the screenshot harness: a scenario that has just pushed samples in needs them
   * on screen now, not in 250 ms.
   */
  void drainNow() {
    store.drainPending(Integer.MAX_VALUE);
    model.refresh();
    updateStatus();
  }

  /**
   * Selects a row, as a click would.
   *
   * @param rowIndex the row to select
   */
  void selectRow(int rowIndex) {
    if (rowIndex >= 0 && rowIndex < table.getRowCount()) {
      table.setRowSelectionInterval(rowIndex, rowIndex);
    }
  }

  /**
   * Brings one details tab to the front.
   *
   * @param title the tab title
   */
  void selectDetailsTab(String title) {
    details.selectTab(title);
  }
}
