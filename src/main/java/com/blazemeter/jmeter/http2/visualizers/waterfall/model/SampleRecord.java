package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;

/**
 * One row of the waterfall: the fields the table, the bars and the filters need, read once out of
 * a {@link SampleResult} and then never recomputed.
 *
 * <p>Flattening matters for the row counts this viewer targets. Sorting tens of thousands of rows,
 * or re-running a filter on every keystroke, would otherwise re-parse a status line and re-derive
 * a thread group name per comparison. The originating {@code SampleResult} is kept so the details
 * panel can show headers and bodies on demand - that reference is what bounds how many samples fit
 * in memory, so {@link WaterfallStore} caps the number of records it retains.
 */
public final class SampleRecord {

  private final SampleResult result;
  private final int sequence;
  private final int depth;
  private final String label;
  private final String method;
  private final String responseCode;
  private final String protocol;
  private final String contentType;
  private final String threadName;
  private final String threadGroup;
  private final String url;
  private final boolean success;
  private final long startTime;
  private final long bytes;
  private final long sentBytes;
  private final PhaseBreakdown phases;

  /**
   * Snapshots {@code result}.
   *
   * @param result   the sample to describe
   * @param sequence arrival order, used as the stable tie-breaker when sorting
   * @param depth    nesting level, {@code 0} for a top-level sample and one more per sub-sample
   *                 generation (an embedded resource, a redirect hop, a transaction child)
   */
  public SampleRecord(SampleResult result, int sequence, int depth) {
    this.result = result;
    this.sequence = sequence;
    this.depth = depth;
    this.label = nullToEmpty(result.getSampleLabel());
    this.method = result instanceof HTTPSampleResult
        ? nullToEmpty(((HTTPSampleResult) result).getHTTPMethod())
        : "";
    this.responseCode = nullToEmpty(result.getResponseCode());
    this.protocol = ProtocolDetector.detect(result);
    this.contentType = shortContentType(result.getContentType());
    this.threadName = nullToEmpty(result.getThreadName());
    this.threadGroup = threadGroupOf(this.threadName);
    this.url = nullToEmpty(result.getUrlAsString());
    this.success = result.isSuccessful();
    this.startTime = result.getStartTime();
    this.bytes = Math.max(0, result.getBytesAsLong());
    this.sentBytes = Math.max(0, result.getSentBytes());
    this.phases = PhaseBreakdown.of(result);
  }

  /**
   * Strips the parameters and the leading type from a content type, so the column shows
   * {@code json} rather than {@code application/json; charset=utf-8}.
   *
   * @param contentType the raw Content-Type value, possibly {@code null}
   * @return the subtype, or an empty string when there is none
   */
  private static String shortContentType(String contentType) {
    if (contentType == null || contentType.isEmpty()) {
      return "";
    }
    int end = contentType.indexOf(';');
    String mediaType = (end >= 0 ? contentType.substring(0, end) : contentType).trim();
    int slash = mediaType.indexOf('/');
    return slash >= 0 ? mediaType.substring(slash + 1) : mediaType;
  }

  /**
   * Recovers the thread group name from a JMeter thread name.
   *
   * <p>JMeter names threads with the group name followed by a group index and a thread number,
   * and that suffix is the only marker available: no JTL column carries the group. Names that do
   * not match the pattern - a sample added by a non-thread-group source, or a JTL saved without
   * thread names - are grouped under their own name rather than being silently merged.
   *
   * @param threadName the sample's thread name
   * @return the thread group name
   */
  private static String threadGroupOf(String threadName) {
    int lastSpace = threadName.lastIndexOf(' ');
    if (lastSpace <= 0) {
      return threadName;
    }
    String suffix = threadName.substring(lastSpace + 1);
    int dash = suffix.indexOf('-');
    if (dash <= 0 || !isDigits(suffix, 0, dash) || !isDigits(suffix, dash + 1, suffix.length())) {
      return threadName;
    }
    return threadName.substring(0, lastSpace);
  }

  private static boolean isDigits(String text, int from, int to) {
    if (from >= to) {
      return false;
    }
    for (int i = from; i < to; i++) {
      if (!Character.isDigit(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * The sample this row was built from, for the details panel.
   *
   * @return the originating result, never {@code null}
   */
  public SampleResult getResult() {
    return result;
  }

  /**
   * Arrival order of this sample.
   *
   * @return a value that increases monotonically for the life of the viewer
   */
  public int getSequence() {
    return sequence;
  }

  /**
   * Nesting level, used to indent sub-samples under their parent.
   *
   * @return {@code 0} for a top-level sample, more for a sub-sample
   */
  public int getDepth() {
    return depth;
  }

  /**
   * The sample label.
   *
   * @return the label, never {@code null}
   */
  public String getLabel() {
    return label;
  }

  /**
   * The HTTP method, when the sample is an HTTP one.
   *
   * @return the method, or an empty string
   */
  public String getMethod() {
    return method;
  }

  /**
   * The response code as reported, which for a failed sample can be a text such as
   * "Non HTTP response code".
   *
   * @return the response code, never {@code null}
   */
  public String getResponseCode() {
    return responseCode;
  }

  /**
   * The leading digit of a numeric response code, for status colouring and filtering.
   *
   * @return 2 to 5 for a numeric code, {@code 0} otherwise
   */
  public int getStatusClass() {
    if (responseCode.length() == 3 && Character.isDigit(responseCode.charAt(0))
        && Character.isDigit(responseCode.charAt(1))
        && Character.isDigit(responseCode.charAt(2))) {
      return responseCode.charAt(0) - '0';
    }
    return 0;
  }

  /**
   * The negotiated protocol.
   *
   * @return for instance {@code HTTP/2}, or an empty string when unknown
   */
  public String getProtocol() {
    return protocol;
  }

  /**
   * The response content subtype.
   *
   * @return for instance {@code json}, or an empty string when unknown
   */
  public String getContentType() {
    return contentType;
  }

  /**
   * The name of the thread that produced the sample.
   *
   * @return the thread name, never {@code null}
   */
  public String getThreadName() {
    return threadName;
  }

  /**
   * The thread group the sample belongs to.
   *
   * @return the group name, never {@code null}
   */
  public String getThreadGroup() {
    return threadGroup;
  }

  /**
   * The sampled URL.
   *
   * @return the URL, or an empty string for a sampler that has none
   */
  public String getUrl() {
    return url;
  }

  /**
   * Whether the sample passed, assertions included.
   *
   * @return {@code true} when successful
   */
  public boolean isSuccess() {
    return success;
  }

  /**
   * When the sample started, on the clock the result was stamped with.
   *
   * @return the start time in epoch milliseconds
   */
  public long getStartTime() {
    return startTime;
  }

  /**
   * When the sample finished, idle time included.
   *
   * @return the end time in epoch milliseconds
   */
  public long getEndTime() {
    return startTime + phases.getSpanMs();
  }

  /**
   * The sample's own duration, as reported in the Time column.
   *
   * @return the elapsed time in milliseconds
   */
  public long getElapsed() {
    return phases.getElapsedMs();
  }

  /**
   * Bytes received.
   *
   * @return the response size in bytes, headers included
   */
  public long getBytes() {
    return bytes;
  }

  /**
   * Bytes sent.
   *
   * @return the request size in bytes
   */
  public long getSentBytes() {
    return sentBytes;
  }

  /**
   * The phase split behind this row's bar.
   *
   * @return the breakdown, never {@code null}
   */
  public PhaseBreakdown getPhases() {
    return phases;
  }
}
