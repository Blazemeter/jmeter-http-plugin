package com.blazemeter.jmeter.http2.regression;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.FileUtils;

/**
 * Locates or downloads an Apache JMeter binary distribution for forked regression runs.
 */
public final class JmeterDistribution {

  private static final String VERSION = System.getProperty(
      "jmeter.regression.version", "5.6.3");
  private static final String DOWNLOAD_URL = "https://archive.apache.org/dist/jmeter/binaries/"
      + "apache-jmeter-" + VERSION + ".zip";

  private final Path homeDir;
  private final Path binDir;
  private final File jmeterJar;

  private JmeterDistribution(Path homeDir) {
    this.homeDir = homeDir;
    this.binDir = homeDir.resolve("bin");
    this.jmeterJar = binDir.resolve("ApacheJMeter.jar").toFile();
  }

  public static JmeterDistribution resolve() throws IOException {
    String explicit = System.getProperty("jmeter.home");
    if (explicit != null && !explicit.trim().isEmpty()) {
      Path home = Paths.get(explicit.trim());
      JmeterDistribution dist = new JmeterDistribution(home);
      if (!dist.jmeterJar.isFile()) {
        throw new IOException("jmeter.home does not contain bin/ApacheJMeter.jar: " + home);
      }
      return dist;
    }

    Path baseDir = Paths.get("target", "jmeter-dist-" + VERSION).toAbsolutePath();
    Path home = provisionHome(baseDir);
    JmeterDistribution dist = new JmeterDistribution(home);
    if (!dist.jmeterJar.isFile()) {
      throw new IOException("Failed to provision JMeter " + VERSION + " under " + baseDir);
    }
    return dist;
  }

  public Path getHomeDir() {
    return homeDir;
  }

  public Path getBinDir() {
    return binDir;
  }

  public File getJmeterJar() {
    return jmeterJar;
  }

  public void installPluginJar(File pluginJar) throws IOException {
    Path extDir = homeDir.resolve("lib").resolve("ext");
    Files.createDirectories(extDir);
    Files.copy(pluginJar.toPath(), extDir.resolve(pluginJar.getName()),
        StandardCopyOption.REPLACE_EXISTING);
  }

  public void removePluginJars() throws IOException {
    Path extDir = homeDir.resolve("lib").resolve("ext");
    if (!Files.isDirectory(extDir)) {
      return;
    }
    try (Stream<Path> stream = Files.list(extDir)) {
      stream.filter(path -> path.getFileName().toString().startsWith("jmeter-bzm-http"))
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException e) {
              throw new IllegalStateException("Could not delete " + path, e);
            }
          });
    }
  }

  private static Path provisionHome(Path baseDir) throws IOException {
    Path existing = findJmeterHomeIfPresent(baseDir);
    if (existing != null) {
      return existing;
    }
    Files.createDirectories(baseDir);
    Path zipPath = baseDir.resolve("apache-jmeter-" + VERSION + ".zip");
    if (!Files.isRegularFile(zipPath)) {
      download(zipPath);
    }
    Path extractRoot = baseDir.resolve("extract");
    if (Files.exists(extractRoot)) {
      FileUtils.deleteDirectory(extractRoot.toFile());
    }
    unzip(zipPath, extractRoot);
    Path extractedHome = findJmeterHome(extractRoot);
    return extractedHome;
  }

  private static Path findJmeterHomeIfPresent(Path baseDir) throws IOException {
    if (!Files.isDirectory(baseDir)) {
      return null;
    }
    Path fromExtract = baseDir.resolve("extract");
    if (Files.isDirectory(fromExtract)) {
      try {
        return findJmeterHome(fromExtract);
      } catch (IOException ignored) {
        return null;
      }
    }
    try {
      return findJmeterHome(baseDir);
    } catch (IOException ignored) {
      return null;
    }
  }

  private static void download(Path destination) throws IOException {
    URL url = URI.create(DOWNLOAD_URL).toURL();
    try (InputStream in = url.openStream()) {
      Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void unzip(Path zipFile, Path destination) throws IOException {
    Files.createDirectories(destination);
    try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        Path outPath = destination.resolve(entry.getName()).normalize();
        if (!outPath.startsWith(destination)) {
          throw new IOException("Zip entry outside target dir: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(outPath);
        } else {
          Files.createDirectories(outPath.getParent());
          Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
        }
        zis.closeEntry();
      }
    }
  }

  private static Path findJmeterHome(Path extractRoot) throws IOException {
    try (Stream<Path> stream = Files.walk(extractRoot, 3)) {
      return stream
          .filter(path -> path.getFileName().toString().equals("ApacheJMeter.jar"))
          .map(path -> path.getParent().getParent())
          .findFirst()
          .orElseThrow(() -> new IOException("ApacheJMeter.jar not found under " + extractRoot));
    }
  }

  static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(path)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      });
    }
  }
}
