package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.swing.ImageIcon;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Integration test to verify that all required dependencies are bundled in the
 * shaded plugin JAR (single-jar distribution).
 * 
 * This test simulates the JMeter runtime environment by:
 * 1. Verifying the shaded plugin JAR is present
 * 2. Verifying critical classes can be loaded from the shaded JAR
 * 3. Verifying HttpClient can be instantiated and started with only those dependencies
 * 
 * This helps catch issues like missing transitive dependencies that work in tests
 * (where Maven includes all transitive deps) but fail in JMeter.
 */
public class HTTP2DependenciesIntegrationTest extends HTTP2TestBase {

  private static final String TARGET_DIR = "target";
  private static final String PLUGIN_JAR_PREFIX = "jmeter-bzm-http2";
  private static final Set<String> SHADED_CRITICAL_CLASSES = new HashSet<>(Arrays.asList(
    // Plugin class
    "com.blazemeter.jmeter.http2.core.HTTP2JettyClient",
    // Core Jetty classes (relocated)
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.client.HttpClient",
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.http.HttpFields",
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.io.ByteBufferPool",
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.io.ArrayByteBufferPool",
    // HTTP/2 classes (relocated)
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.http2.client.HTTP2Client",
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.http2.client.transport"
      + ".ClientConnectionFactoryOverHTTP2",
    // Compression classes (relocated)
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.compression.Compression",
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.client"
      + ".HttpClient$CompressionContentDecoderFactory",
    // Jetty compression client factory (relocated)
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.compression.client"
      + ".CompressionContentDecoderFactory",
    // Brotli compression (relocated)
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.compression.brotli"
      + ".BrotliCompression",
    // Zstandard compression (relocated)
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.compression.zstandard"
      + ".ZstandardCompression",
    // Gzip compression (relocated)
    "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.compression.gzip"
      + ".GzipCompression",
    // Zstd decoder (not relocated, per zstd-jni limitation)
    "com.github.luben.zstd.ZstdInputStream"
  ));

  private static final Set<String> SHADED_COMPRESSION_CLASS_RESOURCES = new HashSet<>(
      Arrays.asList(
          "com/blazemeter/jmeter/http2/shaded/org/eclipse/jetty/compression/Compression.class",
          "com/blazemeter/jmeter/http2/shaded/org/eclipse/jetty/compression/gzip"
              + "/GzipCompression.class"
      ));

  private static final String SHADED_HTTP3_CLIENT_RESOURCE =
      "com/blazemeter/jmeter/http2/shaded/org/eclipse/jetty/http3/client/HTTP3Client.class";
  private static final String SHADED_QUIC_CLIENT_RESOURCE =
      "com/blazemeter/jmeter/http2/shaded/org/eclipse/jetty/quic/client/QuicClientConnector.class";

  /**
   * Brotli4j native libraries under {@code lib/<os>-<arch>/}; must be present for every
   * platform we ship, independent of the OS used to run {@code mvn package}.
   */
  private static final List<String> SHADED_BROTLI_NATIVE_RESOURCES = Arrays.asList(
      "lib/linux-x86_64/libbrotli.so",
      "lib/linux-aarch64/libbrotli.so",
      "lib/windows-x86_64/brotli.dll",
      "lib/windows-aarch64/brotli.dll",
      "lib/osx-x86_64/libbrotli.dylib",
      "lib/osx-aarch64/libbrotli.dylib"
  );

  private static final String BZM_COMMONS_PATH = "com/blazemeter/jmeter/commons/";
  private static final String SHADED_BZM_COMMONS_PATH =
      "com/blazemeter/jmeter/http2/shaded/" + BZM_COMMONS_PATH;
  private static final List<String> BZM_COMMONS_CLASSES = Arrays.asList(
      "BlazemeterLabsLogo", "ThemeUtils", "ThemedIcon");
  private static final String BZM_LOGO_RESOURCE = "blazemeter-by-perforce-logo.png";
  private static final String SAMPLER_GUI_CLASS_RESOURCE =
      "com/blazemeter/jmeter/http2/sampler/gui/HTTP2SamplerGui.class";

