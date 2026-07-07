package com.blazemeter.jmeter.http2.sampler.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.apache.jmeter.protocol.http.config.gui.UrlConfigGui;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Swing {@link JTabbedPane} reserves the content area height for the <em>tallest</em> tab. Nested
 * panes (Basic/Advanced and Parameters/Body/Files) compound the effect and keep the sampler panel
 * (and logo below) sized for the worst case. This host ties the tabbed pane's vertical size to the
 * <em>selected</em> tab and uses vertical glue so extra space sits below the stack, then propagates
 * layout up the tree.
 */
public final class AdaptiveTabbedPaneHeightHost extends JPanel {

  private static final long serialVersionUID = 240L;

  private final JTabbedPane tabbedPane;
  private final Runnable afterLocalTabSync;

  /**
   * @param tabbedPane         pane to host (becomes child; do not add it elsewhere first)
   * @param afterLocalTabSync  optional run after this pane resizes on tab change (e.g. refresh an
   *                           outer tab host)
   */
  public AdaptiveTabbedPaneHeightHost(JTabbedPane tabbedPane, Runnable afterLocalTabSync) {
    super();
    this.tabbedPane = tabbedPane;
    this.afterLocalTabSync = afterLocalTabSync;
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    tabbedPane.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    add(tabbedPane);
    add(Box.createVerticalGlue());
    sync();
    tabbedPane.addChangeListener(e -> SwingUtilities.invokeLater(this::onLocalTabChanged));
  }

  private void onLocalTabChanged() {
    sync();
    invalidate();
    revalidate();
    repaint();
    revalidateAncestors(this);
    if (afterLocalTabSync != null) {
      afterLocalTabSync.run();
    }
  }

  /**
   * Recomputes preferred height from the current tab (public so nested hosts can trigger a parent
   * refresh).
   */
  public void sync() {
    tabbedPane.setPreferredSize(null);
    tabbedPane.setMinimumSize(null);
    tabbedPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

    Dimension panePref = tabbedPane.getPreferredSize();
    int maxChildH = 0;
    for (int i = 0; i < tabbedPane.getTabCount(); i++) {
      Component comp = tabbedPane.getComponentAt(i);
      if (comp != null) {
        maxChildH = Math.max(maxChildH, comp.getPreferredSize().height);
      }
    }
    if (maxChildH == 0) {
      return;
    }
    int tabStrip = Math.max(0, panePref.height - maxChildH);
    Component sel = tabbedPane.getSelectedComponent();
    int selH = sel != null ? sel.getPreferredSize().height : maxChildH;
    int h = Math.max(tabStrip + selH, tabStrip + 80);
    int w = panePref.width > 0 ? panePref.width : Math.max(tabbedPane.getWidth(), 1);
    Dimension sized = new Dimension(w, h);
    tabbedPane.setPreferredSize(sized);
    tabbedPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
    tabbedPane.setMinimumSize(new Dimension(0, h));
  }

  @Override
  public Dimension getPreferredSize() {
    if (isPreferredSizeSet()) {
      return super.getPreferredSize();
    }
    Insets in = getInsets();
    Dimension tp = tabbedPane.getPreferredSize();
    return new Dimension(tp.width + in.left + in.right,
        tp.height + in.top + in.bottom);
  }

  @Override
  public Dimension getMaximumSize() {
    return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
  }

  /**
   * Replaces UrlConfigGui's Parameters / Body / Files tabbed pane with an adaptive host.
   *
   * @return the new host, or {@code null} if not wrapped
   */
  public static AdaptiveTabbedPaneHeightHost wrapUrlConfigPostTabsIfPresent(
      UrlConfigGui urlConfigGui, Runnable afterInnerTabChange) {
    JTabbedPane target = findUrlConfigPostTabbedPane(urlConfigGui);
    if (target == null) {
      return null;
    }
    Container parent = target.getParent();
    if (!(parent instanceof JPanel)) {
      return null;
    }
    if (!(parent.getLayout() instanceof BorderLayout)) {
      return null;
    }
    parent.remove(target);
    AdaptiveTabbedPaneHeightHost host =
        new AdaptiveTabbedPaneHeightHost(target, afterInnerTabChange);
    parent.add(host, BorderLayout.CENTER);
    parent.revalidate();
    parent.repaint();
    return host;
  }

  public static void revalidateAncestors(JComponent c) {
    for (Container p = c.getParent(); p != null; p = p.getParent()) {
      p.invalidate();
      if (p instanceof JComponent) {
        ((JComponent) p).revalidate();
      }
    }
  }

  /**
   * Locates {@link UrlConfigGui}'s Parameters / Body / Files tabbed pane (may sit inside an
   * {@link AdaptiveTabbedPaneHeightHost} after wrapping).
   */
  public static JTabbedPane findUrlConfigPostTabbedPane(Container root) {
    String paramTitle = JMeterUtils.getResString("post_as_parameters");
    for (Component child : root.getComponents()) {
      if (child instanceof JTabbedPane) {
        JTabbedPane tp = (JTabbedPane) child;
        if (tp.getTabCount() > 0 && paramTitle.equals(tp.getTitleAt(0))) {
          return tp;
        }
      }
      if (child instanceof Container) {
        JTabbedPane nested = findUrlConfigPostTabbedPane((Container) child);
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }
}
