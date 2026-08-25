package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.details.HttpHeaders;
import java.util.List;
import org.junit.Test;

public class HttpHeadersTest {

  private static final String RESPONSE = "HTTP/2.0 200 OK\n"
      + "Content-Type: application/json; charset=utf-8\n"
      + "Set-Cookie: sid=abc; Path=/; HttpOnly\n"
      + "Set-Cookie: theme=dark\n"
      + "Location: https://example.com/next?a=1\n";

  @Test
  public void skipsTheStatusLineOfAResponseBlock() {
    List<String[]> headers = HttpHeaders.parse(RESPONSE, true);

    assertThat(headers).extracting(header -> header[0])
        .containsExactly("Content-Type", "Set-Cookie", "Set-Cookie", "Location");
  }

  @Test
  public void keepsEveryLineOfARequestBlock() {
    List<String[]> headers = HttpHeaders.parse("Host: example.com\nAccept: */*\n", false);

    assertThat(headers).hasSize(2);
    assertThat(headers.get(0)).containsExactly("Host", "example.com");
  }

  @Test
  public void splitsOnTheFirstColonOnlySoUrlsAndDatesSurvive() {
    List<String[]> headers = HttpHeaders.parse(RESPONSE, true);

    assertThat(headers.get(3)[1]).isEqualTo("https://example.com/next?a=1");
  }

  @Test
  public void readsTheStatusLine() {
    assertThat(HttpHeaders.statusLine(RESPONSE)).isEqualTo("HTTP/2.0 200 OK");
    assertThat(HttpHeaders.statusLine("HTTP/1.1 404 Not Found")).isEqualTo("HTTP/1.1 404 Not Found");
    assertThat(HttpHeaders.statusLine(null)).isEmpty();
    assertThat(HttpHeaders.statusLine("")).isEmpty();
  }

  @Test
  public void findsRepeatedHeadersCaseInsensitively() {
    assertThat(HttpHeaders.valuesOf(RESPONSE, true, "set-cookie"))
        .containsExactly("sid=abc; Path=/; HttpOnly", "theme=dark");
    assertThat(HttpHeaders.valuesOf(RESPONSE, true, "CONTENT-TYPE"))
        .containsExactly("application/json; charset=utf-8");
  }

  @Test
  public void missingHeadersYieldAnEmptyListRatherThanNull() {
    assertThat(HttpHeaders.valuesOf(RESPONSE, true, "X-Absent")).isEmpty();
    assertThat(HttpHeaders.parse(null, false)).isEmpty();
    assertThat(HttpHeaders.parse("", true)).isEmpty();
  }

  @Test
  public void aLineWithNoColonIsKeptWithAnEmptyValue() {
    List<String[]> headers = HttpHeaders.parse("garbage\n", false);

    assertThat(headers.get(0)).containsExactly("garbage", "");
  }

  @Test
  public void blankLinesAreSkipped() {
    assertThat(HttpHeaders.parse("A: 1\n\n\nB: 2\n", false)).hasSize(2);
  }

  @Test
  public void carriageReturnsAreTrimmedAway() {
    List<String[]> headers = HttpHeaders.parse("Host: example.com\r\n", false);

    assertThat(headers.get(0)).containsExactly("Host", "example.com");
  }
}
