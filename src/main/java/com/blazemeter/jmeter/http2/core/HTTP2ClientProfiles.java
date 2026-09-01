package com.blazemeter.jmeter.http2.core;

import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HTTP2ClientProfiles {

  public static final String PROFILE_BROWSER_LIKE = "browser-like";
  public static final String PROFILE_BROWSER_LIKE_CUSTOM = "browser-like-custom";
  public static final String PROFILE_BROWSER_COMPATIBLE = "browser-compatible";
  public static final String PROFILE_LEGACY = "legacy";
  public static final String DEFAULT_PROFILE_ID = PROFILE_BROWSER_LIKE;
  public static final long DEFAULT_HTTP3_BROKEN_COOLDOWN_MS = 300000;
  public static final long DEFAULT_HTTP1_ONLY_COOLDOWN_MS = 300000;
  public static final long DEFAULT_H2C_CACHE_TTL_MS = 300000;
  public static final long DEFAULT_HAPPY_EYEBALLS_DELAY_MS = 250;

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2ClientProfiles.class);
  private static final String PROFILE_PROPERTY = "httpJettyClient.profile";
  private static final String PROFILE_PREFIX = "blazemeter.http.profiles.";
  private static final String PROFILE_FILE_PROPERTY = "blazemeter.http.profiles.file";
  private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("[a-z0-9_-]+");
  private static final Set<String> BUILT_IN_PROFILE_IDS = new HashSet<>();
  private static final Map<String, ProfileDefinition> BUILT_IN_PROFILES = new LinkedHashMap<>();
  private static final Set<String> PROFILE_FIELDS = new HashSet<>();

  static {
    BUILT_IN_PROFILES.put(PROFILE_BROWSER_LIKE, ProfileDefinition.builder(PROFILE_BROWSER_LIKE)
        .label("Browser-like (Most Browsers)")
        .enableHttp3(true)
        .enableHttp2(true)
        .enableHttp1(true)
        .alpnEnabled(true)
        .fallbackEnabled(true)
        .protocolErrorFallbackEnabled(true)
        .altSvcCacheEnabled(true)
        .http1OnlyCacheEnabled(true)
        .h2cCacheEnabled(true)
        .http2PriorKnowledgeEnabled(false)
        .h2cUpgradeEnabled(false)
        .happyEyeballsDelayMs(DEFAULT_HAPPY_EYEBALLS_DELAY_MS)
        .http3BrokenCooldownMs(DEFAULT_HTTP3_BROKEN_COOLDOWN_MS)
        .http1OnlyCooldownMs(DEFAULT_HTTP1_ONLY_COOLDOWN_MS)
        .h2cCacheTtlMs(DEFAULT_H2C_CACHE_TTL_MS)
        .build());
    BUILT_IN_PROFILES.put(PROFILE_BROWSER_COMPATIBLE,
        ProfileDefinition.builder(PROFILE_BROWSER_COMPATIBLE)
            .label("Browser-compatible (Other Browsers)")
            .enableHttp3(true)
            .enableHttp2(true)
            .enableHttp1(true)
            .alpnEnabled(true)
            .fallbackEnabled(true)
            .protocolErrorFallbackEnabled(true)
            .altSvcCacheEnabled(true)
            .http1OnlyCacheEnabled(true)
            .h2cCacheEnabled(true)
            .http2PriorKnowledgeEnabled(false)
            .h2cUpgradeEnabled(false)
            .happyEyeballsDelayMs(0L)
            .http3BrokenCooldownMs(DEFAULT_HTTP3_BROKEN_COOLDOWN_MS)
            .http1OnlyCooldownMs(DEFAULT_HTTP1_ONLY_COOLDOWN_MS)
            .h2cCacheTtlMs(DEFAULT_H2C_CACHE_TTL_MS)
            .build());
    BUILT_IN_PROFILES.put(PROFILE_LEGACY, ProfileDefinition.builder(PROFILE_LEGACY)
        .label("Legacy / Older Systems")
        .enableHttp3(false)
        .enableHttp2(false)
        .enableHttp1(true)
        .alpnEnabled(false)
        .fallbackEnabled(false)
        .protocolErrorFallbackEnabled(false)
        .altSvcCacheEnabled(false)
        .http1OnlyCacheEnabled(false)
        .h2cCacheEnabled(true)
        .http2PriorKnowledgeEnabled(false)
        .h2cUpgradeEnabled(true)
        .happyEyeballsDelayMs(0L)
        .http3BrokenCooldownMs(0L)
        .http1OnlyCooldownMs(0L)
        .h2cCacheTtlMs(DEFAULT_H2C_CACHE_TTL_MS)
        .build());
    BUILT_IN_PROFILES.put(PROFILE_BROWSER_LIKE_CUSTOM,
        ProfileDefinition.builder(PROFILE_BROWSER_LIKE_CUSTOM)
            .label("Browser-like (Custom)")
            .parentId(PROFILE_BROWSER_LIKE)
            .build());
    BUILT_IN_PROFILE_IDS.addAll(BUILT_IN_PROFILES.keySet());
    Collections.addAll(PROFILE_FIELDS, "label", "extends", "enableHttp3", "enableHttp2",
        "enableHttp1", "alpnEnabled", "fallbackEnabled", "protocolErrorFallbackEnabled",
        "altSvcCacheEnabled", "http1OnlyCacheEnabled", "h2cCacheEnabled",
        "http2PriorKnowledge", "http2PriorKnowledgeEnabled", "h2cUpgradeEnabled",
        "http1Upgrade", "happyEyeballsDelayMs", "http3BrokenCooldownMs",
        "http1OnlyCooldownMs", "h2cCacheTtlMs");
  }

  private HTTP2ClientProfiles() {
  }

  public static List<HTTP2ClientProfile> availableProfiles() {
    Map<String, ProfileDefinition> definitions = loadProfileDefinitions();
    List<HTTP2ClientProfile> profiles = new ArrayList<>();
    for (String profileId : definitions.keySet()) {
      HTTP2ClientProfile profile = resolveFromDefinitions(profileId, definitions);
      if (profile != null) {
        profiles.add(profile);
      }
    }
    return profiles;
  }

  public static HTTP2ClientProfile resolve(String profileId) {
    String selected = StringUtils.trimToNull(profileId);
    if (selected == null) {
      selected = BzmHttpPluginProperties.getPropDefault(PROFILE_PROPERTY, DEFAULT_PROFILE_ID);
    }
    selected = normalizeProfileId(selected);
    Map<String, ProfileDefinition> definitions = loadProfileDefinitions();
    HTTP2ClientProfile profile = resolveFromDefinitions(selected, definitions);
    if (profile != null) {
      return profile;
    }
    LOG.warn("Unknown or invalid HTTP profile '{}'; falling back to '{}'",
        selected, DEFAULT_PROFILE_ID);
    return resolveFromDefinitions(DEFAULT_PROFILE_ID, definitions);
  }

  public static String normalizeProfileId(String profileId) {
    String selected = StringUtils.trimToEmpty(profileId).toLowerCase(Locale.ROOT);
    return selected.isEmpty() ? DEFAULT_PROFILE_ID : selected;
  }

  public static boolean isSamplerCustomProfile(String profileId) {
    return PROFILE_BROWSER_LIKE_CUSTOM.equals(normalizeProfileId(profileId));
  }

  private static HTTP2ClientProfile resolveFromDefinitions(
      String profileId, Map<String, ProfileDefinition> definitions) {
    ProfileDefinition definition =
        resolveDefinition(profileId, definitions, new HashSet<>());
    return definition != null ? definition.toProfile(profileId) : null;
  }

  private static ProfileDefinition resolveDefinition(String profileId,
                                                     Map<String, ProfileDefinition> definitions,
                                                     Set<String> stack) {
    String normalizedProfileId = normalizeProfileId(profileId);
    if (!stack.add(normalizedProfileId)) {
      LOG.warn("Circular HTTP profile inheritance detected at '{}'", normalizedProfileId);
      return null;
    }
    ProfileDefinition definition = definitions.get(normalizedProfileId);
    if (definition == null) {
      return null;
    }
    ProfileDefinition resolved = definition;
    if (!StringUtils.isBlank(definition.parentId)) {
      ProfileDefinition parent = resolveDefinition(definition.parentId, definitions, stack);
      if (parent == null) {
        LOG.warn("HTTP profile '{}' extends unknown profile '{}'",
            normalizedProfileId, definition.parentId);
        return null;
      }
      resolved = parent.merge(definition);
    }
    stack.remove(normalizedProfileId);
    return resolved;
  }

  private static Map<String, ProfileDefinition> loadProfileDefinitions() {
    Map<String, ProfileDefinition> definitions = new LinkedHashMap<>(BUILT_IN_PROFILES);
    Properties profileProperties = new Properties();
    profileProperties.putAll(loadFileProfileProperties());
    profileProperties.putAll(JMeterUtils.getJMeterProperties());
    Map<String, ProfileDefinition.Builder> customBuilders = new HashMap<>();
    for (String key : new TreeSet<>(profileProperties.stringPropertyNames())) {
      if (!key.startsWith(PROFILE_PREFIX) || PROFILE_FILE_PROPERTY.equals(key)) {
        continue;
      }
      ProfileProperty profileProperty = parseProfileProperty(key);
      if (profileProperty == null) {
        continue;
      }
      if (BUILT_IN_PROFILE_IDS.contains(profileProperty.profileId)) {
        LOG.warn("Ignoring custom HTTP profile property for built-in profile '{}': {}",
            profileProperty.profileId, key);
        continue;
      }
      ProfileDefinition.Builder builder = customBuilders.computeIfAbsent(
          profileProperty.profileId, ProfileDefinition::builder);
      applyProfileProperty(builder, profileProperty.field, profileProperties.getProperty(key), key);
    }
    for (String profileId : new TreeSet<>(customBuilders.keySet())) {
      ProfileDefinition definition = customBuilders.get(profileId).defaultParentIfMissing().build();
      definitions.put(profileId, definition);
    }
    return definitions;
  }

  private static Properties loadFileProfileProperties() {
    Properties properties = new Properties();
    String file = BzmHttpPluginProperties.resolveRaw(PROFILE_FILE_PROPERTY);
    if (StringUtils.isBlank(file)) {
      return properties;
    }
    Path path = Paths.get(file.trim());
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException e) {
      LOG.warn("Could not load HTTP profile definitions from '{}'", path, e);
    }
    return properties;
  }

  private static ProfileProperty parseProfileProperty(String key) {
    String suffix = key.substring(PROFILE_PREFIX.length());
    int separator = suffix.indexOf('.');
    if (separator <= 0 || separator >= suffix.length() - 1) {
      LOG.warn("Ignoring malformed HTTP profile property '{}'", key);
      return null;
    }
    String profileId = normalizeProfileId(suffix.substring(0, separator));
    if (!PROFILE_ID_PATTERN.matcher(profileId).matches()) {
      LOG.warn("Ignoring HTTP profile property with invalid profile id '{}': {}",
          profileId, key);
      return null;
    }
    String field = suffix.substring(separator + 1);
    if (!PROFILE_FIELDS.contains(field)) {
      LOG.warn("Ignoring HTTP profile property with unknown field '{}': {}", field, key);
      return null;
    }
    return new ProfileProperty(profileId, field);
  }

  private static void applyProfileProperty(ProfileDefinition.Builder builder, String field,
                                           String value, String key) {
    if ("label".equals(field)) {
      builder.label(StringUtils.trimToNull(value));
    } else if ("extends".equals(field)) {
      builder.parentId(normalizeProfileId(value));
    } else if ("enableHttp3".equals(field)) {
      builder.enableHttp3(parseBoolean(value, key));
    } else if ("enableHttp2".equals(field)) {
      builder.enableHttp2(parseBoolean(value, key));
    } else if ("enableHttp1".equals(field)) {
      builder.enableHttp1(parseBoolean(value, key));
    } else if ("alpnEnabled".equals(field)) {
      builder.alpnEnabled(parseBoolean(value, key));
    } else if ("fallbackEnabled".equals(field)) {
      builder.fallbackEnabled(parseBoolean(value, key));
    } else if ("protocolErrorFallbackEnabled".equals(field)) {
      builder.protocolErrorFallbackEnabled(parseBoolean(value, key));
    } else if ("altSvcCacheEnabled".equals(field)) {
      builder.altSvcCacheEnabled(parseBoolean(value, key));
    } else if ("http1OnlyCacheEnabled".equals(field)) {
      builder.http1OnlyCacheEnabled(parseBoolean(value, key));
    } else if ("h2cCacheEnabled".equals(field)) {
      builder.h2cCacheEnabled(parseBoolean(value, key));
    } else if ("http2PriorKnowledge".equals(field)
        || "http2PriorKnowledgeEnabled".equals(field)) {
      builder.http2PriorKnowledgeEnabled(parseBoolean(value, key));
    } else if ("h2cUpgradeEnabled".equals(field) || "http1Upgrade".equals(field)) {
      builder.h2cUpgradeEnabled(parseBoolean(value, key));
    } else if ("happyEyeballsDelayMs".equals(field)) {
      builder.happyEyeballsDelayMs(parseLong(value, key));
    } else if ("http3BrokenCooldownMs".equals(field)) {
      builder.http3BrokenCooldownMs(parseLong(value, key));
    } else if ("http1OnlyCooldownMs".equals(field)) {
      builder.http1OnlyCooldownMs(parseLong(value, key));
    } else if ("h2cCacheTtlMs".equals(field)) {
      builder.h2cCacheTtlMs(parseLong(value, key));
    }
  }

  private static Boolean parseBoolean(String value, String key) {
    String normalized = StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    if ("true".equals(normalized)) {
      return Boolean.TRUE;
    }
    if ("false".equals(normalized)) {
      return Boolean.FALSE;
    }
    LOG.warn("Ignoring HTTP profile boolean property '{}' with invalid value '{}'", key, value);
    return null;
  }

  private static Long parseLong(String value, String key) {
    String trimmed = StringUtils.trimToEmpty(value);
    try {
      long parsed = Long.parseLong(trimmed);
      if (parsed < 0) {
        LOG.warn("Ignoring HTTP profile timing property '{}' with negative value '{}'",
            key, value);
        return null;
      }
      return parsed;
    } catch (NumberFormatException e) {
      LOG.warn("Ignoring HTTP profile timing property '{}' with invalid value '{}'", key, value);
      return null;
    }
  }

  private static final class ProfileProperty {
    private final String profileId;
    private final String field;

    private ProfileProperty(String profileId, String field) {
      this.profileId = profileId;
      this.field = field;
    }
  }

  private static final class ProfileDefinition {
    private final String id;
    private final String label;
    private final String parentId;
    private final Boolean enableHttp3;
    private final Boolean enableHttp2;
    private final Boolean enableHttp1;
    private final Boolean alpnEnabled;
    private final Boolean fallbackEnabled;
    private final Boolean protocolErrorFallbackEnabled;
    private final Boolean altSvcCacheEnabled;
    private final Boolean http1OnlyCacheEnabled;
    private final Boolean h2cCacheEnabled;
    private final Boolean http2PriorKnowledgeEnabled;
    private final Boolean h2cUpgradeEnabled;
    private final Long happyEyeballsDelayMs;
    private final Long http3BrokenCooldownMs;
    private final Long http1OnlyCooldownMs;
    private final Long h2cCacheTtlMs;

    private ProfileDefinition(Builder builder) {
      id = builder.id;
      label = builder.label;
      parentId = builder.parentId;
      enableHttp3 = builder.enableHttp3;
      enableHttp2 = builder.enableHttp2;
      enableHttp1 = builder.enableHttp1;
      alpnEnabled = builder.alpnEnabled;
      fallbackEnabled = builder.fallbackEnabled;
      protocolErrorFallbackEnabled = builder.protocolErrorFallbackEnabled;
      altSvcCacheEnabled = builder.altSvcCacheEnabled;
      http1OnlyCacheEnabled = builder.http1OnlyCacheEnabled;
      h2cCacheEnabled = builder.h2cCacheEnabled;
      http2PriorKnowledgeEnabled = builder.http2PriorKnowledgeEnabled;
      h2cUpgradeEnabled = builder.h2cUpgradeEnabled;
      happyEyeballsDelayMs = builder.happyEyeballsDelayMs;
      http3BrokenCooldownMs = builder.http3BrokenCooldownMs;
      http1OnlyCooldownMs = builder.http1OnlyCooldownMs;
      h2cCacheTtlMs = builder.h2cCacheTtlMs;
    }

    private static Builder builder(String id) {
      return new Builder(id);
    }

    private ProfileDefinition merge(ProfileDefinition child) {
      return builder(child.id)
          .label(StringUtils.defaultIfBlank(child.label, child.id))
          .enableHttp3(value(child.enableHttp3, enableHttp3))
          .enableHttp2(value(child.enableHttp2, enableHttp2))
          .enableHttp1(value(child.enableHttp1, enableHttp1))
          .alpnEnabled(value(child.alpnEnabled, alpnEnabled))
          .fallbackEnabled(value(child.fallbackEnabled, fallbackEnabled))
          .protocolErrorFallbackEnabled(value(child.protocolErrorFallbackEnabled,
              protocolErrorFallbackEnabled))
          .altSvcCacheEnabled(value(child.altSvcCacheEnabled, altSvcCacheEnabled))
          .http1OnlyCacheEnabled(value(child.http1OnlyCacheEnabled, http1OnlyCacheEnabled))
          .h2cCacheEnabled(value(child.h2cCacheEnabled, h2cCacheEnabled))
          .http2PriorKnowledgeEnabled(value(child.http2PriorKnowledgeEnabled,
              http2PriorKnowledgeEnabled))
          .h2cUpgradeEnabled(value(child.h2cUpgradeEnabled, h2cUpgradeEnabled))
          .happyEyeballsDelayMs(value(child.happyEyeballsDelayMs, happyEyeballsDelayMs))
          .http3BrokenCooldownMs(value(child.http3BrokenCooldownMs, http3BrokenCooldownMs))
          .http1OnlyCooldownMs(value(child.http1OnlyCooldownMs, http1OnlyCooldownMs))
          .h2cCacheTtlMs(value(child.h2cCacheTtlMs, h2cCacheTtlMs))
          .build();
    }

    private HTTP2ClientProfile toProfile(String profileId) {
      return HTTP2ClientProfile.builder(profileId, StringUtils.defaultIfBlank(label, profileId))
          .enableHttp3(Boolean.TRUE.equals(enableHttp3))
          .enableHttp2(Boolean.TRUE.equals(enableHttp2))
          .enableHttp1(Boolean.TRUE.equals(enableHttp1))
          .alpnEnabled(Boolean.TRUE.equals(alpnEnabled))
          .fallbackEnabled(Boolean.TRUE.equals(fallbackEnabled))
          .protocolErrorFallbackEnabled(Boolean.TRUE.equals(protocolErrorFallbackEnabled))
          .altSvcCacheEnabled(Boolean.TRUE.equals(altSvcCacheEnabled))
          .http1OnlyCacheEnabled(Boolean.TRUE.equals(http1OnlyCacheEnabled))
          .h2cCacheEnabled(Boolean.TRUE.equals(h2cCacheEnabled))
          .http2PriorKnowledgeEnabled(Boolean.TRUE.equals(http2PriorKnowledgeEnabled))
          .h2cUpgradeEnabled(Boolean.TRUE.equals(h2cUpgradeEnabled))
          .happyEyeballsDelayMs(value(happyEyeballsDelayMs, 0L))
          .http3BrokenCooldownMs(value(http3BrokenCooldownMs, 0L))
          .http1OnlyCooldownMs(value(http1OnlyCooldownMs, 0L))
          .h2cCacheTtlMs(value(h2cCacheTtlMs, 0L))
          .build();
    }

    private static Boolean value(Boolean primary, Boolean fallback) {
      return primary != null ? primary : fallback;
    }

    private static Long value(Long primary, Long fallback) {
      return primary != null ? primary : fallback;
    }

    private static final class Builder {
      private final String id;
      private String label;
      private String parentId;
      private Boolean enableHttp3;
      private Boolean enableHttp2;
      private Boolean enableHttp1;
      private Boolean alpnEnabled;
      private Boolean fallbackEnabled;
      private Boolean protocolErrorFallbackEnabled;
      private Boolean altSvcCacheEnabled;
      private Boolean http1OnlyCacheEnabled;
      private Boolean h2cCacheEnabled;
      private Boolean http2PriorKnowledgeEnabled;
      private Boolean h2cUpgradeEnabled;
      private Long happyEyeballsDelayMs;
      private Long http3BrokenCooldownMs;
      private Long http1OnlyCooldownMs;
      private Long h2cCacheTtlMs;

      private Builder(String id) {
        this.id = id;
      }

      private Builder label(String label) {
        this.label = label;
        return this;
      }

      private Builder parentId(String parentId) {
        this.parentId = parentId;
        return this;
      }

      private Builder enableHttp3(Boolean enableHttp3) {
        if (enableHttp3 != null) {
          this.enableHttp3 = enableHttp3;
        }
        return this;
      }

      private Builder enableHttp2(Boolean enableHttp2) {
        if (enableHttp2 != null) {
          this.enableHttp2 = enableHttp2;
        }
        return this;
      }

      private Builder enableHttp1(Boolean enableHttp1) {
        if (enableHttp1 != null) {
          this.enableHttp1 = enableHttp1;
        }
        return this;
      }

      private Builder alpnEnabled(Boolean alpnEnabled) {
        if (alpnEnabled != null) {
          this.alpnEnabled = alpnEnabled;
        }
        return this;
      }

      private Builder fallbackEnabled(Boolean fallbackEnabled) {
        if (fallbackEnabled != null) {
          this.fallbackEnabled = fallbackEnabled;
        }
        return this;
      }

      private Builder protocolErrorFallbackEnabled(Boolean protocolErrorFallbackEnabled) {
        if (protocolErrorFallbackEnabled != null) {
          this.protocolErrorFallbackEnabled = protocolErrorFallbackEnabled;
        }
        return this;
      }

      private Builder altSvcCacheEnabled(Boolean altSvcCacheEnabled) {
        if (altSvcCacheEnabled != null) {
          this.altSvcCacheEnabled = altSvcCacheEnabled;
        }
        return this;
      }

      private Builder http1OnlyCacheEnabled(Boolean http1OnlyCacheEnabled) {
        if (http1OnlyCacheEnabled != null) {
          this.http1OnlyCacheEnabled = http1OnlyCacheEnabled;
        }
        return this;
      }

      private Builder h2cCacheEnabled(Boolean h2cCacheEnabled) {
        if (h2cCacheEnabled != null) {
          this.h2cCacheEnabled = h2cCacheEnabled;
        }
        return this;
      }

      private Builder http2PriorKnowledgeEnabled(Boolean http2PriorKnowledgeEnabled) {
        if (http2PriorKnowledgeEnabled != null) {
          this.http2PriorKnowledgeEnabled = http2PriorKnowledgeEnabled;
        }
        return this;
      }

      private Builder h2cUpgradeEnabled(Boolean h2cUpgradeEnabled) {
        if (h2cUpgradeEnabled != null) {
          this.h2cUpgradeEnabled = h2cUpgradeEnabled;
        }
        return this;
      }

      private Builder happyEyeballsDelayMs(Long happyEyeballsDelayMs) {
        if (happyEyeballsDelayMs != null) {
          this.happyEyeballsDelayMs = happyEyeballsDelayMs;
        }
        return this;
      }

      private Builder http3BrokenCooldownMs(Long http3BrokenCooldownMs) {
        if (http3BrokenCooldownMs != null) {
          this.http3BrokenCooldownMs = http3BrokenCooldownMs;
        }
        return this;
      }

      private Builder http1OnlyCooldownMs(Long http1OnlyCooldownMs) {
        if (http1OnlyCooldownMs != null) {
          this.http1OnlyCooldownMs = http1OnlyCooldownMs;
        }
        return this;
      }

      private Builder h2cCacheTtlMs(Long h2cCacheTtlMs) {
        if (h2cCacheTtlMs != null) {
          this.h2cCacheTtlMs = h2cCacheTtlMs;
        }
        return this;
      }

      private Builder defaultParentIfMissing() {
        if (StringUtils.isBlank(parentId)) {
          parentId = DEFAULT_PROFILE_ID;
        }
        return this;
      }

      private ProfileDefinition build() {
        return new ProfileDefinition(this);
      }
    }
  }
}
