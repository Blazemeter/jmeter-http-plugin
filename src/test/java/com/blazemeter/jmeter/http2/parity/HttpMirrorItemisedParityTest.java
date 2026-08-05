package com.blazemeter.jmeter.http2.parity;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.parity.HttpMirrorParitySupport.MirrorParityResult;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.jmeter.protocol.http.control.HttpMirrorServer;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Port of Apache {@code itemised_testPostRequest_UrlEncoded} and
 * {@code itemised_testGetRequest_Parameters} (cases 0–7 / 0–5).
 */
@RunWith(Parameterized.class)
public class HttpMirrorItemisedParityTest extends HTTP2TestBase {

  private static final String TITLE = "title";
  private static final String DESCRIPTION = "description";
  private static final String ISO_8859_1 = "ISO-8859-1";

  @Parameterized.Parameter(0)
  public String mode;

  @Parameterized.Parameter(1)
  public int item;

  private HttpMirrorServer mirrorServer;
  private HTTP2JettyClient client;
  private int mirrorPort;

  @Parameterized.Parameters(name = "{0}-item-{1}")
  public static Collection<Object[]> data() {
    List<Object[]> rows = new ArrayList<>();
    for (int i = 0; i <= 7; i++) {
      rows.add(new Object[] {"POST", i});
    }
    for (int i = 0; i <= 5; i++) {
      rows.add(new Object[] {"GET", i});
    }
    return rows;
  }

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    mirrorPort = HttpMirrorParitySupport.findFreePort();
    mirrorServer = HttpMirrorParitySupport.startMirrorServer(mirrorPort);
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("mirror-itemised");
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
  public void mirrorEchoMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = buildSampler();
    String context = mode + " mirror item " + item;
    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(client, sampler, context);
    if ("GET".equals(mode)) {
      result.assertRequestLineMatches();
    } else {
      result.assertPostBodyMatches();
    }
  }

  private HTTP2Sampler buildSampler() throws Exception {
    if ("GET".equals(mode)) {
      return buildGetParametersSampler(item);
    }
    return buildPostUrlEncodedSampler(item);
  }

  private HTTP2Sampler buildGetParametersSampler(int test) throws Exception {
    String titleValue = "mytitle";
    String descriptionValue = "mydescription";
    String contentEncoding = "";
    boolean alwaysEncoded = false;

    switch (test) {
      case 0:
        break;
      case 1:
        contentEncoding = ISO_8859_1;
        titleValue = "mytitle1Œ";
        descriptionValue = "mydescription1Œ";
        break;
      case 2:
        contentEncoding = "UTF-8";
        titleValue = "mytitle2œ₡ĕÅ";
        descriptionValue = "mydescription2œ₡ĕÅ";
        break;
      case 3:
        contentEncoding = "UTF-8";
        titleValue = "mytitle3œ+₡ ĕ&yesÅ";
        descriptionValue = "mydescription3 œ ₡ ĕ Å";
        break;
      case 4:
        contentEncoding = "UTF-8";
        titleValue = "mytitle4%2F%3D";
        descriptionValue = "mydescription4+++%2F%5C";
        alwaysEncoded = true;
        break;
      case 5:
        return buildGetWithVariablesSampler();
      default:
        throw new IllegalArgumentException("Unsupported GET item: " + test);
    }

    HTTP2Sampler sampler = baseGetSampler(contentEncoding);
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, titleValue, alwaysEncoded);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, descriptionValue, alwaysEncoded);
    return sampler;
  }

  private HTTP2Sampler buildGetWithVariablesSampler() throws Exception {
    HttpMirrorParitySupport.setupMirrorVariables();
    HTTP2Sampler sampler = baseGetSampler("UTF-8");
    HttpMirrorParitySupport.addFormPair(sampler, TITLE,
        "${title_prefix}mytitle5œ₡ĕÅ", false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION,
        "mydescription5œ₡ĕÅ${description_suffix}", false);
    HttpMirrorParitySupport.replaceSamplerVariables(sampler);
    return sampler;
  }

  private HTTP2Sampler buildPostUrlEncodedSampler(int test) throws Exception {
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
        titleValue = "mytitle2œ₡ĕÅ";
        descriptionValue = "mydescription2œ₡ĕÅ";
        break;
      case 3:
        contentEncoding = "UTF-8";
        titleValue = "mytitle3/=";
        descriptionValue = "mydescription3 /\\";
        break;
      case 4:
        contentEncoding = "UTF-8";
        titleValue = "mytitle4%2F%3D";
        descriptionValue = "mydescription4+++%2F%5C";
        alwaysEncoded = true;
        break;
      case 5:
        contentEncoding = "UTF-8";
        titleValue = "/wEPDwULLTE2MzM2OTA0NTYPZBYCAgMPZ/rA+8DZ2dnZ2dnZ2d/GNDar6OshPwdJc=";
        descriptionValue = "mydescription5";
        break;
      case 6:
        contentEncoding = "UTF-8";
        titleValue = "%2FwEPDwULLTE2MzM2OTA0NTYPZBYCAgMPZ%2FrA%2B8DZ2dnZ2dnZ2d%2FGNDar6OshPwdJc%3D";
        descriptionValue = "mydescription6";
        return buildPostProxyStyleSampler(contentEncoding, titleValue, descriptionValue);
      case 7:
        return buildPostWithVariablesSampler();
      default:
        throw new IllegalArgumentException("Unsupported POST item: " + test);
    }

    HTTP2Sampler sampler = basePostSampler(contentEncoding);
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, titleValue, alwaysEncoded);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, descriptionValue, alwaysEncoded);
    return sampler;
  }

  private HTTP2Sampler buildPostProxyStyleSampler(String contentEncoding, String titleValue,
      String descriptionValue) {
    HTTP2Sampler sampler = basePostSampler(contentEncoding);
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, titleValue, false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, descriptionValue, false);
    ((HTTPArgument) sampler.getArguments().getArgument(0)).setAlwaysEncoded(false);
    ((HTTPArgument) sampler.getArguments().getArgument(1)).setAlwaysEncoded(false);
    return sampler;
  }

  private HTTP2Sampler buildPostWithVariablesSampler() throws Exception {
    HttpMirrorParitySupport.setupMirrorVariables();
    HTTP2Sampler sampler = basePostSampler("UTF-8");
    HttpMirrorParitySupport.addFormPair(sampler, TITLE,
        "${title_prefix}mytitle7œ₡ĕÅ", false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION,
        "mydescription7œ₡ĕÅ${description_suffix}", false);
    HttpMirrorParitySupport.replaceSamplerVariables(sampler);
    return sampler;
  }

  private HTTP2Sampler baseGetSampler(String contentEncoding) {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.GET);
    if (!contentEncoding.isEmpty()) {
      sampler.setContentEncoding(contentEncoding);
    }
    return sampler;
  }

  private HTTP2Sampler basePostSampler(String contentEncoding) {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    if (!contentEncoding.isEmpty()) {
      sampler.setContentEncoding(contentEncoding);
    }
    return sampler;
  }
}
