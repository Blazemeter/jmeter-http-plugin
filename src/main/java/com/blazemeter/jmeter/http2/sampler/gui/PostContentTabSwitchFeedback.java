package com.blazemeter.jmeter.http2.sampler.gui;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.config.gui.UrlConfigGui;
import org.apache.jmeter.protocol.http.gui.HTTPArgumentsPanel;
import org.apache.jmeter.util.JMeterUtils;

/**
 * {@link UrlConfigGui} blocks some tab switches (Parameters / Body Data) without telling the
 * user. We detect a click that would have changed the tab but did not, and show why.
 */
public final class PostContentTabSwitchFeedback {

  private static final Object INSTALL_MARK = new Object();

  private static final String TITLE_RAW_BODY_BLOCKED = "Cannot switch to Body Data";
  private static final String MESSAGE_RAW_BODY_BLOCKED =
      "Body Data is only available when there are no named parameters in the Parameters tab.\n"
          + "Remove those rows or clear every name in the table, then try again.";
  private static final String TITLE_PARAMETERS_BLOCKED = "Cannot switch to Parameters";
  private static final String MESSAGE_PARAMETERS_BLOCKED =
      "You can open the Parameters tab only when Body Data is completely empty.\n"
          + "Delete or clear all content in Body Data first.";

  private PostContentTabSwitchFeedback() {
  }

  /**
   * Adds a one-shot mouse listener on the post tabbed pane inside {@code urlConfigGui}.
   */
  public static void installIfApplicable(UrlConfigGui urlConfigGui) {
    JTabbedPane pane = AdaptiveTabbedPaneHeightHost.findUrlConfigPostTabbedPane(urlConfigGui);
    if (pane == null || pane.getClientProperty(INSTALL_MARK) != null) {
      return;
    }
    int paramsIdx = tabIndexForTitle(pane, "post_as_parameters");
    int rawIdx = tabIndexForTitle(pane, "post_body");
    if (paramsIdx < 0 || rawIdx < 0) {
      return;
    }
    Component paramsComp = pane.getComponentAt(paramsIdx);
    if (!(paramsComp instanceof HTTPArgumentsPanel)) {
      return;
    }
    HTTPArgumentsPanel argsPanel = (HTTPArgumentsPanel) paramsComp;
    pane.putClientProperty(INSTALL_MARK, Boolean.TRUE);
    pane.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseReleased(MouseEvent e) {
        if (!pane.isEnabled() || !SwingUtilities.isLeftMouseButton(e)) {
          return;
        }
        int clicked = pane.indexAtLocation(e.getX(), e.getY());
        if (clicked < 0) {
          return;
        }
        int selected = pane.getSelectedIndex();
        if (clicked == selected) {
          return;
        }
        if (clicked == rawIdx && selected != rawIdx && !canSwitchToRawBody(argsPanel)) {
          explain(pane, TITLE_RAW_BODY_BLOCKED, MESSAGE_RAW_BODY_BLOCKED);
          return;
        }
        if (clicked == paramsIdx && selected != paramsIdx && !canSwitchToParameters(pane, rawIdx)) {
          explain(pane, TITLE_PARAMETERS_BLOCKED, MESSAGE_PARAMETERS_BLOCKED);
        }
      }
    });
  }

  private static void explain(JTabbedPane pane, String title, String message) {
    SwingUtilities.invokeLater(() -> {
      Window owner = SwingUtilities.getWindowAncestor(pane);
      if (owner == null) {
        owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
      }
      JOptionPane.showMessageDialog(owner, message, title,
          JOptionPane.INFORMATION_MESSAGE);
    });
  }

  private static int tabIndexForTitle(JTabbedPane tp, String jmeterMessageKey) {
    String want = JMeterUtils.getResString(jmeterMessageKey);
    for (int i = 0; i < tp.getTabCount(); i++) {
      if (want.equals(tp.getTitleAt(i))) {
        return i;
      }
    }
    return -1;
  }

  private static boolean canSwitchToRawBody(HTTPArgumentsPanel argsPanel) {
    Arguments arguments = (Arguments) argsPanel.createTestElement();
    for (int i = 0; i < arguments.getArgumentCount(); i++) {
      if (!StringUtils.isEmpty(arguments.getArgument(i).getName())) {
        return false;
      }
    }
    return true;
  }

  private static boolean canSwitchToParameters(JTabbedPane pane, int rawBodyTabIndex) {
    return getRawBodyText(pane, rawBodyTabIndex).isEmpty();
  }

  private static String getRawBodyText(JTabbedPane pane, int rawBodyTabIndex) {
    if (rawBodyTabIndex < 0 || rawBodyTabIndex >= pane.getTabCount()) {
      return "";
    }
    Component body = pane.getComponentAt(rawBodyTabIndex);
    if (body instanceof JScrollPane) {
      Component view = ((JScrollPane) body).getViewport().getView();
      if (view instanceof JTextComponent) {
        return ((JTextComponent) view).getText();
      }
    }
    if (body instanceof JTextArea) {
      return ((JTextArea) body).getText();
    }
    return "";
  }
}
