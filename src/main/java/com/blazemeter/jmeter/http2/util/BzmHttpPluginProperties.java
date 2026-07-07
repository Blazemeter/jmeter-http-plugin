package com.blazemeter.jmeter.http2.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Reads JVM properties for this plugin using {@code blazemeter.http.*} first, then legacy
 * {@code HTTP2Sampler.*} and {@code httpJettyClient.*} (same suffix). Pass any accepted form.
 *
 * <p>HTTP Async Controller globals use {@code blazemeter.http.controller.*} first, then legacy
 * {@code http2AsyncController.*} (same suffix). Use {@link #resolveControllerRaw} and related
 * methods for those keys only.
 *
 * <p>When persisting to {@code user.properties} or syncing runtime, only {@code blazemeter.http.*}
 * is written; legacy lines for that setting are removed.
 */
public final class BzmHttpPluginProperties {

  /** Preferred prefix for async-controller-related JMeter properties. */
  public static final String CONTROLLER_PREFERRED_PREFIX = "blazemeter.http.controller.";
  /** Legacy prefix for async-controller-related JMeter properties (backward compatibility). */
  public static final String CONTROLLER_LEGACY_PREFIX = "http2AsyncController.";

  private static final String PREFERRED_PREFIX = "blazemeter.http.";
  private static final String LEGACY_SAMPLER_PREFIX = "HTTP2Sampler.";
  private static final String LEGACY_JETTY_PREFIX = "httpJettyClient.";

  private BzmHttpPluginProperties() {
  }

  private static String canonicalSuffix(String propertyName) {
    if (propertyName.startsWith(PREFERRED_PREFIX)) {
      return propertyName.substring(PREFERRED_PREFIX.length());
    }
    if (propertyName.startsWith(LEGACY_SAMPLER_PREFIX)) {
      return propertyName.substring(LEGACY_SAMPLER_PREFIX.length());
    }
    if (propertyName.startsWith(LEGACY_JETTY_PREFIX)) {
      return propertyName.substring(LEGACY_JETTY_PREFIX.length());
    }
    return propertyName;
  }

  private static String preferredKeyForSuffix(String suffix) {
    return PREFERRED_PREFIX + suffix;
  }

  private static String samplerKeyForSuffix(String suffix) {
    return LEGACY_SAMPLER_PREFIX + suffix;
  }

  private static String jettyKeyForSuffix(String suffix) {
    return LEGACY_JETTY_PREFIX + suffix;
  }

  private static String[] allKeysInResolveOrder(String propertyName) {
    String suffix = canonicalSuffix(propertyName);
    return new String[] {
        preferredKeyForSuffix(suffix),
        samplerKeyForSuffix(suffix),
        jettyKeyForSuffix(suffix)
    };
  }

  /** Suffix after {@link #CONTROLLER_PREFERRED_PREFIX} or {@link #CONTROLLER_LEGACY_PREFIX}. */
  public static String controllerCanonicalSuffix(String propertyName) {
    if (propertyName.startsWith(CONTROLLER_PREFERRED_PREFIX)) {
      return propertyName.substring(CONTROLLER_PREFERRED_PREFIX.length());
    }
    if (propertyName.startsWith(CONTROLLER_LEGACY_PREFIX)) {
      return propertyName.substring(CONTROLLER_LEGACY_PREFIX.length());
    }
    return propertyName;
  }

  private static String[] controllerKeysInResolveOrder(String propertyName) {
    String suffix = controllerCanonicalSuffix(propertyName);
    return new String[] {
        CONTROLLER_PREFERRED_PREFIX + suffix,
        CONTROLLER_LEGACY_PREFIX + suffix
    };
  }

  /** True if any recognized async-controller JVM property is set for this setting. */
  public static boolean isControllerDefined(String propertyName) {
    Properties props = JMeterUtils.getJMeterProperties();
    for (String k : controllerKeysInResolveOrder(propertyName)) {
      if (props.containsKey(k)) {
        return true;
      }
    }
    return false;
  }

  /** Tries {@code blazemeter.http.controller.*}, then legacy {@code http2AsyncController.*}. */
  public static String resolveControllerRaw(String propertyName) {
    for (String k : controllerKeysInResolveOrder(propertyName)) {
      String v = JMeterUtils.getProperty(k);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  public static String getControllerPropDefault(String propertyName, String defaultValue) {
    String raw = resolveControllerRaw(propertyName);
    return raw != null ? raw : defaultValue;
  }

  public static boolean getControllerPropDefault(String propertyName, boolean defaultValue) {
    String raw = resolveControllerRaw(propertyName);
    if (raw == null) {
      return defaultValue;
    }
    String trimmed = StringUtils.trimToEmpty(raw);
    if (trimmed.isEmpty()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(trimmed);
  }

  /**
   * Saves using {@code blazemeter.http.controller.*} only, removing that setting under the legacy
   * prefix when present.
   */
  public static void persistControllerUserProperty(File userPropertiesFile, String propertyName,
                                                   String value) throws IOException {
    String suffix = controllerCanonicalSuffix(propertyName);
    String persistedKey = CONTROLLER_PREFERRED_PREFIX + suffix;
    List<Pattern> patterns = new ArrayList<>();
    for (String k : new String[] {persistedKey, CONTROLLER_LEGACY_PREFIX + suffix}) {
      patterns.add(Pattern.compile("^\\s*" + Pattern.quote(k) + "\\s*=.*"));
    }
    List<String> lines =
        userPropertiesFile.isFile()
            ? Files.readAllLines(userPropertiesFile.toPath(), StandardCharsets.ISO_8859_1)
            : new ArrayList<>();
    List<String> kept = new ArrayList<>();
    for (String line : lines) {
      boolean match = false;
      for (Pattern pat : patterns) {
        if (pat.matcher(line).matches()) {
          match = true;
          break;
        }
      }
      if (!match) {
        kept.add(line);
      }
    }
    if (!kept.isEmpty() && !StringUtils.isBlank(kept.get(kept.size() - 1))) {
      kept.add("");
    }
    kept.add(persistedKey + "=" + value);
    File parent = userPropertiesFile.getParentFile();
    if (parent != null) {
      Files.createDirectories(parent.toPath());
    }
    Files.write(userPropertiesFile.toPath(), kept, StandardCharsets.ISO_8859_1);
  }

  /**
   * Sets {@code blazemeter.http.controller.*} and removes the legacy
   * {@code http2AsyncController.*} line.
   */
  public static void syncControllerRuntime(String propertyName, String value) {
    String suffix = controllerCanonicalSuffix(propertyName);
    Properties jmProps = JMeterUtils.getJMeterProperties();
    jmProps.remove(CONTROLLER_LEGACY_PREFIX + suffix);
    JMeterUtils.setProperty(CONTROLLER_PREFERRED_PREFIX + suffix, value);
  }

  /** True if any of the recognized keys for this setting is present in merged properties. */
  public static boolean isDefined(String propertyName) {
    Properties props = JMeterUtils.getJMeterProperties();
    for (String k : allKeysInResolveOrder(propertyName)) {
      if (props.containsKey(k)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tries {@code blazemeter.http.*}, then {@code HTTP2Sampler.*}, then {@code httpJettyClient.*}.
   */
  public static String resolveRaw(String propertyName) {
    for (String k : allKeysInResolveOrder(propertyName)) {
      String v = JMeterUtils.getProperty(k);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  public static String getPropDefault(String propertyName, String defaultValue) {
    String raw = resolveRaw(propertyName);
    return raw != null ? raw : defaultValue;
  }

  public static boolean getPropDefault(String propertyName, boolean defaultValue) {
    String raw = resolveRaw(propertyName);
    if (raw == null) {
      return defaultValue;
    }
    String trimmed = StringUtils.trimToEmpty(raw);
    if (trimmed.isEmpty()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(trimmed);
  }

  /**
   * Saves using {@code blazemeter.http.*} only, removing lines for that setting under any accepted
   * prefix.
   */
  public static void persistUserProperty(File userPropertiesFile, String propertyName, String value)
      throws IOException {
    String suffix = canonicalSuffix(propertyName);
    String persistedKey = preferredKeyForSuffix(suffix);
    List<Pattern> patterns = new ArrayList<>();
    for (String k : new String[] {
        persistedKey, samplerKeyForSuffix(suffix), jettyKeyForSuffix(suffix)
    }) {
      patterns.add(Pattern.compile("^\\s*" + Pattern.quote(k) + "\\s*=.*"));
    }
    List<String> lines =
        userPropertiesFile.isFile()
            ? Files.readAllLines(userPropertiesFile.toPath(), StandardCharsets.ISO_8859_1)
            : new ArrayList<>();
    List<String> kept = new ArrayList<>();
    for (String line : lines) {
      boolean match = false;
      for (Pattern pat : patterns) {
        if (pat.matcher(line).matches()) {
          match = true;
          break;
        }
      }
      if (!match) {
        kept.add(line);
      }
    }
    if (!kept.isEmpty() && !StringUtils.isBlank(kept.get(kept.size() - 1))) {
      kept.add("");
    }
    kept.add(persistedKey + "=" + value);
    File parent = userPropertiesFile.getParentFile();
    if (parent != null) {
      Files.createDirectories(parent.toPath());
    }
    Files.write(userPropertiesFile.toPath(), kept, StandardCharsets.ISO_8859_1);
  }

  /** Sets {@code blazemeter.http.*} and removes legacy keys for the same suffix. */
  public static void syncRuntime(String propertyName, String value) {
    String suffix = canonicalSuffix(propertyName);
    Properties jmProps = JMeterUtils.getJMeterProperties();
    jmProps.remove(samplerKeyForSuffix(suffix));
    jmProps.remove(jettyKeyForSuffix(suffix));
    JMeterUtils.setProperty(preferredKeyForSuffix(suffix), value);
  }
}
