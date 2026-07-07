package com.blazemeter.jmeter.http2.sampler.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.event.ChangeListener;
import org.apache.jmeter.gui.util.HorizontalPanel;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.protocol.http.config.gui.UrlConfigGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JLabeledTextField;

public class HTTP2SamplerPanel extends JPanel {

  /**
   * UrlConfigGui uses very wide columns (e.g. path=80, domain=40) and pins the redirect/options
   * row with {@code setMinimumSize(getPreferredSize())}, which forces horizontal scroll in JMeter's
   * property panel instead of reflowing when the window is narrowed.
   */
  private static final int RESPONSIVE_TEXTFIELD_MAX_COLS = 26;

  private static final int RESPONSIVE_TEXTAREA_MAX_COLS = 36;

  /**
   * Flow panels wider than this are treated as "option bars" that should wrap/shrink with the
   * viewport (JMeter UrlConfigGui redirect checkboxes row).
   */
  private static final int FLOW_BAR_REFLOW_MIN_PREFERRED_WIDTH = 360;

  private static final String PROFILE_COMBO_PROTOTYPE =
      "Browser-compatible (Other Browsers)";

  private static final String PROFILE_BROWSER_LIKE = "browser-like";
  private static final String PROFILE_BROWSER_LIKE_CUSTOM = "browser-like-custom";
  private static final String PROFILE_BROWSER_COMPATIBLE = "browser-compatible";
  private static final String PROFILE_LEGACY = "legacy";
  private static final long DEFAULT_HTTP3_BROKEN_COOLDOWN_MS = 300000;
  private static final long DEFAULT_HTTP1_ONLY_COOLDOWN_MS = 300000;
  private static final long DEFAULT_H2C_CACHE_TTL_MS = 300000;
  private static final long DEFAULT_HAPPY_EYEBALLS_DELAY_MS = 250;
  private static final String[] PROFILE_LABELS = new String[] {
      "Browser-like (Most Browsers)",
      "Browser-compatible (Other Browsers)",
      "Legacy / Older Systems",
      "Browser-like (Custom)"
  };
  private static final String[] PROFILE_VALUES = new String[] {
      PROFILE_BROWSER_LIKE,
      PROFILE_BROWSER_COMPATIBLE,
      PROFILE_LEGACY,
      PROFILE_BROWSER_LIKE_CUSTOM
  };
  private static final int URL_CONFIG_TABLE_VIEWPORT_MIN_ROWS = 3;
  private static final int URL_CONFIG_TABLE_VIEWPORT_MAX_ROWS = 6;

  private JTabbedPane tabbedPane;
  private int selectedTabIndex = 0;
  private UrlConfigGui urlConfigGui;
  private AdaptiveTabbedPaneHeightHost urlConfigPostTabHost;
  private AdaptiveTabbedPaneHeightHost outerTabHost;
  private final JTextField connectTimeOutField = new JTextField(10);
  private final JTextField responseTimeOutField = new JTextField(10);
  private final JTextField proxySchemeField = new JTextField(5);
  private final JTextField proxyHostField = new JTextField(10);
  private final JTextField proxyPortField = new JTextField(10);
  private final JTextField proxyUserField = new JTextField(5);
  private final JPasswordField proxyPassField = new JPasswordField(5);
  private final JCheckBox retrieveEmbeddedResourcesCheckBox = new JCheckBox(
      JMeterUtils.getResString("web_testing_retrieve_images"));
  private final JCheckBox concurrentDownloadCheckBox = new JCheckBox(
      JMeterUtils.getResString("web_testing_concurrent_download"));
  private final JTextField concurrentPoolField = new JTextField(2);
  private final JLabeledTextField embeddedResourcesRegexField = new JLabeledTextField(
      JMeterUtils.getResString("web_testing_embedded_url_pattern"), 20);
  private final JCheckBox http1Upgrade = new JCheckBox("H2C Upgrade (HTTP/1.1 Upgrade)");
  private final JComboBox<String> profileSelector = new JComboBox<>(PROFILE_LABELS);
  private final JCheckBox enableHttp3Check = new JCheckBox("Enable HTTP/3 (Alt-Svc + QUIC)");
  private final JCheckBox enableHttp2Check = new JCheckBox("Enable HTTP/2");
  private final JCheckBox enableHttp1Check = new JCheckBox("Enable HTTP/1.1");
  private final JCheckBox alpnEnabledCheck = new JCheckBox("Enable ALPN");
  private final JCheckBox fallbackEnabledCheck = new JCheckBox("Automatic fallback");
  private final JCheckBox protocolErrorFallbackCheck =
      new JCheckBox("Fallback on HTTP/2 protocol_error");
  private final JCheckBox altSvcCacheCheck = new JCheckBox("Alt-Svc cache");
  private final JCheckBox http1OnlyCacheCheck = new JCheckBox("HTTP/1.1-only cache (HTTPS)");
  private final JCheckBox h2cCacheCheck = new JCheckBox("H2C cache (HTTP)");
  private final JCheckBox http2PriorKnowledgeCheck =
      new JCheckBox("HTTP/2 prior knowledge for cleartext (h2c)");
  private final JLabeledTextField happyEyeballsDelayField =
      new JLabeledTextField("Happy Eyeballs delay (ms)", 8);
  private final JLabeledTextField http3BrokenCooldownField =
      new JLabeledTextField("HTTP/3 broken cooldown (ms)", 8);
  private final JLabeledTextField http1OnlyCooldownField =
      new JLabeledTextField("HTTP/1.1-only cache TTL (ms)", 8);
  private final JLabeledTextField h2cCacheTtlField =
      new JLabeledTextField("H2C cache TTL (ms)", 8);

