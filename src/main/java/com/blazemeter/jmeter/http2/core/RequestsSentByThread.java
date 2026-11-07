package com.blazemeter.jmeter.http2.core;

/**
 * Counts the requests each thread hands to the plugin's Jetty clients, so a caller can tell whether
 * a stretch of its own work sent any.
 *
 * <p>Jetty notifies a request as queued on the thread that sends it, for a blocking send and for
 * one with a listener alike, so the count of a thread is the requests that thread sent and no
 * other's. A JMeter thread runs one sampler at a time: what it sends while collecting an async
 * request - a GOAWAY retry, an HTTP/1.1 fallback - belongs to that request. What Jetty sends on its
 * own threads (a redirect it follows, an authentication challenge it answers) and what the protocol
 * race sends from its executors are not counted here, and are not sends of the collecting thread.
 *
 * <p>This is the signal, rather than a flag set by each path that sends a request again, so that a
 * path added later is counted without having to report itself.
 */
public final class RequestsSentByThread {

  /** A plain counter: only its own thread ever reads or writes it. */
  private static final ThreadLocal<long[]> SENT = ThreadLocal.withInitial(() -> new long[1]);

  private RequestsSentByThread() {
  }

  /**
   * How many requests the current thread has sent so far. Only the difference between two reads
   * means anything.
   */
  public static long current() {
    return SENT.get()[0];
  }

  /**
   * Counts one request sent by the current thread. Called by the plugin's clients when Jetty queues
   * a request, and by a stand-in for them in tests.
   */
  public static void recordSent() {
    SENT.get()[0]++;
  }
}
