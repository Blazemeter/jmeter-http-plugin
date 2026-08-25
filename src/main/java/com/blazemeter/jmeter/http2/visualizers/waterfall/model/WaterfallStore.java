package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Holds the samples the viewer knows about, and bridges the two threads that touch them.
 *
 * <p>Sampler threads call {@link #offer(SampleResult)}, which only appends to a lock-free queue:
 * a listener must never make a test thread wait, and must never touch Swing. The event dispatch
 * thread later calls {@link #drainPending(int)}, which is where results are flattened into
 * {@link SampleRecord}s and where sub-samples are expanded. Draining is capped per call so that
 * loading a large {@code .jtl} - which can hand over hundreds of thousands of rows in one go -
 * cannot freeze the UI for a whole batch.
 *
 * <p>Records keep a reference to their {@code SampleResult} so the details panel can show bodies,
 * which makes retention the memory ceiling of the whole viewer. The oldest records are therefore
 * evicted once {@link #getMaxSamples()} is exceeded, and {@link #getDroppedCount()} reports how
 * many were lost so the UI can say so rather than quietly showing a partial timeline.
 */
public final class WaterfallStore {

  /** JMeter property bounding how many samples are retained; {@code -1} means no limit. */
  public static final String MAX_SAMPLES_PROPERTY = "blazemeter.waterfall.maxSamples";

  private static final int DEFAULT_MAX_SAMPLES = 50000;

  /**
   * Sub-sample nesting is expanded recursively, and a malformed result tree (a sub-sample that
   * reaches back to an ancestor) would otherwise recurse until the stack runs out. No real result
   * nests anywhere near this deep.
   */
  private static final int MAX_DEPTH = 16;

  /** Evicting one record at a time is quadratic; evicting a slice keeps it amortised. */
  private static final int EVICTION_BLOCK_DIVISOR = 10;

  private final Queue<SampleResult> pending = new ConcurrentLinkedQueue<>();
  private final List<SampleRecord> records = new ArrayList<>();
  private final int maxSamples;
  private boolean includeSubSamples = true;
  private int nextSequence;
  private int droppedCount;

  /** Creates a store bounded by the {@value #MAX_SAMPLES_PROPERTY} JMeter property. */
  public WaterfallStore() {
    this(JMeterUtils.getPropDefault(MAX_SAMPLES_PROPERTY, DEFAULT_MAX_SAMPLES));
  }

  /**
   * Creates a store with an explicit retention bound.
   *
   * @param maxSamples how many records to retain, or a non-positive value for no bound
   */
  public WaterfallStore(int maxSamples) {
    this.maxSamples = maxSamples;
  }

  /**
   * Queues a sample for display. Safe to call from any thread, and cheap enough to call from a
   * sampler thread on the hot path.
   *
   * @param result the sample that just finished
   */
  public void offer(SampleResult result) {
    if (result != null) {
      pending.add(result);
    }
  }

  /**
   * Moves queued samples into the record list. Must run on the event dispatch thread.
   *
   * @param maxRecords the most records to create in this call, bounding how long the caller
   *                   occupies the event dispatch thread
   * @return how many records were added
   */
  public int drainPending(int maxRecords) {
    int added = 0;
    SampleResult result;
    while (added < maxRecords && (result = pending.poll()) != null) {
      added += append(result, 0);
    }
    evictOverflow();
    return added;
  }

  /**
   * Whether more samples are waiting to be drained.
   *
   * @return {@code true} when {@link #drainPending(int)} still has work
   */
  public boolean hasPending() {
    return !pending.isEmpty();
  }

  private int append(SampleResult result, int depth) {
    records.add(new SampleRecord(result, nextSequence++, depth));
    int added = 1;
    if (!includeSubSamples || depth >= MAX_DEPTH) {
      return added;
    }
    for (SampleResult child : result.getSubResults()) {
      if (child != null) {
        added += append(child, depth + 1);
      }
    }
    return added;
  }

  private void evictOverflow() {
    if (maxSamples <= 0 || records.size() <= maxSamples) {
      return;
    }
    int excess = records.size() - maxSamples;
    int block = Math.max(excess, maxSamples / EVICTION_BLOCK_DIVISOR);
    int toRemove = Math.min(records.size(), block);
    records.subList(0, toRemove).clear();
    droppedCount += toRemove;
  }

  /**
   * The retained records, oldest first. Must be read on the event dispatch thread.
   *
   * @return a read-only view that reflects later drains
   */
  public List<SampleRecord> getRecords() {
    return Collections.unmodifiableList(records);
  }

  /**
   * How many records were evicted to stay within the retention bound.
   *
   * @return the number of samples dropped since the last {@link #clear()}
   */
  public int getDroppedCount() {
    return droppedCount;
  }

  /**
   * The retention bound in force.
   *
   * @return the maximum number of records, or a non-positive value when unbounded
   */
  public int getMaxSamples() {
    return maxSamples;
  }

  /**
   * Whether sub-samples are expanded into rows of their own.
   *
   * @return {@code true} when embedded resources, redirect hops and transaction children get rows
   */
  public boolean isIncludeSubSamples() {
    return includeSubSamples;
  }

  /**
   * Sets whether sub-samples get rows of their own. Only affects samples drained afterwards, so
   * callers changing it should reload or clear.
   *
   * @param includeSubSamples {@code true} to expand sub-samples
   */
  public void setIncludeSubSamples(boolean includeSubSamples) {
    this.includeSubSamples = includeSubSamples;
  }

  /** Discards every queued and retained sample. */
  public void clear() {
    pending.clear();
    records.clear();
    nextSequence = 0;
    droppedCount = 0;
  }
}
