package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.table.AbstractTableModel;

/**
 * The table model behind the waterfall: applies the filter, the grouping and the sort, and hands
 * the result to JTable as a flat list of rows.
 *
 * <p>Sorting happens here rather than in a {@code TableRowSorter} so that a view index and a model
 * index are always the same number. That is what lets group headings sit in the same list as
 * sample rows, and it means a renderer painting a bar can look the row up directly instead of
 * translating an index on every cell.
 *
 * <p>Two update paths exist, and the difference is what keeps a live run smooth. When nothing is
 * grouped and no column is sorted - the state a viewer is in while a test runs - arriving samples
 * are appended and only the new rows are announced, which is O(new). Any other state rebuilds the
 * row list, which is O(n log n) but only runs when the user changes something or when the arriving
 * samples have to be re-sorted into place anyway.
 */
public final class WaterfallTableModel extends AbstractTableModel {

  private static final long serialVersionUID = 1L;

  /** Fraction of the timeline left empty after the last sample, so its bar is not flush right. */
  private static final double TRAILING_HEADROOM = 0.02;

  private final WaterfallStore store;
  private final FilterCriteria filter = new FilterCriteria();
  private final TimeAxis axis = new TimeAxis();
  private final List<WaterfallRow> rows = new ArrayList<>();
  private final List<WaterfallColumn> columns = new ArrayList<>();
  private final Set<String> collapsedGroups = new HashSet<>();
  private final Set<String> knownProtocols = new TreeSet<>();
  private GroupMode groupMode = GroupMode.NONE;
  private WaterfallColumn sortColumn;
  private boolean sortAscending = true;
  private int consumedRecords;
  private int lastDroppedCount;
  private long boundsStart;
  private long boundsEnd;

  /**
   * Creates a model over {@code store}.
   *
   * @param store where the samples live
   */
  public WaterfallTableModel(WaterfallStore store) {
    this.store = store;
    for (WaterfallColumn column : WaterfallColumn.values()) {
      if (column.isVisibleByDefault()) {
        columns.add(column);
      }
    }
  }

  /**
   * Takes account of samples added to the store since the last call.
   *
   * <p>Uses the incremental path when the current view allows it, and falls back to a full rebuild
   * otherwise - including when the store has evicted old samples, since that shifts every index
   * the incremental path relies on.
   *
   * @return {@code true} when the table changed
   */
  public boolean refresh() {
    List<SampleRecord> records = store.getRecords();
    boolean evicted = store.getDroppedCount() != lastDroppedCount;
    lastDroppedCount = store.getDroppedCount();
    if (evicted) {
      rebuild();
      return true;
    }
    if (records.size() == consumedRecords) {
      return false;
    }
    if (!canAppend()) {
      rebuild();
      return true;
    }
    int firstNewRow = rows.size();
    for (int i = consumedRecords; i < records.size(); i++) {
      SampleRecord record = records.get(i);
      knownProtocols.add(record.getProtocol());
      if (filter.matches(record)) {
        rows.add(WaterfallRow.sample(record));
        extendBounds(record.getStartTime(), record.getEndTime());
      }
    }
    consumedRecords = records.size();
    publishBounds();
    if (rows.size() > firstNewRow) {
      fireTableRowsInserted(firstNewRow, rows.size() - 1);
    } else {
      // The bars of the rows already on screen still have to be re-scaled against the new bounds.
      fireTableRowsUpdated(0, Math.max(0, rows.size() - 1));
    }
    return true;
  }

  /**
   * Whether arriving samples can simply be appended, which needs the rows to be in arrival order
   * and ungrouped.
   *
   * @return {@code true} when the incremental path applies
   */
  private boolean canAppend() {
    return groupMode == GroupMode.NONE && sortColumn == null;
  }

  /** Rebuilds the whole row list from the store, then tells the table everything changed. */
  public void rebuild() {
    rows.clear();
    boundsStart = 0;
    boundsEnd = 0;
    List<SampleRecord> records = store.getRecords();
    consumedRecords = records.size();
    lastDroppedCount = store.getDroppedCount();
    List<SampleRecord> visible = new ArrayList<>();
    for (SampleRecord record : records) {
      knownProtocols.add(record.getProtocol());
      if (filter.matches(record)) {
        visible.add(record);
      }
    }
    if (groupMode == GroupMode.NONE) {
      addSampleRows(sorted(visible));
    } else {
      addGroupedRows(visible);
    }
    publishBounds();
    fireTableDataChanged();
  }

