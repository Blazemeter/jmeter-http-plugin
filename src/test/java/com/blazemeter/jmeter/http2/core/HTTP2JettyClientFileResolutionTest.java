package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.services.FileServer;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Test;

/**
 * HttpClient4 resolves an HTTP request file argument via {@link FileServer}, relative to the
 * running test plan's directory (falling back to JMeter's {@code bin} dir) - not relative to the
 * JVM's working directory. {@link HTTP2JettyClient} used to call {@code Paths.get(file.getPath())}
 * directly, so a relative path that resolves fine for a real JMeter test plan would fail here.
 */
public class HTTP2JettyClientFileResolutionTest extends HTTP2TestBase {

  private TeardownableServer server;
  private HTTP2JettyClient client;
  private Path tempDir;

  @After
  public void tearDown() throws Exception {
    FileServer.getFileServer().resetBase();
    if (client != null) {
      client.stop();
    }
    if (server != null) {
      server.stop();
    }
    if (tempDir != null) {
      Files.walk(tempDir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path -> path.toFile().delete());
    }
  }

  @Test
  public void resolvesSingleFilePostBodyRelativeToFileServerBaseDir() throws Exception {
    String fileContent = "resolved-via-fileserver-base-dir";
    tempDir = Files.createTempDirectory("http2-file-resolution-test");
    Files.write(tempDir.resolve("upload.txt"), fileContent.getBytes(StandardCharsets.UTF_8));
    FileServer.getFileServer().setBase(tempDir.toFile());

    int port = startHttp1OnlyServer();
    client = new HTTP2JettyClient(false, "file-resolution-test");
    client.start();

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.POST);
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setPath(ServerBuilder.SERVER_PATH_200_FILE_SENT);
    sampler.setProtocol("http");
    // Bare filename, no directory: can only resolve via FileServer's configured base dir, never
    // via a plain Paths.get(...) relative to the JVM's working directory.
    sampler.setHTTPFiles(new HTTPFileArg[] {new HTTPFileArg("upload.txt", "", "text/plain")});

    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("POST_FILE_RESOLUTION");
    result.setHTTPMethod(HTTPConstants.POST);
    result.setURL(new URL("http", "localhost", port, ServerBuilder.SERVER_PATH_200_FILE_SENT));

    HTTPSampleResult sampled = client.sample(sampler, result, false, 0);

    assertThat(sampled.isSuccessful()).isTrue();
    assertThat(sampled.getResponseDataAsString()).isEqualTo(fileContent);
  }

  @Test
  public void throwsAClearErrorWhenFileCannotBeResolvedAnywhere() throws Exception {
    tempDir = Files.createTempDirectory("http2-file-resolution-test-missing");
    FileServer.getFileServer().setBase(tempDir.toFile());

    int port = startHttp1OnlyServer();
    client = new HTTP2JettyClient(false, "file-resolution-missing-test");
    client.start();

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.POST);
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setPath(ServerBuilder.SERVER_PATH_200_FILE_SENT);
    sampler.setProtocol("http");
    sampler.setHTTPFiles(
        new HTTPFileArg[] {new HTTPFileArg("does-not-exist.txt", "", "text/plain")});

    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("POST_FILE_RESOLUTION_MISSING");
    result.setHTTPMethod(HTTPConstants.POST);
    result.setURL(new URL("http", "localhost", port, ServerBuilder.SERVER_PATH_200_FILE_SENT));

    org.junit.Assert.assertThrows(java.io.IOException.class,
        () -> client.sample(sampler, result, false, 0));
  }

  private int startHttp1OnlyServer() throws Exception {
    server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }
}
