package com.blazemeter.jmeter.http2.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BzmHttpPluginPropertiesFileTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void persistRemovesLegacyLineAndWritesEffectiveKey() throws Exception {
    File f = folder.newFile("user.properties");
    Files.write(f.toPath(),
        "HTTP2Sampler.proxy_enabled=false\nfoo=bar\n".getBytes(StandardCharsets.ISO_8859_1));

    BzmHttpPluginProperties.persistUserProperty(
        f, "HTTP2Sampler.proxy_enabled", "true");

    String text =
        Files.readAllLines(f.toPath(), StandardCharsets.ISO_8859_1)
            .stream()
            .collect(Collectors.joining("\n"));
    assertTrue(text.contains("blazemeter.http.proxy_enabled=true"));
    assertTrue(text.contains("foo=bar"));
    assertFalse(text.contains("HTTP2Sampler.proxy_enabled"));
  }

  @Test
  public void persistRemovesHttpJettyLineAndWritesEffectiveKey() throws Exception {
    File f = folder.newFile("user2.properties");
    Files.write(f.toPath(),
        "httpJettyClient.proxy_enabled=false\nbaz=qux\n"
            .getBytes(StandardCharsets.ISO_8859_1));

    BzmHttpPluginProperties.persistUserProperty(
        f, "httpJettyClient.proxy_enabled", "true");

    String text =
        Files.readAllLines(f.toPath(), StandardCharsets.ISO_8859_1)
            .stream()
            .collect(Collectors.joining("\n"));
    assertTrue(text.contains("blazemeter.http.proxy_enabled=true"));
    assertTrue(text.contains("baz=qux"));
    assertFalse(text.contains("httpJettyClient.proxy_enabled"));
  }

  @Test
  public void persistRemovesLegacyHttp2AsyncControllerLineAndWritesPreferredKey() throws Exception {
    File f = folder.newFile("user3.properties");
    Files.write(f.toPath(),
        "http2AsyncController.limitMaxParallel=true\nfoo=z\n".getBytes(StandardCharsets.ISO_8859_1));

    BzmHttpPluginProperties.persistControllerUserProperty(
        f, "http2AsyncController.limitMaxParallel", "false");

    String text =
        Files.readAllLines(f.toPath(), StandardCharsets.ISO_8859_1).stream()
            .collect(Collectors.joining("\n"));
    assertTrue(text.contains("blazemeter.http.controller.limitMaxParallel=false"));
    assertTrue(text.contains("foo=z"));
    assertFalse(text.contains("http2AsyncController.limitMaxParallel"));
  }
}
