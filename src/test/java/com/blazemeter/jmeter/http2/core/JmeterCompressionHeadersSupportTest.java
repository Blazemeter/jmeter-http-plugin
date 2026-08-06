package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.HashMap;
import java.util.Map;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.Test;
import org.mockito.Mockito;

public class JmeterCompressionHeadersSupportTest {

  private static Request mockRequestWithAttributes() {
    Map<String, Object> attributes = new HashMap<>();
    Request request = Mockito.mock(Request.class);
    Mockito.when(request.getAttributes()).thenReturn(attributes);
    Mockito.doAnswer(invocation -> {
      attributes.put(invocation.getArgument(0), invocation.getArgument(1));
      return invocation.getMock();
    }).when(request).attribute(anyString(), any());
    return request;
  }

  @Test
  public void shouldRestoreWireCompressionHeadersRemovedByDecoder() {
    Request request = mockRequestWithAttributes();

    HttpFields wire = HttpFields.build()
        .add(HttpHeader.CONTENT_ENCODING, "gzip")
        .add(HttpHeader.CONTENT_LENGTH, "643");
    JmeterCompressionHeadersSupport.captureIfCompressed(request, wire);

    ContentResponse contentResponse = Mockito.mock(ContentResponse.class);
    HttpFields decoded = HttpFields.build()
        .add(HttpHeader.CONTENT_TYPE, "application/json")
        .add(HttpHeader.CONTENT_LENGTH, "238");
    Mockito.when(contentResponse.getRequest()).thenReturn(request);
    Mockito.when(contentResponse.getHeaders()).thenReturn(decoded);

    HttpFields merged = JmeterCompressionHeadersSupport.headersForSampleResult(contentResponse);

    assertThat(merged.get(HttpHeader.CONTENT_ENCODING)).isEqualTo("gzip");
    assertThat(merged.get(HttpHeader.CONTENT_LENGTH)).isEqualTo("643");
    assertThat(merged.get(HttpHeader.CONTENT_TYPE)).isEqualTo("application/json");
  }

  @Test
  public void shouldNotOverrideExistingHeaders() {
    Request request = mockRequestWithAttributes();

    HttpFields wire = HttpFields.build()
        .add(HttpHeader.CONTENT_ENCODING, "gzip")
        .add(HttpHeader.CONTENT_LENGTH, "643");
    JmeterCompressionHeadersSupport.captureIfCompressed(request, wire);

    ContentResponse contentResponse = Mockito.mock(ContentResponse.class);
    HttpFields decoded = HttpFields.build()
        .add(HttpHeader.CONTENT_ENCODING, "gzip")
        .add(HttpHeader.CONTENT_LENGTH, "238");
    Mockito.when(contentResponse.getRequest()).thenReturn(request);
    Mockito.when(contentResponse.getHeaders()).thenReturn(decoded);

    HttpFields merged = JmeterCompressionHeadersSupport.headersForSampleResult(contentResponse);

    assertThat(merged.get(HttpHeader.CONTENT_LENGTH)).isEqualTo("238");
  }

  @Test
  public void shouldCaptureContentMd5WhenPresent() {
    Request request = mockRequestWithAttributes();

    HttpFields wire = HttpFields.build()
        .add(HttpHeader.CONTENT_ENCODING, "br")
        .add(HTTPConstants.HEADER_CONTENT_LENGTH, "100")
        .add("Content-MD5", "abc123");
    JmeterCompressionHeadersSupport.captureIfCompressed(request, wire);

    ContentResponse contentResponse = Mockito.mock(ContentResponse.class);
    HttpFields decoded = HttpFields.build().add(HttpHeader.CONTENT_TYPE, "text/plain");
    Mockito.when(contentResponse.getRequest()).thenReturn(request);
    Mockito.when(contentResponse.getHeaders()).thenReturn(decoded);

    HttpFields merged = JmeterCompressionHeadersSupport.headersForSampleResult(contentResponse);

    assertThat(merged.get(HttpHeader.CONTENT_ENCODING)).isEqualTo("br");
    assertThat(merged.get(HTTPConstants.HEADER_CONTENT_LENGTH)).isEqualTo("100");
    assertThat(merged.get("Content-MD5")).isEqualTo("abc123");
  }
}
