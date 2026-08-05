package com.blazemeter.jmeter.http2.sampler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.apache.jmeter.samplers.SampleResult;
import org.assertj.core.api.JUnitSoftAssertions;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HTTP2SamplerTest extends HTTP2TestBase {

  @Rule
  public final JUnitSoftAssertions softly = new JUnitSoftAssertions();
  @Mock
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;

  @Before
  public void setup() {
    sampler = new HTTP2Sampler(() -> client);
  }

  @Test
  public void shouldReturnErrorMessageWhenThreadIsInterrupted() throws Exception {
    when(client.sample(any(), any(), anyBoolean(), anyInt()))
        .thenThrow(new InterruptedException());
    validateErrorResponse(sampler.sample(), InterruptedException.class.getName());
  }

  private void validateErrorResponse(SampleResult result, String code) {
    softly.assertThat(result.isSuccessful()).isEqualTo(false);
    softly.assertThat(result.getResponseCode()).isEqualTo("Non HTTP response code: " + code);
  }

  @Test
  public void shouldReturnErrorMessageWhenClientThrowException() throws Exception {
    when(client.sample(any(), any(), anyBoolean(), anyInt()))
        .thenThrow(new TimeoutException());
    validateErrorResponse(sampler.sample(), TimeoutException.class.getName());
  }

  @Test
  public void timeoutFailuresAreExpectedSampleOutcomesNotPluginErrors() {
    softly.assertThat(HTTP2Sampler.isExpectedSampleFailure(
            new TimeoutException("Total timeout 500 ms elapsed")))
        .as("Jetty request timeouts must not be logged at ERROR")
        .isTrue();
    softly.assertThat(HTTP2Sampler.isExpectedSampleFailure(
            new java.net.SocketTimeoutException("Read timed out")))
        .isTrue();
    softly.assertThat(HTTP2Sampler.isExpectedSampleFailure(
            new java.util.concurrent.ExecutionException(
                new TimeoutException("Total timeout 500 ms elapsed"))))
        .as("timeouts wrapped by Jetty/async completion must still be treated as expected")
        .isTrue();
    softly.assertThat(HTTP2Sampler.isExpectedSampleFailure(new IOException("connection reset")))
        .as("unexpected I/O must keep ERROR logging")
        .isFalse();
  }
}
