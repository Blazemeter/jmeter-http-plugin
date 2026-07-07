package com.blazemeter.jmeter.http2.sampler.gui;

import com.blazemeter.jmeter.commons.BlazemeterLabsLogo;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.HTTP2SamplerConverter;
import com.thoughtworks.xstream.XStream;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Properties;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2SamplerGui extends AbstractSamplerGui implements Scrollable {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2SamplerGui.class);
  private static final String PLUGIN_REPOSITORY_URL = "https://github.com/Blazemeter/jmeter-http2"
      + "-plugin";
  private final HTTP2SamplerPanel http2SamplerPanel;

  static {
    try {
      Field field = SaveService.class.getDeclaredField("JMXSAVER");
      field.setAccessible(true);
      XStream jmxSaver = (XStream) field.get(null);
      jmxSaver.registerConverter(new HTTP2SamplerConverter(jmxSaver.getMapper()),
          XStream.PRIORITY_VERY_HIGH);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      LOG.error("Error while creating BlazeMeter HTTP jmx converter", e);
    }
  }

  public HTTP2SamplerGui() {
    http2SamplerPanel = new HTTP2SamplerPanel(true);
    http2SamplerPanel.resetFields();

    setLayout(new BorderLayout(0, 5));
    setBorder(makeBorder());

    add(makeTitlePanel(), BorderLayout.NORTH);
    add(http2SamplerPanel, BorderLayout.CENTER);
    add(new BlazemeterLabsLogo(PLUGIN_REPOSITORY_URL), BorderLayout.PAGE_END);
  }

  @Override
  public String getStaticLabel() {
    return "bzm - HTTP Sampler";
  }

  @Override
  public String getLabelResource() {
    return null;
  }

  @Override
  public TestElement createTestElement() {
    HTTP2Sampler http2Sampler = new HTTP2Sampler();
    configureTestElement(http2Sampler);
    http2Sampler.setConcurrentDwn(true);
    return http2Sampler;
  }

  @Override
  public void modifyTestElement(TestElement testElement) {
    testElement.clear();
    configureTestElement(testElement);
    if (testElement instanceof HTTP2Sampler) {
      HTTP2Sampler http2Sampler = (HTTP2Sampler) testElement;
      http2Sampler.setImageParser(http2SamplerPanel.getRetrieveEmbeddedResources());
      http2Sampler.setConcurrentDwn(http2SamplerPanel.getConcurrentDownload());
      http2Sampler.setConcurrentPool(http2SamplerPanel.getConcurrentPool());
      http2Sampler.setEmbeddedUrlRE(http2SamplerPanel.getEmbeddedResourcesRegex());
      http2Sampler.setConnectTimeout(http2SamplerPanel.getConnectTimeOut());
      http2Sampler.setResponseTimeout(http2SamplerPanel.getResponseTimeOut());
      http2Sampler.setProxyHost(http2SamplerPanel.getProxyHost());
      http2Sampler.setProxyScheme(http2SamplerPanel.getProxyScheme());
      http2Sampler.setProxyPortInt(http2SamplerPanel.getProxyPort());
      http2Sampler.setProxyUser(http2SamplerPanel.getProxyUser());
      http2Sampler.setProxyPass(http2SamplerPanel.getProxyPass());
      http2Sampler.setProperty("version", getPluginVersion());
      http2SamplerPanel.getUrlConfigGui().modifyTestElement(http2Sampler);
      http2Sampler.setUiTabIndex(http2SamplerPanel.getSelectedTabIndex());
      http2Sampler.setHttp1UpgradeEnabled(http2SamplerPanel.isHttp1UpgradeSelected());
      String profile = http2SamplerPanel.getProfile();
      http2Sampler.setProfile(profile);
      http2Sampler.setEnableHttp3(http2SamplerPanel.isEnableHttp3Selected());
      http2Sampler.setEnableHttp2(http2SamplerPanel.isEnableHttp2Selected());
      http2Sampler.setEnableHttp1(http2SamplerPanel.isEnableHttp1Selected());
      http2Sampler.setAlpnEnabled(http2SamplerPanel.isAlpnEnabledSelected());
      http2Sampler.setFallbackEnabled(http2SamplerPanel.isFallbackEnabledSelected());
      http2Sampler.setProtocolErrorFallbackEnabled(
          http2SamplerPanel.isProtocolErrorFallbackSelected());
      http2Sampler.setAltSvcCacheEnabled(http2SamplerPanel.isAltSvcCacheSelected());
      http2Sampler.setHttp1OnlyCacheEnabled(http2SamplerPanel.isHttp1OnlyCacheSelected());
      http2Sampler.setH2cCacheEnabled(http2SamplerPanel.isH2cCacheSelected());
      http2Sampler.setHttp2PriorKnowledgeEnabled(
          http2SamplerPanel.isHttp2PriorKnowledgeSelected());
      http2Sampler.setHappyEyeballsDelayMs(http2SamplerPanel.getHappyEyeballsDelayMs());
      http2Sampler.setHttp3BrokenCooldownMs(http2SamplerPanel.getHttp3BrokenCooldownMs());
      http2Sampler.setHttp1OnlyCooldownMs(http2SamplerPanel.getHttp1OnlyCooldownMs());
      http2Sampler.setH2cCacheTtlMs(http2SamplerPanel.getH2cCacheTtlMs());
    }
  }

  private String getPluginVersion() {
    try {
      final Properties properties = new Properties();
      properties.load(this.getClass().getClassLoader().getResourceAsStream("project.properties"));
      return properties.getProperty("version");
    } catch (IOException e) {
      LOG.warn("Could not write plugin version", e);
      return "";
    }
  }

  @Override
  public void configure(TestElement testElement) {
    super.configure(testElement);
    if (testElement instanceof HTTP2Sampler) {
      HTTP2Sampler http2Sampler = (HTTP2Sampler) testElement;
      http2SamplerPanel.setRetrieveEmbeddedResources(http2Sampler.isImageParser());
      http2SamplerPanel.setConcurrentDownload(http2Sampler.isConcurrentDwn());
      http2SamplerPanel.setConcurrentPool(http2Sampler.getConcurrentPool());
      http2SamplerPanel.setEmbeddedResourcesRegex(http2Sampler.getEmbeddedUrlRE());
      http2SamplerPanel
          .setConnectTimeOut(http2Sampler.getPropertyAsString(HTTPSamplerBase.CONNECT_TIMEOUT));
      http2SamplerPanel
          .setResponseTimeOut(http2Sampler.getPropertyAsString(HTTPSamplerBase.RESPONSE_TIMEOUT));
      http2SamplerPanel
          .setProxyScheme(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYSCHEME));
      http2SamplerPanel.setProxyHost(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYHOST));
      http2SamplerPanel.setProxyPort(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYPORT));
      http2SamplerPanel.setProxyUser(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYUSER));
      http2SamplerPanel.setProxyPass(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYPASS));
      http2SamplerPanel.getUrlConfigGui().configure(http2Sampler);
      http2SamplerPanel.refreshUrlConfigLayoutAfterDataChange();
      String profile = http2Sampler.getProfile();
      http2SamplerPanel.setProfile(profile);
      http2SamplerPanel.applyProfileDefaultsFor(profile);
      http2SamplerPanel.setHttp1UpgradeSelected(http2Sampler.isHttp1UpgradeEnabled());
      http2SamplerPanel.setEnableHttp3Selected(http2Sampler.getEnableHttp3());
      http2SamplerPanel.setEnableHttp2Selected(http2Sampler.getEnableHttp2());
      http2SamplerPanel.setEnableHttp1Selected(http2Sampler.getEnableHttp1());
      http2SamplerPanel.setAlpnEnabledSelected(http2Sampler.getAlpnEnabled());
      http2SamplerPanel.setFallbackEnabledSelected(http2Sampler.getFallbackEnabled());
      http2SamplerPanel.setProtocolErrorFallbackSelected(
          http2Sampler.getProtocolErrorFallbackEnabled());
      http2SamplerPanel.setAltSvcCacheSelected(http2Sampler.getAltSvcCacheEnabled());
      http2SamplerPanel.setHttp1OnlyCacheSelected(http2Sampler.getHttp1OnlyCacheEnabled());
      http2SamplerPanel.setH2cCacheSelected(http2Sampler.getH2cCacheEnabled());
      http2SamplerPanel.setHttp2PriorKnowledgeSelected(
          http2Sampler.getHttp2PriorKnowledgeEnabled());
      Long happyEyeballsDelay = http2Sampler.getHappyEyeballsDelayMs();
      if (happyEyeballsDelay != null) {
        http2SamplerPanel.setHappyEyeballsDelayMs(happyEyeballsDelay);
      }
      Long http3BrokenCooldown = http2Sampler.getHttp3BrokenCooldownMs();
      if (http3BrokenCooldown != null) {
        http2SamplerPanel.setHttp3BrokenCooldownMs(http3BrokenCooldown);
      }
      Long http1OnlyCooldown = http2Sampler.getHttp1OnlyCooldownMs();
      if (http1OnlyCooldown != null) {
        http2SamplerPanel.setHttp1OnlyCooldownMs(http1OnlyCooldown);
      }
      Long h2cCacheTtl = http2Sampler.getH2cCacheTtlMs();
      if (h2cCacheTtl != null) {
        http2SamplerPanel.setH2cCacheTtlMs(h2cCacheTtl);
      }
      http2SamplerPanel.setSelectedTabIndex(http2Sampler.getUiTabIndex());
    }
  }

  @Override
  public void clearGui() {
    super.clearGui();
    http2SamplerPanel.resetFields();
  }

  /**
   * JMeter shows sampler settings inside a {@link javax.swing.JScrollPane}. Without this contract,
   * the viewport keeps the widest preferred width ever seen, so shrinking the main window leaves a
   * useless horizontal scrollbar. Tracking viewport width reflows the panel when the frame narrows.
   */
  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return getPreferredSize();
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
    return orientation == SwingConstants.VERTICAL
        ? Math.max(1, visibleRect.height / 10)
        : Math.max(1, visibleRect.width / 10);
  }

  @Override
  public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
    return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return false;
  }
}
