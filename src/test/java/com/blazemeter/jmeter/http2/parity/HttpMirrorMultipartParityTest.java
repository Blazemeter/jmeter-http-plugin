package com.blazemeter.jmeter.http2.parity;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.parity.HttpMirrorParitySupport.MirrorParityResult;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.util.Arrays;
import java.util.Collection;
import org.apache.jmeter.protocol.http.control.HttpMirrorServer;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.threads.JMeterContextService;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Port of Apache {@code testPostRequest_FormMultipart} scenarios (items 0–6). */
@RunWith(Parameterized.class)
public class HttpMirrorMultipartParityTest extends HTTP2TestBase {

  private static final String TITLE = "title";
  private static final String DESCRIPTION = "description";
  private static final String ISO_8859_1 = "ISO-8859-1";

  @Parameterized.Parameter
  public int item;

  private HttpMirrorServer mirrorServer;
  private HTTP2JettyClient client;
  private int mirrorPort;

  @Parameterized.Parameters(name = "multipart-item-{0}")
  public static Collection<Integer> data() {
    return Arrays.asList(0, 1, 2, 3, 4, 5, 6);
  }

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    mirrorPort = findFreePort();
    mirrorServer = new HttpMirrorServer(mirrorPort, 10, 10);
    mirrorServer.start();
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("mirror-multipart");
  }

  @After
  public void tearDown() throws Exception {
    HttpMirrorParitySupport.clearMirrorVariables();
    if (client != null) {
      client.stop();
    }
    if (mirrorServer != null) {
      mirrorServer.stopServer();
    }
  }

  @Test
  public void multipartEchoMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = buildMultipartSampler(item);
    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "multipart item " + item);
    result.assertEchoContains("Content-Transfer-Encoding: 8bit");
    result.assertMultipartFieldValuesMatch(TITLE);
    result.assertMultipartFieldValuesMatch(DESCRIPTION);
  }

  private HTTP2Sampler buildMultipartSampler(int test) throws Exception {
    String titleValue = "mytitle";
    String descriptionValue = "mydescription";
    String contentEncoding = "";
    boolean alwaysEncoded = false;

    switch (test) {
      case 0:
        break;
      case 1:
        contentEncoding = ISO_8859_1;
        break;
      case 2:
        contentEncoding = "UTF-8";
        titleValue = "mytitleœ₡ĕÅ";
        descriptionValue = "mydescriptionœ₡ĕÅ";
        break;
      case 3:
        contentEncoding = "UTF-8";
        titleValue = "mytitle/=";
        descriptionValue = "mydescription /\\";
        break;
      case 4:
        contentEncoding = "UTF-8";
        titleValue = "mytitle%2F%3D";
        descriptionValue = "mydescription+++%2F%5C";
        alwaysEncoded = true;
        break;
      case 5:
        contentEncoding = "UTF-8";
        titleValue = "/wEPDwULLTE2MzM2OTA0NTYPZBYCAgMPZ/rA+8DZ2dnZ2dnZ2d/GNDar6OshPwdJc=";
        descriptionValue = "mydescription";
        break;
      case 6:
        return buildMultipartWithVariablesSampler();
      default:
        throw new IllegalArgumentException("Unsupported multipart item: " + test);
    }

    HTTP2Sampler sampler = baseMultipartSampler(contentEncoding);
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, titleValue, alwaysEncoded);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, descriptionValue, alwaysEncoded);
    return sampler;
  }

  private HTTP2Sampler buildMultipartWithVariablesSampler() throws Exception {
    HttpMirrorParitySupport.setupMirrorVariables();
    HTTP2Sampler sampler = baseMultipartSampler("UTF-8");
    HttpMirrorParitySupport.addFormPair(sampler, TITLE,
        "${title_prefix}mytitleœ₡ĕÅ", false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION,
        "mydescriptionœ₡ĕÅ${description_suffix}", false);
    HttpMirrorParitySupport.replaceSamplerVariables(sampler);
    return sampler;
  }

  private HTTP2Sampler baseMultipartSampler(String contentEncoding) {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    sampler.setDoMultipart(true);
    if (!contentEncoding.isEmpty()) {
      sampler.setContentEncoding(contentEncoding);
    }
    return sampler;
  }

  private static int findFreePort() throws Exception {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
