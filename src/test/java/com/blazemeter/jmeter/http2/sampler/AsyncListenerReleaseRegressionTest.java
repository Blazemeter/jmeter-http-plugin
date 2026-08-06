package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.Request;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Async completion used to keep {@code asyncListener} (unlimited Jetty buffer) until the next
 * iteration, pinning large response bodies across the whole loop.
 */
@RunWith(MockitoJUnitRunner.class)
public class AsyncListenerReleaseRegressionTest extends HTTP2TestBase {

  @Mock
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    sampler = new HTTP2Sampler(() -> client);
    sampler.setSyncRequest(false);
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("example.com");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPath("/");

    when(client.getMaxBufferSize()).thenReturn(1024 * 1024);
    when(client.getRequestTimeout()).thenReturn(0);
    when(client.dispatchAsync(any(), any(), any())).thenReturn(mock(Request.class));
  }

  @Test
  public void asyncCompletionMustDropFutureListenerImmediately() throws Exception {
    HTTPSampleResult pending = new HTTPSampleResult();
    pending.setURL(new URL("https://example.com/"));
    pending.setHTTPMethod(HTTPConstants.GET);
    pending.setSuccessful(true);
    pending.setResponseCode("200");
    when(client.sampleFromListener(any(), any(), anyBoolean(), anyInt(), any()))
        .thenReturn(pending);

    URL url = new URL("https://example.com/");
    assertThat(sampler.sample(url, HTTPConstants.GET, false, 1)).isNull();
    assertThat(sampler.getFutureResponseListener())
        .as("dispatch publishes the listener for the controller to wait on")
        .isNotNull();

    HTTPSampleResult result = sampler.sample(url, HTTPConstants.GET, false, 1);
    assertThat(result).isSameAs(pending);
    assertThat(sampler.getFutureResponseListener())
        .as("listener must be released right after completion, not held until iterationStart")
        .isNull();
    // Field retention: sample() returns the result to JMeterThread; the sampler must not keep its
    // own reference until the next iterationStart (that pinned every completed body for the rest
    // of an HTTP2Controller iteration).
    assertThat(samplerResultField(sampler))
        .as("sampler.result must be cleared in the completion finally")
        .isNull();
  }

  @Test
  public void clearPendingSampleStateMustDropListenerAndResult() throws Exception {
    URL url = new URL("https://example.com/");
    assertThat(sampler.sample(url, HTTPConstants.GET, false, 1)).isNull();
    assertThat(sampler.getFutureResponseListener()).isNotNull();

    sampler.clearPendingSampleState();

    assertThat(sampler.getFutureResponseListener()).isNull();
    assertThat(samplerResultField(sampler)).isNull();
  }

  private static HTTPSampleResult samplerResultField(HTTP2Sampler sampler) throws Exception {
    java.lang.reflect.Field field = HTTP2Sampler.class.getDeclaredField("result");
    field.setAccessible(true);
    return (HTTPSampleResult) field.get(sampler);
  }
}
