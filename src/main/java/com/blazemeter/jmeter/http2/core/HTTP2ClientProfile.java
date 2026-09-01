package com.blazemeter.jmeter.http2.core;

public final class HTTP2ClientProfile {

  private final String id;
  private final String label;
  private final boolean enableHttp3;
  private final boolean enableHttp2;
  private final boolean enableHttp1;
  private final boolean alpnEnabled;
  private final boolean fallbackEnabled;
  private final boolean protocolErrorFallbackEnabled;
  private final boolean altSvcCacheEnabled;
  private final boolean http1OnlyCacheEnabled;
  private final boolean h2cCacheEnabled;
  private final boolean http2PriorKnowledgeEnabled;
  private final boolean h2cUpgradeEnabled;
  private final long happyEyeballsDelayMs;
  private final long http3BrokenCooldownMs;
  private final long http1OnlyCooldownMs;
  private final long h2cCacheTtlMs;

  private HTTP2ClientProfile(Builder builder) {
    id = builder.id;
    label = builder.label;
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

  public static Builder builder(String id, String label) {
    return new Builder(id, label);
  }

  public String getId() {
    return id;
  }

  public String getLabel() {
    return label;
  }

  public boolean isEnableHttp3() {
    return enableHttp3;
  }

  public boolean isEnableHttp2() {
    return enableHttp2;
  }

  public boolean isEnableHttp1() {
    return enableHttp1;
  }

  public boolean isAlpnEnabled() {
    return alpnEnabled;
  }

  public boolean isFallbackEnabled() {
    return fallbackEnabled;
  }

  public boolean isProtocolErrorFallbackEnabled() {
    return protocolErrorFallbackEnabled;
  }

  public boolean isAltSvcCacheEnabled() {
    return altSvcCacheEnabled;
  }

  public boolean isHttp1OnlyCacheEnabled() {
    return http1OnlyCacheEnabled;
  }

  public boolean isH2cCacheEnabled() {
    return h2cCacheEnabled;
  }

  public boolean isHttp2PriorKnowledgeEnabled() {
    return http2PriorKnowledgeEnabled;
  }

  public boolean isH2cUpgradeEnabled() {
    return h2cUpgradeEnabled;
  }

  public long getHappyEyeballsDelayMs() {
    return happyEyeballsDelayMs;
  }

  public long getHttp3BrokenCooldownMs() {
    return http3BrokenCooldownMs;
  }

  public long getHttp1OnlyCooldownMs() {
    return http1OnlyCooldownMs;
  }

  public long getH2cCacheTtlMs() {
    return h2cCacheTtlMs;
  }

  public static final class Builder {
    private final String id;
    private final String label;
    private boolean enableHttp3;
    private boolean enableHttp2;
    private boolean enableHttp1;
    private boolean alpnEnabled;
    private boolean fallbackEnabled;
    private boolean protocolErrorFallbackEnabled;
    private boolean altSvcCacheEnabled;
    private boolean http1OnlyCacheEnabled;
    private boolean h2cCacheEnabled;
    private boolean http2PriorKnowledgeEnabled;
    private boolean h2cUpgradeEnabled;
    private long happyEyeballsDelayMs;
    private long http3BrokenCooldownMs;
    private long http1OnlyCooldownMs;
    private long h2cCacheTtlMs;

    private Builder(String id, String label) {
      this.id = id;
      this.label = label;
    }

    public Builder enableHttp3(boolean enableHttp3) {
      this.enableHttp3 = enableHttp3;
      return this;
    }

    public Builder enableHttp2(boolean enableHttp2) {
      this.enableHttp2 = enableHttp2;
      return this;
    }

    public Builder enableHttp1(boolean enableHttp1) {
      this.enableHttp1 = enableHttp1;
      return this;
    }

    public Builder alpnEnabled(boolean alpnEnabled) {
      this.alpnEnabled = alpnEnabled;
      return this;
    }

    public Builder fallbackEnabled(boolean fallbackEnabled) {
      this.fallbackEnabled = fallbackEnabled;
      return this;
    }

    public Builder protocolErrorFallbackEnabled(boolean protocolErrorFallbackEnabled) {
      this.protocolErrorFallbackEnabled = protocolErrorFallbackEnabled;
      return this;
    }

    public Builder altSvcCacheEnabled(boolean altSvcCacheEnabled) {
      this.altSvcCacheEnabled = altSvcCacheEnabled;
      return this;
    }

    public Builder http1OnlyCacheEnabled(boolean http1OnlyCacheEnabled) {
      this.http1OnlyCacheEnabled = http1OnlyCacheEnabled;
      return this;
    }

    public Builder h2cCacheEnabled(boolean h2cCacheEnabled) {
      this.h2cCacheEnabled = h2cCacheEnabled;
      return this;
    }

    public Builder http2PriorKnowledgeEnabled(boolean http2PriorKnowledgeEnabled) {
      this.http2PriorKnowledgeEnabled = http2PriorKnowledgeEnabled;
      return this;
    }

    public Builder h2cUpgradeEnabled(boolean h2cUpgradeEnabled) {
      this.h2cUpgradeEnabled = h2cUpgradeEnabled;
      return this;
    }

    public Builder happyEyeballsDelayMs(long happyEyeballsDelayMs) {
      this.happyEyeballsDelayMs = happyEyeballsDelayMs;
      return this;
    }

    public Builder http3BrokenCooldownMs(long http3BrokenCooldownMs) {
      this.http3BrokenCooldownMs = http3BrokenCooldownMs;
      return this;
    }

    public Builder http1OnlyCooldownMs(long http1OnlyCooldownMs) {
      this.http1OnlyCooldownMs = http1OnlyCooldownMs;
      return this;
    }

    public Builder h2cCacheTtlMs(long h2cCacheTtlMs) {
      this.h2cCacheTtlMs = h2cCacheTtlMs;
      return this;
    }

    public HTTP2ClientProfile build() {
      return new HTTP2ClientProfile(this);
    }
  }
}
