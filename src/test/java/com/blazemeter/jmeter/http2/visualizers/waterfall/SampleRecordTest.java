package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.ProtocolDetector;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.Test;

public class SampleRecordTest {

  private static SampleRecord record(SampleResult result) {
    return new SampleRecord(result, 0, 0);
  }

  @Test
  public void readsTheFieldsTheTableShows() {
    SampleResult result = SampleResultBuilder.http()
        .label("home page")
        .method("GET")
        .url("https://example.com/index.html")
        .code("200")
        .contentType("text/html; charset=utf-8")
        .responseHeaders("HTTP/2.0 200 OK\nContent-Type: text/html\n")
        .thread("Shoppers 1-4")
        .bytes(4096)
        .timing(5000, 320)
        .build();

    SampleRecord record = record(result);

    assertThat(record.getLabel()).isEqualTo("home page");
    assertThat(record.getMethod()).isEqualTo("GET");
    assertThat(record.getResponseCode()).isEqualTo("200");
    assertThat(record.getStatusClass()).isEqualTo(2);
    assertThat(record.getProtocol()).isEqualTo("HTTP/2");
    assertThat(record.getContentType()).isEqualTo("html");
    assertThat(record.getThreadGroup()).isEqualTo("Shoppers");
    assertThat(record.getBytes()).isEqualTo(4096);
    assertThat(record.getStartTime()).isEqualTo(5000);
    assertThat(record.getEndTime()).isEqualTo(5320);
    assertThat(record.getElapsed()).isEqualTo(320);
    assertThat(record.isSuccess()).isTrue();
  }

  @Test
  public void recoversTheThreadGroupFromTheThreadNameSuffix() {
    assertThat(record(SampleResultBuilder.http().thread("Checkout Flow 2-17").build())
        .getThreadGroup()).isEqualTo("Checkout Flow");
  }

  @Test
  public void keepsTheWholeThreadNameWhenItDoesNotMatchThePattern() {
    assertThat(record(SampleResultBuilder.http().thread("setUp Thread Group").build())
        .getThreadGroup()).isEqualTo("setUp Thread Group");
    assertThat(record(SampleResultBuilder.http().thread("").build()).getThreadGroup()).isEmpty();
  }

  @Test
  public void nonNumericResponseCodeHasNoStatusClass() {
    SampleRecord record = record(SampleResultBuilder.http()
        .code("Non HTTP response code: java.net.ConnectException")
        .success(false)
        .build());

    assertThat(record.getStatusClass()).isZero();
    assertThat(record.isSuccess()).isFalse();
  }

  @Test
  public void missingFieldsBecomeEmptyStringsRatherThanNull() {
    SampleRecord record = record(new SampleResult());

    assertThat(record.getLabel()).isEmpty();
    assertThat(record.getMethod()).isEmpty();
    assertThat(record.getUrl()).isEmpty();
    assertThat(record.getProtocol()).isEqualTo(ProtocolDetector.UNKNOWN);
    assertThat(record.getContentType()).isEmpty();
  }

  @Test
  public void depthIsKeptForIndentingSubSamples() {
    assertThat(new SampleRecord(SampleResultBuilder.http().build(), 7, 2).getDepth()).isEqualTo(2);
    assertThat(new SampleRecord(SampleResultBuilder.http().build(), 7, 2).getSequence())
        .isEqualTo(7);
  }
}
