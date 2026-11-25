package com.blazemeter.jmeter.http2.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.protocol.http.control.HttpMirrorServer;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;

/** Helpers for {@link HttpMirrorParityTest} (Apache mirror-server parity). */
public final class HttpMirrorParitySupport {

  /** Default path used by {@code TestHTTPSamplersAgainstHttpMirrorServer}. */
  public static final String MIRROR_PATH = "/test/somescript.jsp";

  private HttpMirrorParitySupport() {
  }

  private static final int MIRROR_START_ATTEMPTS = 5;
  private static final long MIRROR_START_TIMEOUT_MILLIS = 5_000;
  private static final int PROBE_TIMEOUT_MILLIS = 1_000;

  /** A started {@link HttpMirrorServer} and the port it really listens on. */
  public static final class RunningMirror {

    private final HttpMirrorServer server;
    private final int port;

    private RunningMirror(HttpMirrorServer server, int port) {
      this.server = server;
      this.port = port;
    }

    public HttpMirrorServer server() {
      return server;
    }

    public int port() {
      return port;
    }
  }

  /**
   * Starts a mirror on a free port and returns once that mirror - not something else - answers on
   * it.
   *
   * <p>{@link HttpMirrorServer} extends {@link Thread} and binds its listening socket inside
   * {@code run()}, so {@code start()} returns before the socket is bound. The free port is also
   * only free when it is picked: something else can take it before the mirror binds, and the
   * mirror then only logs the failure and ends, while the port keeps answering - or appears to: a
   * probe to a free ephemeral port can even connect to itself. In CI a test got its connection
   * refused that way. So the mirror is asked to echo a token, the answer must be an HTTP response
   * carrying it, and a mirror that could not bind is replaced by one on another port.
   */
  public static RunningMirror startMirrorServer() throws Exception {
    Exception lastFailure = null;
    for (int attempt = 0; attempt < MIRROR_START_ATTEMPTS; attempt++) {
      int port = findFreePort();
      HttpMirrorServer server = new HttpMirrorServer(port, 10, 10);
      server.start();
      try {
        awaitMirrorAnswering(server, port);
        return new RunningMirror(server, port);
      } catch (IllegalStateException e) {
        lastFailure = e;
        server.stopServer();
        server.join(TimeUnit.SECONDS.toMillis(1));
      }
    }
    throw new IllegalStateException(
        "HttpMirrorServer did not start after " + MIRROR_START_ATTEMPTS + " attempts", lastFailure);
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void awaitMirrorAnswering(HttpMirrorServer server, int port)
      throws InterruptedException {
    String token = "mirror-probe-" + UUID.randomUUID();
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MIRROR_START_TIMEOUT_MILLIS);
    IOException lastFailure = null;
    while (System.nanoTime() < deadline) {
      if (server.getException() != null || !server.isAlive()) {
        throw new IllegalStateException(
            "HttpMirrorServer could not listen on port " + port, server.getException());
      }
      try {
        if (mirrorEchoes(port, token)) {
          return;
        }
      } catch (IOException e) {
        lastFailure = e;
      }
      Thread.sleep(20);
    }
    throw new IllegalStateException(
        "HttpMirrorServer did not answer on port " + port + " within "
            + MIRROR_START_TIMEOUT_MILLIS + " ms", lastFailure);
  }

