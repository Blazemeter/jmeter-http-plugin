package com.blazemeter.jmeter.http2.visualizers.waterfall.render;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.TimeAxis;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallColumn;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallFormat;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallRow;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Renders every text column of the waterfall table. One instance is installed per column and
 * knows, from the {@link WaterfallColumn} it was built for, what to read off the row.
 *
 * <p>Driving all the columns from one class keeps two things in one place that would otherwise
 * drift apart: how a group heading differs from a sample row in each column, and the text a cell
 * shows - which {@link #textFor} also supplies to the clipboard export, so what gets copied is
 * exactly what was on screen.
 *
 * <p>Every cell has its foreground and its background assigned explicitly, on every render, with no
 * "leave it as the default" branch. That is not tidiness: the default renderer treats a
 * {@code setForeground} call as the renderer's new unselected default and re-applies it to every
 * later cell, so
 * colouring one failed row red used to turn every row below it red as well. One renderer instance
 * serving a whole column means its state has to be fully reset per cell.
 */
public final class WaterfallCellRenderer extends DefaultTableCellRenderer {

  private static final long serialVersionUID = 1L;

  /** Pixels of indent per sub-sample generation. */
  private static final int INDENT_STEP = 14;

  private static final int CELL_PADDING = 4;

  /** Shown where a column has nothing to say, so an empty cell does not read as a missing one. */
  private static final String ABSENT = "-";

  private final WaterfallColumn column;
  private final TimeAxis axis;

  /**
   * Creates the renderer for one column.
   *
   * @param column the column being rendered
   * @param axis   the shared axis, needed by the Start column to express a start relative to the
   *               beginning of the timeline rather than as an epoch value
   */
  public WaterfallCellRenderer(WaterfallColumn column, TimeAxis axis) {
    this.column = column;
    this.axis = axis;
    setHorizontalAlignment(alignmentFor(column));
  }

  private static int alignmentFor(WaterfallColumn column) {
    switch (column) {
      case SIZE:
      case TIME:
      case START:
        return SwingConstants.RIGHT;
      default:
        return SwingConstants.LEFT;
    }
  }

  @Override
  public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
      boolean hasFocus, int rowIndex, int columnIndex) {
    WaterfallRow row = value instanceof WaterfallRow ? (WaterfallRow) value : null;
    super.getTableCellRendererComponent(table, textFor(row, column, axis), isSelected, hasFocus,
        rowIndex, columnIndex);
    setIcon(null);
    setBorder(BorderFactory.createEmptyBorder(0, CELL_PADDING, 0, CELL_PADDING));
    setFont(row != null && row.isGroup() ? table.getFont().deriveFont(Font.BOLD)
        : table.getFont());
    setBackground(backgroundFor(table, row, isSelected));
    setForeground(foregroundFor(table, row, isSelected));
    if (row == null) {
      return this;
    }
    if (row.isGroup()) {
      applyGroupName(row);
    } else if (column == WaterfallColumn.NAME && row.getRecord().getDepth() > 0) {
      setBorder(BorderFactory.createEmptyBorder(0,
          CELL_PADDING + row.getRecord().getDepth() * INDENT_STEP, 0, CELL_PADDING));
    }
    return this;
  }

  /**
   * The background of a cell.
   *
   * @param table      the table being painted
   * @param row        the row, may be {@code null}
   * @param isSelected whether the row is selected
   * @return the colour to paint behind the text
   */
  private static Color backgroundFor(JTable table, WaterfallRow row, boolean isSelected) {
    if (isSelected) {
      return table.getSelectionBackground();
    }
    return row != null && row.isGroup() ? WaterfallColors.groupBackground()
        : table.getBackground();
  }

  /**
   * The colour of a cell's text: the status class in the Status column, the failure colour across
   * a failed row, and a muted tone for the secondary columns.
   *
   * @param table      the table being painted
   * @param row        the row, may be {@code null}
   * @param isSelected whether the row is selected
   * @return the colour to draw the text in
   */
  private Color foregroundFor(JTable table, WaterfallRow row, boolean isSelected) {
    if (isSelected) {
      return table.getSelectionForeground();
    }
    if (row == null || row.isGroup()) {
      return WaterfallColors.foreground();
    }
    SampleRecord record = row.getRecord();
    if (column == WaterfallColumn.STATUS) {
      return WaterfallColors.status(record.getStatusClass(), record.isSuccess());
    }
    if (!record.isSuccess()) {
      return WaterfallColors.error();
    }
    if (column == WaterfallColumn.TYPE || column == WaterfallColumn.THREAD) {
      return WaterfallColors.mutedForeground();
    }
    return WaterfallColors.foreground();
  }

  /**
   * Writes a group heading's Name cell: the look and feel's own tree disclosure icon, so it matches
   * whatever theme JMeter is running, plus the size and failure count.
   *
   * @param row the group row
   */
  private void applyGroupName(WaterfallRow row) {
    if (column != WaterfallColumn.NAME) {
      return;
    }
    setIcon(UIManager.getIcon(row.isCollapsed() ? "Tree.collapsedIcon" : "Tree.expandedIcon"));
    String errors = row.getErrorCount() > 0 ? ", " + row.getErrorCount() + " failed" : "";
    setText(row.getGroupName() + "  (" + row.getSampleCount() + errors + ")");
  }

  /**
   * The text a cell shows.
   *
   * <p>Also used when copying rows to the clipboard, so the two can never disagree.
   *
   * @param row    the row, may be {@code null} for a stale index
   * @param column the column
   * @param axis   the shared axis, for the relative Start column
   * @return the cell text, never {@code null}
   */
  public static String textFor(WaterfallRow row, WaterfallColumn column, TimeAxis axis) {
    if (row == null) {
      return "";
    }
    if (row.isGroup()) {
      return groupTextFor(row, column, axis);
    }
    SampleRecord record = row.getRecord();
    switch (column) {
      case NAME:
        return record.getLabel();
      case METHOD:
        return orAbsent(record.getMethod());
      case STATUS:
        return orAbsent(record.getResponseCode());
      case PROTOCOL:
        return orAbsent(record.getProtocol());
      case TYPE:
        return orAbsent(record.getContentType());
      case SIZE:
        return WaterfallFormat.size(record.getBytes());
      case TIME:
        return WaterfallFormat.duration(record.getElapsed());
      case START:
        return WaterfallFormat.duration(record.getStartTime() - axis.getBoundsStart());
      case THREAD:
        return record.getThreadName();
      default:
        return "";
    }
  }

  private static String groupTextFor(WaterfallRow row, WaterfallColumn column, TimeAxis axis) {
    switch (column) {
      case NAME:
        return row.getGroupName();
      case SIZE:
        return WaterfallFormat.size(row.getBytes());
      case TIME:
        return WaterfallFormat.duration(row.getEndTime() - row.getStartTime());
      case START:
        return WaterfallFormat.duration(row.getStartTime() - axis.getBoundsStart());
      default:
        return "";
    }
  }

  private static String orAbsent(String value) {
    return value == null || value.isEmpty() ? ABSENT : value;
  }
}
