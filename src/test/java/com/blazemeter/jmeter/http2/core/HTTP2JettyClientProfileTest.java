package com.blazemeter.jmeter.http2.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

public class HTTP2JettyClientProfileTest extends HTTP2TestBase {

  private static final String PROFILE_PROPERTY = "httpJettyClient.profile";
  private static final String ENABLE_HTTP3 = "httpJettyClient.enableHttp3";
  private static final String ENABLE_HTTP2 = "httpJettyClient.enableHttp2";
  private static final String ENABLE_HTTP1 = "httpJettyClient.enableHttp1";
  private static final String ALPN_ENABLED = "httpJettyClient.alpnEnabled";
  private static final String HAPPY_EYEBALLS = "httpJettyClient.happyEyeballsDelayMs";
  private static final String DISABLE_FALLBACK = "httpJettyClient.disableFallback";
  private static final String PROTOCOL_ERROR_FALLBACK =
      "httpJettyClient.protocolErrorFallbackEnabled";
  private static final String ALT_SVC_CACHE = "httpJettyClient.altSvcCacheEnabled";
  private static final String HTTP1_ONLY_CACHE = "httpJettyClient.http1OnlyCacheEnabled";
  private static final String H2C_CACHE = "httpJettyClient.h2cCacheEnabled";
  private static final String PROFILE_PREFIX = "blazemeter.http.profiles.";

  @BeforeClass
  public static void setupClass() {
    com.blazemeter.jmeter.http2.sampler.JMeterTestUtils.setupJmeterEnv();
  }

  @After
  public void cleanup() {
    remove(PROFILE_PROPERTY);
    remove(ENABLE_HTTP3);
    remove(ENABLE_HTTP2);
    remove(ENABLE_HTTP1);
    remove(ALPN_ENABLED);
    remove(HAPPY_EYEBALLS);
    remove(DISABLE_FALLBACK);
    remove(PROTOCOL_ERROR_FALLBACK);
    remove(ALT_SVC_CACHE);
    remove(HTTP1_ONLY_CACHE);
    remove(H2C_CACHE);
    removeCustomProfiles();
  }

  @Test
  public void shouldApplyBrowserCompatibleDefaults() throws Exception {
    set(PROFILE_PROPERTY, "browser-compatible");
    HTTP2JettyClient client = new HTTP2JettyClient(false, "test");

    assertTrue(getBooleanField(client, "enableHttp3"));
    assertTrue(getBooleanField(client, "enableHttp2"));
    assertTrue(getBooleanField(client, "enableHttp1"));
    assertTrue(getBooleanField(client, "alpnEnabled"));
    assertEquals(0L, getLongField(client, "happyEyeballsDelayMs"));
  }

  @Test
  public void shouldApplyLegacyDefaults() throws Exception {
    set(PROFILE_PROPERTY, "legacy");
    HTTP2JettyClient client = new HTTP2JettyClient(false, "test");

    assertFalse(getBooleanField(client, "enableHttp3"));
    assertFalse(getBooleanField(client, "enableHttp2"));
    assertTrue(getBooleanField(client, "enableHttp1"));
    assertFalse(getBooleanField(client, "alpnEnabled"));
    assertFalse(getBooleanField(client, "fallbackEnabled"));
    assertFalse(getBooleanField(client, "protocolErrorFallbackEnabled"));
  }

  @Test
  public void shouldAllowBrowserLikeCustomOverrides() throws Exception {
    set(PROFILE_PROPERTY, "browser-like-custom");
    set(ENABLE_HTTP3, "false");
    set(HAPPY_EYEBALLS, "0");
    HTTP2JettyClient client = new HTTP2JettyClient(false, "test");

    assertFalse(getBooleanField(client, "enableHttp3"));
    assertEquals(0L, getLongField(client, "happyEyeballsDelayMs"));
  }

