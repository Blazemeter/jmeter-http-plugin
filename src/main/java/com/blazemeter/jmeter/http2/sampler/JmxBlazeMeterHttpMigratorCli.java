package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.cli.JMeterCliEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.apache.jorphan.collections.HashTree;

/**
 * Command-line entry point for {@link JmxBlazeMeterHttpMigrator}: migrates stock JMeter HTTP
 * Request samplers to {@link HTTP2Sampler} in one {@code .jmx} file, or in every {@code .jmx}
 * file under a directory.
 *
 * <p>Run from {@code <jmeter.home>/lib/ext/} (where this plugin's jar is installed) with
 * {@code <jmeter.home>/lib/*} and {@code <jmeter.home>/lib/ext/*} on the classpath; see
 * {@code scripts/jmx-migrate.sh} / {@code scripts/jmx-migrate.cmd}.
 */
public final class JmxBlazeMeterHttpMigratorCli {

  static final int EXIT_OK = 0;
  static final int EXIT_USAGE_ERROR = 1;
  static final int EXIT_RUNTIME_ERROR = 2;

  private JmxBlazeMeterHttpMigratorCli() {
  }

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    CliOptions options;
    try {
      options = CliOptions.parse(args);
    } catch (IllegalArgumentException e) {
      err.println("Error: " + e.getMessage());
      printUsage(err);
      return EXIT_USAGE_ERROR;
    }
    if (options.help) {
      printUsage(out);
      return EXIT_OK;
    }
    try {
      JMeterCliEnvironment.ensureInitialized();
      if (options.source.isDirectory()) {
        return runBatch(options, out);
      }
      return runSingleFile(options, out, err);
    } catch (IOException e) {
      err.println("Error: " + e.getMessage());
      return EXIT_RUNTIME_ERROR;
    }
  }

  private static int runSingleFile(CliOptions options, PrintStream out, PrintStream err)
      throws IOException {
    File source = options.source;
    if (!source.isFile()) {
      err.println("Error: source file not found: " + source);
      return EXIT_RUNTIME_ERROR;
    }
    HashTree tree = JmxBlazeMeterHttpMigrator.loadTree(source);
    if (options.dryRun) {
      int count = JmxBlazeMeterHttpMigrator.countMigratableSamplers(tree);
      out.println(count + " sampler(s) would be migrated in " + source);
      return EXIT_OK;
    }
    JmxBlazeMeterHttpMigrator.MigrationResult result =
        JmxBlazeMeterHttpMigrator.migrateTreeWithDetails(tree);
    File destination = options.inPlace ? source : options.target;
    JmxBlazeMeterHttpMigrator.saveTree(tree, destination);
    out.println(result.getReplacedCount() + " sampler(s) migrated: " + source + " -> "
        + destination);
    return EXIT_OK;
  }

  private static int runBatch(CliOptions options, PrintStream out) throws IOException {
    List<Path> jmxFiles = findJmxFiles(options.source.toPath());
    if (jmxFiles.isEmpty()) {
      out.println("No .jmx files found under " + options.source);
      return EXIT_OK;
    }
    int totalReplaced = 0;
    for (Path jmxFile : jmxFiles) {
      HashTree tree = JmxBlazeMeterHttpMigrator.loadTree(jmxFile.toFile());
      if (options.dryRun) {
        int count = JmxBlazeMeterHttpMigrator.countMigratableSamplers(tree);
        totalReplaced += count;
        out.println(count + " sampler(s) would be migrated in " + jmxFile);
        continue;
      }
      JmxBlazeMeterHttpMigrator.MigrationResult result =
          JmxBlazeMeterHttpMigrator.migrateTreeWithDetails(tree);
      File destination = options.inPlace
          ? jmxFile.toFile()
          : resolveBatchDestination(options.source.toPath(), jmxFile, options.target.toPath());
      Files.createDirectories(destination.getAbsoluteFile().getParentFile().toPath());
      JmxBlazeMeterHttpMigrator.saveTree(tree, destination);
      totalReplaced += result.getReplacedCount();
      out.println(result.getReplacedCount() + " sampler(s) migrated: " + jmxFile + " -> "
          + destination);
    }
    out.println((options.dryRun ? "Total would migrate: " : "Total migrated: ") + totalReplaced
        + " sampler(s) across " + jmxFiles.size() + " file(s)");
    return EXIT_OK;
  }

  private static File resolveBatchDestination(Path sourceDir, Path jmxFile, Path targetDir) {
    Path relative = sourceDir.toAbsolutePath().relativize(jmxFile.toAbsolutePath());
    return targetDir.resolve(relative).toFile();
  }

  private static List<Path> findJmxFiles(Path dir) throws IOException {
    List<Path> result = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(dir)) {
      stream.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jmx"))
          .sorted()
          .forEach(result::add);
    }
    return result;
  }

  private static void printUsage(PrintStream out) {
    out.println("Usage:");
    out.println("  migrator <source.jmx> <target.jmx> [--dry-run]");
    out.println("  migrator <source.jmx> --in-place [--dry-run]");
    out.println("  migrator <source-dir> --out <target-dir> [--dry-run]");
    out.println("  migrator <source-dir> --in-place [--dry-run]");
    out.println("  migrator --help");
    out.println();
    out.println("Replaces stock JMeter HTTP Request samplers with the BlazeMeter HTTP");
    out.println("sampler, in a single .jmx file or recursively across a directory.");
    out.println();
    out.println("Options:");
    out.println("  --in-place      overwrite the source file(s) instead of writing elsewhere");
    out.println("  --out <dir>     write migrated files to <dir>, mirroring the source layout");
    out.println("  --dry-run       report how many samplers would migrate, without writing");
    out.println("  --help          show this message");
  }

  private static final class CliOptions {
    private File source;
    private File target;
    private boolean inPlace;
    private boolean dryRun;
    private boolean help;

    static CliOptions parse(String[] args) {
      CliOptions options = new CliOptions();
      List<String> positional = new ArrayList<>();
      int i = 0;
      while (i < args.length) {
        String arg = args[i];
        i++;
        switch (arg) {
          case "--help":
          case "-h":
            options.help = true;
            break;
          case "--in-place":
            options.inPlace = true;
            break;
          case "--dry-run":
            options.dryRun = true;
            break;
          case "--out":
            if (i >= args.length) {
              throw new IllegalArgumentException("--out requires a directory argument");
            }
            options.target = new File(args[i]);
            i++;
            break;
          default:
            positional.add(arg);
        }
      }
      if (options.help) {
        return options;
      }
      if (positional.isEmpty()) {
        throw new IllegalArgumentException("Missing <source> argument");
      }
      options.source = new File(positional.get(0));
      if (positional.size() > 1) {
        if (options.target != null) {
          throw new IllegalArgumentException("Specify either a positional target or --out, "
              + "not both");
        }
        options.target = new File(positional.get(1));
      }
      if (positional.size() > 2) {
        throw new IllegalArgumentException("Unexpected extra argument: " + positional.get(2));
      }
      if (options.inPlace && options.target != null) {
        throw new IllegalArgumentException("--in-place cannot be combined with a target");
      }
      if (!options.dryRun && !options.inPlace && options.target == null) {
        throw new IllegalArgumentException("Specify a target, --in-place, or --dry-run");
      }
      return options;
    }
  }
}