  private static boolean mirrorEchoes(int port, String token) throws IOException {
    try (Socket probe = new Socket()) {
      probe.connect(new InetSocketAddress("localhost", port), PROBE_TIMEOUT_MILLIS);
      probe.setSoTimeout(PROBE_TIMEOUT_MILLIS);
      OutputStream out = probe.getOutputStream();
      out.write(("GET /" + token + " HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      ByteArrayOutputStream answer = new ByteArrayOutputStream();
      InputStream in = probe.getInputStream();
      byte[] buffer = new byte[1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        answer.write(buffer, 0, read);
      }
      // A socket connected to itself reads back the request: only a response is the mirror's.
      String text = answer.toString(StandardCharsets.ISO_8859_1.name());
      return text.startsWith("HTTP/") && text.contains(token);
    } catch (SocketTimeoutException e) {
      return false;
    }
  }

  public static HTTP2Sampler baseMirrorSampler(int mirrorPort, String method) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(method);
    sampler.setProtocol("http");
    sampler.setDomain("localhost");
    sampler.setPort(mirrorPort);
    sampler.setPath(MIRROR_PATH);
    sampler.setUseKeepAlive(true);
    sampler.setFollowRedirects(true);
    return sampler;
  }

  public static void addFormPair(HTTP2Sampler sampler, String name, String value,
      boolean alwaysEncoded) {
    if (alwaysEncoded) {
      sampler.addEncodedArgument(name, value);
      return;
    }
    Arguments args = sampler.getArguments();
    if (args == null) {
      args = new Arguments();
      sampler.setArguments(args);
    }
    args.addArgument(new HTTPArgument(name, value, false));
  }

  public static void addRawBodyValues(HTTP2Sampler sampler, String... values) {
    Arguments args = new Arguments();
    for (String value : values) {
      HTTPArgument arg = new HTTPArgument("", value, false);
      arg.setAlwaysEncoded(false);
      args.addArgument(arg);
    }
    sampler.setArguments(args);
    sampler.setPostBodyRaw(true);
  }

  public static void addFileUpload(HTTP2Sampler sampler, String fieldName, java.io.File file,
      String mimeType) {
    sampler.setDoMultipart(true);
    sampler.setHTTPFiles(new HTTPFileArg[] {
        new HTTPFileArg(file.getAbsolutePath(), fieldName, mimeType)
    });
  }

  public static MirrorParityResult runMirrorParity(HTTP2JettyClient client, HTTP2Sampler sampler,
      String context) throws Exception {
    URL url = sampler.getUrl();
    HTTPSampleResult reference = HttpClient4PluginParitySupport.sampleHttpClient4(sampler, url);
    HTTPSampleResult plugin = HttpClient4PluginParitySupport.samplePlugin(client, sampler, url);
    HttpClient4PluginParitySupport.assertCoreParity(reference, plugin, context);
    return new MirrorParityResult(reference, plugin);
  }

  public static String requestLine(HTTPSampleResult mirrorEcho) {
    String echo = mirrorEcho.getResponseDataAsString().replace("\r\n", "\n");
    int newline = echo.indexOf('\n');
    return newline >= 0 ? echo.substring(0, newline) : echo;
  }

  public static void assertSameRequestLine(HTTPSampleResult reference, HTTPSampleResult plugin) {
    assertThat(requestLine(plugin)).isEqualTo(requestLine(reference));
  }

  public static void assertEchoContainsBoth(HTTPSampleResult reference, HTTPSampleResult plugin,
      String... fragments) {
    for (String fragment : fragments) {
      assertThat(reference.getResponseDataAsString()).as("reference echo").contains(fragment);
      assertThat(plugin.getResponseDataAsString()).as("plugin echo").contains(fragment);
    }
  }

  public static void assertPostBodyContainsBoth(HTTPSampleResult reference, HTTPSampleResult plugin,
      String bodyFragment) {
    String refBody = bodyAfterHeaders(reference.getResponseDataAsString());
    String pluginBody = bodyAfterHeaders(plugin.getResponseDataAsString());
    assertThat(refBody).contains(bodyFragment);
    assertThat(pluginBody).contains(bodyFragment);
  }

  static String bodyAfterHeaders(String echo) {
    String normalized = echo.replace("\r\n", "\n");
    int divider = normalized.indexOf("\n\n");
    return divider >= 0 ? normalized.substring(divider + 2) : normalized;
  }

  static String multipartFieldValue(String multipartBody, String fieldName) {
    String normalized = multipartBody.replace("\r\n", "\n");
    String marker = "name=\"" + fieldName + "\"";
    int fieldStart = normalized.indexOf(marker);
    if (fieldStart < 0) {
      return "";
    }
    int transferEncoding = normalized.indexOf("Content-Transfer-Encoding: 8bit", fieldStart);
    int searchFrom = transferEncoding >= 0 ? transferEncoding : fieldStart;
    int lineEnd = normalized.indexOf('\n', searchFrom);
    if (lineEnd < 0) {
      return "";
    }
    int valueStart = lineEnd + 1;
    if (valueStart < normalized.length() && normalized.charAt(valueStart) == '\n') {
      valueStart++;
    }
    int valueEnd = normalized.indexOf("\n--", valueStart);
    String value = valueEnd >= 0 ? normalized.substring(valueStart, valueEnd)
        : normalized.substring(valueStart);
    return value.replace("\n", "").trim();
  }

  static Map<String, String> parseUrlEncodedBody(String body) {
    Map<String, String> fields = new LinkedHashMap<>();
    if (body == null || body.isEmpty()) {
      return fields;
    }
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String name = urlDecodeComponent(pair.substring(0, eq));
      String value = urlDecodeComponent(pair.substring(eq + 1));
      fields.put(name, value);
    }
    return fields;
  }

