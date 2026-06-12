package com.blazemeter.jmeter.http2.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URL;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.control.HttpMirrorServer;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Parity for skippable HTTP arguments (blank name / unresolved variable) as in JMeter 5.6.3.
 * Per-argument {@code enabled} checkbox parity targets JMeter 5.7+ ({@code HttpSamplerDisableArgumentsTest}).
 */
public class HttpDisableArgumentsParityTest extends HTTP2TestBase {

  private HttpMirrorServer mirrorServer;
  private HTTP2JettyClient client;
  private int mirrorPort;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    mirrorPort = findFreePort();
    mirrorServer = new HttpMirrorServer(mirrorPort, 10, 10);
    mirrorServer.start();
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("disable-args-parity");
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (mirrorServer != null) {
      mirrorServer.stopServer();
    }
  }

  @Test
  public void skippableArgumentsAreOmittedLikeHttpClient4() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.POST);
    sampler.setProtocol("http");
    sampler.setDomain("localhost");
    sampler.setPort(mirrorPort);
    sampler.setPath("/mirror-args");
    sampler.setUseKeepAlive(true);

    Arguments args = new Arguments();
    args.addArgument(new HTTPArgument("keep", "yes", false));
    args.addArgument(new HTTPArgument("", "blank-name", false));
    args.addArgument(new HTTPArgument("${optionalVar}", "unresolved", false));
    sampler.setArguments(args);

    URL url = new URL("http", "localhost", mirrorPort, "/mirror-args");
    HTTPSampleResult reference = HttpClient4PluginParitySupport.sampleHttpClient4(sampler, url);
    HTTPSampleResult plugin = HttpClient4PluginParitySupport.samplePlugin(client, sampler, url);

    HttpClient4PluginParitySupport.assertCoreParity(reference, plugin, "skippable args");
    assertThat(reference.getResponseDataAsString()).contains("keep=yes");
    assertThat(plugin.getResponseDataAsString()).contains("keep=yes");
    assertThat(reference.getResponseDataAsString()).doesNotContain("blank-name");
    assertThat(plugin.getResponseDataAsString()).doesNotContain("blank-name");
    assertThat(reference.getResponseDataAsString()).doesNotContain("unresolved");
    assertThat(plugin.getResponseDataAsString()).doesNotContain("unresolved");
  }

  private static int findFreePort() throws Exception {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
