package com.blazemeter.jmeter.http2.regression;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared helpers for JMeter HTTP regression suites. */
public final class JmeterRegressionSupport {

  public static final String RESOURCE_ROOT = "jmeter-regression/5.6.3";

  private JmeterRegressionSupport() {
  }

  public static final String TIER1_TESTS =
      "TEST_HTTP,ResponseDecompression,TestHeaderManager,TestCookieManager";

  /** F4: HTTPS, HTML embedded parser, digest/basic auth (see docs/jmeter-regression.md). */
  public static final String TIER4_TESTS =
      "HTMLParserTestFile_2,TEST_HTTPS,Http4ImplDigestAuth,Http4ImplPreemptiveBasicAuth";

  /** Optional / flaky Apache batch plans (external services; not in default CI). */
  /** HTTP-focused flaky plans (BUG_62847/Bug54685 are JMeter core-only, not HTTP sampler tests). */
  public static final String TIER_FLAKY_TESTS = "TestKeepAlive,TestRedirectionPolicies";

  /** Optional; requires HttpClient4 CPS throttling not yet implemented in the Jetty client. */
  public static final String TIER_FLAKY_OPTIONAL_TESTS = "SlowCharsFeature";

  /**
   * Plans that call third-party hosts; ref/plugin runs are sequential so 5xx vs 2xx drift is
   * environmental, not a sampler parity gap.
   */
  private static final Set<String> EXTERNAL_SERVICE_DRIFT_TESTS = Set.of(
      "Http4ImplDigestAuth",
      "Http4ImplPreemptiveBasicAuth",
      "TestKeepAlive",
      "TestRedirectionPolicies",
      "SlowCharsFeature");

  public static boolean isOptionalUnsupportedPlan(String testBaseName) {
    return "SlowCharsFeature".equals(testBaseName)
        && !Boolean.getBoolean("jmeter.regression.enableSlowChars");
  }

  private static final String DEFAULT_REGRESSION_TESTS = TIER1_TESTS;

  /** Plans that only make sense against HttpClient4 / HTTP/1.1 (keep-alive, Connection: close). */
  private static final Set<String> HTTP1_ONLY_PLANS = Set.of("TestKeepAlive");

  public static boolean isHttp1OnlyPlan(String testBaseName) {
    return HTTP1_ONLY_PLANS.contains(testBaseName);
  }

  public static boolean toleratesExternalServiceDrift(String testBaseName) {
    if (Boolean.getBoolean("jmeter.regression.tolerateExternalServiceDrift")) {
      return true;
    }
    return EXTERNAL_SERVICE_DRIFT_TESTS.contains(testBaseName);
  }