  public HTTP2SamplerPanel(boolean isSampler) {
    setLayout(new BorderLayout(0, 5));
    setBorder(BorderFactory.createEmptyBorder());
    add(createTabbedConfigPane(isSampler));
  }

  private JPanel createTabbedConfigPane(boolean isSampler) {
    tabbedPane = new JTabbedPane();
    ChangeListener tabChangeListener = e -> selectedTabIndex = tabbedPane.getSelectedIndex();
    tabbedPane.addChangeListener(tabChangeListener);
    final AdaptiveTabbedPaneHeightHost[] outerRef = new AdaptiveTabbedPaneHeightHost[1];
    Runnable refreshOuterOnly = () -> SwingUtilities.invokeLater(() -> {
      AdaptiveTabbedPaneHeightHost outer = outerRef[0];
      if (outer != null) {
        outer.sync();
        AdaptiveTabbedPaneHeightHost.revalidateAncestors(outer);
      }
    });
    urlConfigGui = new UrlConfigGui(isSampler, true, true);
    applyResponsiveSizingTree(urlConfigGui);
    urlConfigPostTabHost = AdaptiveTabbedPaneHeightHost.wrapUrlConfigPostTabsIfPresent(urlConfigGui,
        refreshOuterOnly);
    PostContentTabSwitchFeedback.installIfApplicable(urlConfigGui);
    tabbedPane.add(JMeterUtils.getResString("web_testing_basic"), urlConfigGui);
    final JPanel advancedPanel = createAdvancedConfigPanel();
    applyResponsiveSizingTree(advancedPanel);
    tabbedPane.add(JMeterUtils.getResString("web_testing_advanced"), advancedPanel);
    outerTabHost = new AdaptiveTabbedPaneHeightHost(tabbedPane, null);
    outerRef[0] = outerTabHost;
    UrlConfigTableViewportSizer.install(urlConfigGui, URL_CONFIG_TABLE_VIEWPORT_MIN_ROWS,
        URL_CONFIG_TABLE_VIEWPORT_MAX_ROWS, this::refreshAdaptiveTabHeightsOnly);
    return outerTabHost;
  }

