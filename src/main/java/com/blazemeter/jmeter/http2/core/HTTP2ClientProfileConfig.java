package com.blazemeter.jmeter.http2.core;

public class HTTP2ClientProfileConfig {

  private final String profile;
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

  private HTTP2ClientProfileConfig(Builder builder) {
    this.profile = builder.profile;
    this.enableHttp3 = builder.enableHttp3;
    this.enableHttp2 = builder.enableHttp2;
    this.enableHttp1 = builder.enableHttp1;
    this.alpnEnabled = builder.alpnEnabled;
    this.fallbackEnabled = builder.fallbackEnabled;
    this.protocolErrorFallbackEnabled = builder.protocolErrorFallbackEnabled;
    this.altSvcCacheEnabled = builder.altSvcCacheEnabled;
    this.http1OnlyCacheEnabled = builder.http1OnlyCacheEnabled;
    this.h2cCacheEnabled = builder.h2cCacheEnabled;
    this.http2PriorKnowledgeEnabled = builder.http2PriorKnowledgeEnabled;
    this.h2cUpgradeEnabled = builder.h2cUpgradeEnabled;
    this.happyEyeballsDelayMs = builder.happyEyeballsDelayMs;
    this.http3BrokenCooldownMs = builder.http3BrokenCooldownMs;
    this.http1OnlyCooldownMs = builder.http1OnlyCooldownMs;
    this.h2cCacheTtlMs = builder.h2cCacheTtlMs;
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getProfile() {
    return profile;
  }

  public Boolean getEnableHttp3() {
    return enableHttp3;
  }

  public Boolean getEnableHttp2() {
    return enableHttp2;
  }

  public Boolean getEnableHttp1() {
    return enableHttp1;
  }

  public Boolean getAlpnEnabled() {
    return alpnEnabled;
  }

  public Boolean getFallbackEnabled() {
    return fallbackEnabled;
  }

  public Boolean getProtocolErrorFallbackEnabled() {
    return protocolErrorFallbackEnabled;
  }

  public Boolean getAltSvcCacheEnabled() {
    return altSvcCacheEnabled;
  }

  public Boolean getHttp1OnlyCacheEnabled() {
    return http1OnlyCacheEnabled;
  }

  public Boolean getH2cCacheEnabled() {
    return h2cCacheEnabled;
  }

  public Boolean getHttp2PriorKnowledgeEnabled() {
    return http2PriorKnowledgeEnabled;
  }

  public Boolean getH2cUpgradeEnabled() {
    return h2cUpgradeEnabled;
  }

  public Long getHappyEyeballsDelayMs() {
    return happyEyeballsDelayMs;
  }

  public Long getHttp3BrokenCooldownMs() {
    return http3BrokenCooldownMs;
  }

  public Long getHttp1OnlyCooldownMs() {
    return http1OnlyCooldownMs;
  }

  public Long getH2cCacheTtlMs() {
    return h2cCacheTtlMs;
  }

  public static final class Builder {
    private String profile;
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

    public Builder profile(String profile) {
      this.profile = profile;
      return this;
    }

    public Builder enableHttp3(Boolean enableHttp3) {
      this.enableHttp3 = enableHttp3;
      return this;
    }

    public Builder enableHttp2(Boolean enableHttp2) {
      this.enableHttp2 = enableHttp2;
      return this;
    }

    public Builder enableHttp1(Boolean enableHttp1) {
      this.enableHttp1 = enableHttp1;
      return this;
    }

    public Builder alpnEnabled(Boolean alpnEnabled) {
      this.alpnEnabled = alpnEnabled;
      return this;
    }

    public Builder fallbackEnabled(Boolean fallbackEnabled) {
      this.fallbackEnabled = fallbackEnabled;
      return this;
    }

    public Builder protocolErrorFallbackEnabled(Boolean protocolErrorFallbackEnabled) {
      this.protocolErrorFallbackEnabled = protocolErrorFallbackEnabled;
      return this;
    }

    public Builder altSvcCacheEnabled(Boolean altSvcCacheEnabled) {
      this.altSvcCacheEnabled = altSvcCacheEnabled;
      return this;
    }

    public Builder http1OnlyCacheEnabled(Boolean http1OnlyCacheEnabled) {
      this.http1OnlyCacheEnabled = http1OnlyCacheEnabled;
      return this;
    }

    public Builder h2cCacheEnabled(Boolean h2cCacheEnabled) {
      this.h2cCacheEnabled = h2cCacheEnabled;
      return this;
    }

    public Builder http2PriorKnowledgeEnabled(Boolean http2PriorKnowledgeEnabled) {
      this.http2PriorKnowledgeEnabled = http2PriorKnowledgeEnabled;
      return this;
    }

    public Builder h2cUpgradeEnabled(Boolean h2cUpgradeEnabled) {
      this.h2cUpgradeEnabled = h2cUpgradeEnabled;
      return this;
    }

    public Builder happyEyeballsDelayMs(Long happyEyeballsDelayMs) {
      this.happyEyeballsDelayMs = happyEyeballsDelayMs;
      return this;
    }

    public Builder http3BrokenCooldownMs(Long http3BrokenCooldownMs) {
      this.http3BrokenCooldownMs = http3BrokenCooldownMs;
      return this;
    }

    public Builder http1OnlyCooldownMs(Long http1OnlyCooldownMs) {
      this.http1OnlyCooldownMs = http1OnlyCooldownMs;
      return this;
    }

    public Builder h2cCacheTtlMs(Long h2cCacheTtlMs) {
      this.h2cCacheTtlMs = h2cCacheTtlMs;
      return this;
    }

    public HTTP2ClientProfileConfig build() {
      return new HTTP2ClientProfileConfig(this);
    }
  }
}
