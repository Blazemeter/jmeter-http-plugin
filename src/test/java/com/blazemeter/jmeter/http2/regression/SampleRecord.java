package com.blazemeter.jmeter.http2.regression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Normalized view of one JMeter sample (and nested sub-samples). */
public final class SampleRecord {

  private final String label;
  private final boolean successful;
  private final String responseCode;
  private final String responseMessage;
  private final String responseData;
  private final String responseHeaders;
  private final List<SampleRecord> children;

  public SampleRecord(
      String label,
      boolean successful,
      String responseCode,
      String responseMessage,
      String responseData,
      String responseHeaders,
      List<SampleRecord> children) {
    this.label = label;
    this.successful = successful;
    this.responseCode = responseCode;
    this.responseMessage = responseMessage;
    this.responseData = responseData == null ? "" : responseData;
    this.responseHeaders = responseHeaders == null ? "" : responseHeaders;
    this.children = children == null
        ? Collections.emptyList()
        : Collections.unmodifiableList(new ArrayList<>(children));
  }

  public String getLabel() {
    return label;
  }

  public boolean isSuccessful() {
    return successful;
  }

  public String getResponseCode() {
    return responseCode;
  }

  public String getResponseMessage() {
    return responseMessage;
  }

  public String getResponseData() {
    return responseData;
  }

  public String getResponseHeaders() {
    return responseHeaders;
  }

  public List<SampleRecord> getChildren() {
    return children;
  }
}
