package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link JettyCacheManager#buildCachedSampleResult} delegates to JMeter's own
 * {@code HTTPAbstractImpl#updateSampleResultForResourceInCache}, so its behaviour for each
 * {@code cache_manager.cached_resource_mode} matches {@code HTTPHC4Impl} exactly instead of a
 * hand-rolled reimplementation.
 */
public class JettyCacheManagerTest extends HTTP2TestBase {

  private static final String MODE_PROPERTY = "cache_manager.cached_resource_mode";
  private static final String RETURN_200_MESSAGE_PROPERTY = "RETURN_200_CACHE.message";
  private static final String CUSTOM_STATUS_CODE_PROPERTY = "RETURN_CUSTOM_STATUS.code";
  private static final String CUSTOM_STATUS_MESSAGE_PROPERTY = "RETURN_CUSTOM_STATUS.message";

  private final JettyCacheManager jettyCacheManager =
      JettyCacheManager.fromCacheManager(new CacheManager());

  @Before
  @After
  public void resetCacheModeProperties() throws ReflectiveOperationException {
    // Other test classes (e.g. HTTP2JettyClientTest) set these same JMeter properties without
    // cleaning up; clear them both before and after so this test never depends on run order.
    JMeterUtils.getJMeterProperties().remove(MODE_PROPERTY);
    JMeterUtils.getJMeterProperties().remove(RETURN_200_MESSAGE_PROPERTY);
    JMeterUtils.getJMeterProperties().remove(CUSTOM_STATUS_CODE_PROPERTY);
    JMeterUtils.getJMeterProperties().remove(CUSTOM_STATUS_MESSAGE_PROPERTY);
    JmeterCachedResourceModeSupport.refreshSnapshotFromProperties();
  }

  private static HTTPSampleResult newSampleResult() {
    HTTPSampleResult result = new HTTPSampleResult();
    result.sampleStart();
    return result;
  }

  private static void setCacheMode(String mode) throws ReflectiveOperationException {
    JMeterUtils.setProperty(MODE_PROPERTY, mode);
    JmeterCachedResourceModeSupport.refreshSnapshotFromProperties();
  }

  @Test
  public void returnsNoSampleWhenModeIsReturnNoSample() throws Exception {
    setCacheMode("RETURN_NO_SAMPLE");

    HTTPSampleResult result = jettyCacheManager.buildCachedSampleResult(newSampleResult());

    assertThat(result).isNull();
  }

  @Test
  public void returns200OkWithConfigurableMessageWhenModeIsReturn200Cache() throws Exception {
    JMeterUtils.setProperty(RETURN_200_MESSAGE_PROPERTY, "(from cache)");
    setCacheMode("RETURN_200_CACHE");
    HTTPSampleResult sample = newSampleResult();

    HTTPSampleResult result = jettyCacheManager.buildCachedSampleResult(sample);

    assertThat(result).isSameAs(sample);
    assertThat(result.getResponseCode()).isEqualTo("200");
    assertThat(result.getResponseMessage()).isEqualTo("(from cache)");
    assertThat(result.isSuccessful()).isTrue();
  }

  @Test
  public void return200CacheFallsBackToDefaultMessageWhenPropertyUnset() throws Exception {
    setCacheMode("RETURN_200_CACHE");

    HTTPSampleResult result = jettyCacheManager.buildCachedSampleResult(newSampleResult());

    assertThat(result.getResponseMessage()).isEqualTo("(ex cache)");
  }

  @Test
  public void returnsCustomStatusWhenModeIsReturnCustomStatus() throws Exception {
    JMeterUtils.setProperty(CUSTOM_STATUS_CODE_PROPERTY, "304");
    JMeterUtils.setProperty(CUSTOM_STATUS_MESSAGE_PROPERTY, "Not Modified (cached)");
    setCacheMode("RETURN_CUSTOM_STATUS");
    HTTPSampleResult sample = newSampleResult();

    HTTPSampleResult result = jettyCacheManager.buildCachedSampleResult(sample);

    assertThat(result).isSameAs(sample);
    assertThat(result.getResponseCode()).isEqualTo("304");
    assertThat(result.getResponseMessage()).isEqualTo("Not Modified (cached)");
    assertThat(result.isSuccessful()).isTrue();
  }

  @Test
  public void fromCacheManagerReturnsNullWhenNoCacheManagerConfigured() {
    assertThat(JettyCacheManager.fromCacheManager(null)).isNull();
  }
}
