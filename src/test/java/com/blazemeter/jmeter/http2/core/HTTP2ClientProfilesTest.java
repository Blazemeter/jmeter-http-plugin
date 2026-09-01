package com.blazemeter.jmeter.http2.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

public class HTTP2ClientProfilesTest extends HTTP2TestBase {

  private static final String PROFILE_PREFIX = "blazemeter.http.profiles.";
  private Path profileFile;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @After
  public void cleanup() throws IOException {
    Properties properties = JMeterUtils.getJMeterProperties();
    List<Object> keys = new ArrayList<>(properties.keySet());
    for (Object key : keys) {
      if (String.valueOf(key).startsWith(PROFILE_PREFIX)
          || "httpJettyClient.profile".equals(key)
          || "blazemeter.http.profile".equals(key)) {
        properties.remove(key);
      }
    }
    if (profileFile != null) {
      Files.deleteIfExists(profileFile);
      profileFile = null;
    }
  }

  @Test
  public void shouldResolveBuiltInBrowserCompatibleProfile() {
    HTTP2ClientProfile profile = HTTP2ClientProfiles.resolve("browser-compatible");

    assertEquals("browser-compatible", profile.getId());
    assertTrue(profile.isEnableHttp3());
    assertTrue(profile.isEnableHttp2());
    assertTrue(profile.isEnableHttp1());
    assertEquals(0L, profile.getHappyEyeballsDelayMs());
  }

  @Test
  public void shouldResolveUserProfileWithInheritance() {
    set("blazemeter.http.profiles.mobile-h3.label", "Mobile H3 Conservative");
    set("blazemeter.http.profiles.mobile-h3.extends", "browser-like");
    set("blazemeter.http.profiles.mobile-h3.enableHttp3", "false");
    set("blazemeter.http.profiles.mobile-h3.happyEyeballsDelayMs", "500");

    HTTP2ClientProfile profile = HTTP2ClientProfiles.resolve("mobile-h3");

    assertEquals("mobile-h3", profile.getId());
    assertEquals("Mobile H3 Conservative", profile.getLabel());
    assertFalse(profile.isEnableHttp3());
    assertTrue(profile.isEnableHttp2());
    assertTrue(profile.isEnableHttp1());
    assertEquals(500L, profile.getHappyEyeballsDelayMs());
  }

  @Test
  public void shouldDefaultUserProfilesToBrowserLikeParent() {
    set("blazemeter.http.profiles.h2-only.enableHttp1", "false");
    set("blazemeter.http.profiles.h2-only.enableHttp3", "false");

    HTTP2ClientProfile profile = HTTP2ClientProfiles.resolve("h2-only");

    assertFalse(profile.isEnableHttp3());
    assertTrue(profile.isEnableHttp2());
    assertFalse(profile.isEnableHttp1());
    assertTrue(profile.isAlpnEnabled());
  }

  @Test
  public void shouldExposeUserProfilesInAvailableProfiles() {
    set("blazemeter.http.profiles.mobile-h3.label", "Mobile H3 Conservative");

    boolean found = HTTP2ClientProfiles.availableProfiles().stream()
        .anyMatch(profile -> "mobile-h3".equals(profile.getId())
            && "Mobile H3 Conservative".equals(profile.getLabel()));

    assertTrue(found);
  }

  @Test
  public void shouldLoadFileProfilesAndAllowJmeterOverrides() throws IOException {
    profileFile = Files.createTempFile("bzm-http-profiles", ".properties");
    Files.write(profileFile, Arrays.asList(
        "blazemeter.http.profiles.file-profile.label=File Profile",
        "blazemeter.http.profiles.file-profile.enableHttp3=false"));
    set("blazemeter.http.profiles.file", profileFile.toString());
    set("blazemeter.http.profiles.file-profile.enableHttp3", "true");

    HTTP2ClientProfile profile = HTTP2ClientProfiles.resolve("file-profile");

    assertEquals("File Profile", profile.getLabel());
    assertTrue(profile.isEnableHttp3());
  }

  @Test
  public void shouldFallbackToBrowserLikeWhenProfileIsInvalid() {
    set("blazemeter.http.profiles.broken.extends", "missing-profile");

    HTTP2ClientProfile profile = HTTP2ClientProfiles.resolve("broken");

    assertEquals(HTTP2ClientProfiles.DEFAULT_PROFILE_ID, profile.getId());
    assertTrue(profile.isEnableHttp3());
  }

  private static void set(String key, String value) {
    JMeterUtils.getJMeterProperties().setProperty(key, value);
  }
}
