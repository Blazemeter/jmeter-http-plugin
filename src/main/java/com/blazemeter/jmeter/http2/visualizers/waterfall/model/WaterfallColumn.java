package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

import java.util.Comparator;

/**
 * The columns of the waterfall table: their headings, their default widths and how a click on
 * their header sorts the rows.
 *
 * <p>Keeping the comparator next to the column is what lets the table model sort without a
 * {@code TableRowSorter}. The model sorts its own row list instead, so a view index is always a
 * model index - which is what makes it possible to mix group headings in with sample rows, and
 * keeps the waterfall bars, the row heights and the selection in agreement.
 */
public enum WaterfallColumn {

  /** Sample label, indented by sub-sample depth. */
  NAME("Name", 260, 140, true, Comparator.comparing(SampleRecord::getLabel)),

  /** HTTP method, blank for a non-HTTP sampler. */
  METHOD("Method", 70, 58, true, Comparator.comparing(SampleRecord::getMethod)),

  /** Response code, coloured by status class. */
  STATUS("Status", 70, 58, true, Comparator.comparing(SampleRecord::getResponseCode)),

  /** Negotiated protocol, blank when the sample records no status line. */
  PROTOCOL("Protocol", 80, 66, true, Comparator.comparing(SampleRecord::getProtocol)),

  /** Response content subtype. */
  TYPE("Type", 80, 56, false, Comparator.comparing(SampleRecord::getContentType)),

  /** Bytes received. */
  SIZE("Size", 84, 80, true, Comparator.comparingLong(SampleRecord::getBytes)),

  /** Elapsed time. */
  TIME("Time", 84, 80, true, Comparator.comparingLong(SampleRecord::getElapsed)),

  /** Start time relative to the first sample on screen. */
  START("Start", 84, 80, true, Comparator.comparingLong(SampleRecord::getStartTime)),

  /** Thread name. */
  THREAD("Thread", 150, 90, false, Comparator.comparing(SampleRecord::getThreadName)),

  /** The bars. Not sortable: a click on this header would have no meaningful ordering. */
  WATERFALL("Waterfall", 520, 160, true, null);

  private final String title;
  private final int preferredWidth;
  private final int minimumWidth;
  private final boolean visibleByDefault;
  private final Comparator<SampleRecord> comparator;

  WaterfallColumn(String title, int preferredWidth, int minimumWidth, boolean visibleByDefault,
      Comparator<SampleRecord> comparator) {
    this.title = title;
    this.preferredWidth = preferredWidth;
    this.minimumWidth = minimumWidth;
    this.visibleByDefault = visibleByDefault;
    this.comparator = comparator;
  }

  /**
   * The column heading.
   *
   * @return the text shown in the table header
   */
  public String getTitle() {
    return title;
  }

  /**
   * The width the column gets before the user resizes it.
   *
   * @return the preferred width in pixels at the default font scale
   */
  public int getPreferredWidth() {
    return preferredWidth;
  }

  /**
   * The narrowest the column may be squeezed to.
   *
   * <p>The numeric columns get a floor wide enough for their longest realistic value, because a
   * table narrower than the sum of its preferred widths shrinks every column proportionally, and a
   * Size column reading {@code 500.29 ...} is worse than a narrower Name column.
   *
   * @return the minimum width in pixels at the default font scale
   */
  public int getMinimumWidth() {
    return minimumWidth;
  }

  /**
   * Whether the column is shown until the user says otherwise.
   *
   * @return {@code true} when visible in a fresh viewer
   */
  public boolean isVisibleByDefault() {
    return visibleByDefault;
  }

  /**
   * How to order rows by this column.
   *
   * @return the comparator, or {@code null} when the column cannot be sorted
   */
  public Comparator<SampleRecord> getComparator() {
    return comparator;
  }

  /**
   * Whether a click on this header should sort.
   *
   * @return {@code true} when the column has an ordering
   */
  public boolean isSortable() {
    return comparator != null;
  }
}
