package com.blazemeter.jmeter.http2.gui;

import com.blazemeter.jmeter.http2.proxy.HTTP2SampleCreator;
import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.EditCommand;
import org.apache.jmeter.protocol.http.proxy.gui.ProxyControlGui;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends a BlazeMeter HTTP hint next to the recorder "Type" label when proxy
 * recording uses BlazeMeter ({@link HTTP2SampleCreator#isBlazeMeterProxyRecorderActive()}).
 * The Type implementation combo stays enabled and untouched (no tooltip).
 *
 * <p>Applies to the stock {@linkplain ProxyControlGui}
 * (HTTP(S) Test Script Recorder) and to third-party subclasses that may not match
 * {@code instanceof ProxyControlGui} across JMeter/plugin class loaders, for example
 * {@code CorrelationProxyControlGui} (Automatic Correlation Recorder). Those are
 * recognized by superclass FQCN chain and/or simple class name, without compile-time
 * dependency on the correlation plugin.</p>
 *
 * <p>Walks {@link Container} trees; bundle label {@link JMeterUtils#getResString(String)}
 * keys the Type row when present.
 *
 * <p>Patches run <em>after</em> {@link EditCommand} for the tree-driven
 * {@link org.apache.jmeter.gui.action.ActionNames#EDIT} action, so
 * {@link GuiPackage#updateCurrentGui()} / {@code configure} have finished.
 *
 * <p>The same recorder panel instance is often reused ({@code configure} may reset
 * widgets).
 *
 * <p>At default log (INFO): hook install, Type label note failures;
 * grep {@code [bzm proxy recorder Type UI]}.
 * Verbose tracing: {@code blazemeter.http.debugProxyRecorderTypeUi=true}.
 */
public final class ProxyRecorderSamplerTypeUi {

  /** Logger for this helper. */
  private static final Logger LOG =
      LoggerFactory.getLogger(ProxyRecorderSamplerTypeUi.class);

  /**
   * Enables verbose INFO tracing when set to {@code true} in
   * {@code user.properties}.
   */
  private static final String PROP_DEBUG_PROXY_RECORDER_TYPE_UI =
      "blazemeter.http.debugProxyRecorderTypeUi";

  /** Log line prefix for grep in jmeter.log. */
  private static final String LOG_PREFIX = "[bzm proxy recorder Type UI] ";

  /**
   * FQCN of {@link ProxyControlGui}; used only for string compares on superclass
   * chains (optional correlation-recorder GUIs loaded in another loader).
   */
  private static final String JMETER_PROXY_CONTROL_GUI_CLASS_NAME =
      ProxyControlGui.class.getName();

  /** Simple name of correlation recorder GUI (BlazeMeter plugin; not on our classpath). */
  private static final String CORRELATION_PROXY_CONTROL_GUI_SIMPLE_NAME =
      "CorrelationProxyControlGui";

  /** Bundle key: label beside the recorder implementation combo. */
  private static final String PROXY_SAMPLER_TYPE_KEY =
      "proxy_sampler_type";

  /** Guard: {@link ActionRouter} post-{@link EditCommand} hook once. */
  private static final AtomicBoolean RECORDER_UI_HOOK_INSTALLED =
      new AtomicBoolean(false);

  /**
   * Marks HTML appended to the Type label; everything from here is removed on
   * restore ({@link #stripBzmTypeNoteFromLabel(JLabel)}).
   */
  private static final String HTML_NOTE_MARKER = "<!--bzm-proxy-type-note-->";

  /**
   * HTML after {@link #HTML_NOTE_MARKER}: visible {@code BlazeMeter HTTP} (yellow) + disable path.
   */
  private static final String TYPE_LABEL_NOTE_HTML =
      " <span style='color:#CC9900;font-weight:bold'>BlazeMeter HTTP as primary</span>"
          + "<br/>Select alternative HTTP client to use when the request is not supported by "
          + "BlazeMeter HTTP."
          + "<br/>To Disable BlazeMeter HTTP on this recorder:"
          + "<br/>Tools -&gt; BlazeMeter HTTP -&gt; "
          + "Disable Recording with HTTP(S) Test Script Recorder";

  /** Minimum plausible item count for the implementation picker. */
  private static final int MIN_IMPL_COMBO_ITEMS = 2;

  /** Upper bound so we ignore unrelated combo boxes with many entries. */
  private static final int MAX_IMPL_COMBO_ITEMS = 10;

  private static final int SPEC_WEIGHT_HIT = 2;

  /** Hidden ctor for static helpers. */
  private ProxyRecorderSamplerTypeUi() {
    // Hidden.
  }

  /**
   * Reads {@link #PROP_DEBUG_PROXY_RECORDER_TYPE_UI}.
   *
   * @return whether verbose tracing is enabled.
   */
  private static boolean isDebugProxyRecorderTypeUi() {
    return BzmHttpPluginProperties.getPropDefault(
        PROP_DEBUG_PROXY_RECORDER_TYPE_UI, false);
  }

  /**
   * Logs one INFO line when tracing is on.
   *
   * @param message slf4j pattern (with {} placeholders).
   * @param args slf4j format arguments.
   */
  private static void logDebug(final String message, final Object... args) {
    if (isDebugProxyRecorderTypeUi()) {
      LOG.info(LOG_PREFIX + message, args);
    }
  }

  /**
   * After {@link ActionRouter} handling and post-{@link EditCommand} layout,
   * defer {@link #maybePatchIfRecorderSamplerTypeCompatGui}.
   */
  private static void schedulePatchAfterEditCommand() {
    SwingUtilities.invokeLater(
        ProxyRecorderSamplerTypeUi::maybePatchIfRecorderSamplerTypeCompatGui);
  }

  /**
   * Registers one post-{@link EditCommand} hook: refresh Type UI when the main
   * panel is {@link ProxyControlGui} or a compatible subclass loaded elsewhere.
   */
  public static void installRecorderUiHookOnce() {
    if (!RECORDER_UI_HOOK_INSTALLED.compareAndSet(false, true)) {
      return;
    }
    SwingUtilities.invokeLater(
        () -> {
          try {
            ActionRouter.getInstance()
                .addPostActionListener(
                    EditCommand.class,
                    e -> ProxyRecorderSamplerTypeUi
                        .schedulePatchAfterEditCommand());
            LOG.info(
                LOG_PREFIX.concat(
                    "Hook registered (post-EditCommand)."));
          } catch (RuntimeException ex) {
            RECORDER_UI_HOOK_INSTALLED.set(false);
            LOG.warn(
                LOG_PREFIX.concat("failed to register post-EditCommand hook."),
                ex);
          }
        });
  }

  /**
   * If the recorder or correlation-recorder panel is visible, apply BlazeMeter Type
   * label suffix when relevant.
   */
  static void maybePatchIfRecorderSamplerTypeCompatGui() {
    GuiPackage gp = GuiPackage.getInstance();
    if (gp == null) {
      logDebug("GuiPackage null; skip.");
      return;
    }
    Object guiObj = gp.getCurrentGui();
    Container recorderRoot = recorderSamplerTypePanelRoot(guiObj);
    if (recorderRoot == null) {
      logDebug(
          "currentGui is not recorder/correlation Type panel (actual={}); skip.",
          guiObj == null ? "null" : guiObj.getClass().getName());
      return;
    }
    logDebug(
        "currentGui recorder-compatible ({}); scanning for implementation combo.",
        recorderRoot.getClass().getName());
    applyRecorderSamplerTypeRestrictions(recorderRoot);
  }

  /**
   * Resolves editor root panels that reuse the recorder "Type" + implementation combo
   * ({@linkplain ProxyControlGui} and similar), including correlation plugins not
   * assignable via {@code instanceof ProxyControlGui}.
   */
  private static Container recorderSamplerTypePanelRoot(final Object guiObj) {
    if (!(guiObj instanceof Container)) {
      return null;
    }
    Container c = (Container) guiObj;
    if (guiObj instanceof ProxyControlGui) {
      return c;
    }
    Class<?> clazz = guiObj.getClass();
    if (CORRELATION_PROXY_CONTROL_GUI_SIMPLE_NAME.equals(clazz.getSimpleName())) {
      return c;
    }
    if (hasSuperclassWithName(clazz, JMETER_PROXY_CONTROL_GUI_CLASS_NAME)) {
      return c;
    }
    return null;
  }

  private static boolean hasSuperclassWithName(
      final Class<?> start, final String fqcn) {
    for (Class<?> z = start; z != null; z = z.getSuperclass()) {
      if (fqcn.equals(z.getName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Re-applies recorder UI tweaks whenever this panel is current (repeatable).
   *
   * @param recorderPanel recorder (or correlation) panel in main editor area.
   */
  private static void applyRecorderSamplerTypeRestrictions(
      final Container recorderPanel) {
    final boolean blazeMeterOn =
        HTTP2SampleCreator.isBlazeMeterProxyRecorderActive();
    String rawTypeLabel = JMeterUtils.getResString(PROXY_SAMPLER_TYPE_KEY);
    String expectedTypeLabel = normalizeVisibleText(rawTypeLabel);
    logDebug(
        "Bundle key '{}' raw='{}' normalized='{}'.",
        PROXY_SAMPLER_TYPE_KEY,
        rawTypeLabel,
        expectedTypeLabel);
    logDebug(
        "BlazeMeter proxy recorder mode active (decorate Type label)={}.",
        blazeMeterOn);
    logDebug(
        "{} resolved raw={} parsedBoolean={} (default if unset: {}) → BlazeMeter recorder UI on={}",
        HTTP2SampleCreator.PROXY_ENABLED,
        BzmHttpPluginProperties.resolveRaw(HTTP2SampleCreator.PROXY_ENABLED),
        BzmHttpPluginProperties.getPropDefault(
            HTTP2SampleCreator.PROXY_ENABLED, HTTP2SampleCreator.PROXY_ENABLED_DEFAULT),
        HTTP2SampleCreator.PROXY_ENABLED_DEFAULT,
        blazeMeterOn);
    List<JComboBox<?>> comboBoxes = collectComboBoxes(recorderPanel);
    logDebug(
        "Found {} JComboBox under recorder panel.",
        comboBoxes.size());
    for (int i = 0; i < comboBoxes.size(); i++) {
      JComboBox<?> cb = comboBoxes.get(i);
      boolean match = looksLikeHttpSamplerImplementationCombo(cb);
      logDebug(
          "Combo #{}: likeImplPicker={} {}",
          i,
          match,
          summarizeCombo(cb));
    }
    JComboBox<?> samplerTypeCombo =
        identifySamplerImplementationCombo(comboBoxes);
    if (samplerTypeCombo == null) {
      if (blazeMeterOn) {
        LOG.warn(
            LOG_PREFIX.concat(
                "sampler-type combo not identified ({}); "
                    + "set debugProxyRecorderTypeUi for dump."),
            comboBoxes.size());
        logDebug("No implementation combo matched heuristics.");
      } else {
        logDebug("Combo not identified; BlazeMeter proxy off, skip.");
      }
      return;
    }
    JLabel typeLabel =
        expectedTypeLabel.isEmpty()
            ? findLabelBefore(samplerTypeCombo)
            : findMatchingTypeLabel(
                recorderPanel, expectedTypeLabel, samplerTypeCombo);

    if (!blazeMeterOn) {
      if (typeLabel != null) {
        stripBzmTypeNoteFromLabel(typeLabel);
      }
      logDebug("BlazeMeter proxy off; Type UI left as stock JMeter.");
      return;
    }

    if (typeLabel != null) {
      String cur = typeLabel.getText();
      if (cur == null || !cur.contains(HTML_NOTE_MARKER)) {
        typeLabel.setText(formatTypeLabelWithRecorderNote(rawTypeLabel));
        logDebug("Set BlazeMeter HTML note on Type label.");
      } else {
        logDebug("Type label already had BlazeMeter HTML note; skipped append.");
      }
    } else {
      logDebug("Type JLabel not found; BlazeMeter note skipped.");
    }
  }

  /**
   * Strips everything from {@link #HTML_NOTE_MARKER} onward (current note format).
   *
   * @param typeLabel label beside the implementation combo.
   */
  private static void stripBzmTypeNoteFromLabel(final JLabel typeLabel) {
    String t = typeLabel.getText();
    if (t == null) {
      return;
    }
    int m = t.indexOf(HTML_NOTE_MARKER);
    if (m >= 0) {
      typeLabel.setText(unwrapSimpleHtmlToPlain(t.substring(0, m)));
    }
  }

  /**
   * Multiline Type hint: HTML marker allows {@link #stripBzmTypeNoteFromLabel} to restore.
   */
  private static String formatTypeLabelWithRecorderNote(final String baseLabelText) {
    String esc = escapeMinimalHtml(baseLabelText == null ? "" : baseLabelText);
    return "<html>" + esc + HTML_NOTE_MARKER + TYPE_LABEL_NOTE_HTML + "</html>";
  }

  private static String escapeMinimalHtml(final String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String unwrapSimpleHtmlToPlain(final String html) {
    if (html == null) {
      return "";
    }
    String s = html.trim();
    if (s.regionMatches(true, 0, "<html>", 0, 6)) {
      s = s.substring(6).trim();
    }
    if (s.regionMatches(true, s.length() - 7, "</html>", 0, 7)) {
      s = s.substring(0, s.length() - 7).trim();
    }
    return s;
  }

  /**
   * One-line summary of combo items for logs.
   *
   * @param cb combo in the recorder panel.
   * @return item count, labels, enabled flag.
   */
  private static String summarizeCombo(final JComboBox<?> cb) {
    int n = cb.getItemCount();
    StringBuilder sb = new StringBuilder();
    sb.append("(n=").append(n).append(") items=[");
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        sb.append(" | ");
      }
      sb.append(String.valueOf(cb.getItemAt(i)));
    }
    sb.append("] enabled=").append(cb.isEnabled());
    return sb.toString();
  }

  /**
   * Collects {@link JComboBox} instances under a container.
   *
   * @param root subtree root (typically {@link ProxyControlGui}).
   * @return mutable list of combo boxes found in preorder.
   */
  private static List<JComboBox<?>> collectComboBoxes(final Container root) {
    List<JComboBox<?>> out = new ArrayList<>();
    collectComboBoxes0(root, out);
    return out;
  }

  /**
   * Recursive helper for {@link #collectComboBoxes(Container)}.
   *
   * @param c current container node.
   * @param out accumulates combos.
   */
  private static void collectComboBoxes0(
      final Container c, final List<JComboBox<?>> out) {
    for (Component ch : c.getComponents()) {
      if (ch instanceof JComboBox) {
        out.add((JComboBox<?>) ch);
      } else if (ch instanceof Container) {
        collectComboBoxes0((Container) ch, out);
      }
    }
  }

  /**
   * Picks the combo whose items look like recorder implementation names.
   *
   * @param boxes all combos under the recorder panel.
   * @return chosen combo or null when uncertain.
   */
  private static JComboBox<?> identifySamplerImplementationCombo(
      final List<JComboBox<?>> boxes) {
    List<JComboBox<?>> candidates = new ArrayList<>();
    for (JComboBox<?> cb : boxes) {
      if (looksLikeHttpSamplerImplementationCombo(cb)) {
        candidates.add(cb);
      }
    }
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    if (candidates.isEmpty()) {
      return null;
    }
    Collections.sort(
        candidates,
        Comparator.comparingInt(
                ProxyRecorderSamplerTypeUi::estimateComboSpecificity)
            .reversed());
    return candidates.get(0);
  }

  /**
   * Matches the small Java vs HTTP-client implementation picker.
   *
   * @param cb candidate combo box.
   * @return true if item texts resemble JMeter recorder options.
   */
  private static boolean looksLikeHttpSamplerImplementationCombo(
      final JComboBox<?> cb) {
    int n = cb.getItemCount();
    if (n < MIN_IMPL_COMBO_ITEMS || n > MAX_IMPL_COMBO_ITEMS) {
      return false;
    }
    boolean hasJava = false;
    boolean hasClientish = false;
    for (int i = 0; i < n; i++) {
      String t = String.valueOf(cb.getItemAt(i)).toLowerCase(Locale.ROOT);
      if (t.contains("java") || t.contains("jdk")) {
        hasJava = true;
      }
      if (t.contains("http")
          || t.contains("hc4")
          || t.contains("hc3")
          || t.contains("hc")
          || t.contains("client")
          || t.contains("apache")) {
        hasClientish = true;
      }
    }
    return hasJava && hasClientish;
  }

  /**
   * Ranks combos: Java vs HttpClient-style picker wins if several match.
   *
   * @param cb combo to score.
   * @return nonnegative score (higher is more specific).
   */
  private static int estimateComboSpecificity(final JComboBox<?> cb) {
    int score = 0;
    for (int i = 0; i < cb.getItemCount(); i++) {
      String t = String.valueOf(cb.getItemAt(i)).toLowerCase(Locale.ROOT);
      if (t.contains("java")) {
        score += SPEC_WEIGHT_HIT;
      }
      if (t.contains("hc4") || t.contains("httpclient")) {
        score += SPEC_WEIGHT_HIT;
      }
    }
    return score;
  }

  /**
   * Finds Type label matching bundle text; prefers same-parent row as combo.
   *
   * @param root subtree to search (recorder GUI).
   * @param expectedTypeLabel lowercase normalized bundle text for "Type".
   * @param combo implementation combo paired with that label row.
   * @return matching label or null.
   */
  private static JLabel findMatchingTypeLabel(
      final Container root,
      final String expectedTypeLabel,
      final JComboBox<?> combo) {
    List<JLabel> labels = collectLabels(root);
    for (JLabel lab : labels) {
      String plain = normalizeVisibleText(lab.getText());
      if (plain.equals(expectedTypeLabel)) {
        if (combo.getParent() == lab.getParent()) {
          return lab;
        }
      }
    }
    for (JLabel lab : labels) {
      String plain = normalizeVisibleText(lab.getText());
      if (plain.equals(expectedTypeLabel)) {
        return lab;
      }
    }
    return null;
  }

  /**
   * Sibling heuristic: JLabel just before combo in parent's component list.
   *
   * @param combo recorder implementation combo box.
   * @return preceding label when present.
   */
  private static JLabel findLabelBefore(final JComboBox<?> combo) {
    Container parent = combo.getParent();
    if (parent == null) {
      return null;
    }
    Component[] ch = parent.getComponents();
    for (int i = 1; i < ch.length; i++) {
      if (ch[i] == combo && ch[i - 1] instanceof JLabel) {
        return (JLabel) ch[i - 1];
      }
    }
    return null;
  }

  /**
   * Collects all {@link JLabel} under {@code root}.
   *
   * @param root subtree root.
   * @return list of labels in preorder.
   */
  private static List<JLabel> collectLabels(final Container root) {
    List<JLabel> labels = new ArrayList<>();
    collectLabels0(root, labels);
    return labels;
  }

  /**
   * Recursive helper for {@link #collectLabels(Container)}.
   *
   * @param c current container node.
   * @param labels accumulates labels.
   */
  private static void collectLabels0(
      final Container c, final List<JLabel> labels) {
    for (Component ch : c.getComponents()) {
      if (ch instanceof JLabel) {
        labels.add((JLabel) ch);
      }
      if (ch instanceof Container) {
        collectLabels0((Container) ch, labels);
      }
    }
  }

  /**
   * Strip trivial HTML wrappers; normalize whitespace; lowercase result.
   *
   * @param raw label text possibly containing tags.
   * @return lowercase single-spaced plaintext.
   */
  private static String normalizeVisibleText(final String raw) {
    if (raw == null) {
      return "";
    }
    String s =
        raw.replace("<html>", "")
            .replace("</html>", "")
            .replace("<HTML>", "")
            .replace("</HTML>", "")
            .replaceAll("\\<.*?\\>", " ");
    final char nbsp = '\u00a0';
    s = s.replace(nbsp, ' ').trim().replaceAll("\\s+", " ");
    return s.toLowerCase(Locale.ROOT);
  }
}
