package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Method;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.junit.Test;

/**
 * {@link HTTP2Sampler#getUserAgent} reads the {@code User-Agent} value out of the serialized
 * {@code requestHeaders} string to reuse it when parsing embedded resources. When User-Agent is
 * the last header, there's no trailing {@code '\n'} to bound the substring.
 */
public class HTTP2SamplerUserAgentExtractionTest extends HTTP2TestBase {

  @Test
  public void extractsUserAgentWhenItIsTheLastHeader() throws Exception {
    String requestHeaders = "Host: example.org\nUser-Agent: Mozilla/5.0 (test)";

    String userAgent = getUserAgent(requestHeaders);

    assertThat(userAgent).isEqualTo("Mozilla/5.0 (test)");
  }

  @Test
  public void extractsUserAgentWhenFollowedByOtherHeaders() throws Exception {
    String requestHeaders = "Host: example.org\nUser-Agent: Mozilla/5.0 (test)\nAccept: */*\n";

    String userAgent = getUserAgent(requestHeaders);

    assertThat(userAgent).isEqualTo("Mozilla/5.0 (test)");
  }

  @Test
  public void returnsNullWhenNoUserAgentHeaderPresent() throws Exception {
    String requestHeaders = "Host: example.org\nAccept: */*\n";

    assertThat(getUserAgent(requestHeaders)).isNull();
  }

  private static String getUserAgent(String requestHeaders) throws Exception {
    HTTPSampleResult sampleResult = new HTTPSampleResult();
    sampleResult.setRequestHeaders(requestHeaders);

    Method method = HTTP2Sampler.class.getDeclaredMethod("getUserAgent", HTTPSampleResult.class);
    method.setAccessible(true);
    return (String) method.invoke(new HTTP2Sampler(), sampleResult);
  }
}
