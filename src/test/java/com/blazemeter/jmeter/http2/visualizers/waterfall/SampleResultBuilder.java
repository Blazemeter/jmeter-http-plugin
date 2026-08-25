package com.blazemeter.jmeter.http2.visualizers.waterfall;

import java.net.MalformedURLException;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;

/**
 * Builds samples with exact timings, so waterfall tests can assert on milliseconds rather than on
 * whatever the wall clock did while the test ran.
 *
 * <p>{@code SampleResult.setStartTime} is protected, and {@code setStampAndTime} means different
 * things depending on the {@code sampleresult.timestamp.start} property, so neither can pin a start
 * time from a test. Subclassing to reach the protected setter is the only deterministic way.
 */
final class SampleResultBuilder {

  private final StampedHttpResult result = new StampedHttpResult();
  private long start = 1_000_000L;
  private long elapsed;
  private long idle;

  static SampleResultBuilder http() {
    return new SampleResultBuilder();
  }

  SampleResultBuilder label(String label) {
    result.setSampleLabel(label);
    return this;
  }

  SampleResultBuilder method(String method) {
    result.setHTTPMethod(method);
    return this;
  }

  SampleResultBuilder url(String url) {
    try {
      result.setURL(new URL(url));
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }
    return this;
  }

  SampleResultBuilder code(String code) {
    result.setResponseCode(code);
    result.setSuccessful(code.startsWith("2") || code.startsWith("3"));
    return this;
  }

  SampleResultBuilder success(boolean success) {
    result.setSuccessful(success);
    return this;
  }

  SampleResultBuilder thread(String threadName) {
    result.setThreadName(threadName);
    return this;
  }

  SampleResultBuilder responseHeaders(String headers) {
    result.setResponseHeaders(headers);
    return this;
  }

  SampleResultBuilder requestHeaders(String headers) {
    result.setRequestHeaders(headers);
    return this;
  }

  SampleResultBuilder cookies(String cookies) {
    result.setCookies(cookies);
    return this;
  }

  SampleResultBuilder contentType(String contentType) {
    result.setContentType(contentType);
    return this;
  }

  SampleResultBuilder body(String body) {
    result.setResponseData(body, "UTF-8");
    return this;
  }

  SampleResultBuilder bytes(long bytes) {
    result.setBytes(bytes);
    return this;
  }

  SampleResultBuilder timing(long startTime, long elapsedMillis) {
    this.start = startTime;
    this.elapsed = elapsedMillis;
    return this;
  }

  SampleResultBuilder idle(long idleMillis) {
    this.idle = idleMillis;
    return this;
  }

  SampleResultBuilder connect(long connectMillis) {
    result.setConnectTime(connectMillis);
    return this;
  }

  SampleResultBuilder latency(long latencyMillis) {
    result.setLatency(latencyMillis);
    return this;
  }

  SampleResultBuilder child(SampleResult child) {
    result.addRawSubResult(child);
    return this;
  }

  SampleResult build() {
    result.setIdleTime(idle);
    result.stamp(start, elapsed);
    return result;
  }

  /** Exposes the protected start-time setter. */
  private static final class StampedHttpResult extends HTTPSampleResult {

    /**
     * Stamps the sample.
     *
     * <p>Rejects a start time of zero rather than accepting it quietly.
     * {@code SampleResult.setEndTime} reads {@code startTime == 0} as "setStartTime was never
     * called", logs an error and leaves the elapsed time at zero - which in a waterfall test shows
     * up as a bar that is entirely grey idle time, several steps away from the cause.
     *
     * @param startTime     the start time, which must not be zero
     * @param elapsedMillis the elapsed time
     */
    void stamp(long startTime, long elapsedMillis) {
      if (startTime <= 0) {
        throw new IllegalArgumentException(
            "SampleResult treats a start time of 0 as never started and leaves elapsed at 0;"
                + " use a non-zero base and offsets from it");
      }
      setStartTime(startTime);
      setEndTime(startTime + elapsedMillis + getIdleTime());
    }
  }
}
