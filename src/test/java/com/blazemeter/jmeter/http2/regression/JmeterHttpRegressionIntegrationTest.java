package com.blazemeter.jmeter.http2.regression;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.JmxBlazeMeterHttpMigrator;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.jorphan.collections.HashTree;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Runs Apache JMeter {@code bin/testfiles} plans against HttpClient4 (reference) and migrated
 * {@code HTTP2Sampler} (plugin), comparing sample results semantically.
 */
@RunWith(Parameterized.class)
public class JmeterHttpRegressionIntegrationTest extends HTTP2TestBase {

  private static final String REF_IMPL = "HttpClient4";
  private static final String PLUGIN_IMPL = "BzmHttp";

  private static JmeterDistribution distribution;
  private static JmeterRegressionRunner runner;
  private static Path workRoot;
  private static File pluginJar;
  private static boolean http3Enabled;

  private final String testName;
  private final RegressionProtocolProfile protocolProfile;

  public JmeterHttpRegressionIntegrationTest(String testName,
      RegressionProtocolProfile protocolProfile) {
    this.testName = testName;
    this.protocolProfile = protocolProfile;
  }

  @Parameterized.Parameters(name = "{0}-{1}")
  public static List<Object[]> data() {
    List<Object[]> rows = new ArrayList<>();
    RegressionProtocolProfile protocolFilter = resolveProtocolFilter();
    for (String test : JmeterRegressionSupport.configuredTestNames()) {
      String baseName = JmeterRegressionSupport.testBaseName(test);
      for (RegressionProtocolProfile profile : RegressionProtocolProfile.values()) {
        if (profile == RegressionProtocolProfile.HTTP3 && !Boolean.getBoolean("it.http3")) {
          continue;
        }
        if (profile != RegressionProtocolProfile.HTTP1_ONLY
            && JmeterRegressionSupport.isHttp1OnlyPlan(baseName)) {
          continue;
        }
        if (protocolFilter != null && profile != protocolFilter) {
          continue;
        }
        rows.add(new Object[] {test, profile});
      }
    }
    return rows;
  }

  private static RegressionProtocolProfile resolveProtocolFilter() {
    String value = System.getProperty("jmeter.regression.protocol");
    if (value == null || value.isBlank()) {
      return null;
    }
    return RegressionProtocolProfile.fromSystemProperty();
  }

  @BeforeClass
  public static void setUpClass() throws Exception {
    assumeTrue("Set -Djmeter.regression=true to run JMeter regression tests",
        Boolean.getBoolean("jmeter.regression"));
    http3Enabled = Boolean.getBoolean("it.http3");
    distribution = JmeterDistribution.resolve();
    workRoot = Path.of("target", "jmeter-regression-work");
    Files.createDirectories(workRoot);
    runner = new JmeterRegressionRunner(distribution, workRoot);
    pluginJar = JmeterRegressionSupport.resolvePluginJar();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    if (distribution != null) {
      distribution.removePluginJars();
    }
  }

  @Test
  public void referenceAndPluginProduceEquivalentSamples() throws Exception {
    if (protocolProfile == RegressionProtocolProfile.HTTP3 && !http3Enabled) {
      return;
    }

    String baseName = JmeterRegressionSupport.testBaseName(testName);
    assumeTrue("Plan requires -Djmeter.regression.enableSlowChars=true (CPS throttling not in Jetty yet)",
        !JmeterRegressionSupport.isOptionalUnsupportedPlan(baseName));
    Path caseDir = workRoot.resolve(baseName + "-" + protocolProfile.getId());
    Files.createDirectories(caseDir);

    File sourceJmx = JmeterRegressionSupport.copyResourceToWorkDir(testName + ".jmx", caseDir);
    File batchProps = JmeterRegressionSupport.copyResourceToWorkDir(
        "jmeter-batch.properties", caseDir);
    File log4jXml = JmeterRegressionSupport.copyResourceToWorkDir("log4j2-batch.xml", caseDir);

    String migratedBaseName = baseName + "_bzm";
    JmeterRegressionSupport.cleanupResultArtifacts(distribution, baseName, REF_IMPL, PLUGIN_IMPL);
    JmeterRegressionSupport.cleanupPlanCollectorArtifacts(distribution, baseName, migratedBaseName);
    JmeterRegressionSupport.stageFixtures(baseName, distribution, caseDir);

    Map<String, String> refArgs = JmeterRegressionRunner.referenceHttpClient4Args();
    distribution.removePluginJars();
    JmeterRegressionRunner.RunResult refRun = runner.run(
        baseName + "-ref-" + protocolProfile.getId(),
        sourceJmx,
        batchProps,
        log4jXml,
        refArgs);

    assertEquals("Reference JMeter run failed, see " + refRun.getLogFile(),
        0, refRun.getExitCode());
    assertTrue("Reference log has errors: " + refRun.getLogFile(),
        !JmeterRegressionRunner.logHasErrors(refRun.getLogFile()));

    File refSamples = refRun.resolveSampleFile();
    assertNotNull("Reference sample file not found for " + baseName, refSamples);
    assertTrue("Reference sample file missing: " + refSamples, refSamples.isFile());
    File archivedRef = JmeterRegressionSupport.archiveResultXml(
        refSamples, caseDir, "reference-samples.xml");
    List<SampleRecord> referenceSamples = JtlSampleLoader.load(archivedRef);

    JmeterRegressionSupport.cleanupPlanCollectorArtifacts(distribution, baseName, migratedBaseName);

    File migratedJmx = caseDir.resolve(migratedBaseName + ".jmx").toFile();
    HashTree tree = JmxBlazeMeterHttpMigrator.loadTree(sourceJmx);
    int replaced = JmxBlazeMeterHttpMigrator.migrateTree(tree);
    assertTrue("Expected HTTP samplers to migrate in " + testName, replaced > 0);
    JmxBlazeMeterHttpMigrator.saveTree(tree, migratedJmx);

    Map<String, String> pluginArgs = JmeterRegressionRunner.pluginArgs(protocolProfile);
    pluginArgs.put("jmeter.httpsampler", PLUGIN_IMPL);

    distribution.installPluginJar(pluginJar);
    JmeterRegressionRunner.RunResult pluginRun = runner.run(
        baseName + "-plugin-" + protocolProfile.getId(),
        migratedJmx,
        batchProps,
        log4jXml,
        pluginArgs);

    assertEquals("Plugin JMeter run failed, see " + pluginRun.getLogFile(),
        0, pluginRun.getExitCode());
    assertTrue("Plugin log has errors: " + pluginRun.getLogFile(),
        !JmeterRegressionRunner.logHasErrors(pluginRun.getLogFile()));

    File pluginSamplesFile = pluginRun.resolveSampleFile();
    assertNotNull("Plugin sample file not found for " + baseName, pluginSamplesFile);
    assertTrue("Plugin sample file missing: " + pluginSamplesFile, pluginSamplesFile.isFile());
    List<SampleRecord> pluginSamples = JtlSampleLoader.load(pluginSamplesFile);

    HttpsampleResultComparator.ComparisonResult comparison =
        HttpsampleResultComparator.compare(referenceSamples, pluginSamples,
            JmeterRegressionSupport.toleratesExternalServiceDrift(baseName));
    assertTrue("Sample mismatch for " + testName + " [" + protocolProfile.getId() + "]: "
        + comparison.formattedDiff(), comparison.isEqual());
  }
}
