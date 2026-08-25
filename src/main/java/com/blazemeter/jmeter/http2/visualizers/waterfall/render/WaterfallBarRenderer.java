package com.blazemeter.jmeter.http2.visualizers.waterfall.render;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.PhaseBreakdown;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.TimeAxis;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallFormat;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallRow;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;

/**
 * Paints the waterfall column: one bar per row, split into its timing phases, positioned by the
 * shared {@link TimeAxis} so that every bar in the table is on the same time scale.
 *
 * <p>A single instance serves the whole column, which is what makes tens of thousands of rows
 * affordable: JTable asks the renderer for one cell at a time and only for the rows on screen, so
 * nothing is allocated per row and no component tree is built.
 *
 * <p>Two details keep the picture honest at small scales. A bar narrower than a couple of pixels
 * is widened to a marker, because a 3 ms request in a five-minute run would otherwise vanish
 * entirely and read as "no sample here". And each non-empty phase is guaranteed at least one
 * pixel while segment boundaries are accumulated in floating point, so a bar never loses a phase
 * to rounding nor drifts away from its own end time.
 */
public final class WaterfallBarRenderer extends JComponent implements TableCellRenderer {

  private static final long serialVersionUID = 1L;

  /** Narrower than this and a bar stops being visible at all, so it is widened to this. */
  private static final int MIN_BAR_WIDTH = 3;

  private static final int MIN_BAR_HEIGHT = 5;
  private static final int MAX_BAR_HEIGHT = 14;
  private static final int VERTICAL_PADDING = 6;

  /** Gap between the end of a bar and its duration label. */
  private static final int LABEL_GAP = 6;

  private static final int MIN_GRID_SPACING = 60;
  private static final float GROUP_BAR_ALPHA = 0.35f;

  private final TimeAxis axis;
  private transient WaterfallRow row;
  private boolean selected;
  private Color rowBackground;

  /**
   * Creates the renderer.
   *
   * @param axis the axis shared with the ruler, read at paint time so a zoom needs only a repaint
   */
  public WaterfallBarRenderer(TimeAxis axis) {
    this.axis = axis;
    setOpaque(true);
  }

