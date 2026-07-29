package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * {@link HTTP2Sampler} used to register response parsers ({@code HTTPResponse.parsers} and
 * friends) in a static initializer, which runs once at class-load time. JMeter batch runs
 * configure these via {@code -q jmeter-batch.properties}, loaded after this class may already
 * have been initialized, so the static block would read stale/default properties forever. Parsers
 * must instead load lazily, on first use, so whatever properties are actually in effect by then
 * get picked up.
 */
public class HTTP2SamplerLazyResponseParserLoadingTest extends HTTP2TestBase {

  private static final String TEST_CONTENT_TYPE = "text/test-lazy-parser";
  private static final String TEST_PARSER_KEY = "testLazyParser";

  private Map<String, String> originalParsers;
  private String originalResponseParsersProperty;
  private String originalClassNameProperty;
  private String originalTypesProperty;
  /** Only {@link #loadsParsersOnFirstUseFromPropertiesSetAfterClassInitialization} mutates these. */
  private boolean restoredResponseParserProperties;

  @After
  public void tearDown() throws Exception {
    if (restoredResponseParserProperties) {
      restoreProperty("HTTPResponse.parsers", originalResponseParsersProperty);
      restoreProperty(TEST_PARSER_KEY + ".className", originalClassNameProperty);
      restoreProperty(TEST_PARSER_KEY + ".types", originalTypesProperty);
    }
    if (originalParsers != null) {
      Map<String, String> parsers = parsersField();
      parsers.clear();
      parsers.putAll(originalParsers);
    }
  }

  /**
   * {@link #doesNotReloadOnceParsersAreAlreadyRegistered} must not wipe {@code HTTPResponse.parsers}
   * for later test classes (JUnit creates a fresh instance per method; that test never captures the
   * original property, and a naive {@code restore(null)} would {@code remove} the suite default).
   */
  @AfterClass
  public static void restoreSuiteResponseParserProperties() throws Exception {
    JMeterTestUtils.ensureResponseParserProperties();
    parsersField().clear();
  }

  @Test
  public void loadsParsersOnFirstUseFromPropertiesSetAfterClassInitialization() throws Exception {
    Map<String, String> parsers = parsersField();
    originalParsers = new HashMap<>(parsers);
    parsers.clear();

    originalResponseParsersProperty = JMeterUtils.getProperty("HTTPResponse.parsers");
    originalClassNameProperty = JMeterUtils.getProperty(TEST_PARSER_KEY + ".className");
    originalTypesProperty = JMeterUtils.getProperty(TEST_PARSER_KEY + ".types");
    restoredResponseParserProperties = true;

    // Set AFTER the class (and its old static block, if it still existed) would already have
    // run - simulating a JMeter batch run applying -q jmeter-batch.properties post-class-load.
    JMeterUtils.setProperty("HTTPResponse.parsers", TEST_PARSER_KEY);
    JMeterUtils.setProperty(TEST_PARSER_KEY + ".className",
        "org.apache.jmeter.protocol.http.parser.RegexpHTMLParser");
    JMeterUtils.setProperty(TEST_PARSER_KEY + ".types", TEST_CONTENT_TYPE);

    invokeEnsureResponseParsersLoaded();

    assertThat(parsersField())
        .as("parser registered from properties set after class initialization")
        .containsEntry(TEST_CONTENT_TYPE, "org.apache.jmeter.protocol.http.parser.RegexpHTMLParser");
  }

  @Test
  public void doesNotReloadOnceParsersAreAlreadyRegistered() throws Exception {
    Map<String, String> parsers = parsersField();
    originalParsers = new HashMap<>(parsers);
    parsers.put("text/already-loaded-marker", "some.Marker");

    // HTTPResponse.parsers left unset/whatever it currently is: if this reloaded, it would not
    // add our marker back after a clear, but it also must not wipe it out just by being called.
    invokeEnsureResponseParsersLoaded();

    assertThat(parsersField()).containsKey("text/already-loaded-marker");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> parsersField() throws Exception {
    Field field = HTTP2Sampler.class.getDeclaredField("PARSERS_FOR_CONTENT_TYPE");
    field.setAccessible(true);
    return (Map<String, String>) field.get(null);
  }

  private static void invokeEnsureResponseParsersLoaded() throws Exception {
    Method method = HTTP2Sampler.class.getDeclaredMethod("ensureResponseParsersLoaded");
    method.setAccessible(true);
    method.invoke(null);
  }

  private static void restoreProperty(String key, String originalValue) {
    if (originalValue == null) {
      JMeterUtils.getJMeterProperties().remove(key);
    } else {
      JMeterUtils.setProperty(key, originalValue);
    }
  }
}
