package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

/**
 * A visible line of the table: either one sample, or a heading that stands for a collapsed or
 * expanded group of samples.
 *
 * <p>Modelling both as the same type keeps the table a flat list, which is what makes JTable's own
 * row virtualisation do the work: only the lines on screen are ever asked for, whether the viewer
 * is holding fifty samples or fifty thousand. A tree table would have cost that for no gain, since
 * grouping here is never more than one level deep.
 */
public final class WaterfallRow {

  private final SampleRecord record;
  private final String groupName;
  private final int sampleCount;
  private final int errorCount;
  private final long startTime;
  private final long endTime;
  private final long bytes;
  private final boolean collapsed;

  private WaterfallRow(SampleRecord record, String groupName, int sampleCount, int errorCount,
      long startTime, long endTime, long bytes, boolean collapsed) {
    this.record = record;
    this.groupName = groupName;
    this.sampleCount = sampleCount;
    this.errorCount = errorCount;
    this.startTime = startTime;
    this.endTime = endTime;
    this.bytes = bytes;
    this.collapsed = collapsed;
  }

  /**
   * Wraps one sample.
   *
   * @param record the sample
   * @return a sample row
   */
  public static WaterfallRow sample(SampleRecord record) {
    return new WaterfallRow(record, null, 1, record.isSuccess() ? 0 : 1, record.getStartTime(),
        record.getEndTime(), record.getBytes(), false);
  }

  /**
   * Builds a group heading.
   *
   * @param groupName   the group's name, a thread group or a label
   * @param sampleCount how many samples it contains
   * @param errorCount  how many of those failed
   * @param startTime   the earliest start time in the group, in epoch milliseconds
   * @param endTime     the latest end time in the group, in epoch milliseconds
   * @param bytes       the total bytes received by the group
   * @param collapsed   whether its samples are currently hidden
   * @return a group row
   */
  public static WaterfallRow group(String groupName, int sampleCount, int errorCount,
      long startTime, long endTime, long bytes, boolean collapsed) {
    return new WaterfallRow(null, groupName, sampleCount, errorCount, startTime, endTime, bytes,
        collapsed);
  }

  /**
   * Whether this row is a group heading rather than a sample.
   *
   * @return {@code true} for a group heading
   */
  public boolean isGroup() {
    return record == null;
  }

  /**
   * The sample behind this row.
   *
   * @return the sample, or {@code null} for a group heading
   */
  public SampleRecord getRecord() {
    return record;
  }

  /**
   * The group's name.
   *
   * @return the name, or {@code null} for a sample row
   */
  public String getGroupName() {
    return groupName;
  }

  /**
   * How many samples this row stands for.
   *
   * @return {@code 1} for a sample row, the group size for a heading
   */
  public int getSampleCount() {
    return sampleCount;
  }

  /**
   * How many failures this row stands for.
   *
   * @return {@code 0} or {@code 1} for a sample row, the group's failure count for a heading
   */
  public int getErrorCount() {
    return errorCount;
  }

  /**
   * Where this row's bar begins.
   *
   * @return the start time in epoch milliseconds
   */
  public long getStartTime() {
    return startTime;
  }

  /**
   * Where this row's bar ends.
   *
   * @return the end time in epoch milliseconds
   */
  public long getEndTime() {
    return endTime;
  }

  /**
   * The bytes this row accounts for.
   *
   * @return the byte count
   */
  public long getBytes() {
    return bytes;
  }

  /**
   * Whether a group heading's samples are hidden.
   *
   * @return {@code true} when collapsed
   */
  public boolean isCollapsed() {
    return collapsed;
  }
}
