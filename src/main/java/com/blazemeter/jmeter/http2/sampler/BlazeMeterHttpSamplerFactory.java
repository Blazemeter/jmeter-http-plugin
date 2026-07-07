package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.sampler.gui.HTTP2SamplerGui;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.StringProperty;

/**
 * Helpers that configure {@link HTTP2Sampler} for recorder use and migrating Apache HTTP requests.
 *
 * @see com.blazemeter.jmeter.http2.proxy.HTTP2SampleCreator
 * @see HttpSamplerToBlazeMeterHttpMigrator
 */
public final class BlazeMeterHttpSamplerFactory {

  private BlazeMeterHttpSamplerFactory() {
  }

  /**
   * Sets {@link TestElement#GUI_CLASS} / {@link TestElement#TEST_CLASS} for BlazeMeter HTTP.
   */
  public static void setGuiAndTestClassForBlazeMeterHttp(HTTP2Sampler sampler) {
    sampler.setProperty(
        new StringProperty(TestElement.GUI_CLASS, HTTP2SamplerGui.class.getName()));
    sampler.setProperty(
        new StringProperty(TestElement.TEST_CLASS, HTTP2Sampler.class.getName()));
  }

  /**
   * Applies recorder sampler defaults ahead of populateSampler.
   * Migration skips this step so migrated plans preserve cloned sampler properties instead.
   */
  public static void applyProxyRecordingDefaults(HTTP2Sampler sampler) {
    sampler.setHttp1UpgradeEnabled(false);
    sampler.setFollowRedirects(false);
    sampler.setUseKeepAlive(true);
  }

  /** Empty BlazeMeter sampler before populateSampler applies the captured HTTP request payload. */
  public static HTTP2Sampler newSamplerForProxyRecording() {
    HTTP2Sampler sampler = new HTTP2Sampler();
    setGuiAndTestClassForBlazeMeterHttp(sampler);
    applyProxyRecordingDefaults(sampler);
    return sampler;
  }
}