  @BeforeClass
  public static void once() {
    HTTP2TestBase.once();
  }

  @Test
  public void shouldHaveShadedJarInTarget() {
    Path pluginJar = findPluginJar(Paths.get(TARGET_DIR));
    assertThat(pluginJar)
        .as("Shaded plugin JAR should exist in target/")
        .isNotNull()
        .exists();
  }

  @Test
  public void shouldLoadCriticalClassesFromJmeterTestLib() throws Exception {
    Path pluginJar = requireShadedJar();

    List<URL> jarUrls = new ArrayList<>();
    jarUrls.add(pluginJar.toUri().toURL());

    URLClassLoader jmeterClassLoader = new URLClassLoader(
        jarUrls.toArray(new URL[0]),
        null // No parent classloader - simulates JMeter's isolated classpath
    );

    try {
      for (String className : SHADED_CRITICAL_CLASSES) {
        try {
          Class<?> clazz = jmeterClassLoader.loadClass(className);
          assertNotNull(
              String.format("Class '%s' should be loadable from JMeter test lib JARs", className),
              clazz
          );
        } catch (ClassNotFoundException e) {
          // Some classes might be optional (e.g., HTTP/3 classes if not available)
          if (className.contains("http3") || className.contains("quic")) {
            // HTTP/3 classes are optional - skip if not found
            continue;
          }
          // For critical classes, fail the test
          fail(String.format(
              "Critical class '%s' not found in JMeter test lib JARs. " +
              "This will cause NoClassDefFoundError in JMeter runtime. " +
              "Missing dependency: %s",
              className, e.getMessage()
          ));
        }
      }
    } finally {
      jmeterClassLoader.close();
    }
  }

  @Test
  public void shouldStartHttpClientWithJmeterTestLibDependencies() throws Exception {
    Path pluginJar = requireShadedJar();

    List<URL> jarUrls = new ArrayList<>();
    jarUrls.add(pluginJar.toUri().toURL());

    URLClassLoader jmeterClassLoader = new URLClassLoader(
        jarUrls.toArray(new URL[0]),
        Thread.currentThread().getContextClassLoader()
    );

    try {
        String httpClientClassName =
          "com.blazemeter.jmeter.http2.shaded.org.eclipse.jetty.client.HttpClient";
      Class<?> httpClientClass = jmeterClassLoader.loadClass(httpClientClassName);

      // Try to instantiate HttpClient (this will trigger doStart() which requires Compression class)
      // We use reflection to avoid compile-time dependency issues
      Object httpClient = httpClientClass.getDeclaredConstructor().newInstance();

      assertNotNull("HttpClient should be instantiable", httpClient);

      // Try to start the client (this is where NoClassDefFoundError occurred before)
      try {
        httpClientClass.getMethod("start").invoke(httpClient);

        // If we get here, HttpClient started successfully
        // Stop it to clean up
        try {
          httpClientClass.getMethod("stop").invoke(httpClient);
        } catch (Exception e) {
          // Ignore stop errors in test cleanup
        }
      } catch (Exception e) {
        // Check if it's the Compression class error
        Throwable cause = e.getCause();
        if (cause != null && cause.getClass().getName().equals("java.lang.NoClassDefFoundError")) {
          String message = cause.getMessage();
          if (message != null && message.contains("org/eclipse/jetty/compression/Compression")) {
            fail(String.format(
                "HttpClient.start() failed with NoClassDefFoundError for Compression class. " +
                "This indicates missing compression dependencies. " +
                "Error: %s",
                message
            ));
          }
        }
        // Re-throw other exceptions
        throw new RuntimeException("HttpClient.start() failed", e);
      }
    } finally {
      jmeterClassLoader.close();
    }
  }

  @Test
  public void shouldHaveCompressionDependencies() {
    Path pluginJar = requireShadedJar();

    for (String resource : SHADED_COMPRESSION_CLASS_RESOURCES) {
      assertJarContainsClass(pluginJar, resource, "Shaded jar should include " + resource);
    }
  }

