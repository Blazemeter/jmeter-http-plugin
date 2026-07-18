package com.blazemeter.jmeter.http2.parity;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.parity.HttpMirrorParitySupport.MirrorParityResult;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.util.Arrays;
import java.util.Collection;
import org.apache.jmeter.protocol.http.control.HttpMirrorServer;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Port of Apache {@code testPostRequest_BodyFromParameterValues} scenarios (items 0–9). */
@RunWith(Parameterized.class)
public class HttpMirrorRawBodyParityTest extends HTTP2TestBase {

  private static final String ISO_8859_1 = "ISO-8859-1";

  @Parameterized.Parameter
  public int item;

  private HttpMirrorServer mirrorServer;
  private HTTP2JettyClient client;
  private int mirrorPort;

  @Parameterized.Parameters(name = "raw-body-item-{0}")
  public static Collection<Integer> data() {
    return Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
  }

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    mirrorPort = HttpMirrorParitySupport.findFreePort();
    mirrorServer = HttpMirrorParitySupport.startMirrorServer(mirrorPort);
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("mirror-raw-body");
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
  public void rawBodyEchoMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = buildRawBodySampler(item);
    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "raw body item " + item);
    result.assertRawPostBodyMatches();
  }

  private HTTP2Sampler buildRawBodySampler(int test) throws Exception {
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
        titleValue = "mytitle/=";
        descriptionValue = "mydescription /\\";
        alwaysEncoded = true;
        break;
      case 5:
        contentEncoding = "UTF-8";
        titleValue = "mytitle%2F%3D";
        descriptionValue = "mydescription+++%2F%5C";
        break;
      case 6:
        contentEncoding = "UTF-8";
        titleValue = "mytitle%2F%3D";
        descriptionValue = "mydescription+++%2F%5C";
        alwaysEncoded = true;
        break;
      case 7:
        contentEncoding = "UTF-8";
        titleValue = "/wEPDwULLTE2MzM2OTA0NTYPZBYCAgMPZ/rA+8DZ2dnZ2dnZ2d/GNDar6OshPwdJc=";
        descriptionValue = "mydescription";
        break;
      case 8:
        contentEncoding = "UTF-8";
        titleValue = "mytitle++";
        descriptionValue = "mydescription+";
        break;
      case 9:
        return buildRawBodyWithVariablesSampler();
      default:
        throw new IllegalArgumentException("Unsupported raw body item: " + test);
    }

    HTTP2Sampler sampler = baseRawBodySampler(contentEncoding);
    addRawValue(sampler, titleValue, alwaysEncoded);
    addRawValue(sampler, descriptionValue, alwaysEncoded);
    return sampler;
  }

  private HTTP2Sampler buildRawBodyWithVariablesSampler() throws Exception {
    HttpMirrorParitySupport.setupMirrorVariables();
    HTTP2Sampler sampler = baseRawBodySampler("UTF-8");
    addRawValue(sampler, "${title_prefix}mytitleœ₡ĕÅ", false);
    addRawValue(sampler, "mydescriptionœ₡ĕÅ${description_suffix}", false);
    HttpMirrorParitySupport.replaceSamplerVariables(sampler);
    return sampler;
  }

  private static void addRawValue(HTTP2Sampler sampler, String value, boolean alwaysEncoded) {
    org.apache.jmeter.config.Arguments args = sampler.getArguments();
    if (args == null) {
      args = new org.apache.jmeter.config.Arguments();
      sampler.setArguments(args);
    }
    HTTPArgument arg = new HTTPArgument("", value, alwaysEncoded);
    arg.setAlwaysEncoded(alwaysEncoded);
    args.addArgument(arg);
  }

  private HTTP2Sampler baseRawBodySampler(String contentEncoding) {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    sampler.setPostBodyRaw(true);
    if (!contentEncoding.isEmpty()) {
      sampler.setContentEncoding(contentEncoding);
    }
    return sampler;
  }
}