  private void refreshAdaptiveTabHeightsOnly() {
    SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
      if (urlConfigPostTabHost != null) {
        urlConfigPostTabHost.sync();
      }
      if (outerTabHost != null) {
        outerTabHost.sync();
        AdaptiveTabbedPaneHeightHost.revalidateAncestors(outerTabHost);
      }
    }));
  }

  /**
   * Re-run table viewport sizing and adaptive tab heights after UrlConfigGui data is loaded or
   * cleared.
   */
  public void refreshUrlConfigLayoutAfterDataChange() {
    if (urlConfigGui == null) {
      return;
    }
    UrlConfigTableViewportSizer.refresh(urlConfigGui, URL_CONFIG_TABLE_VIEWPORT_MIN_ROWS,
        URL_CONFIG_TABLE_VIEWPORT_MAX_ROWS);
    refreshAdaptiveTabHeightsOnly();
  }

  private Border makeBorder() {
    return BorderFactory.createEmptyBorder(10, 10, 5, 10);
  }

  private JPanel createAdvancedConfigPanel() {
    JPanel advancedPanel = new VerticalPanel();
    advancedPanel.setBorder(makeBorder());
    advancedPanel.add(createClientBehaviorPanel());
    advancedPanel.add(createTimeOutPanel());
    advancedPanel.add(createProxyPanel());
    advancedPanel.add(createEmbeddedResourcesPanel());
    return advancedPanel;
  }

  private JPanel createClientBehaviorPanel() {
    JPanel behaviorPanel = new VerticalPanel();
    behaviorPanel.setBorder(BorderFactory.createTitledBorder("Client Behavior"));

    JPanel profilePanel = new JPanel(new BorderLayout(5, 0));
    profilePanel.add(new JLabel("Profile"), BorderLayout.WEST);
    profilePanel.add(profileSelector, BorderLayout.CENTER);
    behaviorPanel.add(profilePanel);

    JPanel protocolPanel = new VerticalPanel();
    protocolPanel.setBorder(BorderFactory.createTitledBorder("Protocols"));
    protocolPanel.add(enableHttp3Check);
    protocolPanel.add(enableHttp2Check);
    protocolPanel.add(enableHttp1Check);
    protocolPanel.add(http1Upgrade);
    protocolPanel.add(alpnEnabledCheck);
    behaviorPanel.add(protocolPanel);

    JPanel fallbackPanel = new VerticalPanel();
    fallbackPanel.setBorder(BorderFactory.createTitledBorder("Fallback"));
    fallbackPanel.add(fallbackEnabledCheck);
    fallbackPanel.add(protocolErrorFallbackCheck);
    behaviorPanel.add(fallbackPanel);

    JPanel cachePanel = new VerticalPanel();
    cachePanel.setBorder(BorderFactory.createTitledBorder("Cache"));
    cachePanel.add(altSvcCacheCheck);
    cachePanel.add(http1OnlyCacheCheck);
    cachePanel.add(h2cCacheCheck);
    cachePanel.add(http2PriorKnowledgeCheck);
    behaviorPanel.add(cachePanel);

    JPanel timingPanel = new VerticalPanel();
    timingPanel.setBorder(BorderFactory.createTitledBorder("Timing"));
    timingPanel.add(happyEyeballsDelayField);
    timingPanel.add(http3BrokenCooldownField);
    timingPanel.add(http1OnlyCooldownField);
    timingPanel.add(h2cCacheTtlField);
    behaviorPanel.add(timingPanel);

    profileSelector.setPrototypeDisplayValue(PROFILE_COMBO_PROTOTYPE);
    relaxHorizontalMinimumWidth(profileSelector);
    profileSelector.addActionListener(e -> updateProfileControls());
    enableHttp1Check.addActionListener(e -> handleProtocolToggle(enableHttp1Check));
    enableHttp2Check.addActionListener(e -> handleProtocolToggle(enableHttp2Check));
    enableHttp3Check.addActionListener(e -> handleProtocolToggle(enableHttp3Check));
    fallbackEnabledCheck.addActionListener(e -> updateConditionalVisibility());
    updateProfileControls();

    return behaviorPanel;
  }

  private JPanel createTimeOutPanel() {
    JPanel timeOutPanel = new JPanel(new GridBagLayout());
    timeOutPanel.setBorder(BorderFactory.createTitledBorder(
        JMeterUtils.getResString("web_server_timeout_title")));
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridy = 0;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 0.5;
    gbc.insets = new Insets(0, 0, 0, 5);
    gbc.gridx = 0;
    timeOutPanel.add(createPanelWithLabelForField(connectTimeOutField,
        JMeterUtils.getResString("web_server_timeout_connect")), gbc);
    gbc.gridx = 1;
    gbc.insets = new Insets(0, 5, 0, 0);
    timeOutPanel.add(createPanelWithLabelForField(responseTimeOutField,
        JMeterUtils.getResString("web_server_timeout_response")), gbc);
    return timeOutPanel;
  }

  private JPanel createPanelWithLabelForField(JTextField field, String labelString) {
    JLabel label = new JLabel(labelString);
    label.setLabelFor(field);
    JPanel panel = new JPanel(new BorderLayout(5, 0));
    panel.add(label, BorderLayout.WEST);
    panel.add(field, BorderLayout.CENTER);
    relaxHorizontalMinimumWidth(panel);
    return panel;
  }

  private static void relaxHorizontalMinimumWidth(JComponent component) {
    Dimension min = component.getMinimumSize();
    component.setMinimumSize(new Dimension(0, min.height));
  }

  /**
   * Makes third-party (JMeter UrlConfigGui) and heavy Swing widgets friendlier to narrow
   * viewports by capping column-based preferred widths and clearing artificial minimum widths.
   * Visits children first so parent {@link FlowLayout} sizes reflect updated text fields.
   */
  private static void applyResponsiveSizingTree(Component root) {
    if (root instanceof Container) {
      for (Component child : ((Container) root).getComponents()) {
        applyResponsiveSizingTree(child);
      }
    }

    if (root instanceof JTextField) {
      JTextField tf = (JTextField) root;
      if (tf.getColumns() > RESPONSIVE_TEXTFIELD_MAX_COLS) {
        tf.setColumns(RESPONSIVE_TEXTFIELD_MAX_COLS);
      }
      relaxHorizontalMinimumWidth(tf);
    } else if (root instanceof JTextArea) {
      JTextArea ta = (JTextArea) root;
      if (ta.getColumns() > RESPONSIVE_TEXTAREA_MAX_COLS) {
        ta.setColumns(RESPONSIVE_TEXTAREA_MAX_COLS);
      }
      relaxHorizontalMinimumWidth(ta);
    } else if (root instanceof JComboBox) {
      relaxHorizontalMinimumWidth((JComboBox<?>) root);
    } else if (root instanceof JPanel) {
      JPanel jp = (JPanel) root;
      LayoutManager lm = jp.getLayout();
      if (lm instanceof FlowLayout) {
        Dimension min = jp.getMinimumSize();
        jp.setMinimumSize(new Dimension(0, min.height));
        reflowWideSingleRowFlowBar(jp);
      }
    }

    if (root instanceof JComponent) {
      String className = root.getClass().getName();
      if (className.endsWith("JLabeledTextField") || className.endsWith("JLabeledChoice")) {
        relaxHorizontalMinimumWidth((JComponent) root);
      }
    }
  }

  /**
   * UrlConfigGui pins a single FlowLayout row of redirect/multipart checkboxes; its preferred
   * width is the sum of all toggles and forces horizontal scrolling. Re-layout as a 2-column grid.
   */
  private static void reflowWideSingleRowFlowBar(JPanel panel) {
    int n = panel.getComponentCount();
    if (n < 4 || n > 8) {
      return;
    }
    if (!looksLikeJMeterToggleOptionBar(panel)) {
      return;
    }
    for (Component child : panel.getComponents()) {
      if (child instanceof JButton) {
        return;
      }
    }
    if (panel.getPreferredSize().width < FLOW_BAR_REFLOW_MIN_PREFERRED_WIDTH) {
      return;
    }
    Component[] children = panel.getComponents();
    panel.removeAll();
    panel.setLayout(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.anchor = GridBagConstraints.WEST;
    gbc.insets = new Insets(0, 0, 4, 16);
    int col = 0;
    int row = 0;
    final int lastColIndex = 1;
    for (Component child : children) {
      gbc.gridx = col;
      gbc.gridy = row;
      panel.add(child, gbc);
      col++;
      if (col > lastColIndex) {
        col = 0;
        row++;
      }
    }
    relaxHorizontalMinimumWidth(panel);
  }

  private static boolean looksLikeJMeterToggleOptionBar(JPanel panel) {
    boolean hasPlainCheckBox = false;
    for (Component child : panel.getComponents()) {
      if (child instanceof JCheckBox) {
        hasPlainCheckBox = true;
      }
      String childClass = child.getClass().getName();
      if (childClass.endsWith("JBooleanPropertyEditor")) {
        return true;
      }
    }
    return hasPlainCheckBox && panel.getComponentCount() >= 4;
  }

  private JPanel createProxyPanel() {
    JPanel proxyPanel = new HorizontalPanel();
    proxyPanel.setBorder(BorderFactory
        .createTitledBorder(JMeterUtils.getResString("web_proxy_server_title")));
    proxyPanel.add(createProxyServerPanel());
    return proxyPanel;
  }

  private JPanel createProxyServerPanel() {
    JPanel proxyServerPanel = new JPanel(new BorderLayout(5, 0));
    proxyServerPanel.add(createPanelWithLabelForField(proxySchemeField, JMeterUtils.getResString(
        "web_proxy_scheme")), BorderLayout.WEST);
    proxyServerPanel.add(createPanelWithLabelForField(proxyHostField, JMeterUtils.getResString(
        "web_server_domain")), BorderLayout.CENTER);
    proxyServerPanel.add(createPanelWithLabelForField(proxyPortField, JMeterUtils.getResString(
        "web_server_port")), BorderLayout.EAST);
    return proxyServerPanel;
  }

  private JPanel createEmbeddedResourcesPanel() {
    JPanel embeddedResourcesPanel = new JPanel(new BorderLayout(5, 0));
    embeddedResourcesPanel.setBorder(BorderFactory
        .createTitledBorder(BorderFactory.createEtchedBorder(),
            JMeterUtils.getResString("web_testing_retrieve_title")));
    retrieveEmbeddedResourcesCheckBox.addItemListener(e -> updateEnableStatus());
    concurrentDownloadCheckBox.addItemListener(e -> updateEnableStatus());
    concurrentPoolField.setMinimumSize(
        new Dimension(10, (int) concurrentPoolField.getPreferredSize().getHeight()));
    concurrentPoolField.setMaximumSize(
        new Dimension(30, (int) concurrentPoolField.getPreferredSize().getHeight()));
    JPanel leftControls = new HorizontalPanel();
    leftControls.add(retrieveEmbeddedResourcesCheckBox);
    leftControls.add(concurrentDownloadCheckBox);
    leftControls.add(concurrentPoolField);
    embeddedResourcesPanel.add(leftControls, BorderLayout.WEST);
    embeddedResourcesPanel.add(embeddedResourcesRegexField, BorderLayout.CENTER);
    return embeddedResourcesPanel;
  }

  private void updateEnableStatus() {
    concurrentDownloadCheckBox.setEnabled(retrieveEmbeddedResourcesCheckBox.isSelected());
    embeddedResourcesRegexField.setEnabled(retrieveEmbeddedResourcesCheckBox.isSelected());
    concurrentPoolField
        .setEnabled(retrieveEmbeddedResourcesCheckBox.isSelected() && concurrentDownloadCheckBox
            .isSelected());
  }

  private void updateProfileControls() {
    applyProfileDefaults(getProfile());
    updateConditionalVisibility();
  }

  private void handleProtocolToggle(JCheckBox checkbox) {
    if (checkbox.isSelected()) {
      applyRelatedDefaults(checkbox);
    }
    updateConditionalVisibility();
  }

  private void applyRelatedDefaults(JCheckBox checkbox) {
    if (checkbox == enableHttp3Check) {
      altSvcCacheCheck.setSelected(true);
    } else if (checkbox == enableHttp2Check) {
      fallbackEnabledCheck.setSelected(true);
      protocolErrorFallbackCheck.setSelected(true);
      if (enableHttp1Check.isSelected()) {
        h2cCacheCheck.setSelected(true);
      }
    } else if (checkbox == enableHttp1Check) {
      http1OnlyCacheCheck.setSelected(true);
      if (enableHttp2Check.isSelected()) {
        h2cCacheCheck.setSelected(true);
      }
    }
  }

  private void updateConditionalVisibility() {
    boolean customProfile = PROFILE_BROWSER_LIKE_CUSTOM.equals(getProfile());
    boolean http1Enabled = enableHttp1Check.isSelected();
    boolean http2Enabled = enableHttp2Check.isSelected();
    boolean http3Enabled = enableHttp3Check.isSelected();
    boolean fallbackEnabled = fallbackEnabledCheck.isSelected();

    if (customProfile) {
      setHttp1Visibility(true);
      setHttp2Visibility(true, true);
      setHttp3Visibility(true);
      setH2cVisibility(true, true);
    } else {
      setHttp1Visibility(http1Enabled);
      setHttp2Visibility(http2Enabled, fallbackEnabled);
      setHttp3Visibility(http3Enabled);
      setH2cVisibility(http1Enabled && http2Enabled, http2Enabled);
    }

    revalidate();
    repaint();
  }

  private void setHttp1Visibility(boolean visible) {
    enableHttp1Check.setVisible(true);
    http1OnlyCacheCheck.setVisible(visible);
    http1OnlyCooldownField.setVisible(visible);
  }

  private void setHttp2Visibility(boolean http2Enabled, boolean fallbackEnabled) {
    enableHttp2Check.setVisible(true);
    alpnEnabledCheck.setVisible(http2Enabled);
    protocolErrorFallbackCheck.setVisible(http2Enabled && fallbackEnabled);
  }

  private void setHttp3Visibility(boolean http3Enabled) {
    enableHttp3Check.setVisible(true);
    altSvcCacheCheck.setVisible(http3Enabled);
    happyEyeballsDelayField.setVisible(http3Enabled);
    http3BrokenCooldownField.setVisible(http3Enabled);
  }

  private void setH2cVisibility(boolean upgradeVisible, boolean cacheVisible) {
    http1Upgrade.setVisible(upgradeVisible);
    h2cCacheCheck.setVisible(cacheVisible);
    h2cCacheTtlField.setVisible(cacheVisible);
    http2PriorKnowledgeCheck.setVisible(cacheVisible);
  }

  private void applyProfileDefaults(String profile) {
    if (PROFILE_BROWSER_COMPATIBLE.equals(profile)) {
      setProtocolDefaults(true, true, true, true);
      setFallbackDefaults(true, true);
      setCacheDefaults(true, true, true, false);
      http1Upgrade.setSelected(false);
      setTimingDefaults(0L, DEFAULT_HTTP3_BROKEN_COOLDOWN_MS, DEFAULT_HTTP1_ONLY_COOLDOWN_MS,
          DEFAULT_H2C_CACHE_TTL_MS);
    } else if (PROFILE_LEGACY.equals(profile)) {
      setProtocolDefaults(false, false, true, false);
      setFallbackDefaults(false, false);
      setCacheDefaults(false, false, true, false);
      http1Upgrade.setSelected(true);
      setTimingDefaults(0L, 0L, 0L, DEFAULT_H2C_CACHE_TTL_MS);
    } else {
      setProtocolDefaults(true, true, true, true);
      setFallbackDefaults(true, true);
      setCacheDefaults(true, true, true, false);
      http1Upgrade.setSelected(false);
      setTimingDefaults(DEFAULT_HAPPY_EYEBALLS_DELAY_MS, DEFAULT_HTTP3_BROKEN_COOLDOWN_MS,
          DEFAULT_HTTP1_ONLY_COOLDOWN_MS, DEFAULT_H2C_CACHE_TTL_MS);
    }
  }

  private void setProtocolDefaults(boolean http3, boolean http2, boolean http1, boolean alpn) {
    enableHttp3Check.setSelected(http3);
    enableHttp2Check.setSelected(http2);
    enableHttp1Check.setSelected(http1);
    alpnEnabledCheck.setSelected(alpn);
  }

  private void setFallbackDefaults(boolean fallbackEnabled, boolean protocolErrorFallback) {
    fallbackEnabledCheck.setSelected(fallbackEnabled);
    protocolErrorFallbackCheck.setSelected(protocolErrorFallback);
  }

  private void setCacheDefaults(boolean altSvc, boolean http1Only, boolean h2cCache,
                                boolean http2PriorKnowledge) {
    altSvcCacheCheck.setSelected(altSvc);
    http1OnlyCacheCheck.setSelected(http1Only);
    h2cCacheCheck.setSelected(h2cCache);
    http2PriorKnowledgeCheck.setSelected(http2PriorKnowledge);
  }

  private void setTimingDefaults(long happyEyeballs, long http3BrokenCooldown,
                                 long http1OnlyCooldown, long h2cCacheTtl) {
    happyEyeballsDelayField.setText(String.valueOf(happyEyeballs));
    http3BrokenCooldownField.setText(String.valueOf(http3BrokenCooldown));
    http1OnlyCooldownField.setText(String.valueOf(http1OnlyCooldown));
    h2cCacheTtlField.setText(String.valueOf(h2cCacheTtl));
  }

  public void resetFields() {
    urlConfigGui.clear();
    refreshUrlConfigLayoutAfterDataChange();
    http1Upgrade.setSelected(false);
    setProfile(PROFILE_BROWSER_LIKE);
    setSelectedTabIndex(0);
    retrieveEmbeddedResourcesCheckBox.setSelected(false);
    concurrentDownloadCheckBox.setSelected(false);
    concurrentPoolField.setText(String.valueOf(HTTPSamplerBase.CONCURRENT_POOL_SIZE));
    updateEnableStatus();
    connectTimeOutField.setText("");
    responseTimeOutField.setText("");
    proxySchemeField.setText("");
    proxyHostField.setText("");
    proxyPortField.setText("");
    proxyUserField.setText("");
    proxyPassField.setText("");
  }

  public int getSelectedTabIndex() {
    if (tabbedPane != null) {
      selectedTabIndex = tabbedPane.getSelectedIndex();
    }
    return selectedTabIndex;
  }

  public void setSelectedTabIndex(int index) {
    int target = index;
    if (tabbedPane != null) {
      target = index >= 0 && index < tabbedPane.getTabCount() ? index : 0;
      tabbedPane.setSelectedIndex(target);
    }
    selectedTabIndex = target >= 0 ? target : 0;
  }

  public UrlConfigGui getUrlConfigGui() {
    return urlConfigGui;
  }

  public boolean isHttp1UpgradeSelected() {
    return http1Upgrade.isSelected();
  }

  public void setHttp1UpgradeSelected(boolean enabled) {
    http1Upgrade.setSelected(enabled);
  }

  public String getProfile() {
    int index = profileSelector.getSelectedIndex();
    if (index >= 0 && index < PROFILE_VALUES.length) {
      return PROFILE_VALUES[index];
    }
    return PROFILE_BROWSER_LIKE;
  }

  public void setProfile(String profile) {
    int index = 0;
    for (int i = 0; i < PROFILE_VALUES.length; i++) {
      if (PROFILE_VALUES[i].equals(profile)) {
        index = i;
        break;
      }
    }
    profileSelector.setSelectedIndex(index);
    updateProfileControls();
  }

  public void applyProfileDefaultsFor(String profile) {
    applyProfileDefaults(profile);
  }

  public boolean isEnableHttp3Selected() {
    return enableHttp3Check.isSelected();
  }

  public void setEnableHttp3Selected(Boolean enabled) {
    if (enabled != null) {
      enableHttp3Check.setSelected(enabled);
    }
  }

  public boolean isEnableHttp2Selected() {
    return enableHttp2Check.isSelected();
  }

  public void setEnableHttp2Selected(Boolean enabled) {
    if (enabled != null) {
      enableHttp2Check.setSelected(enabled);
    }
  }

  public boolean isEnableHttp1Selected() {
    return enableHttp1Check.isSelected();
  }

  public void setEnableHttp1Selected(Boolean enabled) {
    if (enabled != null) {
      enableHttp1Check.setSelected(enabled);
    }
  }

  public boolean isAlpnEnabledSelected() {
    return alpnEnabledCheck.isSelected();
  }

  public void setAlpnEnabledSelected(Boolean enabled) {
    if (enabled != null) {
      alpnEnabledCheck.setSelected(enabled);
    }
  }

  public boolean isFallbackEnabledSelected() {
    return fallbackEnabledCheck.isSelected();
  }

  public void setFallbackEnabledSelected(Boolean enabled) {
    if (enabled != null) {
      fallbackEnabledCheck.setSelected(enabled);
    }
  }

  public boolean isProtocolErrorFallbackSelected() {
    return protocolErrorFallbackCheck.isSelected();
  }

  public void setProtocolErrorFallbackSelected(Boolean enabled) {
    if (enabled != null) {
      protocolErrorFallbackCheck.setSelected(enabled);
    }
  }

  public boolean isAltSvcCacheSelected() {
    return altSvcCacheCheck.isSelected();
  }

  public void setAltSvcCacheSelected(Boolean enabled) {
    if (enabled != null) {
      altSvcCacheCheck.setSelected(enabled);
    }
  }

  public boolean isHttp1OnlyCacheSelected() {
    return http1OnlyCacheCheck.isSelected();
  }

  public void setHttp1OnlyCacheSelected(Boolean enabled) {
    if (enabled != null) {
      http1OnlyCacheCheck.setSelected(enabled);
    }
  }

  public boolean isH2cCacheSelected() {
    return h2cCacheCheck.isSelected();
  }

  public void setH2cCacheSelected(Boolean enabled) {
    if (enabled != null) {
      h2cCacheCheck.setSelected(enabled);
    }
  }

  public boolean isHttp2PriorKnowledgeSelected() {
    return http2PriorKnowledgeCheck.isSelected();
  }

  public void setHttp2PriorKnowledgeSelected(Boolean enabled) {
    if (enabled != null) {
      http2PriorKnowledgeCheck.setSelected(enabled);
    }
  }

  public Long getHappyEyeballsDelayMs() {
    return parseLong(happyEyeballsDelayField.getText());
  }

  public void setHappyEyeballsDelayMs(Long value) {
    happyEyeballsDelayField.setText(value == null ? "" : String.valueOf(value));
  }

  public Long getHttp3BrokenCooldownMs() {
    return parseLong(http3BrokenCooldownField.getText());
  }

  public void setHttp3BrokenCooldownMs(Long value) {
    http3BrokenCooldownField.setText(value == null ? "" : String.valueOf(value));
  }

  public Long getHttp1OnlyCooldownMs() {
    return parseLong(http1OnlyCooldownField.getText());
  }

  public void setHttp1OnlyCooldownMs(Long value) {
    http1OnlyCooldownField.setText(value == null ? "" : String.valueOf(value));
  }

  public Long getH2cCacheTtlMs() {
    return parseLong(h2cCacheTtlField.getText());
  }

  public void setH2cCacheTtlMs(Long value) {
    h2cCacheTtlField.setText(value == null ? "" : String.valueOf(value));
  }

  private Long parseLong(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(trimmed);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  public String getConnectTimeOut() {
    return connectTimeOutField.getText();
  }

  public String getResponseTimeOut() {
    return responseTimeOutField.getText();
  }

  public String getProxyScheme() {
    return proxySchemeField.getText();
  }

  public String getProxyHost() {
    return proxyHostField.getText();
  }

  public String getProxyPort() {
    return proxyPortField.getText();
  }

  public String getProxyUser() {
    return proxyUserField.getText();
  }

  public String getProxyPass() {
    return new String(proxyPassField.getPassword());
  }

  public boolean getRetrieveEmbeddedResources() {
    return retrieveEmbeddedResourcesCheckBox.isSelected();
  }

  public boolean getConcurrentDownload() {
    return concurrentDownloadCheckBox.isSelected();
  }

  public String getConcurrentPool() {
    return concurrentPoolField.getText();
  }

  public String getEmbeddedResourcesRegex() {
    return embeddedResourcesRegexField.getText();
  }

  public void setConnectTimeOut(String connectTimeOut) {
    this.connectTimeOutField.setText(connectTimeOut);
  }

  public void setResponseTimeOut(String responseTimeOut) {
    this.responseTimeOutField.setText(responseTimeOut);
  }

  public void setProxyScheme(String proxyScheme) {
    this.proxySchemeField.setText(proxyScheme);
  }

  public void setProxyHost(String proxyHost) {
    this.proxyHostField.setText(proxyHost);
  }

  public void setProxyPort(String proxyPort) {
    this.proxyPortField.setText(proxyPort);
  }

  public void setProxyUser(String proxyUser) {
    this.proxyUserField.setText(proxyUser);
  }

  public void setProxyPass(String proxyPass) {
    this.proxyPassField.setText(proxyPass);
  }

  public void setRetrieveEmbeddedResources(boolean retrieveEmbeddedResources) {
    this.retrieveEmbeddedResourcesCheckBox.setSelected(retrieveEmbeddedResources);
  }

  public void setConcurrentDownload(boolean concurrentDownload) {
    this.concurrentDownloadCheckBox.setSelected(concurrentDownload);
  }

  public void setConcurrentPool(String concurrentPool) {
    this.concurrentPoolField.setText(concurrentPool);
  }

  public void setEmbeddedResourcesRegex(String embeddedResourcesRegex) {
    this.embeddedResourcesRegexField.setText(embeddedResourcesRegex);
  }
}
