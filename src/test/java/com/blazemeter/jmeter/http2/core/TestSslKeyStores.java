package com.blazemeter.jmeter.http2.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates ephemeral JKS keystores for SSL handshake simulations.
 */
final class TestSslKeyStores {

  static final String PASSWORD = ServerBuilder.KEYSTORE_PASSWORD;
  private static final String KEYTOOL = resolveKeytoolExecutable();

  private TestSslKeyStores() {
  }

  static Path createJks(String alias, String distinguishedName) throws IOException, InterruptedException {
    Path keystore = Files.createTempDirectory("ssl-sim-").resolve(alias + ".jks");
    runKeytool(List.of(
        KEYTOOL,
        "-genkeypair",
        "-alias", alias,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "365",
        "-keystore", keystore.toAbsolutePath().toString(),
        "-storetype", "JKS",
        "-storepass", PASSWORD,
        "-keypass", PASSWORD,
        "-dname", distinguishedName
    ));
    return keystore;
  }

  private static void runKeytool(List<String> command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IOException("keytool failed (exit " + exitCode + "): " + output);
    }
  }

  private static String resolveKeytoolExecutable() {
    String javaHome = System.getProperty("java.home");
    Path candidate = Path.of(javaHome, "bin", isWindows() ? "keytool.exe" : "keytool");
    if (Files.isRegularFile(candidate)) {
      return candidate.toString();
    }
    return isWindows() ? "keytool.exe" : "keytool";
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

}
