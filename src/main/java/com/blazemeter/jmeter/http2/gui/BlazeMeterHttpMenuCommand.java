package com.blazemeter.jmeter.http2.gui;

import com.blazemeter.jmeter.http2.proxy.HTTP2SampleCreator;
import com.blazemeter.jmeter.http2.sampler.HttpSamplerToBlazeMeterHttpMigrator;
import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Restart;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.apache.jmeter.gui.plugin.MenuCreator.MENU_LOCATION;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSampler;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tools → BlazeMeter HTTP: {@link HTTP2SampleCreator} recording toggle,
 * migrating HTTPSamplerProxy / HTTPSampler HTTP Request samplers to BlazeMeter HTTP.
 * Reads the proxy flag from {@link BzmHttpPluginProperties}.
 *
 * <p>Not registered via SPI: JMeter also scans {@code lib/ext} for {@link MenuCreator}
 * and {@link org.apache.jmeter.gui.action.Command} implementations. Only
 * {@link BlazeMeterHttpMenuCommandBootstrap} is listed in {@code META-INF/services};
 * this class is instantiated by the bootstrap on Java 17+.
 */
public class BlazeMeterHttpMenuCommand {

  private static final Logger LOG = LoggerFactory.getLogger(BlazeMeterHttpMenuCommand.class);

  /**
   * Action command for {@link ActionRouter}; internal id, not UI text.
   */
  private static final String ACTION_PROXY_TOGGLE = "bzm_http2_proxy_recording_toggle";

  private static final String ACTION_MIGRATE_ALL =
      "bzm_http2_migrate_all_http_samplers";

  /** Routed through {@link ActionRouter} for Tools menu migrate-selected. */
  private static final String ACTION_MIGRATE_SELECTED =
      "bzm_http2_migrate_selected_http_samplers";

  private static final String ROOT_MENU_LABEL = "BlazeMeter HTTP";
  private static final String LABEL_ENABLE_PROXY_RECORDING =
      "Enable Recording with HTTP(S) Test Script Recorder";
  private static final String LABEL_DISABLE_PROXY_RECORDING =
      "Disable Recording with HTTP(S) Test Script Recorder";

  private static final String LABEL_MIGRATE_ENTIRE_PLAN =
      "Migrate Entire Test Plan (HTTP Request → BlazeMeter HTTP)…";
  private static final String LABEL_MIGRATE_SELECTED =
      "Migrate Selected (HTTP Request → BlazeMeter HTTP)…";

  private static final String DIALOG_TITLE = "BlazeMeter HTTP";

  /**
   * Unified restart log under {@code java.io.tmpdir}: truncated on each GUI
   * restart only (bounded across unrelated runs).
   */
  private static final String RESTART_LOG_BASENAME =
      "bzm-http2-jmeter-restart.log";

  /**
   * Argv payload for {@link BlazeMeterJmeterRestartRelay};
   * rewritten on each GUI restart attempt.
   */
  private static final String RESTART_ARGV_BASENAME =
      "bzm-http2-jmeter-restart.argv";

  private static final Set<String> ACTION_NAMES = Set.of(
      ACTION_PROXY_TOGGLE,
      ACTION_MIGRATE_ALL,
      ACTION_MIGRATE_SELECTED);

  /** Grep-friendly marker that this plugin JAR loaded and hooks were scheduled. */
  private static final String PLUGIN_LOAD_LOG_PREFIX = "[bzm http2 plugin] ";

  static {
    LOG.info(
        PLUGIN_LOAD_LOG_PREFIX.concat(
            "BlazeMeter HTTP classes loaded; scheduling recorder GUI hook on EDT."));
    SwingUtilities.invokeLater(
        ProxyRecorderSamplerTypeUi::installRecorderUiHookOnce);
  }

  public Set<String> getActionNames() {
    return ACTION_NAMES;
  }

