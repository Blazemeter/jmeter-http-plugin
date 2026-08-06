package com.blazemeter.jmeter.http2.sampler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.assertj.core.api.JUnitSoftAssertions;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

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
  public void shouldKeepCookieDataInSamplerDataWhenConnectFails() throws Exception {
    when(client.sample(any(), any(), anyBoolean(), anyInt()))
        .thenAnswer((Answer<HTTPSampleResult>) invocation -> {
          HTTPSampleResult result = invocation.getArgument(1);
          result.setCookies("myCookie=value1; mySecureCookie=value3");
          result.sampleStart();
          result.sampleEnd();
          throw new ConnectException("Connection refused");
        });
    sampler.setMethod(HTTPConstants.GET);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setDomain("localhost");
    sampler.setPort(8082);
    sampler.setPath("/");

    SampleResult result = sampler.sample();

    softly.assertThat(result.isSuccessful()).isFalse();
    softly.assertThat(result.getSamplerData())
        .as("connect failures must keep cookies already applied to the prepared result")
        .contains("Cookie Data:")
        .contains("myCookie=value1; mySecureCookie=value3");
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
    softly.assertThat(HTTP2Sampler.isExpectedSampleFailure(
            new java.util.concurrent.ExecutionException(
                new ConnectException("Connection refused"))))
        .as("connect refused is an expected sample outcome (e.g. dead HTTPS mirror port)")
        .isTrue();
    softly.assertThat(HTTP2Sampler.isExpectedSampleFailure(new IOException("connection reset")))
        .as("unexpected I/O must keep ERROR logging")
        .isFalse();
  }
}
