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
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JmxBlazeMeterHttpMigratorCliTest extends HTTP2TestBase {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private File sampleJmx;
  private File otherSampleJmx;

  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @Before
  public void setUpStreams() {
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @Before
  public void writeSampleFixtures() throws Exception {
    sampleJmx = tempFolder.newFile("sample.jmx");
    JmxBlazeMeterHttpMigrator.saveTree(buildSamplePlan("first-call", "/a"), sampleJmx);
    otherSampleJmx = tempFolder.newFile("other-sample.jmx");
    JmxBlazeMeterHttpMigrator.saveTree(buildSamplePlan("second-call", "/b"), otherSampleJmx);
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
        new String[] {sampleJmx.getPath(), "out.jmx", "--in-place"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_USAGE_ERROR, exitCode);
  }

  @Test
  public void failsWithUsageErrorWhenNeitherTargetNorInPlaceNorDryRun() {
    int exitCode =
        JmxBlazeMeterHttpMigratorCli.run(new String[] {sampleJmx.getPath()}, out, err);
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
        new String[] {sampleJmx.getPath(), target.getPath()}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);
    assertTrue(target.isFile());

    HashTree migrated = JmxBlazeMeterHttpMigrator.loadTree(target);
    assertEquals(0, JmxBlazeMeterHttpMigrator.countMigratableSamplers(migrated));
    assertTrue(JmxBlazeMeterHttpMigrator.countHttp2Samplers(migrated) > 0);

    HashTree original = JmxBlazeMeterHttpMigrator.loadTree(sampleJmx);
    assertTrue("source file must stay untouched",
        JmxBlazeMeterHttpMigrator.countMigratableSamplers(original) > 0);
  }

  @Test
  public void migratesSingleFileInPlace() throws Exception {
    File source = tempFolder.newFile("in-place.jmx");
    Files.copy(sampleJmx.toPath(), source.toPath(),
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
    Files.copy(sampleJmx.toPath(), source.toPath(),
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
    Files.copy(sampleJmx.toPath(), new File(sourceDir, "sample.jmx").toPath());
    Files.copy(otherSampleJmx.toPath(), new File(nested, "other-sample.jmx").toPath());

    File targetDir = new File(tempFolder.getRoot(), "target");
    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {sourceDir.getPath(), "--out", targetDir.getPath()}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);

    File migratedSample = new File(targetDir, "sample.jmx");
    File migratedOther = new File(new File(targetDir, "nested"), "other-sample.jmx");
    assertTrue(migratedSample.isFile());
    assertTrue(migratedOther.isFile());
    assertTrue(JmxBlazeMeterHttpMigrator.countHttp2Samplers(
        JmxBlazeMeterHttpMigrator.loadTree(migratedSample)) > 0);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("Total migrated:"));
  }

  @Test
  public void dryRunOverDirectoryReportsTotalsWithoutWriting() throws Exception {
    File sourceDir = tempFolder.newFolder("source-dry");
    Files.copy(sampleJmx.toPath(), new File(sourceDir, "sample.jmx").toPath());

    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {sourceDir.getPath(), "--dry-run"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("Total would migrate:"));
    assertFalse(new File(sourceDir, "sample.jmx.bak").exists());
  }

  @Test
  public void reportsWhenDirectoryHasNoJmxFiles() throws Exception {
    File emptyDir = tempFolder.newFolder("empty");
    int exitCode = JmxBlazeMeterHttpMigratorCli.run(
        new String[] {emptyDir.getPath(), "--in-place"}, out, err);
    assertEquals(JmxBlazeMeterHttpMigratorCli.EXIT_OK, exitCode);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("No .jmx files found"));
  }

  private static HashTree buildSamplePlan(String samplerName, String path) {
    HashTree tree = new ListedHashTree();
    HTTPSamplerProxy sampler = new HTTPSamplerProxy();
    sampler.setName(samplerName);
    sampler.setDomain("example.org");
    sampler.setPath(path);
    sampler.setMethod("GET");
    sampler.setArguments(new Arguments());
    // A real .jmx always has guiclass set (by the GUI when the element is created);
    // SaveService.loadTree NPEs reading it back otherwise.
    sampler.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS,
        "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
    tree.add(sampler);
    return tree;
  }
}
