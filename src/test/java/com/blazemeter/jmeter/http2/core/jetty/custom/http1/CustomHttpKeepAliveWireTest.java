package com.blazemeter.jmeter.http2.core.jetty.custom.http1;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.core.HTTP2ClientProfileConfig;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.core.JmeterHttpClientAttributes;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Wire-level HTTP/1 keep-alive parity checks. Runs under Surefire (not Failsafe) so tests use
 * unshaded {@code target/classes} and avoid Jetty type mismatches from the shaded plugin jar.
 */
public class CustomHttpKeepAliveWireTest {

  @BeforeClass
  public static void setupJmeter() {
    JMeterTestUtils.setupJmeterEnv();
  }

  private ClientConnector connector;
  private HttpClient client;
  private ExecutorService executor;

  @Before
  public void setUp() throws Exception {
    connector = new ClientConnector();
    connector.setSelectors(1);
    QueuedThreadPool threadPool = new QueuedThreadPool();
    threadPool.setName("keepalive-wire-test");
    connector.setExecutor(threadPool);
    executor = Executors.newSingleThreadExecutor();
    ClientConnectionFactory.Info http11 = CustomHttpClientConnectionFactory.CUSTOM_HTTP11;
    HttpClientTransport transport = new HttpClientTransportDynamic(connector, http11);
    client = new HttpClient(transport);
    client.setUserAgentField(null);
    client.start();
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (executor != null) {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void http2JettyClientEchoesConnectionKeepAliveOnPlainHttp() throws Exception {
    JMeterUtils.setProperty("blazemeter.http.enableHttp2", "false");
    JMeterUtils.setProperty("blazemeter.http.enableHttp3", "false");
    JMeterUtils.setProperty("blazemeter.http.profile", "legacy");

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      Future<String> received = executor.submit(() -> readRequest(serverSocket));

      HTTP2JettyClient jettyClient = new HTTP2JettyClient(false, "keepalive-echo-test");
      jettyClient.start();
      try {
        HTTP2Sampler sampler = new HTTP2Sampler();
        sampler.setMethod("GET");
        sampler.setDomain("localhost");
        sampler.setPort(port);
        sampler.setPath("/test");
        sampler.setProtocol("http");
        sampler.setUseKeepAlive(true);

        URL url = new URL("http", "localhost", port, "/test");
        HTTPSampleResult result = new HTTPSampleResult();
        result.setSampleLabel("GET_NO_PARAMETERS");
        result.setHTTPMethod("GET");
        result.setURL(url);

        HTTPSampleResult sampleResult = jettyClient.sample(sampler, result, false, 0);
        String wireRequest = received.get(10, TimeUnit.SECONDS);

        assertThat(wireRequest).contains("GET /test HTTP/1.1");
        assertThat(wireRequest).contains("Connection: keep-alive");
        assertThat(wireRequest).contains("User-Agent: ");
        assertThat(sampleResult.getResponseDataAsString()).contains("Connection: keep-alive");
        assertThat(sampleResult.getResponseDataAsString()).contains("User-Agent: ");
      } finally {
        jettyClient.stop();
      }
    }
  }

  @Test
  public void http2SamplerEchoesConnectionKeepAliveOnPlainHttp() throws Exception {
    JMeterUtils.setProperty("blazemeter.http.enableHttp2", "false");
    JMeterUtils.setProperty("blazemeter.http.enableHttp3", "false");
    JMeterUtils.setProperty("blazemeter.http.enableHttp1", "true");
    JMeterUtils.setProperty("blazemeter.http.profile", "legacy");
    JMeterUtils.setProperty("blazemeter.http.altSvcCacheEnabled", "false");
    JMeterUtils.setProperty("blazemeter.http.h2cCacheEnabled", "false");

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      Future<String> received = executor.submit(() -> readRequest(serverSocket));

      HTTP2Sampler sampler = new HTTP2Sampler();
      sampler.setMethod("GET");
      sampler.setDomain("localhost");
      sampler.setPort(port);
      sampler.setPath("/test");
      sampler.setProtocol("http");
      sampler.setUseKeepAlive(true);

      HTTP2JettyClient jettyClient = new HTTP2JettyClient(false, "sampler-keepalive-test",
          HTTP2ClientProfileConfig.builder().profile("browser-like").build());
      jettyClient.start();
      try {
        URL url = sampler.getUrl();
        HTTPSampleResult result = new HTTPSampleResult();
        result.setSampleLabel("GET_NO_PARAMETERS");
        result.setHTTPMethod("GET");
        result.setURL(url);
        HTTPSampleResult sampleResult = jettyClient.sample(sampler, result, false, 0);
        String wireRequest = received.get(10, TimeUnit.SECONDS);

        assertThat(wireRequest).contains("GET /test HTTP/1.1");
        assertThat(wireRequest).contains("Connection: keep-alive");
        assertThat(wireRequest).contains("User-Agent: ");
        assertThat(sampleResult.getResponseDataAsString()).contains("Connection: keep-alive");
        assertThat(sampleResult.getResponseDataAsString()).contains("User-Agent: ");
      } finally {
        jettyClient.stop();
      }
    }
  }

