package com.blazemeter.jmeter.http2.parity;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Port of Apache JMeter {@code TestRedirects}: redirect location and status with follow disabled.
 */
@RunWith(Parameterized.class)
public class HttpRedirectsParityTest extends HTTP2TestBase {

  @Parameterized.Parameter(0)
  public int redirectCode;

  @Parameterized.Parameter(1)
  public String method;

  @Parameterized.Parameter(2)
  public boolean shouldExposeRedirectLocation;

  private ParityRedirectServer redirectServer;
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;

  @Parameterized.Parameters(name = "{0}-{1}-redirect={2}")
  public static Collection<Object[]> data() {
  return Arrays.asList(
        row(301, "GET", true),
        row(301, "HEAD", true),
        row(301, "POST", true),
        row(301, "PUT", true),
        row(301, "DELETE", true),
        row(302, "GET", true),
        row(302, "HEAD", true),
        row(302, "POST", true),
        row(303, "GET", true),
        row(303, "POST", true),
        row(307, "GET", true),
        row(307, "HEAD", true),
        row(307, "POST", false),
        row(307, "PUT", false),
        row(308, "GET", true),
        row(308, "HEAD", true),
        row(308, "POST", true),
        row(300, "GET", false),
        row(304, "GET", false),
        row(305, "GET", false),
        row(306, "GET", false));
  }

  private static Object[] row(int code, String method, boolean shouldRedirect) {
    return new Object[] {code, method, shouldRedirect};
  }

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    redirectServer = new ParityRedirectServer();
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("redirect-parity");
    sampler = new HTTP2Sampler();
    sampler.setFollowRedirects(false);
    sampler.setAutoRedirects(false);
    sampler.setUseKeepAlive(true);
    sampler.setMethod(method);
    sampler.setProtocol("http");
    sampler.setDomain("localhost");
    sampler.setPort(redirectServer.getPort());
    sampler.setPath("/some-location?status=" + redirectCode);
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (redirectServer != null) {
      redirectServer.close();
    }
  }

  @Test
  public void pluginMatchesHttpClient4RedirectSemantics() throws Exception {
    URL url = new URL(redirectServer.url("/some-location?status=" + redirectCode));
    HTTPSampleResult reference = HttpClient4PluginParitySupport.sampleHttpClient4(sampler, url);
    HTTPSampleResult plugin = HttpClient4PluginParitySupport.samplePlugin(client, sampler, url);

    HttpClient4PluginParitySupport.assertCoreParity(reference, plugin,
        redirectCode + " " + method);

    String expectedLocation = shouldExposeRedirectLocation
        ? redirectServer.url("/target")
        : null;
    org.assertj.core.api.Assertions.assertThat(reference.getRedirectLocation())
        .isEqualTo(expectedLocation);
    org.assertj.core.api.Assertions.assertThat(plugin.getRedirectLocation())
        .isEqualTo(expectedLocation);
    org.assertj.core.api.Assertions.assertThat(reference.getResponseCode())
        .isEqualTo(String.valueOf(redirectCode));
    org.assertj.core.api.Assertions.assertThat(plugin.getResponseCode())
        .isEqualTo(String.valueOf(redirectCode));
  }
}