  public void doActionAfterCheck(ActionEvent e) {
    String cmd = e.getActionCommand();
    if (ACTION_PROXY_TOGGLE.equals(cmd)) {
      doProxyToggle();
    } else if (ACTION_MIGRATE_ALL.equals(cmd)) {
      migrateEntireTestPlan();
    } else if (ACTION_MIGRATE_SELECTED.equals(cmd)) {
      migrateCurrentSelection();
    } else {
      LOG.warn("Unknown action command: {}", cmd);
    }
  }

  private void doProxyToggle() {
    boolean current =
        BzmHttpPluginProperties.getPropDefault(
            HTTP2SampleCreator.PROXY_ENABLED, HTTP2SampleCreator.PROXY_ENABLED_DEFAULT);
    boolean next = !current;

    String binDir = JMeterUtils.getJMeterBinDir();
    if (StringUtils.isBlank(binDir)) {
      JMeterUtils.reportErrorToUser(
          "Cannot resolve JMeter bin directory; user.properties was not updated.",
          JMeterUtils.getResString("error_title"));
      return;
    }
    File userProperties = new File(binDir, "user.properties");

    try {
      BzmHttpPluginProperties.persistUserProperty(
          userProperties, HTTP2SampleCreator.PROXY_ENABLED, Boolean.toString(next));
      BzmHttpPluginProperties.syncRuntime(
          HTTP2SampleCreator.PROXY_ENABLED, Boolean.toString(next));
      LOG.info(
          "{} updated to {} (default if unset: {}).",
          HTTP2SampleCreator.PROXY_ENABLED,
          next,
          HTTP2SampleCreator.PROXY_ENABLED_DEFAULT);
    } catch (IOException ex) {
      LOG.error("Could not write {}", userProperties.getAbsolutePath(), ex);
      JMeterUtils.reportErrorToUser("Could not update user.properties: " + ex.getMessage(),
          JMeterUtils.getResString("error_title"), ex);
      return;
    }

    int restart = JOptionPane.showConfirmDialog(
        GuiPackage.getInstance().getMainFrame(),
        "Restart JMeter now to apply the change?",
        "Restart JMeter",
        JOptionPane.YES_NO_OPTION,
        JOptionPane.QUESTION_MESSAGE);
    if (restart == JOptionPane.YES_OPTION) {
      File apacheJar = new File(binDir, "ApacheJMeter.jar");
      if (apacheJar.isFile()) {
        try {
          restartJmeterAfterExitLikePluginsManager(apacheJar);
        } catch (RuntimeException ex) {
          LOG.error("BlazeMeter HTTP: restart aborted", ex);
          JMeterUtils.reportErrorToUser(
              "Restart could not start: " + ex.getMessage() + "\nDetails in jmeter.log.",
              JMeterUtils.getResString("error_title"),
              ex);
        }
      } else {
        Restart.restartApplication(null);
      }
    }
  }

