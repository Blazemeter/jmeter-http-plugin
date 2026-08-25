package com.blazemeter.jmeter.http2.visualizers.waterfall.render;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallColumn;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallTableModel;
import java.awt.Component;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.TableCellRenderer;

/**
 * Adds the sort indicator to the table header.
 *
 * <p>The model sorts its own rows rather than using a {@code TableRowSorter}, which means JTable
 * draws no arrow of its own. This wraps whatever header renderer the current look and feel
 * installed and only sets an icon on it, so the header keeps the platform's own appearance -
 * including under JMeter's dark theme - instead of being redrawn from scratch here.
 */
public final class WaterfallHeaderRenderer implements TableCellRenderer {

  private final TableCellRenderer delegate;
  private final WaterfallTableModel model;

  /**
   * Wraps a header renderer.
   *
   * @param delegate the look and feel's own header renderer
   * @param model    the model holding the current sort state
   */
  public WaterfallHeaderRenderer(TableCellRenderer delegate, WaterfallTableModel model) {
    this.delegate = delegate;
    this.model = model;
  }

  @Override
  public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
      boolean hasFocus, int rowIndex, int columnIndex) {
    Component component = delegate.getTableCellRendererComponent(table, value, isSelected,
        hasFocus, rowIndex, columnIndex);
    if (!(component instanceof JLabel)) {
      return component;
    }
    JLabel label = (JLabel) component;
    WaterfallColumn column = model.getColumn(columnIndex);
    label.setIcon(column == model.getSortColumn() ? sortIcon(model.isSortAscending()) : null);
    label.setHorizontalTextPosition(SwingConstants.LEADING);
    return label;
  }

  private static Icon sortIcon(boolean ascending) {
    return UIManager.getIcon(ascending ? "Table.ascendingSortIcon" : "Table.descendingSortIcon");
  }
}
