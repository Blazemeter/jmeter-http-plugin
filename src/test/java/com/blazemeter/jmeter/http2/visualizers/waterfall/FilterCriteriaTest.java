package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.FilterCriteria;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import org.junit.Test;

public class FilterCriteriaTest {

  private static SampleRecord record(String label, String url, String code, String protocol,
      long elapsed, boolean success) {
    return new SampleRecord(SampleResultBuilder.http()
        .label(label)
        .url(url)
        .code(code)
        .success(success)
        .responseHeaders(protocol + " " + code + " OK\n")
        .timing(1000, elapsed)
        .build(), 0, 0);
  }

  private static SampleRecord homePage() {
    return record("Home Page", "https://example.com/home", "200", "HTTP/2", 120, true);
  }

  private static SampleRecord failedApi() {
    return record("API call", "https://example.com/api/v1/orders", "500", "HTTP/1.1", 3000, false);
  }

  @Test
  public void anEmptyFilterPassesEverything() {
    FilterCriteria filter = new FilterCriteria();

    assertThat(filter.isActive()).isFalse();
    assertThat(filter.matches(homePage())).isTrue();
    assertThat(filter.matches(failedApi())).isTrue();
  }

  @Test
  public void textMatchesTheLabelOrTheUrlCaseInsensitively() {
    FilterCriteria filter = new FilterCriteria();

    filter.setText("HOME");
    assertThat(filter.matches(homePage())).isTrue();
    assertThat(filter.matches(failedApi())).isFalse();

    filter.setText("/api/");
    assertThat(filter.matches(homePage())).isFalse();
    assertThat(filter.matches(failedApi())).isTrue();
  }

  @Test
  public void regexModeCompilesTheTerm() {
    FilterCriteria filter = new FilterCriteria();
    filter.setRegex(true);
    filter.setText("v[0-9]+/orders$");

    assertThat(filter.isRegexValid()).isTrue();
    assertThat(filter.matches(failedApi())).isTrue();
    assertThat(filter.matches(homePage())).isFalse();
  }

  @Test
  public void aHalfTypedRegexHidesNothingRatherThanEverything() {
    FilterCriteria filter = new FilterCriteria();
    filter.setRegex(true);
    filter.setText("orders(");

    assertThat(filter.isRegexValid()).isFalse();
    assertThat(filter.matches(homePage())).isTrue();
    assertThat(filter.matches(failedApi())).isTrue();
  }

  @Test
  public void errorsOnlyHidesSuccessfulSamples() {
    FilterCriteria filter = new FilterCriteria();
    filter.setErrorsOnly(true);

    assertThat(filter.isActive()).isTrue();
    assertThat(filter.matches(homePage())).isFalse();
    assertThat(filter.matches(failedApi())).isTrue();
  }

  @Test
  public void minTimeIsolatesTheSlowSamples() {
    FilterCriteria filter = new FilterCriteria();
    filter.setMinTimeMs(1000);

    assertThat(filter.matches(homePage())).isFalse();
    assertThat(filter.matches(failedApi())).isTrue();
  }

  @Test
  public void aNegativeMinTimeIsClampedToZero() {
    FilterCriteria filter = new FilterCriteria();
    filter.setMinTimeMs(-500);

    assertThat(filter.getMinTimeMs()).isZero();
    assertThat(filter.isActive()).isFalse();
  }

  @Test
  public void noStatusClassTickedMeansEveryStatusPasses() {
    FilterCriteria filter = new FilterCriteria();

    assertThat(filter.matches(homePage())).isTrue();
    assertThat(filter.matches(failedApi())).isTrue();

    filter.setStatusClassAccepted(5, true);

    assertThat(filter.matches(homePage())).isFalse();
    assertThat(filter.matches(failedApi())).isTrue();

    filter.setStatusClassAccepted(5, false);

    assertThat(filter.matches(homePage())).isTrue();
  }

  @Test
  public void protocolsNarrowToTheTickedOnes() {
    FilterCriteria filter = new FilterCriteria();
    filter.setProtocolAccepted("HTTP/2", true);

    assertThat(filter.matches(homePage())).isTrue();
    assertThat(filter.matches(failedApi())).isFalse();
  }

  @Test
  public void conditionsCombineWithAnd() {
    FilterCriteria filter = new FilterCriteria();
    filter.setText("api");
    filter.setMinTimeMs(5000);

    assertThat(filter.matches(failedApi())).isFalse();
  }

  @Test
  public void resetReturnsToPassThrough() {
    FilterCriteria filter = new FilterCriteria();
    filter.setText("api");
    filter.setErrorsOnly(true);
    filter.setMinTimeMs(100);
    filter.setStatusClassAccepted(4, true);
    filter.setProtocolAccepted("HTTP/3", true);

    filter.reset();

    assertThat(filter.isActive()).isFalse();
    assertThat(filter.matches(homePage())).isTrue();
  }
}