  private static String urlDecodeComponent(String component) {
    return URLDecoder.decode(component, StandardCharsets.UTF_8);
  }

  public static void setupMirrorVariables() {
    JMeterUtils.setLocale(Locale.ENGLISH);
    JMeterVariables vars = new JMeterVariables();
    vars.put("title_prefix", "a testÅ");
    vars.put("description_suffix", "the_end");
    JMeterContextService.getContext().setVariables(vars);
    JMeterContextService.getContext().setSamplingStarted(true);
  }

  public static void clearMirrorVariables() {
    JMeterContextService.getContext().setVariables(null);
    JMeterContextService.getContext().setSamplingStarted(false);
  }

  public static void replaceSamplerVariables(HTTP2Sampler sampler) throws Exception {
    ValueReplacer replacer = new ValueReplacer();
    replacer.setUserDefinedVariables(new TestPlan().getUserDefinedVariables());
    replacer.replaceValues(sampler);
  }

  public static void assertRawPostBodiesMatch(HTTPSampleResult reference, HTTPSampleResult plugin) {
    String refBody = bodyAfterHeaders(reference.getResponseDataAsString());
    String pluginBody = bodyAfterHeaders(plugin.getResponseDataAsString());
    assertThat(pluginBody).as("raw POST body echo").isEqualTo(refBody);
  }

  public static void assertUrlEncodedBodiesMatch(HTTPSampleResult reference,
      HTTPSampleResult plugin) {
    String refBody = bodyAfterHeaders(reference.getResponseDataAsString());
    String pluginBody = bodyAfterHeaders(plugin.getResponseDataAsString());
    assertThat(parseUrlEncodedBody(pluginBody))
        .as("urlencoded POST body")
        .isEqualTo(parseUrlEncodedBody(refBody));
  }

  public static void assertMultipartFieldValuesMatch(HTTPSampleResult reference,
      HTTPSampleResult plugin, String fieldName) {
    String refBody = bodyAfterHeaders(reference.getResponseDataAsString());
    String pluginBody = bodyAfterHeaders(plugin.getResponseDataAsString());
    assertThat(multipartFieldValue(pluginBody, fieldName))
        .as("multipart field %s", fieldName)
        .isEqualTo(multipartFieldValue(refBody, fieldName));
  }

  public static URL mirrorUrl(int port) throws Exception {
    return new URL("http", "localhost", port, MIRROR_PATH);
  }

  public static URL mirrorUrl(int port, String path) throws Exception {
    return new URL("http", "localhost", port, path);
  }

  public record MirrorParityResult(HTTPSampleResult reference, HTTPSampleResult plugin) {
    public void assertRequestLineMatches() {
      assertSameRequestLine(reference, plugin);
    }

    public void assertEchoContains(String... fragments) {
      assertEchoContainsBoth(reference, plugin, fragments);
    }

    public void assertPostBodyContains(String fragment) {
      assertPostBodyContainsBoth(reference, plugin, fragment);
    }

    public void assertMultipartFieldValuesMatch(String fieldName) {
      HttpMirrorParitySupport.assertMultipartFieldValuesMatch(reference, plugin, fieldName);
    }

    public void assertPostBodyMatches() {
      HttpMirrorParitySupport.assertUrlEncodedBodiesMatch(reference, plugin);
    }

    public void assertRawPostBodyMatches() {
      HttpMirrorParitySupport.assertRawPostBodiesMatch(reference, plugin);
    }
  }
}