  @Test
  public void shouldHaveBrotliDependency() {
    Path pluginJar = requireShadedJar();

    assertJarContainsClass(pluginJar,
      "com/blazemeter/jmeter/http2/shaded/org/eclipse/jetty/compression/brotli/"
        + "BrotliCompression.class",
      "Shaded jar should include Jetty Brotli compression class");

    assertJarContainsClass(pluginJar,
      "com/aayushatharva/brotli4j/decoder/BrotliInputStream.class",
      "Shaded jar should include Brotli4j decoder class");
  }

  @Test
  public void shouldBundleBrotliNativesForAllSupportedPlatforms() {
    Path pluginJar = requireShadedJar();

    for (String resource : SHADED_BROTLI_NATIVE_RESOURCES) {
      assertJarContainsClass(pluginJar, resource,
          "Shaded jar must include Brotli4j native for all shipped platforms: " + resource);
    }
  }

  @Test
  public void shouldHaveZstdDependency() {
    Path pluginJar = requireShadedJar();

    assertJarContainsClass(pluginJar,
        "com/blazemeter/jmeter/http2/shaded/org/eclipse/jetty/compression/zstandard/"
            + "ZstandardCompression.class",
        "Shaded jar should include Jetty Zstandard compression class");

    assertJarContainsClass(pluginJar,
        "com/github/luben/zstd/ZstdInputStream.class",
        "Shaded jar should include Zstd decoder class");
  }

  @Test
  public void shouldNotRelocateZstdClasses() {
    Path pluginJar = requireShadedJar();

    assertJarDoesNotContainClass(pluginJar,
        "com/blazemeter/jmeter/http2/shaded/com/github/luben/zstd/ZstdInputStream.class",
        "Shaded jar must not relocate zstd-jni classes");
  }

  @Test
  public void shouldHaveHttp3Dependencies() {
    Path pluginJar = requireShadedJar();

    boolean hasHttp3Client = jarContainsEntry(pluginJar, SHADED_HTTP3_CLIENT_RESOURCE);
    Assume.assumeTrue("HTTP/3 classes not packaged in shaded jar", hasHttp3Client);
    assertJarContainsClass(pluginJar, SHADED_HTTP3_CLIENT_RESOURCE,
        "Shaded jar should include " + SHADED_HTTP3_CLIENT_RESOURCE);
    if (jarContainsEntry(pluginJar, SHADED_QUIC_CLIENT_RESOURCE)) {
      assertJarContainsClass(pluginJar, SHADED_QUIC_CLIENT_RESOURCE,
          "Shaded jar should include " + SHADED_QUIC_CLIENT_RESOURCE);
    }
  }

  /*
   Other BlazeMeter plugins ship their own jmeter-bzm-commons in JMeter's lib/, which is loaded
   before lib/ext/. Only a relocated copy keeps the sampler GUI on the version this plugin ships.
   */
  @Test
  public void shouldBundleBzmCommonsOnlyRelocated() {
    Path pluginJar = requireShadedJar();

    for (String className : BZM_COMMONS_CLASSES) {
      assertJarContainsClass(pluginJar, SHADED_BZM_COMMONS_PATH + className + ".class",
          "Shaded jar should include relocated bzm-commons class " + className);
      assertJarDoesNotContainClass(pluginJar, BZM_COMMONS_PATH + className + ".class",
          "Shaded jar must not include unrelocated bzm-commons class " + className);
    }
    for (String theme : Arrays.asList("light-theme", "dark-theme")) {
      assertJarContainsClass(pluginJar, theme + "/" + BZM_LOGO_RESOURCE,
          "Shaded jar should keep bzm-commons icons at the jar root: " + theme);
    }
  }