  private void addSampleRows(List<SampleRecord> records) {
    for (SampleRecord record : records) {
      rows.add(WaterfallRow.sample(record));
      extendBounds(record.getStartTime(), record.getEndTime());
    }
  }

  private void addGroupedRows(List<SampleRecord> visible) {
    Map<String, List<SampleRecord>> groups = new LinkedHashMap<>();
    for (SampleRecord record : visible) {
      groups.computeIfAbsent(groupMode.keyOf(record), key -> new ArrayList<>()).add(record);
    }
    for (Map.Entry<String, List<SampleRecord>> entry : groups.entrySet()) {
      List<SampleRecord> members = entry.getValue();
      long start = Long.MAX_VALUE;
      long end = Long.MIN_VALUE;
      long bytes = 0;
      int errors = 0;
      for (SampleRecord record : members) {
        start = Math.min(start, record.getStartTime());
        end = Math.max(end, record.getEndTime());
        bytes += record.getBytes();
        if (!record.isSuccess()) {
          errors++;
        }
      }
      boolean collapsed = collapsedGroups.contains(entry.getKey());
      rows.add(WaterfallRow.group(entry.getKey(), members.size(), errors, start, end, bytes,
          collapsed));
      extendBounds(start, end);
      if (!collapsed) {
        addSampleRows(sorted(members));
      }
    }
  }

  /**
   * Orders a list of samples by the active sort.
   *
   * <p>Arrival order is the tie-breaker in both directions, so a repaint never reshuffles rows
   * that compare equal - watching identical labels swap places on every update makes a live
   * waterfall unreadable.
   *
   * @param records the samples to order, returned untouched when no column is sorted
   * @return the ordered list
   */
  private List<SampleRecord> sorted(List<SampleRecord> records) {
    if (sortColumn == null || !sortColumn.isSortable()) {
      return records;
    }
    Comparator<SampleRecord> comparator = sortColumn.getComparator();
    if (!sortAscending) {
      comparator = comparator.reversed();
    }
    records.sort(comparator.thenComparingInt(SampleRecord::getSequence));
    return records;
  }

  private void extendBounds(long start, long end) {
    if (boundsEnd == 0 && boundsStart == 0) {
      boundsStart = start;
      boundsEnd = end;
      return;
    }
    boundsStart = Math.min(boundsStart, start);
    boundsEnd = Math.max(boundsEnd, end);
  }

  /**
   * Hands the accumulated bounds to the axis, with a little headroom on the right.
   *
   * <p>Without the headroom the last sample's bar ends exactly at the edge of the column, where it
   * reads as clipped rather than as finished - and on a run whose slowest request is also its last,
   * that is the bar the eye goes to first.
   */
  private void publishBounds() {
    long span = Math.max(0, boundsEnd - boundsStart);
    long headroom = Math.max(1, Math.round(span * TRAILING_HEADROOM));
    axis.setBounds(boundsStart, boundsStart == 0 && boundsEnd == 0 ? 0 : boundsEnd + headroom);
  }

  /**
   * Re-applies the filter and the grouping after the toolbar changed them.
   *
   * <p>Separate from {@link #rebuild()} only as documentation of intent; the work is the same.
   */
  public void filterChanged() {
    rebuild();
  }

  /**
   * The row at a table index.
   *
   * @param rowIndex the index, which is both a view and a model index
   * @return the row, or {@code null} when the index is stale
   */
  public WaterfallRow getRow(int rowIndex) {
    return rowIndex >= 0 && rowIndex < rows.size() ? rows.get(rowIndex) : null;
  }

