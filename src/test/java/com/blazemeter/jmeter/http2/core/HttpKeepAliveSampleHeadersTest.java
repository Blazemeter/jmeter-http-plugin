package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

public class HttpKeepAliveSampleHeadersTest {

  private ExecutorService executor;

  @BeforeClass
  public static void setupJmeter() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @After
  public void tearDown() throws Exception {
    try {
      if (executor != null) {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        executor = null;
      }
    } finally {
      // Always clear: if executor teardown throws, leaked legacy profile properties make later
      // HTTP/2 tests negotiate HTTP/1.1 (or skip h2c upgrade caching) for the rest of the suite.
      JMeterUtils.getJMeterProperties().remove("blazemeter.http.enableHttp2");
      JMeterUtils.getJMeterProperties().remove("blazemeter.http.enableHttp3");
      JMeterUtils.getJMeterProperties().remove("blazemeter.http.profile");
      System.clearProperty("blazemeter.http.enableHttp2");
      System.clearProperty("blazemeter.http.enableHttp3");
      System.clearProperty("blazemeter.http.profile");
    }
  }

  @Test
  public void sampleRequestHeadersIncludeKeepAliveWhenEnabled() throws Exception {
    configureLegacyHttp1Only();

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      Future<String> received = startWireCapture(serverSocket);

      HTTP2JettyClient jettyClient = new HTTP2JettyClient(false, "keepalive-sample-test");
      jettyClient.start();
      try {
        HTTPSampleResult sampleResult = samplePlainHttp(jettyClient, port, true);
        String wireRequest = received.get(10, TimeUnit.SECONDS);

        assertThat(sampleResult.getRequestHeaders())
            .contains(HTTPConstants.HEADER_CONNECTION + ": " + HTTPConstants.KEEP_ALIVE);
        assertThat(wireRequest).contains("GET /test HTTP/1.1");
        assertThat(wireRequest.toLowerCase()).doesNotContain("connection: keep-alive");
      } finally {
        jettyClient.stop();
      }
    }
  }

  @Test
  public void sampleRequestHeadersIncludeCloseWhenKeepAliveDisabled() throws Exception {
    configureLegacyHttp1Only();

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      Future<String> received = startWireCapture(serverSocket);

      HTTP2JettyClient jettyClient = new HTTP2JettyClient(false, "keepalive-close-test");
      jettyClient.start();
      try {
        HTTPSampleResult sampleResult = samplePlainHttp(jettyClient, port, false);
        String wireRequest = received.get(10, TimeUnit.SECONDS);

        assertThat(sampleResult.getRequestHeaders())
            .contains(HTTPConstants.HEADER_CONNECTION + ": "
                + HTTPConstants.CONNECTION_CLOSE);
        assertThat(wireRequest.toLowerCase()).contains("connection: close");
      } finally {
        jettyClient.stop();
      }
    }
  }

  private static void configureLegacyHttp1Only() {
    JMeterUtils.setProperty("blazemeter.http.enableHttp2", "false");
    JMeterUtils.setProperty("blazemeter.http.enableHttp3", "false");
    JMeterUtils.setProperty("blazemeter.http.profile", "legacy");
  }

  private Future<String> startWireCapture(ServerSocket serverSocket) {
    executor = Executors.newSingleThreadExecutor();
    return executor.submit(() -> readRequest(serverSocket));
  }

  private static HTTPSampleResult samplePlainHttp(HTTP2JettyClient jettyClient, int port,
                                                  boolean useKeepAlive) throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod("GET");
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setPath("/test");
    sampler.setProtocol("http");
    sampler.setUseKeepAlive(useKeepAlive);

    URL url = new URL("http", "localhost", port, "/test");
    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("GET_NO_PARAMETERS");
    result.setHTTPMethod("GET");
    result.setURL(url);

    return jettyClient.sample(sampler, result, false, 0);
  }

  private static String readRequest(ServerSocket serverSocket) throws Exception {
    try (Socket socket = serverSocket.accept();
         InputStream in = socket.getInputStream();
         OutputStream out = socket.getOutputStream()) {
      byte[] buffer = new byte[4096];
      int read = in.read(buffer);
      out.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\n"
          .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return new String(buffer, 0, read, StandardCharsets.US_ASCII);
    }
  }
}