  @Override
  public Component getTableCellRendererComponent(JTable table, Object value,
      boolean isSelected, boolean hasFocus, int rowIndex, int columnIndex) {
    this.row = value instanceof WaterfallRow ? (WaterfallRow) value : null;
    this.selected = isSelected;
    if (isSelected) {
      rowBackground = table.getSelectionBackground();
    } else if (this.row != null && this.row.isGroup()) {
      rowBackground = WaterfallColors.groupBackground();
    } else {
      rowBackground = table.getBackground();
    }
    setFont(table.getFont());
    return this;
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int width = getWidth();
      int height = getHeight();
      g.setColor(rowBackground);
      g.fillRect(0, 0, width, height);
      paintGrid(g, width, height);
      if (row == null || width <= 0) {
        return;
      }
      if (row.isGroup()) {
        paintGroupBar(g, width, height);
      } else {
        paintSampleBar(g, width, height);
      }
    } finally {
      g.dispose();
    }
  }

  /**
   * Draws the vertical gridlines, at the same instants the ruler labels.
   *
   * @param g      the target
   * @param width  the cell width
   * @param height the cell height
   */
  private void paintGrid(Graphics2D g, int width, int height) {
    long step = TimeTicks.step(axis.getViewSpanMs(), width, MIN_GRID_SPACING);
    long tick = TimeTicks.firstTick(axis.getBoundsStart(), axis.getViewStart(), step);
    g.setColor(WaterfallColors.grid());
    while (tick <= axis.getViewEnd()) {
      int x = (int) Math.round(axis.xFor(tick, width));
      g.drawLine(x, 0, x, height);
      tick += step;
    }
  }

  /**
   * Draws one sample as adjacent phase segments.
   *
   * @param g      the target
   * @param width  the cell width
   * @param height the cell height
   */
  private void paintSampleBar(Graphics2D g, int width, int height) {
    PhaseBreakdown phases = row.getRecord().getPhases();
    int barHeight = barHeight(height);
    int y = (height - barHeight) / 2;
    double left = axis.xFor(row.getStartTime(), width);
    double right = axis.xFor(row.getEndTime(), width);
    if (right - left < MIN_BAR_WIDTH) {
      right = left + MIN_BAR_WIDTH;
    }
    long span = Math.max(1, phases.getSpanMs());
    double scale = (right - left) / span;
    double cursor = left;
    cursor = paintSegment(g, cursor, scale, phases.getIdleMs(), y, barHeight,
        WaterfallColors.idle());
    cursor = paintSegment(g, cursor, scale, phases.getConnectMs(), y, barHeight,
        WaterfallColors.connect());
    cursor = paintSegment(g, cursor, scale, phases.getTtfbMs(), y, barHeight,
        WaterfallColors.ttfb());
    cursor = paintSegment(g, cursor, scale, phases.getDownloadMs(), y, barHeight,
        WaterfallColors.download());
    cursor = paintSegment(g, cursor, scale, phases.getUnbrokenMs(), y, barHeight,
        WaterfallColors.unbroken());
    paintDurationLabel(g, left, cursor, width, height,
        WaterfallFormat.duration(row.getRecord().getElapsed()));
  }

  /**
   * Draws one phase and returns where the next one starts.
   *
   * @param g          the target
   * @param cursor     the sub-pixel left edge of this segment
   * @param scale      pixels per millisecond
   * @param millis     the phase duration; a zero duration draws nothing
   * @param y          the top of the bar
   * @param barHeight  the bar height
   * @param color      the phase colour
   * @return the sub-pixel left edge of the next segment
   */
  private double paintSegment(Graphics2D g, double cursor, double scale, long millis, int y,
      int barHeight, Color color) {
    if (millis <= 0) {
      return cursor;
    }
    double next = cursor + millis * scale;
    int x = (int) Math.round(cursor);
    int segmentWidth = Math.max(1, (int) Math.round(next) - x);
    g.setColor(color);
    g.fillRect(x, y, segmentWidth, barHeight);
    return next;
  }

  /**
   * Draws a group heading's span as a hollow bar, so it reads as an envelope around its samples
   * rather than as a sample of its own.
   *
   * @param g      the target
   * @param width  the cell width
   * @param height the cell height
   */
  private void paintGroupBar(Graphics2D g, int width, int height) {
    int barHeight = barHeight(height);
    int y = (height - barHeight) / 2;
    int left = (int) Math.round(axis.xFor(row.getStartTime(), width));
    int right = (int) Math.round(axis.xFor(row.getEndTime(), width));
    int barWidth = Math.max(MIN_BAR_WIDTH, right - left);
    Color base = row.getErrorCount() > 0 ? WaterfallColors.error() : WaterfallColors.download();
    g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(),
        Math.round(255 * GROUP_BAR_ALPHA)));
    g.fillRect(left, y, barWidth, barHeight);
    g.setColor(base);
    g.drawRect(left, y, barWidth, barHeight);
    paintDurationLabel(g, left, left + (double) barWidth, width, height,
        WaterfallFormat.duration(row.getEndTime() - row.getStartTime()));
  }

  /**
   * Writes the duration next to the bar, on whichever side has room. Suppressed when neither side
   * fits, rather than overlapping the bar it describes.
   *
   * @param g      the target
   * @param left   the bar's left edge
   * @param right  the bar's right edge
   * @param width  the cell width
   * @param height the cell height
   * @param text   the label
   */
  private void paintDurationLabel(Graphics2D g, double left, double right, int width, int height,
      String text) {
    Font font = getFont();
    if (font == null) {
      return;
    }
    g.setFont(font);
    FontMetrics metrics = g.getFontMetrics();
    int textWidth = metrics.stringWidth(text);
    int baseline = (height + metrics.getAscent() - metrics.getDescent()) / 2;
    int x = (int) Math.round(right) + LABEL_GAP;
    if (x + textWidth > width) {
      x = (int) Math.round(left) - LABEL_GAP - textWidth;
    }
    if (x < 0) {
      return;
    }
    g.setColor(selected ? WaterfallColors.foreground() : WaterfallColors.mutedForeground());
    g.drawString(text, x, baseline);
  }

  private static int barHeight(int rowHeight) {
    return Math.max(MIN_BAR_HEIGHT, Math.min(MAX_BAR_HEIGHT, rowHeight - VERTICAL_PADDING));
  }
}
