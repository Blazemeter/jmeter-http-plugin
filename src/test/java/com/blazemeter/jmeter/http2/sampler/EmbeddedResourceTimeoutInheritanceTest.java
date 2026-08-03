package com.blazemeter.jmeter.http2.sampler;

import static org.junit.Assert.assertEquals;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Method;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Embedded-resource child samplers must inherit the parent's Connect Timeout and Response Timeout.
 *
 * <p>JMeter builds them with {@code base.clone()} ({@code HTTPSamplerBase.ASyncSample}) so they
 * inherit every property; this plugin builds them from a fresh {@link HTTP2Sampler} and copies
 * settings explicitly. When the timeouts were left out of that copy, an embedded request reported 0
 * for both - meaning "no timeout" - so {@code HTTP2JettyClient} never applied a deadline to it and a
 * stalled resource blocked the JMeter thread forever in the concurrent download drain.
 */
public class EmbeddedResourceTimeoutInheritanceTest extends HTTP2TestBase {

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Test
  public void shouldCopyParentTimeoutsToEmbeddedSampler() throws Exception {
    HTTP2Sampler parent = new HTTP2Sampler();
    parent.setConnectTimeout("5000");
    parent.setResponseTimeout("20000");

    HTTP2Sampler embedded = copySettings(parent);

    assertEquals(5000, embedded.getConnectTimeout());
    assertEquals(20000, embedded.getResponseTimeout());
  }

  /**
   * An unset timeout has to stay unset rather than become a literal "0": both read back as 0, but
   * only the pass-through keeps the plugin 1:1 with JMeter, where an absent value and 0 alike mean
   * "wait indefinitely".
   */
  @Test
  public void shouldKeepUnsetParentTimeoutsUnsetOnEmbeddedSampler() throws Exception {
    HTTP2Sampler embedded = copySettings(new HTTP2Sampler());

    assertEquals(0, embedded.getConnectTimeout());
    assertEquals(0, embedded.getResponseTimeout());
    assertEquals("", embedded.getPropertyAsString("HTTPSampler.connect_timeout"));
    assertEquals("", embedded.getPropertyAsString("HTTPSampler.response_timeout"));
  }

  private static HTTP2Sampler copySettings(HTTP2Sampler parent) throws Exception {
    HTTP2Sampler embedded = new HTTP2Sampler();
    Method copy = HTTP2Sampler.class.getDeclaredMethod(
        "copyJettyProtocolSettingsToEmbeddedSampler", HTTP2Sampler.class);
    copy.setAccessible(true);
    copy.invoke(parent, embedded);
    return embedded;
  }
}
