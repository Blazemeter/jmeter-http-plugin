package com.blazemeter.jmeter.http2.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.util.ValueReplacer;
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