  @Test
  public void shouldApplyUserDefinedProfileDefaults() throws Exception {
    set("blazemeter.http.profiles.mobile-h3.extends", "browser-like");
    set("blazemeter.http.profiles.mobile-h3.enableHttp3", "false");
    set("blazemeter.http.profiles.mobile-h3.happyEyeballsDelayMs", "500");
    set(PROFILE_PROPERTY, "mobile-h3");
    HTTP2JettyClient client = new HTTP2JettyClient(false, "test");

    assertFalse(getBooleanField(client, "enableHttp3"));
    assertTrue(getBooleanField(client, "enableHttp2"));
    assertEquals(500L, getLongField(client, "happyEyeballsDelayMs"));
  }

  @Test
  public void shouldHonorDisableFallbackCompatibilityFlag() throws Exception {
    set(PROFILE_PROPERTY, "browser-like");
    set(DISABLE_FALLBACK, "true");
    HTTP2JettyClient client = new HTTP2JettyClient(false, "test");

    assertFalse(getBooleanField(client, "protocolErrorFallbackEnabled"));
  }

  @Test
  public void shouldOverrideProtocolErrorFallbackFlag() throws Exception {
    set(PROFILE_PROPERTY, "browser-like");
    set(DISABLE_FALLBACK, "true");
    set(PROTOCOL_ERROR_FALLBACK, "true");
    HTTP2JettyClient client = new HTTP2JettyClient(false, "test");

    assertTrue(getBooleanField(client, "protocolErrorFallbackEnabled"));
  }

  @Test
  public void shouldAllowDisablingCachesViaOverrides() throws Exception {
    set(PROFILE_PROPERTY, "browser-like-custom");
    set(ALT_SVC_CACHE, "false");
    set(HTTP1_ONLY_CACHE, "false");
    set(H2C_CACHE, "false");
    HTTP2JettyClient client = new HTTP2JettyClient(false, "test");

    assertFalse(getBooleanField(client, "altSvcCacheEnabled"));
    assertFalse(getBooleanField(client, "http1OnlyCacheEnabled"));
    assertFalse(getBooleanField(client, "h2cCacheEnabled"));
  }

  @Test
  public void shouldReuseNoH3ClientWhenHttp3Disabled() throws Exception {
    set(PROFILE_PROPERTY, "browser-like-custom");
    set(ENABLE_HTTP3, "false");
    HTTP2JettyClient client = new HTTP2JettyClient(false, "test");

    Object mainClient = getField(client, "httpClient");
    Object noH3Client = getField(client, "httpClientNoH3");
    assertTrue(mainClient == noH3Client);
  }

  @Test
  public void shouldExcludeHttp2FromProtocolsWhenAlpnDisabled() throws Exception {
    set(PROFILE_PROPERTY, "browser-like-custom");
    set(ENABLE_HTTP3, "false");
    set(ALPN_ENABLED, "false");
    set(ENABLE_HTTP2, "true");
    set(ENABLE_HTTP1, "true");
    HTTP2JettyClient client = new HTTP2JettyClient(false, "test");

    String protocols = (String) getField(client, "mainProtocolsSnapshot");
    assertFalse(protocols.contains("h2"));
  }

  private static void set(String key, String value) {
    JMeterUtils.getJMeterProperties().setProperty(key, value);
  }

  private static void remove(String key) {
    JMeterUtils.getJMeterProperties().remove(key);
  }

  private static void removeCustomProfiles() {
    Properties properties = JMeterUtils.getJMeterProperties();
    List<Object> keys = new ArrayList<>(properties.keySet());
    for (Object key : keys) {
      if (String.valueOf(key).startsWith(PROFILE_PREFIX)) {
        properties.remove(key);
      }
    }
  }

  private static boolean getBooleanField(Object target, String fieldName) throws Exception {
    Object value = getField(target, fieldName);
    return Boolean.TRUE.equals(value);
  }

  private static long getLongField(Object target, String fieldName) throws Exception {
    Object value = getField(target, fieldName);
    return (long) value;
  }

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }
}
