package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

import java.util.function.Function;

/**
 * How rows are bucketed under collapsible headings.
 *
 * <p>Grouping by thread group answers "what was this virtual user doing", which a flat timeline of
 * a many-thread run cannot: the rows interleave. Grouping by label answers "how did this request
 * behave across the run", which is the view that finds the one endpoint dragging a test down.
 */
public enum GroupMode {

  /** No headings; rows appear in whatever order the sort dictates. */
  NONE("No grouping", null),

  /** One heading per thread group, as recovered from the sample's thread name. */
  THREAD_GROUP("Thread Group", SampleRecord::getThreadGroup),

  /** One heading per sample label. */
  LABEL("Label", SampleRecord::getLabel);

  private final String title;
  private final Function<SampleRecord, String> keyExtractor;

  GroupMode(String title, Function<SampleRecord, String> keyExtractor) {
    this.title = title;
    this.keyExtractor = keyExtractor;
  }

  /**
   * The name shown in the grouping selector.
   *
   * @return the display name
   */
  public String getTitle() {
    return title;
  }

  /**
   * The group a sample belongs to.
   *
   * @param record the sample
   * @return the group name, or {@code null} when this mode does not group
   */
  public String keyOf(SampleRecord record) {
    return keyExtractor == null ? null : keyExtractor.apply(record);
  }

  @Override
  public String toString() {
    return title;
  }
}
