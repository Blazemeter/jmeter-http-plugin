package com.blazemeter.jmeter.http2.parity;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.parity.HttpMirrorParitySupport.MirrorParityResult;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import org.apache.jmeter.protocol.http.control.HttpMirrorServer;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Port of Apache {@code testPostRequest_FileUpload} scenarios (items 0–2). */
@RunWith(Parameterized.class)
public class HttpMirrorFileUploadParityTest extends HTTP2TestBase {

  private static final String TITLE = "title";
  private static final String DESCRIPTION = "description";
  private static final String ISO_8859_1 = "ISO-8859-1";
  private static final byte[] UPLOAD_BYTES =
      "some foo content &?=01234+56789-|œ♪".getBytes(StandardCharsets.UTF_8);

  private static File uploadFile;

  @Parameterized.Parameter
  public int item;

  private HttpMirrorServer mirrorServer;
  private HTTP2JettyClient client;
  private int mirrorPort;

  @Parameterized.Parameters(name = "file-upload-item-{0}")
  public static Collection<Integer> data() {
    return Arrays.asList(0, 1, 2);
  }

  @BeforeClass
  public static void setupClass() throws Exception {
    JMeterTestUtils.setupJmeterEnv();
    uploadFile = Files.createTempFile("HttpMirrorFileUploadParityTest-", ".tmp").toFile();
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
    mirrorPort = HttpMirrorParitySupport.findFreePort();
    mirrorServer = HttpMirrorParitySupport.startMirrorServer(mirrorPort);
    client = HttpClient4PluginParitySupport.newHttp1PluginClient("mirror-file-upload");
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
  public void fileUploadEchoMatchesHttpClient4() throws Exception {
    HTTP2Sampler sampler = buildFileUploadSampler(item);
    MirrorParityResult result = HttpMirrorParitySupport.runMirrorParity(
        client, sampler, "file upload item " + item);
    result.assertEchoContains("name=\"file1\"", "filename=");
    result.assertMultipartFieldValuesMatch(TITLE);
    result.assertMultipartFieldValuesMatch(DESCRIPTION);
    result.assertEchoContains("some foo content");
  }

  private HTTP2Sampler buildFileUploadSampler(int test) {
    String titleValue = "mytitle";
    String descriptionValue = "mydescription";
    String contentEncoding = "";

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
      default:
        throw new IllegalArgumentException("Unsupported file upload item: " + test);
    }

    HTTP2Sampler sampler = HttpMirrorParitySupport.baseMirrorSampler(mirrorPort, HTTPConstants.POST);
    sampler.setDoMultipart(true);
    if (!contentEncoding.isEmpty()) {
      sampler.setContentEncoding(contentEncoding);
    }
    HttpMirrorParitySupport.addFormPair(sampler, TITLE, titleValue, false);
    HttpMirrorParitySupport.addFormPair(sampler, DESCRIPTION, descriptionValue, false);
    HttpMirrorParitySupport.addFileUpload(sampler, "file1", uploadFile, "text/plain");
    return sampler;
  }
}
