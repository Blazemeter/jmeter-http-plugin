package com.blazemeter.jmeter.http2.regression;

import java.util.LinkedHashMap;
import java.util.Map;

/** JVM properties passed to JMeter when exercising BlazeMeter HTTP under a fixed protocol stack. */
public enum RegressionProtocolProfile {

  HTTP1_ONLY("http1-only",
      "legacy",
      true,
      false,
      false,
      false),

  HTTP2("http2",
      "browser-compatible",
      true,
      true,
      false,
      true),

  HTTP3("http3",
      "browser-compatible",
      true,
      true,
      true,
      true);

  private final String id;
  private final String profileName;
  private final boolean enableHttp1;
  private final boolean enableHttp2;
  private final boolean enableHttp3;
  private final boolean alpnEnabled;

  RegressionProtocolProfile(String id, String profileName, boolean enableHttp1,
      boolean enableHttp2, boolean enableHttp3, boolean alpnEnabled) {
    this.id = id;
    this.profileName = profileName;
    this.enableHttp1 = enableHttp1;
    this.enableHttp2 = enableHttp2;
    this.enableHttp3 = enableHttp3;
    this.alpnEnabled = alpnEnabled;
  }

  public String getId() {
    return id;
  }

  public Map<String, String> jmeterProperties() {
    Map<String, String> props = new LinkedHashMap<>();
    props.put("blazemeter.http.profile", profileName);
    props.put("blazemeter.http.enableHttp1", Boolean.toString(enableHttp1));
    props.put("blazemeter.http.enableHttp2", Boolean.toString(enableHttp2));
    props.put("blazemeter.http.enableHttp3", Boolean.toString(enableHttp3));
    props.put("blazemeter.http.alpnEnabled", Boolean.toString(alpnEnabled));
    props.put("blazemeter.http.fallbackEnabled", "true");
    if (this == HTTP1_ONLY) {
      props.put("blazemeter.http.altSvcCacheEnabled", "false");
      props.put("blazemeter.http.h2cCacheEnabled", "false");
    }
    return props;
  }

  public static RegressionProtocolProfile fromSystemProperty() {
    String value = System.getProperty("jmeter.regression.protocol", "http1-only");
    for (RegressionProtocolProfile profile : values()) {
      if (profile.id.equalsIgnoreCase(value)) {
        return profile;
      }
    }
    return HTTP1_ONLY;
  }
}
