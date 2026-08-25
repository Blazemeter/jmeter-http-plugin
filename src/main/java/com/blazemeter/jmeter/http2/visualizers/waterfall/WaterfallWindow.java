package com.blazemeter.jmeter.http2.visualizers.waterfall;

import java.awt.BorderLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import org.apache.jmeter.gui.GuiPackage;

/**
 * The detached window: the same waterfall panel, given the whole screen.
 *
 * <p>This is meant to be where the analysis happens. A waterfall is a picture whose usefulness is
 * proportional to its width - a bar three pixels wide says nothing about which phase dominated it -
 * and JMeter's listener panel is a fraction of a screen with a test plan tree beside it. So the
 * window opens maximised, and F11 takes it fullscreen for a projector or a second monitor.
 *
 * <p>Closing it never destroys anything: the panel is handed back to the listener slot it came
 * from, with its samples, filters and zoom level intact.
 */
final class WaterfallWindow {

  private static final String TITLE = "Waterfall Viewer";
  private static final int FALLBACK_WIDTH = 1400;
  private static final int FALLBACK_HEIGHT = 900;

  private final JFrame frame = new JFrame(TITLE);
  private final WaterfallPanel panel;
  private boolean fullScreen;

  /**
   * Builds the window around a panel.
   *
   * @param panel   the panel to host
   * @param onClose what to run when the window is closed, which is what puts the panel back
   */
  WaterfallWindow(WaterfallPanel panel, Runnable onClose) {
    this.panel = panel;
    JPanel content = new JPanel(new BorderLayout());
    content.add(panel, BorderLayout.CENTER);
    frame.setContentPane(content);
    // The window must not dispose itself: closing it has to hand the panel back first.
    frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    frame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent event) {
        onClose.run();
      }
    });
    adoptJmeterIcons();
    installShortcuts(content);
  }

  /**
   * Reuses JMeter's own window icons, so the expanded viewer looks like part of the application
   * rather than a stray dialog in the taskbar.
   */
  private void adoptJmeterIcons() {
    GuiPackage guiPackage = GuiPackage.getInstance();
    if (guiPackage == null || guiPackage.getMainFrame() == null) {
      return;
    }
    if (!guiPackage.getMainFrame().getIconImages().isEmpty()) {
      frame.setIconImages(guiPackage.getMainFrame().getIconImages());
    }
  }

  /**
   * F11 toggles fullscreen and Escape leaves it, matching what every other viewer with a fullscreen
   * mode does.
   *
   * @param content the frame's content pane, which owns the key bindings
   */
  private void installShortcuts(JComponent content) {
    content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "waterfallFullScreen");
    content.getActionMap().put("waterfallFullScreen", new AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(ActionEvent event) {
        toggleFullScreen();
      }
    });
    content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "waterfallExitFullScreen");
    content.getActionMap().put("waterfallExitFullScreen", new AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(ActionEvent event) {
        if (fullScreen) {
          toggleFullScreen();
        }
      }
    });
  }

  /** Shows the window maximised. */
  void open() {
    frame.setSize(FALLBACK_WIDTH, FALLBACK_HEIGHT);
    frame.setLocationRelativeTo(null);
    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
    frame.setVisible(true);
    panel.getToolBar().updateExpandButton();
    frame.toFront();
  }

  /** Takes the panel out and disposes of the window. */
  void close() {
    leaveFullScreen();
    frame.getContentPane().removeAll();
    frame.dispose();
  }

  /**
   * Switches between fullscreen and maximised.
   *
   * <p>Exclusive fullscreen is a request the platform can refuse - a window manager may decline, or
   * there may be no device to give. Maximised is the fallback, which is close enough that the user
   * still gets the screen space they asked for.
   */
  private void toggleFullScreen() {
    if (fullScreen) {
      leaveFullScreen();
      return;
    }
    GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .getDefaultScreenDevice();
    if (device.getFullScreenWindow() != null) {
      return;
    }
    device.setFullScreenWindow(frame);
    fullScreen = device.getFullScreenWindow() == frame;
    if (!fullScreen) {
      frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
    }
  }

  private void leaveFullScreen() {
    if (!fullScreen) {
      return;
    }
    fullScreen = false;
    GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
        .setFullScreenWindow(null);
    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
  }
}