  public static List<String> configuredTestNames() {
    String raw = System.getProperty("jmeter.regression.tests");
    if (raw == null || raw.isBlank()) {
      raw = resolveTestsForTier(System.getProperty("jmeter.regression.tier"));
    }
    if (raw == null || raw.isBlank()) {
      raw = DEFAULT_REGRESSION_TESTS;
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  public static Path regressionResourceRoot() {
    return Path.of("src", "test", "resources", RESOURCE_ROOT);
  }

  public static File copyResourceToWorkDir(String resourceName, Path workDir) throws IOException {
    String resourcePath = RESOURCE_ROOT + "/" + resourceName;
    try (InputStream in = JmeterRegressionSupport.class.getClassLoader()
        .getResourceAsStream(resourcePath)) {
      if (in == null) {
        Path fallback = regressionResourceRoot().resolve(resourceName);
        if (Files.isRegularFile(fallback)) {
          Files.createDirectories(workDir);
          Path target = workDir.resolve(resourceName);
          Files.copy(fallback, target, StandardCopyOption.REPLACE_EXISTING);
          return target.toFile();
        }
        throw new IOException("Regression resource not found: " + resourcePath);
      }
      Files.createDirectories(workDir);
      Path target = workDir.resolve(resourceName);
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      return target.toFile();
    }
  }

  public static File resolveResultXml(JmeterDistribution distribution, Path workDir,
      String testBaseName, String implementationSuffix) {
    String suffixed = testBaseName + "_" + implementationSuffix + ".xml";
    File inBin = distribution.getBinDir().resolve(suffixed).toFile();
    if (inBin.isFile()) {
      return inBin;
    }
    File inWork = workDir.resolve(suffixed).toFile();
    if (inWork.isFile()) {
      return inWork;
    }
    String fixed = testBaseName + ".xml";
    File fixedBin = distribution.getBinDir().resolve(fixed).toFile();
    if (fixedBin.isFile()) {
      return fixedBin;
    }
    File fixedWork = workDir.resolve(fixed).toFile();
    if (fixedWork.isFile()) {
      return fixedWork;
    }
    return null;
  }

  public static File archiveResultXml(File source, Path workDir, String archiveName)
      throws IOException {
    if (source == null || !source.isFile()) {
      return null;
    }
    Files.createDirectories(workDir);
    Path target = workDir.resolve(archiveName);
    Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
    return target.toFile();
  }

  public static void cleanupResultArtifacts(JmeterDistribution distribution, String testBaseName,
      String... suffixes) throws IOException {
    cleanupPlanCollectorArtifacts(distribution, testBaseName);
    for (String suffix : suffixes) {
      deleteIfExists(distribution.getBinDir().resolve(testBaseName + "_" + suffix + ".xml"));
      deleteIfExists(distribution.getBinDir().resolve(testBaseName + "_" + suffix + ".csv"));
      deleteIfExists(distribution.getBinDir().resolve(testBaseName + ".jtl"));
      deleteIfExists(distribution.getBinDir().resolve(testBaseName + ".log"));
    }
  }

  /**
   * Deletes {@code ResultCollector} outputs under JMeter {@code bin/}. Those files are opened in
   * append mode and must be removed before each forked run to avoid duplicated samples.
   */
  public static void cleanupPlanCollectorArtifacts(JmeterDistribution distribution,
      String... planBaseNames) throws IOException {
    Path bin = distribution.getBinDir();
    for (String baseName : planBaseNames) {
      if (baseName == null || baseName.isEmpty()) {
        continue;
      }
      deleteIfExists(bin.resolve(baseName + ".xml"));
      deleteIfExists(bin.resolve(baseName + ".csv"));
    }
  }

  private static void deleteIfExists(Path path) throws IOException {
    Files.deleteIfExists(path);
  }

  /** Locates the plugin jar built by {@code mvn package} under {@code target/}. */
  public static File resolvePluginJar() throws IOException {
    try (var stream = Files.list(Path.of("target"))) {
      return stream
          .filter(p -> p.getFileName().toString().startsWith("jmeter-bzm-http2")
              && p.getFileName().toString().endsWith(".jar")
              && !p.getFileName().toString().contains("original"))
          .map(Path::toFile)
          .findFirst()
          .orElseThrow(() -> new IOException(
              "Plugin jar not found under target/. Run mvn package first."));
    }
  }

  public static String testBaseName(String testName) {
    if (testName.endsWith(".jmx")) {
      return testName.substring(0, testName.length() - 4);
    }
    return testName;
  }

  private static String resolveTestsForTier(String tier) {
    if (tier == null || tier.isBlank()) {
      return null;
    }
    return switch (tier.trim().toLowerCase(Locale.ROOT)) {
      case "1", "tier1", "tier-1" -> TIER1_TESTS;
      case "4", "f4", "tier4", "tier-4" -> TIER4_TESTS;
      case "flaky", "optional" -> TIER_FLAKY_TESTS;
      case "all" -> TIER1_TESTS + "," + TIER4_TESTS + "," + TIER_FLAKY_TESTS;
      default -> null;
    };
  }

  /** Copies plan-specific fixtures into JMeter {@code bin/} (and optionally the case work dir). */
  public static void stageFixtures(String baseName, JmeterDistribution distribution, Path caseDir)
      throws IOException {
    if ("TEST_HTTP".equals(baseName)) {
      stageTestHttpFixtures(distribution, caseDir);
    } else if ("HTMLParserTestFile_2".equals(baseName)) {
      stageHtmlParserFixtures(distribution, caseDir);
    }
  }

  /** Copies fixture files required by {@code TEST_HTTP.jmx} into JMeter {@code bin/} and {@code caseDir}. */
  public static void stageTestHttpFixtures(JmeterDistribution distribution, Path caseDir)
      throws IOException {
    for (String fixture : new String[] {"user.properties", "TEST_GET.jmx"}) {
      copyResourceToWorkDir(fixture, distribution.getBinDir());
      if (caseDir != null) {
        copyResourceToWorkDir(fixture, caseDir);
      }
    }
  }

  /** Copies {@code testfiles/} tree required by {@code HTMLParserTestFile_2.jmx}. */
  public static void stageHtmlParserFixtures(JmeterDistribution distribution, Path caseDir)
      throws IOException {
    Path sourceRoot = regressionResourceRoot().resolve("testfiles");
    if (!Files.isDirectory(sourceRoot)) {
      throw new IOException("HTML parser fixtures missing: " + sourceRoot);
    }
    copyResourceTree(sourceRoot, distribution.getBinDir().resolve("testfiles"));
    if (caseDir != null) {
      copyResourceTree(sourceRoot, caseDir.resolve("testfiles"));
    }
  }

  private static void copyResourceTree(Path sourceRoot, Path targetRoot) throws IOException {
    Files.createDirectories(targetRoot);
    try (var stream = Files.walk(sourceRoot)) {
      for (Path source : stream.toList()) {
        Path relative = sourceRoot.relativize(source);
        Path target = targetRoot.resolve(relative);
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }
}
