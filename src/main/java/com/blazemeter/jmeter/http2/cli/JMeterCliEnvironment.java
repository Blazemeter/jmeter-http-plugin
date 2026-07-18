package com.blazemeter.jmeter.http2.cli;

import java.io.File;
import java.net.URISyntaxException;
import java.security.CodeSource;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Bootstraps a headless JMeter environment for command-line tools shipped inside this plugin's
 * jar, without requiring the JMeter GUI or the lightweight test emulator used by unit tests.
 *
 * <p>Resolves {@code JMETER_HOME} by locating this jar under {@code <jmeter.home>/lib/ext/},
 * matching how JMeter loads plugins; falls back to the {@code JMETER_HOME} environment variable
 * or the {@code jmeter.home} system property when the jar isn't installed there.
 */
public final class JMeterCliEnvironment {

  private static volatile boolean initialized;

  private JMeterCliEnvironment() {
  }

  public static synchronized void ensureInitialized() {
    if (initialized) {
      return;
    }
    System.setProperty("java.awt.headless", "true");
    if (isNotBlank(JMeterUtils.getJMeterHome())) {
      // Some other bootstrap (JMeter GUI/engine, or the in-process test emulator) already
      // configured JMeterUtils; reloading properties here would discard that configuration.
      initialized = true;
      return;
    }
    String home = resolveJMeterHome();
    JMeterUtils.setJMeterHome(home);
    File propertiesFile = new File(new File(home, "bin"), "jmeter.properties");
    JMeterUtils.loadJMeterProperties(propertiesFile.getAbsolutePath());
    JMeterUtils.initLocale();
    JMeterUtils.initLogging();
    initialized = true;
  }

  static String resolveJMeterHome() {
    String fromProperty = System.getProperty("jmeter.home");
    if (isNotBlank(fromProperty)) {
      return fromProperty.trim();
    }
    String fromEnv = System.getenv("JMETER_HOME");
    if (isNotBlank(fromEnv)) {
      return fromEnv.trim();
    }
    String fromJarLocation = homeFromExtDirJar(locateOwnJarFile());
    if (fromJarLocation != null) {
      return fromJarLocation;
    }
    throw new IllegalStateException(
        "Could not determine JMeter home. This tool expects to run from <jmeter.home>/lib/ext/, "
            + "or with -Djmeter.home=<path> / the JMETER_HOME environment variable set.");
  }

  /**
   * @return {@code <home>} when {@code jarFile} looks like {@code <home>/lib/ext/some.jar},
   *     {@code null} otherwise (e.g. when running from a build output directory).
   */
  static String homeFromExtDirJar(File jarFile) {
    if (jarFile == null) {
      return null;
    }
    File extDir = jarFile.getParentFile();
    if (extDir == null || !"ext".equalsIgnoreCase(extDir.getName())) {
      return null;
    }
    File libDir = extDir.getParentFile();
    if (libDir == null || !"lib".equalsIgnoreCase(libDir.getName())) {
      return null;
    }
    File home = libDir.getParentFile();
    return home == null ? null : home.getAbsolutePath();
  }

  private static File locateOwnJarFile() {
    try {
      CodeSource codeSource = JMeterCliEnvironment.class.getProtectionDomain().getCodeSource();
      if (codeSource == null) {
        return null;
      }
      return new File(codeSource.getLocation().toURI());
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean isNotBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
