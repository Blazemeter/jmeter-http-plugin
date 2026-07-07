package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.HashMap;
import java.util.Map;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.junit.Test;
import org.mockito.Mockito;

public class JmeterRequestHeadersSupportTest {

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
}