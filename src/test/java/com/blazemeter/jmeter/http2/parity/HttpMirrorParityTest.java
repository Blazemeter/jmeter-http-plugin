package com.blazemeter.jmeter.http2.parity;

import static com.blazemeter.jmeter.http2.parity.HttpMirrorParitySupport.MIRROR_PATH;

import com.blazemeter.jmeter.http2.parity.HttpMirrorParitySupport.MirrorParityResult;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.jmeter.protocol.http.control.HttpMirrorServer;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Port of scenarios from Apache JMeter {@code TestHTTPSamplersAgainstHttpMirrorServer}.
 * Compares HttpClient4 vs BlazeMeter HTTP on {@link HttpMirrorServer} echo (HTTP/1.1).
 */
public class HttpMirrorParityTest extends HTTP2TestBase {

  private static final String TITLE = "title";
  private static final String DESCRIPTION = "description";
  private static final byte[] UPLOAD_BYTES =
      "some foo content &?=01234+56789-|œ♪".getBytes(StandardCharsets.UTF_8);

  private static File uploadFile;

  private HttpMirrorServer mirrorServer;
  private HTTP2JettyClient client;
  private int mirrorPort;

  @BeforeClass
  public static void setupClass() throws Exception {
    JMeterTestUtils.setupJmeterEnv();
    uploadFile = Files.createTempFile("HttpMirrorParityTest-", ".tmp").toFile();
    Files.write(uploadFile.toPath(), UPLOAD_BYTES);
    uploadFile.deleteOnExit();
  }

  @AfterClass
  public static void tearDownClass() {
    if (uploadFile != null) {
      uploadFile.delete();
    }
  }

  @Before
  public void setUp() throws Exception {
    mirrorPort = findFreePort();
    mirrorServer = new HttpMirrorServer(mirrorPort, 10, 10);
    mirrorServer.start();
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("mirror-parity");
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
  public void getWithIso88591EncodingMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.GET);
    sampler.setContentEncoding("ISO-8859-1");
    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "GET ISO-8859-1");
    result.assertRequestLineMatches();
  }

  @Test
  public void getRequestEchoMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.GET);
    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "GET mirror");
    result.assertRequestLineMatches();
    result.assertEchoContains("GET " + MIRROR_PATH + " HTTP/1.1");
  }

  @Test
  public void getWithQueryParametersMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.GET);
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, "mytitle", false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, "mydescription", false);

    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "GET query params");
    result.assertRequestLineMatches();
    result.assertEchoContains("title=mytitle", "description=mydescription");
  }

  @Test
  public void getWithUtf8SpecialCharactersMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.GET);
    sampler.setContentEncoding("UTF-8");
    String titleValue = "mytitle3œ+♪ ĕ&yesÅ";
    String descriptionValue = "mydescription3 œ ♪ ĕ Å";
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, titleValue, false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, descriptionValue, false);

    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "GET UTF-8 params");
    result.assertRequestLineMatches();
  }

  @Test
  public void getWithAlwaysEncodedParametersMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.GET);
    sampler.setContentEncoding("UTF-8");
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, "mytitle4%2F%3D", true);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, "mydescription4+++%2F%5C", true);

    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "GET encoded params");
    result.assertRequestLineMatches();
    result.assertEchoContains("title=mytitle4%2F%3D", "description=mydescription4+++%2F%5C");
  }

  @Test
  public void postUrlEncodedEchoMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, "mytitle", false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, "mydescription", false);

    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "POST urlencoded");
    result.assertPostBodyContains("title=mytitle");
    result.assertPostBodyContains("description=mydescription");
  }

  @Test
  public void postUrlEncodedUtf8MatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    sampler.setContentEncoding("UTF-8");
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, "mytitleœ♪ĕÅ", false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, "mydescriptionœ♪ĕÅ", false);

    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "POST UTF-8");
    result.assertPostBodyContains("title=");
    result.assertPostBodyContains("description=");
  }

  @Test
  public void postMultipartEchoMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    sampler.setDoMultipart(true);
    HttpMirrorParitySupport.addFormPair(sampler, "name1", "value1", false);

    sampler.setPath(MIRROR_PATH + "?name0=value0");
    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "POST multipart");
    result.assertEchoContains("Content-Transfer-Encoding: 8bit", "name=\"name1\"", "value1");
  }

  @Test
  public void postMultipartUtf8SpecialCharsMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    sampler.setContentEncoding("UTF-8");
    sampler.setDoMultipart(true);
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, "mytitle/=", false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, "mydescription /\\", false);

    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "POST multipart UTF-8");
    result.assertEchoContains("name=\"" + TITLE + "\"", "name=\"" + DESCRIPTION + "\"");
    result.assertMultipartFieldValuesMatch(TITLE);
    result.assertMultipartFieldValuesMatch(DESCRIPTION);
  }

  @Test
  public void postFileUploadMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    sampler.setDoMultipart(true);
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, "mytitle", false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, "mydescription", false);
    HttpMirrorParitySupport.addFileUpload(sampler, "file1", uploadFile, "text/plain");

    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "POST file upload");
    result.assertEchoContains("name=\"file1\"", "filename=", "some foo content");
  }

  @Test
  public void postBodyRawFromParameterValuesMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    HttpMirrorParitySupport.addRawBodyValues(sampler, "mytitle", "mydescription");

    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "POST body raw");
    result.assertPostBodyContains("mytitlemydescription");
  }

  @Test
  public void putWithFormBodyMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.PUT);
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, "mytitle", false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, "mydescription", false);
    ((org.apache.jmeter.protocol.http.util.HTTPArgument) sampler.getArguments().getArgument(0))
        .setAlwaysEncoded(false);
    ((org.apache.jmeter.protocol.http.util.HTTPArgument) sampler.getArguments().getArgument(1))
        .setAlwaysEncoded(false);

    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "PUT form");
    result.assertRequestLineMatches();
    result.assertPostBodyContains("title=mytitle");
    result.assertPostBodyContains("description=mydescription");
  }

  private static int findFreePort() throws Exception {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
