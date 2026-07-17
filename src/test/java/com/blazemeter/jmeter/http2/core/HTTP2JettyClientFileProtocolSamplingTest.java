package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.After;
import org.junit.Test;

/**
 * HttpClient4's {@code HTTPFileImpl} handles {@code file://} sampler URLs (used by JMeter's own
 * HTML-parser regression fixtures to test the embedded-resource parser against local files
 * without a live server): it always uses GET, opens the URL directly via Java's built-in
 * {@code file} handler, and always reports {@code text/html} as the content type regardless of
 * the file's real type - not a guess based on the file extension.
 */
public class HTTP2JettyClientFileProtocolSamplingTest extends HTTP2TestBase {

  private HTTP2JettyClient client;
  private Path tempFile;

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (tempFile != null) {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  public void samplesLocalFileWithHardcodedGetMethodAndTextHtmlContentType() throws Exception {
    String fileContent = "not-actually-html-content";
    // .txt on purpose: content type must be hardcoded to text/html, not guessed from extension.
    tempFile = Files.createTempFile("http2-file-protocol-test", ".txt");
    Files.write(tempFile, fileContent.getBytes(StandardCharsets.UTF_8));
    URL fileUrl = tempFile.toUri().toURL();

    HTTPSampleResult sampled = sampleFileUrl(fileUrl, null);

    assertThat(sampled.isSuccessful()).isTrue();
    assertThat(sampled.getResponseCode()).isEqualTo("200");
    assertThat(sampled.getHTTPMethod()).isEqualTo(HTTPConstants.GET);
    // HTTP2Sampler.getContentEncoding() defaults to UTF-8 (JMeter's own schema default) when
    // never explicitly set, so the charset is always present - never plain "text/html".
    assertThat(sampled.getContentType()).isEqualTo("text/html; charset=UTF-8");
    assertThat(sampled.getResponseDataAsString()).isEqualTo(fileContent);
  }

  @Test
  public void appendsSamplerContentEncodingAsCharsetOnTheContentType() throws Exception {
    tempFile = Files.createTempFile("http2-file-protocol-test", ".txt");
    Files.write(tempFile, "content".getBytes(StandardCharsets.UTF_8));
    URL fileUrl = tempFile.toUri().toURL();

    HTTPSampleResult sampled = sampleFileUrl(fileUrl, "ISO-8859-1");

    assertThat(sampled.getContentType()).isEqualTo("text/html; charset=ISO-8859-1");
  }

  @Test
  public void propagatesAnErrorWhenTheFileDoesNotExist() throws Exception {
    tempFile = Files.createTempFile("http2-file-protocol-test", ".txt");
    Files.delete(tempFile);
    URL fileUrl = tempFile.toUri().toURL();
    tempFile = null;

    org.junit.Assert.assertThrows(IOException.class, () -> sampleFileUrl(fileUrl, null));
  }

  private HTTPSampleResult sampleFileUrl(URL fileUrl, String contentEncoding) throws Exception {
    client = new HTTP2JettyClient(false, "file-protocol-sampling-test");
    client.start();

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setProtocol("file");
    if (contentEncoding != null) {
      sampler.setContentEncoding(contentEncoding);
    }

    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("GET_FILE_PROTOCOL");
    result.setHTTPMethod(HTTPConstants.GET);
    result.setURL(fileUrl);

    return client.sample(sampler, result, false, 0);
  }
}
