package com.blazemeter.jmeter.http2.integration;

import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.URI;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test for the HTTP/2 protocol_error regression against blazedemo.com.
 *
 * Run with: -Dit.blazedemo=true (default) or disable with -Dit.blazedemo=false
 */
public class HTTP2ProtocolErrorRegressionBlazeDemoIT extends HTTP2TestBase {
  private static final String BLAZEDEMO_HOST = "blazedemo.com";
  private static final String BLAZEDEMO_PATH = "/";
  private String originalSharedThreadPoolProperty;

  @Before
  public void disableSharedThreadPool() {
    originalSharedThreadPoolProperty = JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");
  }

  @After
  public void restoreSharedThreadPool() {
    if (originalSharedThreadPoolProperty == null) {
      JMeterUtils.getJMeterProperties().remove("httpJettyClient.sharedThreadPool");
    } else {
      JMeterUtils.setProperty("httpJettyClient.sharedThreadPool",
          originalSharedThreadPoolProperty);
    }
  }

  @Test
  public void shouldNotFallbackToHttp11OnBlazeDemo() throws Exception {
    Assume.assumeTrue(Boolean.parseBoolean(System.getProperty("it.blazedemo", "true")));

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain(BLAZEDEMO_HOST);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPath(BLAZEDEMO_PATH);

    HTTP2JettyClient client = new HTTP2JettyClient(false, "IT-BlazeDemo");
    try {
      client.start();
      URL url = URI.create("https://blazedemo.com/").toURL();
      HTTPSampleResult result = client.sample(sampler, buildBaseResult(url), false, 0);
      assertTrue("Expected successful response", result.isSuccessful());
      String responseHeaders = result.getResponseHeaders();
      assertTrue("Expected HTTP/2 response, got: " + responseHeaders,
          responseHeaders.startsWith("HTTP/2"));
    } finally {
      client.stop();
    }
  }

  private HTTPSampleResult buildBaseResult(URL url) {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(url);
    result.setHTTPMethod(HTTPConstants.GET);
    return result;
  }
}
