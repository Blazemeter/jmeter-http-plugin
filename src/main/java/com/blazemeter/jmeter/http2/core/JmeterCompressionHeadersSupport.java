package com.blazemeter.jmeter.http2.core;

import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;

/**
 * Preserves wire {@code Content-Encoding}, {@code Content-Length} and {@code Content-MD5}
 * for compressed responses, matching Apache JMeter {@code HTTPHC4Impl} behaviour after
 * HttpClient {@code ResponseContentEncoding} decodes the body (Bug 59401).
 */
final class JmeterCompressionHeadersSupport {

  static final String REQUEST_ATTR_WIRE_COMPRESSION_HEADERS =
      "bzm.jmeter.wireCompressionHeaders";

  private static final String[] HEADERS_TO_SAVE = {
      HTTPConstants.HEADER_CONTENT_LENGTH,
      HTTPConstants.HEADER_CONTENT_ENCODING,
      "Content-MD5"
  };

  private JmeterCompressionHeadersSupport() {
  }

  static void installCapture(Request request) {
    if (request == null) {
      return;
    }
    request.onResponseHeader(JmeterCompressionHeadersSupport::onResponseHeader);
    request.onResponseHeaders(JmeterCompressionHeadersSupport::onResponseHeaders);
  }

  private static boolean onResponseHeader(Response response, HttpField field) {
    if (response != null && field != null && HttpHeader.CONTENT_ENCODING.is(field.getName())) {
      Request request = response.getRequest();
      if (request != null) {
        mergeSavedHeader(request, field.getName(), field.getValue());
      }
    }
    return true;
  }

  private static void onResponseHeaders(Response response) {
    if (response != null && response.getRequest() != null && response.getHeaders() != null) {
      captureIfCompressed(response.getRequest(), response.getHeaders());
    }
  }

  private static void mergeSavedHeader(Request request, String name, String value) {
    if (request == null || name == null || value == null) {
      return;
    }
    HttpFields.Mutable saved = getOrCreateSaved(request);
    saved.put(name, value);
    request.attribute(REQUEST_ATTR_WIRE_COMPRESSION_HEADERS, saved);
  }

  private static HttpFields.Mutable getOrCreateSaved(Request request) {
    Object existing = request.getAttributes().get(REQUEST_ATTR_WIRE_COMPRESSION_HEADERS);
    if (existing instanceof HttpFields.Mutable) {
      return (HttpFields.Mutable) existing;
    }
    if (existing instanceof HttpFields) {
      HttpFields.Mutable copy = HttpFields.build((HttpFields) existing);
      request.attribute(REQUEST_ATTR_WIRE_COMPRESSION_HEADERS, copy);
      return copy;
    }
    return HttpFields.build();
  }

  static void captureIfCompressed(Request request, HttpFields headers) {
    if (request == null || headers == null) {
      return;
    }
    String contentEncoding = headers.get(HttpHeader.CONTENT_ENCODING);
    if (contentEncoding == null || contentEncoding.trim().isEmpty()) {
      return;
    }
    HttpFields.Mutable saved = getOrCreateSaved(request);
    for (String name : HEADERS_TO_SAVE) {
      String value = headers.get(name);
      if (value != null) {
        saved.put(name, value);
      }
    }
    if (saved.size() > 0) {
      request.attribute(REQUEST_ATTR_WIRE_COMPRESSION_HEADERS, saved);
    }
  }

  static HttpFields headersForSampleResult(ContentResponse contentResponse) {
    if (contentResponse == null || contentResponse.getHeaders() == null) {
      return contentResponse != null ? contentResponse.getHeaders() : HttpFields.EMPTY;
    }
    HttpFields current = contentResponse.getHeaders();
    Request request = contentResponse.getRequest();
    if (request == null) {
      return current;
    }
    Object savedAttr = request.getAttributes().get(REQUEST_ATTR_WIRE_COMPRESSION_HEADERS);
    if (!(savedAttr instanceof HttpFields)) {
      return current;
    }
    HttpFields saved = (HttpFields) savedAttr;
    if (saved.size() == 0) {
      return current;
    }
    HttpFields.Mutable merged = HttpFields.build(current);
    // Jetty removes Content-Encoding but often leaves an updated Content-Length (decoded size).
    // Match HTTPHC4Impl: restore all wire compression headers when encoding was stripped.
    if (!hasContentEncoding(current) && hasContentEncoding(saved)) {
      for (String name : HEADERS_TO_SAVE) {
        String value = saved.get(name);
        if (value != null) {
          merged.put(name, value);
        }
      }
    } else {
      for (String name : HEADERS_TO_SAVE) {
        if (merged.contains(name)) {
          continue;
        }
        String value = saved.get(name);
        if (value != null) {
          merged.put(name, value);
        }
      }
    }
    return merged;
  }

  private static boolean hasContentEncoding(HttpFields headers) {
    if (headers == null) {
      return false;
    }
    String contentEncoding = headers.get(HttpHeader.CONTENT_ENCODING);
    return contentEncoding != null && !contentEncoding.trim().isEmpty();
  }
}
