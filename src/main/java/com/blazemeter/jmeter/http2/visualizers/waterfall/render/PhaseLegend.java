package com.blazemeter.jmeter.http2.visualizers.waterfall.render;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The colour key for the waterfall bars.
 *
 * <p>Worth the strip of screen it takes because the phase colours are the whole point of the view,
 * and because one of them - the slate "no phase data" segment - has no equivalent in a browser, so
 * a reader who knows DevTools by heart still needs telling what it means.
 */
public final class PhaseLegend extends JPanel {

  private static final long serialVersionUID = 1L;

  private static final int SWATCH_SIZE = 9;
  private static final int GAP = 4;
  private static final int ITEM_GAP = 12;

  /** Creates the legend. */
  public PhaseLegend() {
    setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
    addItem("Queueing", WaterfallColors.idle());
    addItem("Connect", WaterfallColors.connect());
    addItem("Waiting (TTFB)", WaterfallColors.ttfb());
    addItem("Download", WaterfallColors.download());
    addItem("No phase data", WaterfallColors.unbroken());
    add(Box.createHorizontalGlue());
  }

  private void addItem(String name, Color color) {
    add(new Swatch(color));
    add(Box.createHorizontalStrut(GAP));
    JLabel label = new JLabel(name);
    label.setEnabled(false);
    add(label);
    add(Box.createHorizontalStrut(ITEM_GAP));
  }

  /** A small square of one phase colour. */
  private static final class Swatch extends JComponent {

    private static final long serialVersionUID = 1L;

    private final Color color;

    private Swatch(Color color) {
      this.color = color;
      Dimension size = new Dimension(SWATCH_SIZE, SWATCH_SIZE);
      setPreferredSize(size);
      setMinimumSize(size);
      setMaximumSize(size);
    }

    @Override
    protected void paintComponent(Graphics g) {
      g.setColor(color);
      g.fillRect(0, 0, getWidth(), getHeight());
    }
  }
}
