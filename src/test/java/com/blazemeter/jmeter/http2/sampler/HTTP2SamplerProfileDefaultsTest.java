package com.blazemeter.jmeter.http2.sampler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

public class HTTP2SamplerProfileDefaultsTest extends HTTP2TestBase {

  private static final String H2C_UPGRADE_DEFAULT_PROPERTY = "httpJettyClient.h2cUpgradeEnabled";
  private static final String JETTY_PROFILE_PROPERTY = "httpJettyClient.profile";
  private static final String HTTP1_UPGRADE_PROPERTY = "HTTP2Sampler.http1_upgrade";
  private static final String PROFILE_PROPERTY = "HTTP2Sampler.profile";

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @After
  public void cleanup() {
    JMeterUtils.getJMeterProperties().remove(H2C_UPGRADE_DEFAULT_PROPERTY);
    JMeterUtils.getJMeterProperties().remove(JETTY_PROFILE_PROPERTY);
    JMeterUtils.getJMeterProperties().remove(HTTP1_UPGRADE_PROPERTY);
    JMeterUtils.getJMeterProperties().remove(PROFILE_PROPERTY);
    JMeterUtils.getJMeterProperties().remove(
        "blazemeter.http.profiles.cleartext-h2.extends");
    JMeterUtils.getJMeterProperties().remove(
        "blazemeter.http.profiles.cleartext-h2.h2cUpgradeEnabled");
  }

  @Test
  public void shouldUseDefaultH2cUpgradeWhenSamplerNotSet() {
    JMeterUtils.getJMeterProperties().setProperty(H2C_UPGRADE_DEFAULT_PROPERTY, "true");
    HTTP2Sampler sampler = new HTTP2Sampler();
    assertTrue(sampler.isHttp1UpgradeEnabled());
  }

  @Test
  public void shouldPreferSamplerValueOverDefaultH2cUpgrade() {
    JMeterUtils.getJMeterProperties().setProperty(H2C_UPGRADE_DEFAULT_PROPERTY, "false");
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setHttp1UpgradeEnabled(true);
    assertTrue(sampler.isHttp1UpgradeEnabled());
  }

  @Test
  public void shouldDisableUpgradeWhenNoDefaultsAndNoSamplerValue() {
    HTTP2Sampler sampler = new HTTP2Sampler();
    assertFalse(sampler.isHttp1UpgradeEnabled());
  }

  @Test
  public void shouldUseLegacyProfileH2cUpgradeDefault() {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setProfile("legacy");
    assertTrue(sampler.isHttp1UpgradeEnabled());
  }

  @Test
  public void shouldUseGlobalProfileWhenSamplerProfileIsNotSet() {
    JMeterUtils.getJMeterProperties().setProperty(JETTY_PROFILE_PROPERTY, "legacy");
    HTTP2Sampler sampler = new HTTP2Sampler();

    assertTrue(sampler.isHttp1UpgradeEnabled());
  }

  @Test
  public void shouldUseUserProfileH2cUpgradeDefault() {
    JMeterUtils.getJMeterProperties().setProperty(
        "blazemeter.http.profiles.cleartext-h2.extends", "browser-like");
    JMeterUtils.getJMeterProperties().setProperty(
        "blazemeter.http.profiles.cleartext-h2.h2cUpgradeEnabled", "true");
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setProfile("cleartext-h2");

    assertTrue(sampler.isHttp1UpgradeEnabled());
  }
}