  @Test
  public void getWithFileArgDoesNotSendEntityHeadersOnWire() throws Exception {
    java.nio.file.Path tempFile = Files.createTempFile("get-with-file", ".xml");
    Files.writeString(tempFile, "<test/>");
    try {
      JMeterUtils.setProperty("blazemeter.http.enableHttp2", "false");
      JMeterUtils.setProperty("blazemeter.http.enableHttp3", "false");
      JMeterUtils.setProperty("blazemeter.http.profile", "legacy");

      try (ServerSocket serverSocket = new ServerSocket(0)) {
        int port = serverSocket.getLocalPort();
        Future<String> received = executor.submit(() -> readRequest(serverSocket));

        HTTP2Sampler sampler = new HTTP2Sampler();
        sampler.setMethod("GET");
        sampler.setDomain("localhost");
        sampler.setPort(port);
        sampler.setPath("/test?name=value");
        sampler.setProtocol("http");
        sampler.setUseKeepAlive(true);
        sampler.setDoMultipart(false);
        sampler.setHTTPFiles(new HTTPFileArg[] {
            new HTTPFileArg(tempFile.toString(), "file", "text/xml")
        });

        HTTP2JettyClient jettyClient = new HTTP2JettyClient(false, "get-file-wire-test");
        jettyClient.start();
        try {
          URL url = sampler.getUrl();
          HTTPSampleResult result = new HTTPSampleResult();
          result.setSampleLabel("GET_WITH_PARAMETERS_IN_URL");
          result.setHTTPMethod("GET");
          result.setURL(url);
          HTTPSampleResult sampleResult = jettyClient.sample(sampler, result, false, 0);
          String wireRequest = received.get(10, TimeUnit.SECONDS);

          assertThat(wireRequest).contains("GET /test?name=value HTTP/1.1");
          assertThat(wireRequest).doesNotContain("Content-Length:");
          assertThat(wireRequest).doesNotContain("Content-Type:");
          assertThat(sampleResult.getRequestHeaders()).doesNotContain("Content-Length:");
          assertThat(sampleResult.getRequestHeaders()).doesNotContain("Content-Type:");
        } finally {
          jettyClient.stop();
        }
      }
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  public void http2ProfileEchoesConnectionKeepAliveWhenH2cUpgradeFails() throws Exception {
    JMeterUtils.setProperty("blazemeter.http.enableHttp2", "true");
    JMeterUtils.setProperty("blazemeter.http.enableHttp3", "false");
    JMeterUtils.setProperty("blazemeter.http.enableHttp1", "true");
    JMeterUtils.setProperty("blazemeter.http.profile", "browser-compatible");
    JMeterUtils.setProperty("blazemeter.http.altSvcCacheEnabled", "false");

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      Future<String> received = executor.submit(() -> readRequestWithKeepAlive(serverSocket));

      HTTP2JettyClient jettyClient = new HTTP2JettyClient(true, "h2c-fallback-keepalive-test",
          HTTP2ClientProfileConfig.builder().profile("browser-compatible")
              .enableHttp2(true).enableHttp1(true).build());
      jettyClient.start();
      try {
        HTTP2Sampler sampler = new HTTP2Sampler();
        sampler.setMethod("GET");
        sampler.setDomain("localhost");
        sampler.setPort(port);
        sampler.setPath("/test");
        sampler.setProtocol("http");
        sampler.setUseKeepAlive(true);

        URL url = new URL("http", "localhost", port, "/test");
        HTTPSampleResult result = new HTTPSampleResult();
        result.setSampleLabel("GET_WITH_PARAMETERS_IN_URL");
        result.setHTTPMethod("GET");
        result.setURL(url);

        HTTPSampleResult sampleResult = jettyClient.sample(sampler, result, false, 0);
        String wireRequest = received.get(10, TimeUnit.SECONDS);

        assertThat(wireRequest).contains("GET /test HTTP/1.1");
        assertThat(wireRequest).contains("Connection: keep-alive");
        assertThat(sampleResult.getResponseDataAsString()).contains("Connection: keep-alive");
      } finally {
        jettyClient.stop();
      }
    }
  }

  @Test
  public void emitsExplicitConnectionKeepAliveOnWire() throws Exception {
    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      Future<String> received = executor.submit(() -> readRequest(serverSocket));

      Request request = client.newRequest("http://localhost:" + port + "/test")
          .version(HttpVersion.HTTP_1_1)
          .attribute(JmeterHttpClientAttributes.USE_KEEPALIVE, Boolean.TRUE);
      HttpFields headers = request.getHeaders();
      if (headers instanceof HttpFields.Mutable) {
        ((HttpFields.Mutable) headers).put(HttpHeader.CONNECTION, "keep-alive");
      }

      try {
        request.send();
      } catch (Exception ignored) {
        // Response parsing is irrelevant for wire-level assertions.
      }
      String wireRequest = received.get(10, TimeUnit.SECONDS);

      assertThat(wireRequest).contains("GET /test HTTP/1.1");
      assertThat(wireRequest).contains("Connection: keep-alive");
    }
  }

  private static String readRequest(ServerSocket serverSocket) throws Exception {
    return readAndEchoRequest(serverSocket);
  }

  private static String readRequestWithKeepAlive(ServerSocket serverSocket) throws Exception {
    String request = "";
    for (int attempt = 0; attempt < 3; attempt++) {
      request = readAndEchoRequest(serverSocket);
      if (request.contains("Connection: keep-alive")) {
        return request;
      }
    }
    return request;
  }

  private static String readAndEchoRequest(ServerSocket serverSocket) throws Exception {
    try (Socket socket = serverSocket.accept();
         InputStream in = socket.getInputStream();
         OutputStream out = socket.getOutputStream()) {
      byte[] buffer = new byte[4096];
      int read = in.read(buffer);
      String request = new String(buffer, 0, read, StandardCharsets.US_ASCII);
      out.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.write(buffer, 0, read);
      out.flush();
      return request;
    }
  }
}
