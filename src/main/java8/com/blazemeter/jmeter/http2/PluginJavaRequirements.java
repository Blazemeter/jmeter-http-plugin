package com.blazemeter.jmeter.http2;

/**
 * Runtime Java version checks for code compiled with an older bytecode level so it can
 * run before the main plugin classes (Java 17+) are loaded.
 */
public final class PluginJavaRequirements {

  public static final int JAVA_VERSION_REQUIRED = 17;

  /** Grep-friendly marker shared with the main plugin classes. */
  public static final String LOG_PREFIX = "[bzm http2 plugin] ";

  private PluginJavaRequirements() {
  }

  public static int getRuntimeMajorVersion() {
    String versionString = System.getProperty("java.version");
    String[] versionElements = versionString.split("\\.");
    return versionElements[0].equals("1")
        ? Integer.parseInt(versionElements[1])
        : Integer.parseInt(versionElements[0]);
  }

  public static boolean isRuntimeSupported() {
    return getRuntimeMajorVersion() >= JAVA_VERSION_REQUIRED;
  }

  public static String unsupportedRuntimeMessage() {
    return LOG_PREFIX
        + "The BlazeMeter HTTP Plugin requires Java "
        + JAVA_VERSION_REQUIRED
        + " or higher (current: "
        + System.getProperty("java.version")
        + "). BlazeMeter HTTP menus and samplers are disabled until you upgrade Java "
        + "and restart JMeter.";
  }
}
