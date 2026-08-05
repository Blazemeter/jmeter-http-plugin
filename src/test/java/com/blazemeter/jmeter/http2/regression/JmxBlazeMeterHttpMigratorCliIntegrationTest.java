package com.blazemeter.jmeter.http2.regression;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.JmxBlazeMeterHttpMigrator;
import com.blazemeter.jmeter.http2.sampler.JmxBlazeMeterHttpMigratorCli;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.jorphan.collections.HashTree;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Forks {@link JmxBlazeMeterHttpMigratorCli} as a real OS process against a downloaded JMeter
 * distribution, with this plugin's jar dropped into {@code lib/ext/} exactly as end users would
 * run it: {@code JMeterCliEnvironment} must self-locate {@code JMETER_HOME} from that layout
 * alone, with no {@code JMETER_HOME} env var or {@code -Djmeter.home} set.
 */
public class JmxBlazeMeterHttpMigratorCliIntegrationTest extends HTTP2TestBase {

  private static final long TIMEOUT_MINUTES = 2;

  private static JmeterDistribution distribution;

  @BeforeClass
  public static void setUpClass() throws Exception {
    assumeTrue("Set -Djmeter.regression=true to run JMeter regression tests",
        Boolean.getBoolean("jmeter.regression"));
    distribution = JmeterDistribution.resolve();
    distribution.installPluginJar(JmeterRegressionSupport.resolvePluginJar());
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    if (distribution != null) {
      distribution.removePluginJars();
    }
  }

  @Test
  public void migratesJmxWhenRunFromRealJmeterLibExt() throws Exception {
    Path workDir = Path.of("target", "jmx-migrator-cli-it");
    Files.createDirectories(workDir);
    File sourceJmx = JmeterRegressionSupport.copyResourceToWorkDir("TEST_HTTP.jmx", workDir);
    File targetJmx = workDir.resolve("TEST_HTTP_migrated.jmx").toFile();
    Files.deleteIfExists(targetJmx.toPath());

    ProcessOutput processOutput = runCli(sourceJmx, targetJmx);
    assertEquals("CLI process failed. Output:\n" + processOutput.output,
        0, processOutput.exitCode);
    assertTrue("CLI process did not write " + targetJmx, targetJmx.isFile());

    HashTree migrated = JmxBlazeMeterHttpMigrator.loadTree(targetJmx);
    assertEquals(0, JmxBlazeMeterHttpMigrator.countMigratableSamplers(migrated));
    assertTrue(JmxBlazeMeterHttpMigrator.countHttp2Samplers(migrated) > 0);
  }

  private ProcessOutput runCli(File sourceJmx, File targetJmx)
      throws Exception {
    Path libDir = distribution.getHomeDir().resolve("lib");
    Path extDir = libDir.resolve("ext");
    // "*" is a JVM classpath-wildcard convention, not a real path segment: Path.resolve("*")
    // throws InvalidPathException on Windows, where "*" is a reserved filename character.
    String classpath = libDir + File.separator + "*" + File.pathSeparator
        + extDir + File.separator + "*";

    List<String> command = new ArrayList<>();
    command.add(JmeterRegressionRunner.resolveJavaExecutable());
    command.add("-Djava.awt.headless=true");
    command.add("-cp");
    command.add(classpath);
    command.add(JmxBlazeMeterHttpMigratorCli.class.getName());
    command.add(sourceJmx.getAbsolutePath());
    command.add(targetJmx.getAbsolutePath());

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(distribution.getBinDir().toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException(
          "CLI process timed out after " + TIMEOUT_MINUTES + " minutes. Output:\n" + output);
    }
    return new ProcessOutput(process.exitValue(), output);
  }

  private static final class ProcessOutput {
    private final int exitCode;
    private final String output;

    ProcessOutput(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output;
    }
  }
}
