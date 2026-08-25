package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.ProtocolDetector;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.Test;

public class ProtocolDetectorTest {

  private static String detect(String responseHeaders) {
    SampleResult result = new SampleResult();
    result.setResponseHeaders(responseHeaders);
    return ProtocolDetector.detect(result);
  }

  @Test
  public void readsTheVersionFromTheStatusLine() {
    assertThat(detect("HTTP/1.1 200 OK\nContent-Length: 3\n")).isEqualTo("HTTP/1.1");
    assertThat(detect("HTTP/1.0 204 No Content\n")).isEqualTo("HTTP/1.0");
  }

  @Test
  public void collapsesTheAlwaysZeroMinorVersionOfHttp2AndHttp3() {
    assertThat(detect("HTTP/2.0 200 OK\n")).isEqualTo("HTTP/2");
    assertThat(detect("HTTP/3.0 200 OK\n")).isEqualTo("HTTP/3");
  }

  @Test
  public void keepsAVersionThatIsAlreadyInDisplayForm() {
    assertThat(detect("HTTP/2 200 OK\n")).isEqualTo("HTTP/2");
  }

  @Test
  public void handlesAStatusLineWithCarriageReturns() {
    assertThat(detect("HTTP/1.1 200 OK\r\nHost: x\r\n")).isEqualTo("HTTP/1.1");
  }

  @Test
  public void reportsUnknownWhenThereIsNoStatusLine() {
    assertThat(detect("")).isEqualTo(ProtocolDetector.UNKNOWN);
    assertThat(detect("Content-Type: application/json\n")).isEqualTo(ProtocolDetector.UNKNOWN);
    assertThat(ProtocolDetector.detect(new SampleResult())).isEqualTo(ProtocolDetector.UNKNOWN);
    assertThat(ProtocolDetector.detect(null)).isEqualTo(ProtocolDetector.UNKNOWN);
  }

  @Test
  public void handlesAStatusLineWithNothingAfterTheVersion() {
    assertThat(detect("HTTP/2.0")).isEqualTo("HTTP/2");
  }
}
