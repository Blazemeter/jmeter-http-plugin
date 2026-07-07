package com.blazemeter.jmeter.http2.gui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point invoked from a shutdown hook in a <strong>separate JVM</strong>, parallel to how
 * <a href="https://github.com/undera/jmeter-plugins-manager">jmeter-plugins-manager</a>'s
 * {@code SafeDeleter} separates plugin I/O / restart from a dying GUI process.
 *
 * <p>All diagnostics for one restart go to one log file ({@code unifiedLog}); this process only
 * appends — the parent clears that file when the restart sequence begins.</p>
 */
public final class BlazeMeterJmeterRestartRelay {

  private BlazeMeterJmeterRestartRelay() {
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      throw new IllegalArgumentException(
          "Expected: <restart-log-path> <argv-file>; got " + args.length + " argument(s)");
    }
    File unifiedLog = new File(args[0]);
    Path argvFile = Path.of(args[1]);
    List<String> lines = Files.readAllLines(argvFile, StandardCharsets.UTF_8);
    List<String> command = new ArrayList<>(lines.size());
    for (String line : lines) {
      String s = trailingNewlinesRemoved(line);
      if (!s.isEmpty()) {
        command.add(s);
      }
    }
    if (command.isEmpty()) {
      try (PrintWriter w =
          new PrintWriter(
              new OutputStreamWriter(
                  new FileOutputStream(unifiedLog, true), StandardCharsets.UTF_8),
              true)) {
        w.println();
        w.println("# ERROR: empty command list argv-file=" + argvFile.toAbsolutePath());
      }
      throw new IllegalStateException("Empty restart command after reading argv file");
    }

    try (PrintWriter w =
        new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(unifiedLog, true), StandardCharsets.UTF_8),
            true)) {
      w.println();
      w.println("# --- relay JVM (second process)");
      for (String part : command) {
        w.println("# relay argv   " + part);
      }
      w.flush();
    }

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(unifiedLog));
    pb.redirectError(ProcessBuilder.Redirect.appendTo(unifiedLog));
    applyWorkingDirectoryIfJarInvocation(pb, command);
    Process p = pb.start();
    try (PrintWriter w =
        new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(unifiedLog, true), StandardCharsets.UTF_8),
            true)) {
      w.println("# jmeterPid=" + p.pid());
      w.flush();
    }
  }

  /** Same idea as launching from {@code JMETER_HOME/bin}. */
  private static void applyWorkingDirectoryIfJarInvocation(
      ProcessBuilder pb, List<String> command) {
    for (int i = 0; i < command.size() - 1; i++) {
      if ("-jar".equals(command.get(i))) {
        File jar = new File(command.get(i + 1));
        File bin = jar.getParentFile();
        if (bin != null && bin.isDirectory()) {
          pb.directory(bin);
        }
        return;
      }
    }
  }

  private static String trailingNewlinesRemoved(String line) {
    String s = line;
    while (!s.isEmpty() && (s.endsWith("\n") || s.endsWith("\r"))) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }
}
