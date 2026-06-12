package com.blazemeter.jmeter.http2.regression;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Forks JMeter in non-GUI mode using the same flags as Apache's BatchTest task. */
public final class JmeterRegressionRunner {

  private static final long DEFAULT_TIMEOUT_MINUTES = Long.parseLong(
      System.getProperty("jmeter.regression.timeoutMinutes", "10"));

  private final JmeterDistribution distribution;
  private final Path workDir;

  public JmeterRegressionRunner(JmeterDistribution distribution, Path workDir) throws IOException {
    this.distribution = distribution;
    this.workDir = workDir;
    Files.createDirectories(workDir);
  }

  public RunResult run(String runId, File jmxFile, File batchProperties, File log4jXml,
      Map<String, String> jmeterArgs) throws IOException, InterruptedException {
    Path runDir = workDir.resolve(runId);
    Files.createDirectories(runDir);

    File logFile = runDir.resolve("jmeter.log").toFile();
    File jtlFile = runDir.resolve("results.jtl").toFile();
    File errFile = runDir.resolve("jmeter.err").toFile();
    Files.deleteIfExists(jtlFile.toPath());
    Files.deleteIfExists(logFile.toPath());
    Files.deleteIfExists(errFile.toPath());

    List<String> command = new ArrayList<>();
    command.add(resolveJavaExecutable());
    command.add("-Xms128m");
    command.add("-Xmx512m");
    command.add("-Djava.awt.headless=true");
    command.add("-Duser.language=en");
    command.add("-Duser.region=en");
    command.add("-Duser.country=US");
    command.add("-cp");
    command.add(distribution.getJmeterJar().getAbsolutePath());
    command.add("org.apache.jmeter.NewDriver");
    command.add("-p");
    command.add(distribution.getBinDir().resolve("jmeter.properties").toString());
    command.add("-q");
    command.add(batchProperties.getAbsolutePath());
    command.add("-n");
    command.add("-t");
    command.add(jmxFile.getAbsolutePath());
    command.add("-i");
    command.add(log4jXml.getAbsolutePath());
    command.add("-j");
    command.add(logFile.getAbsolutePath());
    command.add("-l");
    command.add(jtlFile.getAbsolutePath());
    command.add("-Jmodule=Module");
    command.add("-Gmodule=Module");

    if (jmeterArgs != null) {
      for (Map.Entry<String, String> entry : jmeterArgs.entrySet()) {
        command.add("-J" + entry.getKey() + "=" + entry.getValue());
      }
    }

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(distribution.getBinDir().toFile());
    builder.redirectError(errFile);
    Process process = builder.start();
    boolean finished = process.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
      throw new IOException("JMeter timed out after " + DEFAULT_TIMEOUT_MINUTES + " minutes");
    }

    int exitCode = process.exitValue();
    File xmlResult = findXmlResult(runDir, jmxFile);
    return new RunResult(runId, exitCode, logFile, jtlFile, xmlResult, errFile);
  }

  private File findXmlResult(Path runDir, File jmxFile) {
    String baseName = jmxFile.getName();
    if (baseName.endsWith(".jmx")) {
      baseName = baseName.substring(0, baseName.length() - 4);
    }
    File inRunDir = runDir.resolve(baseName + ".xml").toFile();
    if (inRunDir.isFile()) {
      return inRunDir;
    }
    File inBin = distribution.getBinDir().resolve(baseName + ".xml").toFile();
    if (inBin.isFile()) {
      return inBin;
    }
    File jtl = runDir.resolve("results.jtl").toFile();
    return jtl.isFile() ? jtl : null;
  }

  private static String resolveJavaExecutable() {
    String javaHome = System.getProperty("java.home");
    Path javaBin = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
    return javaBin.toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  public static Map<String, String> referenceHttpClient4Args() {
    Map<String, String> args = new LinkedHashMap<>();
    args.put("jmeter.httpsampler", "HttpClient4");
    return args;
  }

  public static Map<String, String> pluginArgs(RegressionProtocolProfile profile) {
    Map<String, String> args = new LinkedHashMap<>();
    args.putAll(profile.jmeterProperties());
    return args;
  }

  public static boolean logHasErrors(File logFile) throws IOException {
    if (logFile == null || !logFile.isFile() || logFile.length() == 0L) {
      return false;
    }
    String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
    return content.contains("ERROR o.a.j.JMeter:")
        || content.contains("AssertionFailedError");
  }

  public static final class RunResult {
    private final String runId;
    private final int exitCode;
    private final File logFile;
    private final File jtlFile;
    private final File xmlResultFile;
    private final File errFile;

    RunResult(String runId, int exitCode, File logFile, File jtlFile, File xmlResultFile,
        File errFile) {
      this.runId = runId;
      this.exitCode = exitCode;
      this.logFile = logFile;
      this.jtlFile = jtlFile;
      this.xmlResultFile = xmlResultFile;
      this.errFile = errFile;
    }

    public String getRunId() {
      return runId;
    }

    public int getExitCode() {
      return exitCode;
    }

    public File getLogFile() {
      return logFile;
    }

    public File getJtlFile() {
      return jtlFile;
    }

    public File getXmlResultFile() {
      return xmlResultFile;
    }

    public File getErrFile() {
      return errFile;
    }

    /**
     * Per-run {@code -l results.jtl} is preferred over plan {@code ResultCollector} XML in
     * {@code bin/}, which is shared and append-only across runs.
     */
    public File resolveSampleFile() {
      if (jtlFile != null && jtlFile.isFile() && jtlFile.length() > 0L) {
        return jtlFile;
      }
      if (xmlResultFile != null && xmlResultFile.isFile()) {
        return xmlResultFile;
      }
      return jtlFile;
    }
  }
}
