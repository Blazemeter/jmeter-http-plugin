package com.blazemeter.jmeter.http2.visualizers.waterfall.render;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.TimeAxis;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallFormat;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.TableCellRenderer;

/**
 * The time ruler above the waterfall column.
 *
 * <p>Reads the same {@link TimeAxis} and the same {@link TimeTicks} spacing as the bars below it,
 * which is the only reason the labels can be trusted: a ruler computing its own scale would
 * disagree with the bars by a pixel here and there, and a waterfall whose ruler lies is worse than
 * one with no ruler at all.
 *
 * <p>Labels are offsets from the start of the timeline, not wall-clock times. What a performance
 * question needs is "this request started 1.2 s in and the one before it finished at 1.1 s", and a
 * column of absolute timestamps makes that subtraction the reader's job.
 */
public final class TimelineHeaderRenderer extends JComponent implements TableCellRenderer {

  private static final long serialVersionUID = 1L;

  /** Smallest gap between two labels that still leaves them legible. */
  private static final int MIN_LABEL_SPACING = 70;

  private static final int TICK_HEIGHT = 5;
  private static final int LABEL_INSET = 3;
  private static final int DEFAULT_HEIGHT = 22;

  private final TimeAxis axis;

  /**
   * Creates the ruler.
   *
   * @param axis the axis shared with the bars
   */
  public TimelineHeaderRenderer(TimeAxis axis) {
    this.axis = axis;
    setOpaque(true);
  }

  @Override
  public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
      boolean hasFocus, int rowIndex, int columnIndex) {
    if (table != null && table.getTableHeader() != null) {
      setFont(table.getTableHeader().getFont());
    }
    return this;
  }

  @Override
  public Dimension getPreferredSize() {
    FontMetrics metrics = getFont() != null ? getFontMetrics(getFont()) : null;
    int height = metrics != null
        ? metrics.getHeight() + TICK_HEIGHT + LABEL_INSET * 2
        : DEFAULT_HEIGHT;
    return new Dimension(super.getPreferredSize().width, height);
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int width = getWidth();
      int height = getHeight();
      g.setColor(headerColor("TableHeader.background", WaterfallColors.background()));
      g.fillRect(0, 0, width, height);
      g.setColor(WaterfallColors.grid());
      g.drawLine(0, height - 1, width, height - 1);
      if (width <= 0 || getFont() == null) {
        return;
      }
      paintTicks(g, width, height);
    } finally {
      g.dispose();
    }
  }

  private void paintTicks(Graphics2D g, int width, int height) {
    long step = TimeTicks.step(axis.getViewSpanMs(), width, MIN_LABEL_SPACING);
    long origin = axis.getBoundsStart();
    long tick = TimeTicks.firstTick(origin, axis.getViewStart(), step);
    FontMetrics metrics = g.getFontMetrics(getFont());
    int baseline = height - TICK_HEIGHT - LABEL_INSET;
    while (tick <= axis.getViewEnd()) {
      int x = (int) Math.round(axis.xFor(tick, width));
      g.setColor(WaterfallColors.mutedForeground());
      g.drawLine(x, height - TICK_HEIGHT - 1, x, height - 2);
      String label = WaterfallFormat.tick(tick - origin);
      int labelWidth = metrics.stringWidth(label);
      // Anchor the label to the left of its own tick, except at the right edge where that would
      // push it out of the cell and clip it away.
      int labelX = x + LABEL_INSET;
      if (labelX + labelWidth > width) {
        labelX = x - LABEL_INSET - labelWidth;
      }
      if (labelX >= 0) {
        g.setColor(headerColor("TableHeader.foreground", WaterfallColors.foreground()));
        g.drawString(label, labelX, baseline);
      }
      tick += step;
    }
  }

  private static Color headerColor(String key, Color fallback) {
    Color color = UIManager.getColor(key);
    return color != null ? color : fallback;
  }
}
