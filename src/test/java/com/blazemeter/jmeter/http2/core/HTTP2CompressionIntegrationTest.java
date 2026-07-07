package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.BINARY_RESPONSE_BODY;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_BROTLI;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_DEFLATE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_GZIP;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_ZSTD;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.brotli.dec.BrotliInputStream;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class HTTP2CompressionIntegrationTest extends HTTP2TestBase {

  private enum ServerMode {
    HTTP2_TLS,
    H2C_CLEAR,
    HTTP1_CLEAR
  }

  private static final String TARGET_DIR = "target";
  private static final String PLUGIN_JAR_PREFIX = "jmeter-bzm-http2";
  private static final int DEFAULT_TEST_PORT = 6666;

  private TeardownableServer server;
  private int serverPort = -1;
  private ServerMode serverMode = ServerMode.HTTP2_TLS;
  private Object client;
  private URLClassLoader shadedClassLoader;
  private String originalSharedThreadPoolProperty;
  private String originalEnableHttp3Property;

  @BeforeClass
  public static void once() {
    HTTP2TestBase.once();
  }

  @Before
  public void setup() throws Exception {
    originalSharedThreadPoolProperty = JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");
    originalEnableHttp3Property = JMeterUtils.getProperty("httpJettyClient.enableHttp3");
    JMeterUtils.setProperty("httpJettyClient.enableHttp3", "false");
    JMeterTestUtils.setupJmeterEnv();
    buildStartedServer(ServerMode.HTTP2_TLS);
    shadedClassLoader = buildShadedClassLoader();
    client = createClient(shadedClassLoader);
  }

  @After
  public void teardown() throws Exception {
    invokeNoArg(client, "stop");
    client = null;
    if (originalSharedThreadPoolProperty == null) {
      JMeterUtils.getJMeterProperties().remove("httpJettyClient.sharedThreadPool");
    } else {
      JMeterUtils.setProperty("httpJettyClient.sharedThreadPool",
          originalSharedThreadPoolProperty);
    }
    if (originalEnableHttp3Property == null) {
      JMeterUtils.getJMeterProperties().remove("httpJettyClient.enableHttp3");
    } else {
      JMeterUtils.setProperty("httpJettyClient.enableHttp3", originalEnableHttp3Property);
    }
    if (server != null) {
      server.stop();
    }
    serverPort = -1;
    if (shadedClassLoader != null) {
      shadedClassLoader.close();
    }
  }

  @Test
  public void shouldDecompressGzipWithShadedJar() throws Exception {
    // Gzip depends on initialized InflaterPool; regressions can show as stream cancel errors.
    HTTPSampleResult result = sampleWithDirectClient("gzip", SERVER_PATH_200_GZIP,
        ServerMode.HTTP1_CLEAR, true);
    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseData()).containsExactly(BINARY_RESPONSE_BODY);
  }

  @Test
  public void shouldDecompressBrotliWithShadedJar() throws Exception {
    HTTPSampleResult result = sampleWithDirectClient("br", SERVER_PATH_200_BROTLI,
      ServerMode.HTTP2_TLS, true);
    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseData()).containsExactly(BINARY_RESPONSE_BODY);
  }

  @Test
  public void shouldDecompressZstdWithShadedJar() throws Exception {
    HTTPSampleResult result = sampleWithDirectClient("zstd", SERVER_PATH_200_ZSTD,
      ServerMode.HTTP2_TLS, true);
    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseData()).containsExactly(BINARY_RESPONSE_BODY);
  }

  @Test
  public void shouldDecompressDeflateWithShadedJar() throws Exception {
    HTTPSampleResult result = sampleWithEncoding("deflate", SERVER_PATH_200_DEFLATE,
      ServerMode.HTTP2_TLS);
    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseData()).containsExactly(BINARY_RESPONSE_BODY);
  }

  private void buildStartedServer(ServerMode mode) throws Exception {
    if (server != null) {
      server.stop();
    }
    ServerBuilder builder = new ServerBuilder();
    if (mode == ServerMode.HTTP2_TLS) {
      builder.withHTTP2()
          .withALPN()
          .withHTTP2C()
          .withSSL();
    } else if (mode == ServerMode.H2C_CLEAR) {
      builder.withHTTP2C().withHTTP1();
    } else {
      builder.withHTTP1();
    }
    server = builder.buildServer();
    server.start();
    serverMode = mode;
    syncServerPort();
  }

  private HTTPSampleResult sampleWithEncoding(String encoding, String path, ServerMode mode)
      throws Exception {
    Map<String, String> originalProps = new HashMap<>();
    if (mode == ServerMode.H2C_CLEAR || mode == ServerMode.HTTP1_CLEAR) {
      originalProps.put("httpJettyClient.enableHttp2",
          JMeterUtils.getProperty("httpJettyClient.enableHttp2"));
      originalProps.put("httpJettyClient.enableHttp1",
          JMeterUtils.getProperty("httpJettyClient.enableHttp1"));
      originalProps.put("httpJettyClient.alpnEnabled",
          JMeterUtils.getProperty("httpJettyClient.alpnEnabled"));
      originalProps.put("httpJettyClient.http2PriorKnowledge",
          JMeterUtils.getProperty("httpJettyClient.http2PriorKnowledge"));
      originalProps.put("httpJettyClient.enableHttp3",
          JMeterUtils.getProperty("httpJettyClient.enableHttp3"));
      if (mode == ServerMode.H2C_CLEAR) {
        JMeterUtils.setProperty("httpJettyClient.enableHttp2", "true");
        JMeterUtils.setProperty("httpJettyClient.enableHttp1", "true");
        JMeterUtils.setProperty("httpJettyClient.alpnEnabled", "false");
        JMeterUtils.setProperty("httpJettyClient.http2PriorKnowledge", "true");
        JMeterUtils.setProperty("httpJettyClient.enableHttp3", "false");
      } else {
        JMeterUtils.setProperty("httpJettyClient.enableHttp2", "false");
        JMeterUtils.setProperty("httpJettyClient.enableHttp1", "true");
        JMeterUtils.setProperty("httpJettyClient.alpnEnabled", "false");
        JMeterUtils.setProperty("httpJettyClient.http2PriorKnowledge", "false");
        JMeterUtils.setProperty("httpJettyClient.enableHttp3", "false");
      }
    }
    if (mode != serverMode) {
      buildStartedServer(mode);
    }
    Object activeClient = client;
    Object tempClient = null;
    if (mode == ServerMode.H2C_CLEAR || mode == ServerMode.HTTP1_CLEAR) {
      tempClient = createClient(shadedClassLoader);
      activeClient = tempClient;
    }
    invokeNoArg(activeClient, "loadProperties");
    try {
      Object sampler = buildSampler(path, HTTPConstants.GET, encoding);
      HTTPSampleResult result = buildBaseResult(createURL(path), HTTPConstants.GET);
      Object sampled = invokeMethod(activeClient, "sample",
          new Class<?>[] {sampler.getClass(), HTTPSampleResult.class, boolean.class, int.class},
          new Object[] {sampler, result, false, 0});
      return (HTTPSampleResult) sampled;
    } finally {
      if (tempClient != null) {
        invokeNoArg(tempClient, "stop");
      }
      if (mode == ServerMode.H2C_CLEAR || mode == ServerMode.HTTP1_CLEAR) {
        restoreProperty("httpJettyClient.enableHttp2", originalProps);
        restoreProperty("httpJettyClient.enableHttp1", originalProps);
        restoreProperty("httpJettyClient.alpnEnabled", originalProps);
        restoreProperty("httpJettyClient.http2PriorKnowledge", originalProps);
        restoreProperty("httpJettyClient.enableHttp3", originalProps);
      }
    }
  }

    private HTTPSampleResult sampleWithDirectClient(String encoding, String path, ServerMode mode,
      boolean manualDecode) throws Exception {
    Map<String, String> originalProps = new HashMap<>();
    if (mode == ServerMode.H2C_CLEAR || mode == ServerMode.HTTP1_CLEAR) {
      originalProps.put("httpJettyClient.enableHttp2",
          JMeterUtils.getProperty("httpJettyClient.enableHttp2"));
      originalProps.put("httpJettyClient.enableHttp1",
          JMeterUtils.getProperty("httpJettyClient.enableHttp1"));
      originalProps.put("httpJettyClient.alpnEnabled",
          JMeterUtils.getProperty("httpJettyClient.alpnEnabled"));
      originalProps.put("httpJettyClient.http2PriorKnowledge",
          JMeterUtils.getProperty("httpJettyClient.http2PriorKnowledge"));
      originalProps.put("httpJettyClient.enableHttp3",
          JMeterUtils.getProperty("httpJettyClient.enableHttp3"));
      if (mode == ServerMode.H2C_CLEAR) {
        JMeterUtils.setProperty("httpJettyClient.enableHttp2", "true");
        JMeterUtils.setProperty("httpJettyClient.enableHttp1", "true");
        JMeterUtils.setProperty("httpJettyClient.alpnEnabled", "false");
        JMeterUtils.setProperty("httpJettyClient.http2PriorKnowledge", "true");
        JMeterUtils.setProperty("httpJettyClient.enableHttp3", "false");
      } else {
        JMeterUtils.setProperty("httpJettyClient.enableHttp2", "false");
        JMeterUtils.setProperty("httpJettyClient.enableHttp1", "true");
        JMeterUtils.setProperty("httpJettyClient.alpnEnabled", "false");
        JMeterUtils.setProperty("httpJettyClient.http2PriorKnowledge", "false");
        JMeterUtils.setProperty("httpJettyClient.enableHttp3", "false");
      }
    }
    if (mode != serverMode) {
      buildStartedServer(mode);
    }
    Object activeClient = client;
    Object tempClient = null;
    if (mode == ServerMode.H2C_CLEAR || mode == ServerMode.HTTP1_CLEAR) {
      tempClient = createClient(shadedClassLoader);
      activeClient = tempClient;
    }
    invokeNoArg(activeClient, "loadProperties");
    try {
      URI uri = createUri(path);
      Object httpClient = selectHttpClientForUri(activeClient, uri);
      if (manualDecode) {
        disableDecoder(httpClient, encoding);
      }
      Object request = invokeMethod(httpClient, "newRequest",
          new Class<?>[] {URI.class}, new Object[] {uri});
      invokeMethod(request, "method", new Class<?>[] {String.class},
          new Object[] {HTTPConstants.GET});
      if (mode == ServerMode.H2C_CLEAR) {
        setRequestVersion(request, "HTTP_2");
      } else if (mode == ServerMode.HTTP1_CLEAR) {
        setRequestVersion(request, "HTTP_1_1");
      }
      setRequestHeader(request, "Accept-Encoding", encoding);
      Object response = invokeMethod(request, "send", new Class<?>[0], new Object[0]);
      int status = (int) invokeMethod(response, "getStatus", new Class<?>[0], new Object[0]);
      byte[] content = (byte[]) invokeMethod(response, "getContent",
          new Class<?>[0], new Object[0]);
      if (manualDecode) {
        content = decode(encoding, content);
      }
      HTTPSampleResult result = buildBaseResult(createURL(path), HTTPConstants.GET);
      result.setResponseCode(Integer.toString(status));
      result.setSuccessful(status >= 200 && status < 400);
      result.setResponseData(content);
      return result;
    } finally {
      if (tempClient != null) {
        invokeNoArg(tempClient, "stop");
      }
      if (mode == ServerMode.H2C_CLEAR || mode == ServerMode.HTTP1_CLEAR) {
        restoreProperty("httpJettyClient.enableHttp2", originalProps);
        restoreProperty("httpJettyClient.enableHttp1", originalProps);
        restoreProperty("httpJettyClient.alpnEnabled", originalProps);
        restoreProperty("httpJettyClient.http2PriorKnowledge", originalProps);
        restoreProperty("httpJettyClient.enableHttp3", originalProps);
      }
    }
  }

  private void restoreProperty(String key, Map<String, String> originalProps) {
    String value = originalProps.get(key);
    if (value == null) {
      JMeterUtils.getJMeterProperties().remove(key);
    } else {
      JMeterUtils.setProperty(key, value);
    }
  }

  private Object buildSampler(String path, String method, String encoding) throws Exception {
    Class<?> samplerClass = shadedClassLoader.loadClass(
        "com.blazemeter.jmeter.http2.sampler.HTTP2Sampler");
    Object sampler = samplerClass.getDeclaredConstructor().newInstance();
    invokeMethod(sampler, "setDomain", new Class<?>[] {String.class},
        new Object[] {HOST_NAME});
    invokeMethod(sampler, "setPort", new Class<?>[] {int.class},
        new Object[] {getActivePort()});
    invokeMethod(sampler, "setProtocol", new Class<?>[] {String.class},
        new Object[] {serverMode == ServerMode.HTTP2_TLS
            ? HTTPConstants.PROTOCOL_HTTPS
            : HTTPConstants.PROTOCOL_HTTP});
    invokeMethod(sampler, "setPath", new Class<?>[] {String.class},
        new Object[] {path});
    invokeMethod(sampler, "setMethod", new Class<?>[] {String.class},
        new Object[] {method});

    HeaderManager headerManager = new HeaderManager();
    headerManager.add(new Header("Accept-Encoding", encoding));
    invokeMethod(sampler, "setHeaderManager",
        new Class<?>[] {HeaderManager.class}, new Object[] {headerManager});
    return sampler;
  }

  private static HTTPSampleResult buildBaseResult(URL url, String method) {
    HTTPSampleResult ret = new HTTPSampleResult();
    ret.setURL(url);
    ret.setHTTPMethod(method);
    return ret;
  }

  private URL createURL(String path) throws Exception {
    return createUri(path).toURL();
  }

  private URI createUri(String path) throws URISyntaxException {
    String scheme = serverMode == ServerMode.HTTP2_TLS
        ? HTTPConstants.PROTOCOL_HTTPS
        : HTTPConstants.PROTOCOL_HTTP;
    return new URI(scheme, null, HOST_NAME, getActivePort(), path, null, null);
  }

  private int resolveServerPort() {
    if (server == null || server.getConnectors().length == 0) {
      return DEFAULT_TEST_PORT;
    }
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private void syncServerPort() {
    serverPort = resolveServerPort();
  }

  private int getActivePort() {
    return serverPort > 0 ? serverPort : DEFAULT_TEST_PORT;
  }

  private URLClassLoader buildShadedClassLoader() throws Exception {
    Path pluginJar = requireShadedJar();
    List<URL> jarUrls = new ArrayList<>();
    jarUrls.add(pluginJar.toUri().toURL());
    return new ChildFirstClassLoader(jarUrls.toArray(new URL[0]),
        Thread.currentThread().getContextClassLoader());
  }

  private Object createClient(ClassLoader loader) throws Exception {
    Class<?> clientClass = loader.loadClass("com.blazemeter.jmeter.http2.core.HTTP2JettyClient");
    Object instance = clientClass.getDeclaredConstructor().newInstance();
    invokeNoArg(instance, "start");
    return instance;
  }

  private Object invokeMethod(Object target, String name, Class<?>[] types, Object[] args)
      throws Exception {
    return target.getClass().getMethod(name, types).invoke(target, args);
  }

  private Object selectHttpClientForUri(Object clientInstance, URI uri) throws Exception {
    java.lang.reflect.Method method = clientInstance.getClass()
        .getDeclaredMethod("selectHttpClient", URI.class);
    method.setAccessible(true);
    return method.invoke(clientInstance, uri);
  }

  private void disableDecoder(Object httpClient, String encoding) throws Exception {
    Object factories = invokeMethod(httpClient, "getContentDecoderFactories",
        new Class<?>[0], new Object[0]);
    if (factories == null) {
      return;
    }
    try {
      java.lang.reflect.Method removeMethod = factories.getClass()
          .getMethod("remove", Object.class);
      removeMethod.invoke(factories, encoding);
      return;
    } catch (NoSuchMethodException ignored) {
      // Fall through to clear.
    }

    try {
      java.lang.reflect.Method clearMethod = factories.getClass().getMethod("clear");
      clearMethod.invoke(factories);
    } catch (NoSuchMethodException ignored) {
      // Some implementations might not support clearing.
    }
  }

  private byte[] gunzip(byte[] compressed) throws Exception {
    try (ByteArrayInputStream input = new ByteArrayInputStream(compressed);
         GZIPInputStream gzip = new GZIPInputStream(input);
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      return readAll(gzip, output);
    }
  }

  private byte[] unbrotli(byte[] compressed) throws Exception {
    try (ByteArrayInputStream input = new ByteArrayInputStream(compressed);
         BrotliInputStream brotli = new BrotliInputStream(input);
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      return readAll(brotli, output);
    }
  }

  private byte[] unzstd(byte[] compressed) throws Exception {
    try (ByteArrayInputStream input = new ByteArrayInputStream(compressed);
         ZstdInputStream zstd = new ZstdInputStream(input);
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      return readAll(zstd, output);
    }
  }

  private byte[] decode(String encoding, byte[] content) throws Exception {
    if (content == null) {
      return null;
    }
    String normalized = encoding == null ? "" : encoding.toLowerCase();
    switch (normalized) {
      case "gzip":
        return gunzip(content);
      case "br":
        return unbrotli(content);
      case "zstd":
        return unzstd(content);
      default:
        return content;
    }
  }

  private byte[] readAll(InputStream input, ByteArrayOutputStream output) throws Exception {
    byte[] buffer = new byte[4096];
    int read;
    while ((read = input.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private void setRequestVersion(Object request, String versionName) throws Exception {
    ClassLoader loader = request.getClass().getClassLoader();
    Class<?> httpVersionClass = loader.loadClass(
        "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.http.HttpVersion");
    Object version = Enum.valueOf((Class) httpVersionClass, versionName);
    invokeMethod(request, "version", new Class<?>[] {httpVersionClass},
        new Object[] {version});
  }

  private void setRequestHeader(Object request, String name, String value) throws Exception {
    try {
      invokeMethod(request, "header", new Class<?>[] {String.class, String.class},
          new Object[] {name, value});
      return;
    } catch (NoSuchMethodException ignored) {
      // Fall through to header enum handling.
    }
    ClassLoader loader = request.getClass().getClassLoader();
    try {
      Class<?> httpHeaderClass = loader.loadClass(
          "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.http.HttpHeader");
      Object header = Enum.valueOf((Class) httpHeaderClass,
          name.toUpperCase().replace('-', '_'));
      invokeMethod(request, "header", new Class<?>[] {httpHeaderClass, String.class},
          new Object[] {header, value});
      return;
    } catch (ClassNotFoundException | IllegalArgumentException | NoSuchMethodException ignored) {
      // Fall back to setting headers directly.
    }

    Object headers = invokeMethod(request, "getHeaders", new Class<?>[0], new Object[0]);
    java.lang.reflect.Method putMethod = headers.getClass().getDeclaredMethod(
        "put", String.class, String.class);
    putMethod.setAccessible(true);
    putMethod.invoke(headers, name, value);
  }

  private void invokeNoArg(Object target, String name) throws Exception {
    if (target == null) {
      return;
    }
    target.getClass().getMethod(name).invoke(target);
  }

  private Path requireShadedJar() {
    Path pluginJar = findPluginJar(Paths.get(TARGET_DIR));
    if (pluginJar == null) {
      throw new IllegalStateException(
          "Shaded plugin JAR not found in target/. Ensure shade runs before tests.");
    }
    return pluginJar;
  }

  private Path findPluginJar(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return null;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir,
        PLUGIN_JAR_PREFIX + "*.jar")) {
      for (Path path : stream) {
        String name = path.getFileName().toString();
        if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")) {
          continue;
        }
        return path;
      }
    } catch (Exception ignored) {
      return null;
    }
    return null;
  }

  private static class ChildFirstClassLoader extends URLClassLoader {

    private static final String PLUGIN_PREFIX = "com.blazemeter.jmeter.http2.";

    ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
      super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null && name.startsWith(PLUGIN_PREFIX)) {
          try {
            loaded = findClass(name);
          } catch (ClassNotFoundException ignored) {
            // Fall through to parent.
          }
        }
        if (loaded == null) {
          loaded = super.loadClass(name, false);
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }
  }
}