  @Test
  public void shouldReferenceRelocatedBzmCommonsFromSamplerGui() throws IOException {
    Path pluginJar = requireShadedJar();

    byte[] guiClass;
    try (JarFile jarFile = new JarFile(pluginJar.toFile())) {
      JarEntry entry = jarFile.getJarEntry(SAMPLER_GUI_CLASS_RESOURCE);
      assertNotNull("Shaded jar should include " + SAMPLER_GUI_CLASS_RESOURCE, entry);
      try (InputStream in = jarFile.getInputStream(entry)) {
        guiClass = readAll(in);
      }
    }

    assertThat(hasUtf8Constant(guiClass, SHADED_BZM_COMMONS_PATH + "BlazemeterLabsLogo"))
        .as("HTTP2SamplerGui should use the relocated BlazemeterLabsLogo")
        .isTrue();
    assertThat(hasUtf8Constant(guiClass, BZM_COMMONS_PATH + "BlazemeterLabsLogo"))
        .as("HTTP2SamplerGui must not reference the unrelocated BlazemeterLabsLogo")
        .isFalse();
  }

  @Test
  public void shouldLoadIconsWithRelocatedBzmCommons() throws Exception {
    Path pluginJar = requireShadedJar();
    URL pluginJarUrl = pluginJar.toUri().toURL();

    // The parent provides JMeter, which ThemeUtils asks for the active look and feel.
    try (URLClassLoader jmeterClassLoader = new URLClassLoader(new URL[] {pluginJarUrl},
        Thread.currentThread().getContextClassLoader())) {
      Class<?> themedIcon = jmeterClassLoader.loadClass(
          SHADED_BZM_COMMONS_PATH.replace('/', '.') + "ThemedIcon");
      assertThat(themedIcon.getProtectionDomain().getCodeSource().getLocation())
          .as("Relocated ThemedIcon should come from the shaded jar")
          .isEqualTo(pluginJarUrl);

      ImageIcon logo = (ImageIcon) themedIcon.getMethod("fromResourceName", String.class)
          .invoke(null, BZM_LOGO_RESOURCE);
      assertThat(logo.getIconWidth())
          .as("Relocated ThemedIcon should load " + BZM_LOGO_RESOURCE)
          .isPositive();
    }
  }

  private static boolean hasUtf8Constant(byte[] classBytes, String value) {
    // A CONSTANT_Utf8 entry is tag 1, a two-byte length, then the bytes: matching the whole
    // entry keeps the unrelocated name from matching inside the relocated one.
    byte[] text = value.getBytes(StandardCharsets.UTF_8);
    byte[] entry = new byte[text.length + 3];
    entry[0] = 1;
    entry[1] = (byte) (text.length >> 8);
    entry[2] = (byte) text.length;
    System.arraycopy(text, 0, entry, 3, text.length);
    outer:
    for (int i = 0; i <= classBytes.length - entry.length; i++) {
      for (int j = 0; j < entry.length; j++) {
        if (classBytes[i + j] != entry[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private Path requireShadedJar() {
    Path pluginJar = findPluginJar(Paths.get(TARGET_DIR));
    if (pluginJar == null) {
      fail("Shaded plugin JAR not found in target/. Ensure shade runs before tests.");
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
    } catch (IOException ignored) {
      return null;
    }
    return null;
  }

  private void assertJarContainsClass(Path jarPath, String resourceName, String message) {
    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
      assertThat(jarFile.getEntry(resourceName))
          .as(message)
          .isNotNull();
    } catch (IOException e) {
      fail("Failed to open JAR for validation: " + jarPath + " (" + e.getMessage() + ")");
    }
  }

  private void assertJarDoesNotContainClass(Path jarPath, String resourceName, String message) {
    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
      assertThat(jarFile.getEntry(resourceName))
          .as(message)
          .isNull();
    } catch (IOException e) {
      fail("Failed to open JAR for validation: " + jarPath + " (" + e.getMessage() + ")");
    }
  }

  private boolean jarContainsEntry(Path jarPath, String resourceName) {
    try (JarFile jarFile = new JarFile(jarPath.toFile())) {
      return jarFile.getEntry(resourceName) != null;
    } catch (IOException e) {
      fail("Failed to open JAR for validation: " + jarPath + " (" + e.getMessage() + ")");
      return false;
    }
  }
}
