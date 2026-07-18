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

  /**
   * Whether THIS plugin exposes the redirect location - differs from
   * {@link #shouldExposeRedirectLocation} (which reference HttpClient4/JMeter 5.6.3 does) only
   * for 307 + a non-GET/HEAD method: JMeter 5.6.3's {@code HTTPSampleResult.isRedirect()} treats
   * that combination as "not a redirect" and never exposes the location, a bug fixed upstream in
   * apache/jmeter PR #6658 (see {@code Rfc9110Redirects}). This plugin ports that fix by default,
   * so it deliberately diverges from the (buggy) reference here; set
   * {@code -Dblazemeter.http.legacyRedirectMethodHandling=true} to match the reference exactly.
   */
  @Parameterized.Parameter(3)
  public boolean pluginShouldExposeRedirectLocation;

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
        row(307, "POST", false, true),
        row(307, "PUT", false, true),
        row(308, "GET", true),
        row(308, "HEAD", true),
        row(308, "POST", true),
        row(300, "GET", false),
        row(304, "GET", false),
        row(305, "GET", false),
        row(306, "GET", false));
  }

  private static Object[] row(int code, String method, boolean shouldRedirect) {
    return row(code, method, shouldRedirect, shouldRedirect);
  }

  private static Object[] row(int code, String method, boolean shouldRedirect,
      boolean pluginShouldRedirect) {
    return new Object[] {code, method, shouldRedirect, pluginShouldRedirect};
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

    // Not assertCoreParity(): its blanket redirectLocation check doesn't allow the deliberate
    // 307+non-GET/HEAD divergence below, so success/response code/message are asserted directly.
    String context = redirectCode + " " + method;
    org.assertj.core.api.Assertions.assertThat(plugin.isSuccessful())
        .as("%s success", context).isEqualTo(reference.isSuccessful());
    org.assertj.core.api.Assertions.assertThat(plugin.getResponseCode())
        .as("%s response code", context).isEqualTo(reference.getResponseCode());

    String expectedReferenceLocation = shouldExposeRedirectLocation
        ? redirectServer.url("/target")
        : null;
    String expectedPluginLocation = pluginShouldExposeRedirectLocation
        ? redirectServer.url("/target")
        : null;
    org.assertj.core.api.Assertions.assertThat(reference.getRedirectLocation())
        .isEqualTo(expectedReferenceLocation);
    org.assertj.core.api.Assertions.assertThat(plugin.getRedirectLocation())
        .isEqualTo(expectedPluginLocation);
    org.assertj.core.api.Assertions.assertThat(reference.getResponseCode())
        .isEqualTo(String.valueOf(redirectCode));
    org.assertj.core.api.Assertions.assertThat(plugin.getResponseCode())
        .isEqualTo(String.valueOf(redirectCode));
  }
}