  /**
   * Plugins Manager exits the GUI then runs {@code SafeDeleter} in a <em>fresh</em> JVM, which then
   * starts JMeter. Doing that directly from this JVM shutdown hook can fail while AWT/native
   * teardown is underway; mirror the two-stage design with {@link BlazeMeterJmeterRestartRelay}.
   */
  private static void restartJmeterAfterExitLikePluginsManager(File apacheJmeterJar) {
    List<String> jmeterArgv = buildRestartCommandLikePluginsManagerChangesMaker(apacheJmeterJar);
    if (!new File(jmeterArgv.get(0)).isFile()) {
      throw new IllegalStateException("JVM binary not found: " + jmeterArgv.get(0));
    }

    File restartLog = restartUnifiedLogPath();
    File argvFile = restartArgvPath();
    List<String> relayArgv;
    try {
      Files.writeString(
          argvFile.toPath(),
          String.join("\n", jmeterArgv) + '\n',
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);

      relayArgv = relayLauncherArgv(restartLog, argvFile);
      writeRestartDiagnosticHeader(restartLog, jmeterArgv, argvFile, relayArgv);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot write restart log/argv under java.io.tmpdir", e);
    }

    LOG.warn(
        "BlazeMeter HTTP: JMeter restart log (truncated each run): {}",
        restartLog.getAbsolutePath());

    final List<String> relayArgvCopy = new ArrayList<>(relayArgv);
    Thread hook =
        new Thread(
            () -> startRelayInShutdownHook(relayArgvCopy, restartLog),
            "bzmHttp2JmeterRestartRelay");
    Runtime.getRuntime().addShutdownHook(hook);

    try {
      GuiPackage gui = GuiPackage.getInstance();
      ActionRouter.getInstance()
          .actionPerformed(
              new ActionEvent(gui != null ? gui.getMainFrame() : null, 0, ActionNames.EXIT));
    } catch (RuntimeException ex) {
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException ignored) {
        // JVM already shutting down
      }
      appendRestartFailure(restartLog, ex);
      throw ex;
    }
  }

  /** First stage VM: same pattern as Plugins Manager spawning {@code SafeDeleter.main}. */
  private static void startRelayInShutdownHook(List<String> relayArgv, File restartLog) {
    try {
      ProcessBuilder pb = new ProcessBuilder(relayArgv);
      pb.redirectOutput(ProcessBuilder.Redirect.appendTo(restartLog));
      pb.redirectError(ProcessBuilder.Redirect.appendTo(restartLog));
      Process p = pb.start();
      try (PrintWriter w =
          new PrintWriter(
              new OutputStreamWriter(
                  new FileOutputStream(restartLog, true), StandardCharsets.UTF_8),
              true)) {
        w.println();
        w.println("# relayJvmPid=" + p.pid());
        w.flush();
      }
    } catch (Throwable t) {
      appendRestartFailure(restartLog, t);
      t.printStackTrace();
    }
  }

  private static File restartUnifiedLogPath() {
    String tmp = System.getProperty("java.io.tmpdir");
    if (StringUtils.isBlank(tmp)) {
      throw new IllegalStateException("java.io.tmpdir is not set");
    }
    return new File(tmp, RESTART_LOG_BASENAME);
  }

  private static File restartArgvPath() {
    return new File(restartUnifiedLogPath().getParent(), RESTART_ARGV_BASENAME);
  }

  /** First argument: truncated log shared with parent; second: argv listing for JMeter relaunch. */
  private static List<String> relayLauncherArgv(File unifiedLog, File argvFile) {
    List<String> relay = new ArrayList<>();
    relay.add(jvmExecutablePathLikePluginsManagerSafeDeleter());
    relay.add("-classpath");
    relay.add(blazeMeterPluginClasspathEntry());
    relay.add(BlazeMeterJmeterRestartRelay.class.getCanonicalName());
    relay.add(unifiedLog.getAbsolutePath());
    relay.add(argvFile.getAbsolutePath());
    return relay;
  }

  private static String blazeMeterPluginClasspathEntry() {
    URL loc = BlazeMeterHttpMenuCommand.class.getProtectionDomain().getCodeSource().getLocation();
    if (loc == null) {
      throw new IllegalStateException("BlazeMeter HTTP plugin CodeSource is null");
    }
    try {
      return Paths.get(loc.toURI()).toAbsolutePath().toString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("BlazeMeter HTTP plugin CodeSource URI invalid", e);
    }
  }

  /**
   * Overwrites {@code restartLog} from scratch for this restart. Later stages ({@linkplain
   * BlazeMeterJmeterRestartRelay}, JMeter child) append only until the JVM exits.
   */
  private static void writeRestartDiagnosticHeader(
      File restartLog,
      List<String> jmeterArgv,
      File argvFile,
      List<String> relayArgv)
      throws IOException {
    try (PrintWriter w =
        new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(restartLog), StandardCharsets.UTF_8))) {
      w.println("# BlazeMeter HTTP plugin — JMeter restart diagnostics");
      w.println("# created=" + Instant.now());
      w.println("# parentJvmPid=" + ProcessHandle.current().pid());
      w.println("# argvFile=" + argvFile.getAbsolutePath());
      w.println("# --- JMeter relaunch argv ---");
      for (String arg : jmeterArgv) {
        w.println("#   " + arg);
      }
      w.println(
          "# --- first-stage relay argv (" + BlazeMeterJmeterRestartRelay.class.getSimpleName()
              + ") ---");
      for (String arg : relayArgv) {
        w.println("#   " + arg);
      }
      w.println("# --- first-stage relay stdout/stderr ---");
      w.println("# --- JMeter (third process) stdout/stderr appended after relay ---");
    }
  }

  private static void appendRestartFailure(File log, Throwable t) {
    try (PrintWriter w =
        new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(log, true),
                StandardCharsets.UTF_8),
            true)) {
      w.println();
      w.println("# FAILED starting restart relay JVM:");
      t.printStackTrace(w);
      w.flush();
    } catch (IOException e) {
      t.printStackTrace();
      e.printStackTrace();
    }
  }

  /**
   * Matches {@code org.jmeterplugins.repository.ChangesMaker#getRestartFile} content consumed by
   * {@code SafeDeleter#restartFromFile} (one process argument per line). Reopens the loaded
   * {@code .jmx} via {@code -t} when {@link GuiPackage#getTestPlanFile()} is set, like the Plugins
   * Manager dialog.
   */
  private static List<String> buildRestartCommandLikePluginsManagerChangesMaker(
      File apacheJmeterJar) {
    List<String> cmd = new ArrayList<>();
    cmd.add(jvmExecutablePathLikePluginsManagerSafeDeleter());
    for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      cmd.add(a);
    }
    cmd.add("-jar");
    cmd.add(apacheJmeterJar.getAbsolutePath());
    appendReopenTestPlanArgs(cmd);
    return cmd;
  }

  /** Same as Plugins Manager: {@code -t} + absolute path when a file-backed plan is open. */
  private static void appendReopenTestPlanArgs(List<String> cmd) {
    GuiPackage gui = GuiPackage.getInstance();
    if (gui == null) {
      return;
    }
    String testPlan = gui.getTestPlanFile();
    if (StringUtils.isBlank(testPlan)) {
      return;
    }
    cmd.add("-t");
    cmd.add(new File(testPlan).getAbsolutePath());
  }

  /** Same rule as {@code org.jmeterplugins.repository.SafeDeleter#getJVM()}. */
  private static String jvmExecutablePathLikePluginsManagerSafeDeleter() {
    String javaHome = System.getProperty("java.home");
    if (StringUtils.isBlank(javaHome)) {
      throw new IllegalStateException("java.home is not set");
    }
    String sep = File.separator;
    if (System.getProperty("os.name", "").startsWith("Win")) {
      return javaHome + sep + "bin" + sep + "java.exe";
    }
    return javaHome + sep + "bin" + sep + "java";
  }

  private void migrateEntireTestPlan() {
    GuiPackage gui = GuiPackage.getInstance();
    JMeterTreeModel model = gui.getTreeModel();
    int pending = countMigratableApacheHttpRequestNodes(model);
    if (pending == 0) {
      JOptionPane.showMessageDialog(
          gui.getMainFrame(),
          "No Apache JMeter HTTP Request samplers were found in this test plan.",
          DIALOG_TITLE,
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    String msg =
        "Replace " + pending + " Apache HTTP Request sampler(s) "
            + "with BlazeMeter HTTP sampler(s)?\n\n"
            + "This cannot be undone. Save the test plan first if you need a backup.";
    int ok = JOptionPane.showConfirmDialog(
        gui.getMainFrame(),
        msg,
        "Confirm migration",
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE);
    if (ok != JOptionPane.OK_OPTION) {
      return;
    }

    JTree tree = gui.getTreeListener().getJTree();
    JMeterTreeNode[] selectionSnapshot =
        copySelectedNodes(gui.getTreeListener().getSelectedNodes());
    IdentityHashMap<JMeterTreeNode, JMeterTreeNode> oldToNew = new IdentityHashMap<>();

    int done = 0;
    while (true) {
      JMeterTreeNode next = firstMigratableApacheHttpRequestNode(model);
      if (next == null) {
        break;
      }
      try {
        JMeterTreeNode inserted =
            BlazeMeterHttpSamplerTreeReplacement.replaceHttpSamplerNode(next, false);
        oldToNew.put(next, inserted);
      } catch (RuntimeException ex) {
        LOG.error("Migration failed", ex);
        JMeterUtils.reportErrorToUser(
            "Migration stopped after " + done + " replacement(s): " + ex.getMessage(),
            JMeterUtils.getResString("error_title"),
            ex);
        return;
      }
      done++;
    }
    BlazeMeterHttpSamplerTreeReplacement.restoreSelectionSnapshot(
        tree, selectionSnapshot, oldToNew);
    gui.updateCurrentGui();
    JOptionPane.showMessageDialog(
        gui.getMainFrame(),
        "Migrated " + done + " sampler(s).",
        DIALOG_TITLE,
        JOptionPane.INFORMATION_MESSAGE);
  }

  private void migrateCurrentSelection() {
    GuiPackage gui = GuiPackage.getInstance();
    JMeterTreeNode[] selected = gui.getTreeListener().getSelectedNodes();
    if (selected == null || selected.length == 0) {
      JOptionPane.showMessageDialog(
          gui.getMainFrame(),
          "Select one or more elements in the test tree.",
          DIALOG_TITLE,
          JOptionPane.WARNING_MESSAGE);
      return;
    }

    List<JMeterTreeNode> ordered = BlazeMeterHttpTreeSelectionNormalizer.descendantsOutwardFirst(
        selected);
    List<JMeterTreeNode> toMigrate = new ArrayList<>();
    List<String> skippedLabels = new ArrayList<>();
    for (JMeterTreeNode node : ordered) {
      TestElement te = node.getTestElement();
      if (HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(te)) {
        toMigrate.add(node);
      } else {
        skippedLabels.add(elementLabel(te));
      }
    }

    if (toMigrate.isEmpty()) {
      JOptionPane.showMessageDialog(
          gui.getMainFrame(),
          "None of the selected elements are Apache JMeter HTTP Request samplers.\n\n"
              + formatSkippedList(skippedLabels),
          DIALOG_TITLE,
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }

    StringBuilder confirm = new StringBuilder();
    confirm.append("Replace ").append(toMigrate.size())
        .append(" Apache HTTP Request sampler(s) with BlazeMeter HTTP sampler(s)?\n\n");
    confirm.append("This cannot be undone. Save the test plan first if you need a backup.");
    if (!skippedLabels.isEmpty()) {
      confirm.append("\n\n")
          .append(skippedLabels.size())
          .append(" selected item(s) will be skipped (not Apache HTTP Request):\n");
      confirm.append(formatSkippedList(skippedLabels));
    }
    int ok = JOptionPane.showConfirmDialog(
        gui.getMainFrame(),
        confirm.toString(),
        "Confirm migration",
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE);
    if (ok != JOptionPane.OK_OPTION) {
      return;
    }

    JTree tree = gui.getTreeListener().getJTree();
    JMeterTreeNode[] selectionSnapshot =
        copySelectedNodes(gui.getTreeListener().getSelectedNodes());
    IdentityHashMap<JMeterTreeNode, JMeterTreeNode> oldToNew = new IdentityHashMap<>();

    int done = 0;
    for (JMeterTreeNode node : toMigrate) {
      if (!HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(
          node.getTestElement())) {
        continue;
      }
      try {
        JMeterTreeNode inserted =
            BlazeMeterHttpSamplerTreeReplacement.replaceHttpSamplerNode(node, false);
        oldToNew.put(node, inserted);
        done++;
      } catch (RuntimeException ex) {
        LOG.error("Migration failed on node {}", node, ex);
        JMeterUtils.reportErrorToUser(
            "Migration stopped after " + done + " replacement(s): " + ex.getMessage(),
            JMeterUtils.getResString("error_title"),
            ex);
        return;
      }
    }
    BlazeMeterHttpSamplerTreeReplacement.restoreSelectionSnapshot(
        tree, selectionSnapshot, oldToNew);
    gui.updateCurrentGui();
    JOptionPane.showMessageDialog(
        gui.getMainFrame(),
        "Migrated " + done + " sampler(s).",
        DIALOG_TITLE,
        JOptionPane.INFORMATION_MESSAGE);
  }

  private static JMeterTreeNode[] copySelectedNodes(JMeterTreeNode[] selected) {
    if (selected == null || selected.length == 0) {
      return new JMeterTreeNode[0];
    }
    return Arrays.copyOf(selected, selected.length);
  }

  private static int countMigratableApacheHttpRequestNodes(JMeterTreeModel model) {
    return model.getNodesOfType(HTTPSamplerProxy.class).size()
        + model.getNodesOfType(HTTPSampler.class).size();
  }

  /** Prefers HTTPSamplerProxy (normal GUI wiring) before legacy {@link HTTPSampler} instances. */
  private static JMeterTreeNode firstMigratableApacheHttpRequestNode(JMeterTreeModel model) {
    List<JMeterTreeNode> proxies = model.getNodesOfType(HTTPSamplerProxy.class);
    if (!proxies.isEmpty()) {
      return proxies.get(0);
    }
    List<JMeterTreeNode> legacy = model.getNodesOfType(HTTPSampler.class);
    return legacy.isEmpty() ? null : legacy.get(0);
  }

  private static String elementLabel(TestElement te) {
    String type = te.getClass().getSimpleName();
    String name = te.getName();
    if (name == null || name.isEmpty()) {
      return "[" + type + "]";
    }
    return "\"" + name + "\" [" + type + "]";
  }

  /**
   * Formats at most a few skipped labels plus a total count to keep dialogs readable.
   */
  private static String formatSkippedList(List<String> labels) {
    int maxLines = 8;
    StringBuilder sb = new StringBuilder();
    Iterator<String> it = labels.iterator();
    for (int i = 0; i < maxLines && it.hasNext(); i++) {
      sb.append("- ").append(it.next()).append('\n');
    }
    if (it.hasNext()) {
      int remaining = labels.size() - maxLines;
      sb.append("... and ").append(remaining).append(" more.\n");
    }
    return sb.toString().trim();
  }

  public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
    if (location != MENU_LOCATION.TOOLS) {
      return new JMenuItem[0];
    }
    boolean proxyEnabled =
        BzmHttpPluginProperties.getPropDefault(
            HTTP2SampleCreator.PROXY_ENABLED, HTTP2SampleCreator.PROXY_ENABLED_DEFAULT);

    JMenu root = new JMenu(ROOT_MENU_LABEL);
    ActionRouter router = ActionRouter.getInstance();

    JMenuItem toggle = new JMenuItem(
        proxyEnabled ? LABEL_DISABLE_PROXY_RECORDING : LABEL_ENABLE_PROXY_RECORDING);
    toggle.setName(ACTION_PROXY_TOGGLE);
    toggle.setActionCommand(ACTION_PROXY_TOGGLE);
    toggle.addActionListener(router);

    JMenuItem migrateAll = new JMenuItem(LABEL_MIGRATE_ENTIRE_PLAN);
    migrateAll.setName(ACTION_MIGRATE_ALL);
    migrateAll.setActionCommand(ACTION_MIGRATE_ALL);
    migrateAll.addActionListener(router);

    JMenuItem migrateSel = new JMenuItem(LABEL_MIGRATE_SELECTED);
    migrateSel.setName(ACTION_MIGRATE_SELECTED);
    migrateSel.setActionCommand(ACTION_MIGRATE_SELECTED);
    migrateSel.addActionListener(router);

    root.add(toggle);
    root.addSeparator();
    root.add(migrateAll);
    root.add(migrateSel);
    return new JMenuItem[] {root};
  }

  public JMenu[] getTopLevelMenus() {
    return new JMenu[0];
  }

  public boolean localeChanged(MenuElement menu) {
    return false;
  }

  public void localeChanged() {
    // Labels are fixed English strings; no bundle wiring.
  }
}