  /**
   * Finds the table index showing a sample, so a selection can survive a rebuild.
   *
   * @param record the sample to locate, may be {@code null}
   * @return the index, or {@code -1} when the sample is no longer visible
   */
  public int indexOf(SampleRecord record) {
    if (record == null) {
      return -1;
    }
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).getRecord() == record) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Toggles a group heading between collapsed and expanded.
   *
   * @param groupName the group to toggle
   */
  public void toggleGroup(String groupName) {
    if (!collapsedGroups.remove(groupName)) {
      collapsedGroups.add(groupName);
    }
    rebuild();
  }

  /**
   * Collapses or expands every group at once.
   *
   * @param collapsed {@code true} to collapse all
   */
  public void setAllGroupsCollapsed(boolean collapsed) {
    collapsedGroups.clear();
    if (collapsed) {
      for (WaterfallRow row : rows) {
        if (row.isGroup()) {
          collapsedGroups.add(row.getGroupName());
        }
      }
    }
    rebuild();
  }

  /**
   * Applies a header click: first click sorts ascending, a second click on the same column
   * reverses it, a third clears the sort and returns the rows to arrival order.
   *
   * @param column the column whose header was clicked
   */
  public void cycleSort(WaterfallColumn column) {
    if (column == null || !column.isSortable()) {
      return;
    }
    if (column != sortColumn) {
      sortColumn = column;
      sortAscending = true;
    } else if (sortAscending) {
      sortAscending = false;
    } else {
      sortColumn = null;
      sortAscending = true;
    }
    rebuild();
  }

  /**
   * The sorted column.
   *
   * @return the column, or {@code null} when rows are in arrival order
   */
  public WaterfallColumn getSortColumn() {
    return sortColumn;
  }

  /**
   * The sort direction.
   *
   * @return {@code true} when ascending
   */
  public boolean isSortAscending() {
    return sortAscending;
  }

  /**
   * The grouping in force.
   *
   * @return the group mode
   */
  public GroupMode getGroupMode() {
    return groupMode;
  }

  /**
   * Changes the grouping and rebuilds.
   *
   * @param groupMode the new mode
   */
  public void setGroupMode(GroupMode groupMode) {
    if (this.groupMode != groupMode) {
      this.groupMode = groupMode;
      collapsedGroups.clear();
      rebuild();
    }
  }

  /**
   * The filter, edited in place by the toolbar. Call {@link #filterChanged()} afterwards.
   *
   * @return the live filter
   */
  public FilterCriteria getFilter() {
    return filter;
  }

  /**
   * The shared time axis.
   *
   * @return the axis driving the bars and the ruler
   */
  public TimeAxis getAxis() {
    return axis;
  }

  /**
   * The store behind this model.
   *
   * @return the store
   */
  public WaterfallStore getStore() {
    return store;
  }

  /**
   * Every protocol seen so far, for populating the protocol filter.
   *
   * <p>Accumulated over all samples rather than the visible ones, so filtering to HTTP/2 does not
   * remove HTTP/1.1 from the list of things to filter to next.
   *
   * @return the protocols, including an empty string when some sample had none
   */
  public Set<String> getKnownProtocols() {
    return knownProtocols;
  }

  /**
   * How many sample rows are visible, group headings excluded.
   *
   * @return the visible sample count
   */
  public int getVisibleSampleCount() {
    int count = 0;
    for (WaterfallRow row : rows) {
      if (!row.isGroup()) {
        count++;
      }
    }
    return count;
  }

  /** Forgets every row and every accumulated bound. */
  public void clear() {
    rows.clear();
    collapsedGroups.clear();
    knownProtocols.clear();
    consumedRecords = 0;
    lastDroppedCount = 0;
    boundsStart = 0;
    boundsEnd = 0;
    axis.setBounds(0, 0);
    axis.fit();
    fireTableDataChanged();
  }

  /**
   * The column at a table index.
   *
   * @param columnIndex the index
   * @return the column
   */
  public WaterfallColumn getColumn(int columnIndex) {
    return columns.get(columnIndex);
  }

  /**
   * Shows or hides a column.
   *
   * @param column  the column
   * @param visible {@code true} to show it
   */
  public void setColumnVisible(WaterfallColumn column, boolean visible) {
    if (visible == columns.contains(column)) {
      return;
    }
    if (visible) {
      columns.add(column);
      columns.sort(Comparator.comparingInt(WaterfallColumn::ordinal));
    } else if (columns.size() > 1) {
      columns.remove(column);
    }
    fireTableStructureChanged();
  }

  /**
   * Whether a column is shown.
   *
   * @param column the column
   * @return {@code true} when visible
   */
  public boolean isColumnVisible(WaterfallColumn column) {
    return columns.contains(column);
  }

  @Override
  public int getRowCount() {
    return rows.size();
  }

  @Override
  public int getColumnCount() {
    return columns.size();
  }

  @Override
  public String getColumnName(int columnIndex) {
    return columns.get(columnIndex).getTitle();
  }

  @Override
  public Class<?> getColumnClass(int columnIndex) {
    return WaterfallRow.class;
  }

  /**
   * Returns the row itself for every column.
   *
   * <p>Each renderer then reads exactly the fields it draws. A per-column value object would have
   * meant allocating one per visible cell per repaint, and the bar renderer needs the whole row
   * regardless.
   *
   * @param rowIndex    the row
   * @param columnIndex the column
   * @return the row
   */
  @Override
  public Object getValueAt(int rowIndex, int columnIndex) {
    return getRow(rowIndex);
  }

  @Override
  public boolean isCellEditable(int rowIndex, int columnIndex) {
    return false;
  }
}
