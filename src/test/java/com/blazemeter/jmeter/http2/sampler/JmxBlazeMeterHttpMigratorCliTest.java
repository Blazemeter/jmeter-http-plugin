package com.blazemeter.jmeter.http2.sampler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jorphan.collections.HashTree;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JmxBlazeMeterHttpMigratorCliTest extends HTTP2TestBase {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private static File testHttpJmx;
  private static File testGetJmx;

  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeClass
  public static void locateFixtures() {
    testHttpJmx =
        Path.of("src", "test", "resources", "jmeter-regression", "5.6.3", "TEST_HTTP.jmx")
            .toFile();
    testGetJmx =
        Path.of("src", "test", "resources", "jmeter-regression", "5.6.3", "TEST_GET.jmx")
            .toFile();
    assertTrue(testHttpJmx.isFile());
    assertTrue(testGetJmx.isFile());
  }

  @Before
  public void setUpStreams() {
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @Test
  public void printsUsageAndSucceedsOnHelp() {
    int exitCode = JmxBlazeMeterHttpMigratorCli.run(new String[] {"--help"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("Usage:"));
  }

  @Test
  public void failsWithUsageErrorWhenNoArguments() {
    int exitCode = JmxBlazeMeterHttpMigratorCli.run(new String[0], out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_USAGE_ERROR, exitCode);
    assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("Missing"));
  }

  @Test
  public void failsWithUsageErrorWhenTargetAndInPlaceConflict() {
    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {testHttpJmx.getPath(), "out.jmx", "--in-place"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_USAGE_ERROR, exitCode);
  }

  @Test
  public void failsWithUsageErrorWhenNeitherTargetNorInPlaceNorDryRun() {
    int exitCode =
        JmxBlazeMeterHttpMigratorCli.run(new String[] {testHttpJmx.getPath()}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_USAGE_ERROR, exitCode);
  }

  @Test
  public void failsWithRuntimeErrorWhenSourceFileMissing() {
    File missing = new File(tempFolder.getRoot(), "missing.jmx");
    int exitCode =
        JmxBlazeMeterHttpMigratorCli.run(
            new String[] {missing.getPath(), "--in-place"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_RUNTIME_ERROR, exitCode);
  }

  @Test
  public void migratesSingleFileToTarget() throws Exception {
    File target = new File(tempFolder.getRoot(), "migrated.jmx");
    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {testHttpJmx.getPath(), target.getPath()}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);
    assertTrue(target.isFile());

    HashTree migrated = JmxBlazeMeterHttpMigrator.loadTree(target);
    assertEquals(0, JmxBlazeMeterHttpMigrator.countMigratableSamplers(migrated));
    assertTrue(JmxBlazeMeterHttpMigrator.countHttp2Samplers(migrated) > 0);

    HashTree original = JmxBlazeMeterHttpMigrator.loadTree(testHttpJmx);
    assertTrue("source file must stay untouched",
        JmxBlazeMeterHttpMigrator.countMigratableSamplers(original) > 0);
  }

  @Test
  public void migratesSingleFileInPlace() throws Exception {
    File source = tempFolder.newFile("in-place.jmx");
    Files.copy(testHttpJmx.toPath(), source.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {source.getPath(), "--in-place"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);

    HashTree migrated = JmxBlazeMeterHttpMigrator.loadTree(source);
    assertEquals(0, JmxBlazeMeterHttpMigrator.countMigratableSamplers(migrated));
    assertTrue(JmxBlazeMeterHttpMigrator.countHttp2Samplers(migrated) > 0);
  }

  @Test
  public void dryRunReportsCountWithoutWriting() throws Exception {
    File source = tempFolder.newFile("dry-run.jmx");
    Files.copy(testHttpJmx.toPath(), source.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    long beforeModified = source.lastModified();
    long beforeLength = source.length();

    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {source.getPath(), "--dry-run"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("would be migrated"));
    assertEquals(beforeLength, source.length());
    assertEquals(beforeModified, source.lastModified());
  }

  @Test
  public void migratesDirectoryRecursivelyToOutputDirectory() throws Exception {
    File sourceDir = tempFolder.newFolder("source");
    File nested = new File(sourceDir, "nested");
    assertTrue(nested.mkdirs());
    Files.copy(testHttpJmx.toPath(), new File(sourceDir, "TEST_HTTP.jmx").toPath());
    Files.copy(testGetJmx.toPath(), new File(nested, "TEST_GET.jmx").toPath());

    File targetDir = new File(tempFolder.getRoot(), "target");
    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {sourceDir.getPath(), "--out", targetDir.getPath()}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);

    File migratedHttp = new File(targetDir, "TEST_HTTP.jmx");
    File migratedGet = new File(new File(targetDir, "nested"), "TEST_GET.jmx");
    assertTrue(migratedHttp.isFile());
    assertTrue(migratedGet.isFile());
    assertTrue(JmxBlazeMeterHttpMigrator.countHttp2Samplers(
        JmxBlazeMeterHttpMigrator.loadTree(migratedHttp)) > 0);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("Total migrated:"));
  }

  @Test
  public void dryRunOverDirectoryReportsTotalsWithoutWriting() throws Exception {
    File sourceDir = tempFolder.newFolder("source-dry");
    Files.copy(testHttpJmx.toPath(), new File(sourceDir, "TEST_HTTP.jmx").toPath());

    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {sourceDir.getPath(), "--dry-run"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("Total would migrate:"));
    assertFalse(new File(sourceDir, "TEST_HTTP.jmx.bak").exists());
  }

  @Test
  public void reportsWhenDirectoryHasNoJmxFiles() throws Exception {
    File emptyDir = tempFolder.newFolder("empty");
    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {emptyDir.getPath(), "--in-place"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("No .jmx files found"));
  }
}
