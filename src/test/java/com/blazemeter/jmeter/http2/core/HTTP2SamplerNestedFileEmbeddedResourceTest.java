package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.After;
import org.junit.Test;

/**
 * A {@code file://} page with an embedded resource that is itself HTML (e.g. an iframe) must
 * have ITS OWN embedded resources downloaded too, exactly like a real HTTP-embedded HTML resource
 * would recurse. {@link HTTP2Sampler}'s generic embedded-resource child sampler never enabled
 * {@code setImageParser} on the child, so this second level of embedded {@code file://} resources
 * was silently never downloaded.
 */
public class HTTP2SamplerNestedFileEmbeddedResourceTest extends HTTP2TestBase {

  private Path tempDir;

  @After
  public void tearDown() throws Exception {
    if (tempDir != null) {
      try (var stream = Files.walk(tempDir)) {
        stream.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  @Test
  public void downloadsEmbeddedResourcesOfANestedFileEmbeddedHtmlResource() throws Exception {
    tempDir = Files.createTempDirectory("http2-nested-file-embedded-test");
    Files.writeString(tempDir.resolve("parent.html"),
        "<html><body><iframe src=\"child.html\"></iframe></body></html>",
        StandardCharsets.UTF_8);
    Files.writeString(tempDir.resolve("child.html"),
        "<html><body><img src=\"pixel.png\"></body></html>",
        StandardCharsets.UTF_8);
    Files.write(tempDir.resolve("pixel.png"), "pixel-bytes".getBytes(StandardCharsets.UTF_8));

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setProtocol("file");
    sampler.setImageParser(true);
    sampler.setConcurrentDwn(false);

    URL parentUrl = tempDir.resolve("parent.html").toUri().toURL();
    HTTPSampleResult pageResult = invokeSample(sampler, parentUrl);

    SampleResult childHtmlResult = findSubResultByUrlContains(pageResult, "child.html");
    assertThat(childHtmlResult).as("nested file:// HTML sub-result (iframe)").isNotNull();

    SampleResult pixelResult = findSubResultByUrlContains(pageResult, "pixel.png");
    assertThat(pixelResult)
        .as("the nested HTML resource's own embedded image must have been downloaded too")
        .isNotNull();
    assertThat(((HTTPSampleResult) pixelResult).getResponseDataAsString())
        .isEqualTo("pixel-bytes");
  }

  private static HTTPSampleResult invokeSample(HTTP2Sampler sampler, URL url) throws Exception {
    Method sample = HTTP2Sampler.class.getDeclaredMethod(
        "sample", URL.class, String.class, boolean.class, int.class);
    sample.setAccessible(true);
    return (HTTPSampleResult) sample.invoke(sampler, url, HTTPConstants.GET, false, 0);
  }

  private static SampleResult findSubResultByUrlContains(SampleResult root, String substring) {
    if (root.getUrlAsString() != null && root.getUrlAsString().contains(substring)) {
      return root;
    }
    for (SampleResult sr : root.getSubResults()) {
      SampleResult found = findSubResultByUrlContains(sr, substring);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}
