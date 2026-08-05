package com.blazemeter.jmeter.http2.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Redirect parity with automatic follow enabled ({@code followRedirects} + {@code autoRedirects}).
 * Jetty and HttpClient4 both follow at the client layer when {@code autoRedirects} is true.
 */
@RunWith(Parameterized.class)
public class HttpRedirectsFollowParityTest extends HTTP2TestBase {

  @Parameterized.Parameter(0)
  public int redirectCode;

  @Parameterized.Parameter(1)
  public String method;

  @Parameterized.Parameter(2)
  public boolean shouldFollowToTarget;

  private ParityRedirectServer redirectServer;
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;

  @Parameterized.Parameters(name = "follow-{0}-{1}-toTarget={2}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        row(301, "GET", true),
        row(301, "HEAD", true),
        row(302, "GET", true),
        row(302, "HEAD", true),
        row(303, "GET", true),
        row(303, "HEAD", true),
        row(307, "GET", true),
        row(307, "HEAD", true),
        row(307, "POST", true),
        row(308, "GET", true),
        row(308, "HEAD", true));
  }

  private static Object[] row(int code, String method, boolean shouldFollow) {
    return new Object[] {code, method, shouldFollow};
  }

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    redirectServer = new ParityRedirectServer();
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("redirect-follow-parity");
    sampler = new HTTP2Sampler();
    sampler.setFollowRedirects(true);
    sampler.setAutoRedirects(true);
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
  public void pluginMatchesHttpClient4WhenFollowingRedirects() throws Exception {
    URL url = new URL(redirectServer.url("/some-location?status=" + redirectCode));
    HTTPSampleResult reference = HttpClient4PluginParitySupport.sampleHttpClient4(sampler, url);
    HTTPSampleResult plugin = HttpClient4PluginParitySupport.samplePlugin(client, sampler, url);

    HttpClient4PluginParitySupport.assertCoreParity(reference, plugin,
        "follow " + redirectCode + " " + method);

    if (shouldFollowToTarget) {
      assertThat(reference.getResponseCode()).isEqualTo("200");
      assertThat(plugin.getResponseCode()).isEqualTo("200");
      assertThat(reference.getURL().toString()).endsWith("/target");
      assertThat(plugin.getURL().toString()).endsWith("/target");
      if ("GET".equals(method)) {
        HttpClient4PluginParitySupport.assertResponseBodyParity(reference, plugin,
            "follow GET body");
      }
    } else {
      assertThat(reference.getResponseCode()).isEqualTo(String.valueOf(redirectCode));
      assertThat(plugin.getResponseCode()).isEqualTo(String.valueOf(redirectCode));
    }
  }
}
