package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.util.HashMap;
import java.util.Map;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

public class JmeterRequestHeadersSupportTest extends HTTP2TestBase {

  private static final String UA_DISABLED_PROP =
      JmeterRequestHeadersSupport.DEFAULT_USER_AGENT_PROPERTY;

  @After
  public void clearUserAgentProperty() {
    JMeterUtils.getJMeterProperties().remove(UA_DISABLED_PROP);
  }

  private static Request mockRequest(HttpFields.Mutable headers) {
    Map<String, Object> attributes = new HashMap<>();
    Request request = Mockito.mock(Request.class);
    Mockito.when(request.getHeaders()).thenReturn(headers);
    Mockito.when(request.getVersion()).thenReturn(HttpVersion.HTTP_1_1);
    Mockito.when(request.getAttributes()).thenReturn(attributes);
    Mockito.doAnswer(invocation -> {
      attributes.put(invocation.getArgument(0), invocation.getArgument(1));
      return invocation.getMock();
    }).when(request).attribute(anyString(), any());
    return request;
  }

  @Test
  public void prepareFromSamplerAddsConnectionKeepAliveWhenEnabled() {
    HttpFields.Mutable headers = HttpFields.build();
    Request request = mockRequest(headers);

    JmeterRequestHeadersSupport.prepareFromSampler(request, true);

    assertThat(headers.get(HttpHeader.CONNECTION)).isEqualTo(HTTPConstants.KEEP_ALIVE);
  }

  @Test
  public void headersForSampleResultRestoresKeepAliveAfterHeadersCleared() {
    HttpFields.Mutable headers = HttpFields.build();
    Request request = mockRequest(headers);
    JmeterRequestHeadersSupport.prepareFromSampler(request, true);
    headers.remove(HttpHeader.CONNECTION);

    HttpFields sampleHeaders = JmeterRequestHeadersSupport.headersForSampleResult(request);

    assertThat(sampleHeaders.get(HttpHeader.CONNECTION)).isEqualTo(HTTPConstants.KEEP_ALIVE);
  }

  @Test
  public void headersForSampleResultRestoresConnectionCloseWhenDisabled() {
    HttpFields.Mutable headers = HttpFields.build();
    Request request = mockRequest(headers);
    JmeterRequestHeadersSupport.prepareFromSampler(request, false);
    headers.remove(HttpHeader.CONNECTION);

    HttpFields sampleHeaders = JmeterRequestHeadersSupport.headersForSampleResult(request);

    assertThat(sampleHeaders.get(HttpHeader.CONNECTION))
        .isEqualTo(HTTPConstants.CONNECTION_CLOSE);
  }

  @Test
  public void prepareFromSamplerDoesNotOverrideExplicitConnectionHeader() {
    HttpFields.Mutable headers = HttpFields.build()
        .add(HttpHeader.CONNECTION, "Upgrade");
    Request request = mockRequest(headers);

    JmeterRequestHeadersSupport.prepareFromSampler(request, true);

    assertThat(headers.get(HttpHeader.CONNECTION)).isEqualTo("Upgrade");
  }

  @Test
  public void prepareFromSamplerAddsDefaultUserAgentWhenMissing() {
    HttpFields.Mutable headers = HttpFields.build();
    Request request = mockRequest(headers);

    JmeterRequestHeadersSupport.prepareFromSampler(request, true);

    assertThat(headers.get(HttpHeader.USER_AGENT))
        .isEqualTo(JmeterRequestHeadersSupport.defaultUserAgent());
    assertThat(headers.get(HttpHeader.USER_AGENT)).startsWith("BlazeMeter HTTP");
  }

  @Test
  public void prepareFromSamplerDoesNotOverrideConfiguredUserAgent() {
    HttpFields.Mutable headers = HttpFields.build()
        .add(HttpHeader.USER_AGENT, "CustomAgent/1.0");
    Request request = mockRequest(headers);

    JmeterRequestHeadersSupport.prepareFromSampler(request, true);

    assertThat(headers.get(HttpHeader.USER_AGENT)).isEqualTo("CustomAgent/1.0");
  }

  @Test
  public void prepareFromSamplerKeepsEmptyUserAgentFromHeaderManager() {
    HttpFields.Mutable headers = HttpFields.build()
        .add(HttpHeader.USER_AGENT, "");
    Request request = mockRequest(headers);

    JmeterRequestHeadersSupport.prepareFromSampler(request, true);

    assertThat(headers.contains(HttpHeader.USER_AGENT)).isTrue();
    assertThat(headers.get(HttpHeader.USER_AGENT)).isEmpty();
  }

  @Test
  public void prepareFromSamplerOmitsDefaultUserAgentWhenPropertyDisabled() {
    JMeterUtils.setProperty(UA_DISABLED_PROP, "true");
    HttpFields.Mutable headers = HttpFields.build();
    Request request = mockRequest(headers);

    JmeterRequestHeadersSupport.prepareFromSampler(request, true);

    assertThat(headers.contains(HttpHeader.USER_AGENT)).isFalse();
  }
}
