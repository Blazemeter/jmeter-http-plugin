package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Method;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.junit.Test;

/**
 * HttpClient4's multipart writer (Apache HttpMime) writes part headers with canonical casing
 * ({@code Content-Disposition}, {@code Content-Type}, {@code Content-Transfer-Encoding}) and puts
 * the raw argument value in the part body, never URL-encoded. {@link HTTP2JettyClient} used to
 * build these part headers with Jetty's {@code HttpFields}, which lowercases header names and
 * omitted {@code Content-Transfer-Encoding} entirely, and wrote {@code arg.getEncodedValue(...)}
 * instead of the raw value.
 */
public class HTTP2JettyClientMultipartHeaderCasingTest extends HTTP2TestBase {

  private static final HTTP2JettyClient CLIENT = new HTTP2JettyClient(false, "multipart-test");

  @Test
  public void formatsPartHeadersWithCanonicalCasingAndTransferEncoding() throws Exception {
    String headers = invokeFormatMultipartPartHeaders(
        "form-data; name=\"field\"", "text/plain; charset=utf-8", "8bit");

    assertThat(headers).isEqualTo(
        "Content-Disposition: form-data; name=\"field\"\r\n"
            + "Content-Type: text/plain; charset=utf-8\r\n"
            + "Content-Transfer-Encoding: 8bit\r\n");
  }

  @Test
  public void omitsTransferEncodingLineWhenNotProvided() throws Exception {
    String headers = invokeFormatMultipartPartHeaders(
        "form-data; name=\"field\"", "text/plain", null);

    assertThat(headers).isEqualTo(
        "Content-Disposition: form-data; name=\"field\"\r\n"
            + "Content-Type: text/plain\r\n");
  }

  @Test
  public void multipartArgumentValueReturnsRawValueNotUrlEncoded() throws Exception {
    HTTPArgument arg = new HTTPArgument("field", "a value & more = stuff");

    String value = invokeMultipartArgumentValue(arg);

    assertThat(value).isEqualTo("a value & more = stuff");
  }

  private String invokeFormatMultipartPartHeaders(String disposition, String contentType,
                                                   String transferEncoding) throws Exception {
    Method method = HTTP2JettyClient.class.getDeclaredMethod(
        "formatMultipartPartHeaders", String.class, String.class, String.class);
    method.setAccessible(true);
    return (String) method.invoke(CLIENT, disposition, contentType, transferEncoding);
  }

  private String invokeMultipartArgumentValue(HTTPArgument arg) throws Exception {
    Method method = HTTP2JettyClient.class.getDeclaredMethod(
        "multipartArgumentValue", HTTPArgument.class);
    method.setAccessible(true);
    return (String) method.invoke(null, arg);
  }
}
