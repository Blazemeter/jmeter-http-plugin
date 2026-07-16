package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Method;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.http.HttpFields;
import org.junit.Test;

/**
 * {@link HTTP2JettyClient#buildHeadersString} strips the {@code Cookie} header before serializing
 * request headers into the sample result. When Cookie was the only header, the stripped result is
 * empty, and trimming a trailing separator from an empty string used to crash.
 */
public class HTTP2JettyClientHeaderStringTest extends HTTP2TestBase {

  @Test
  public void returnsEmptyStringWhenCookieWasTheOnlyHeader() throws Exception {
    HttpFields headers = HttpFields.build().add(HTTPConstants.HEADER_COOKIE, "session=abc");

    String result = buildHeadersString(headers);

    assertThat(result).isEmpty();
  }

  @Test
  public void returnsEmptyStringWhenHeadersIsNull() throws Exception {
    assertThat(buildHeadersString(null)).isEmpty();
  }

  @Test
  public void stripsCookieButKeepsOtherHeaders() throws Exception {
    HttpFields headers = HttpFields.build()
        .add(HTTPConstants.HEADER_COOKIE, "session=abc")
        .add("Accept", "*/*");

    String result = buildHeadersString(headers);

    assertThat(result).contains("Accept");
    assertThat(result).doesNotContain(HTTPConstants.HEADER_COOKIE);
  }

  private static String buildHeadersString(HttpFields headers) throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient(false, "header-string-test");
    Method method = HTTP2JettyClient.class.getDeclaredMethod("buildHeadersString",
        HttpFields.class);
    method.setAccessible(true);
    return (String) method.invoke(client, headers);
  }
}
