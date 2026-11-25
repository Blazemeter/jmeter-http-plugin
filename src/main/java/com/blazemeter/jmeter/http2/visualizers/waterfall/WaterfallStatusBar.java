package com.blazemeter.jmeter.http2.visualizers.waterfall;

import com.blazemeter.jmeter.http2.visualizers.waterfall.render.PhaseLegend;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.WaterfallColors;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The strip along the bottom: how many samples are on show, and the colour key for the bars.
 *
 * <p>These two used to sit at the end of the toolbar, where they were the first things to be pushed
 * off the edge as soon as the viewer was embedded in JMeter's listener panel - and they are exactly
 * the two things that must not disappear. The legend is what makes the bars readable at all, and
 * the counter is the only place the viewer can admit that it dropped older samples. A status bar of
 * their own is always the full width of the panel, so neither can be squeezed out.
 */
public final class WaterfallStatusBar extends JPanel {

  private static final long serialVersionUID = 1L;

  private final JLabel countLabel = new JLabel();

  /** Builds an empty status bar. */
  public WaterfallStatusBar() {
    super(new BorderLayout(12, 0));
    setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(1, 0, 0, 0, WaterfallColors.grid()),
        BorderFactory.createEmptyBorder(3, 6, 2, 6)));
    add(countLabel, BorderLayout.WEST);
    add(new PhaseLegend(), BorderLayout.EAST);
    update(0, 0, 0);
  }

  /**
   * Updates the sample counter.
   *
   * @param visible how many sample rows are on screen
   * @param total   how many samples are retained
   * @param dropped how many were evicted to stay within the retention bound
   */
  public void update(int visible, int total, int dropped) {
    StringBuilder text = new StringBuilder();
    if (visible == total) {
      text.append(total).append(total == 1 ? " sample" : " samples");
    } else {
      text.append(visible).append(" of ").append(total).append(" samples");
    }
    if (dropped > 0) {
      text.append("   (").append(dropped).append(" older samples dropped)");
    }
    countLabel.setText(text.toString());
    countLabel.setToolTipText(dropped > 0
        ? "The viewer keeps a bounded number of samples so a long run cannot exhaust memory."
            + " Raise blazemeter.waterfall.maxSamples to keep more."
        : null);
  }
}
