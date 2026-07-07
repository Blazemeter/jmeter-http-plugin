package com.blazemeter.jmeter.http2.sampler.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.event.TableModelEvent;
import org.apache.jmeter.protocol.http.config.gui.UrlConfigGui;

/**
 * Keeps UrlConfigGui parameter / file-upload tables usable after adaptive tab sizing: enforces a
 * minimum visible row count, caps growth at {@code maxDataRows}, then relies on the scroll pane.
 */
public final class UrlConfigTableViewportSizer {

  private static final Object INSTALL_MARK = new Object();

  /** Before the UI delegate lays out the table, {@link JTable#getRowHeight()} is often 0. */
  private static final int MIN_FALLBACK_ROW_HEIGHT_PX = 20;

  private UrlConfigTableViewportSizer() {
  }

  /**
   * Attaches listeners and applies sizes. Safe to call once per {@link UrlConfigGui} instance.
   */
  public static void install(UrlConfigGui root, int minDataRows, int maxDataRows,
      Runnable afterTableShapeChanged) {
    visitScrollableTables(root, minDataRows, maxDataRows, afterTableShapeChanged, true);
  }

  /**
   * Re-applies viewport dimensions (e.g. after {@link UrlConfigGui#configure} or {@code clear}).
   */
  public static void refresh(UrlConfigGui root, int minDataRows, int maxDataRows) {
    visitScrollableTables(root, minDataRows, maxDataRows, null, false);
  }

  private static void visitScrollableTables(Container parent, int minDataRows, int maxDataRows,
      Runnable afterTableShapeChanged, boolean mayInstallListeners) {
    for (Component child : parent.getComponents()) {
      if (child instanceof JScrollPane) {
        JScrollPane scroll = (JScrollPane) child;
        Component view = scroll.getViewport().getView();
        if (view instanceof JTable) {
          JTable table = (JTable) view;
          if (mayInstallListeners && table.getClientProperty(INSTALL_MARK) == null) {
            table.putClientProperty(INSTALL_MARK, Boolean.TRUE);
            table.getModel().addTableModelListener(e -> SwingUtilities.invokeLater(() -> {
              apply(scroll, table, minDataRows, maxDataRows);
              if (e.getType() == TableModelEvent.INSERT) {
                int row = e.getLastRow();
                if (row < 0 || row >= table.getRowCount()) {
                  row = table.getRowCount() - 1;
                }
                final int scrollTo = row;
                SwingUtilities.invokeLater(() -> scrollInsertedRowIntoView(table, scrollTo));
              }
              scheduleLayoutChainRefresh(afterTableShapeChanged);
            }));
            table.addAncestorListener(new AncestorListener() {
              @Override
              public void ancestorAdded(AncestorEvent event) {
                SwingUtilities.invokeLater(
                    () -> apply(scroll, table, minDataRows, maxDataRows));
              }

              @Override
              public void ancestorRemoved(AncestorEvent event) {
                // noop
              }

              @Override
              public void ancestorMoved(AncestorEvent event) {
                // noop
              }
            });
          }
          apply(scroll, table, minDataRows, maxDataRows);
        }
      }
      if (child instanceof Container) {
        visitScrollableTables((Container) child, minDataRows, maxDataRows, afterTableShapeChanged,
            mayInstallListeners);
      }
    }
  }

  /**
   * Runs the refresh after this EDT tick so row heights / scrollable prefs are visible to parent
   * {@code getPreferredSize()} (used by adaptive tab hosts).
   */
  private static void scheduleLayoutChainRefresh(Runnable afterTableShapeChanged) {
    if (afterTableShapeChanged == null) {
      return;
    }
    SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(afterTableShapeChanged));
  }

  private static void apply(JScrollPane scroll, JTable table, int minDataRows, int maxDataRows) {
    int rows = Math.min(Math.max(table.getRowCount(), minDataRows), maxDataRows);
    int h = dataViewportHeightForRows(table, rows);
    Dimension prior = table.getPreferredScrollableViewportSize();
    int w = prior != null && prior.width > 80
        ? prior.width
        : Math.max(360, table.getPreferredSize().width);
    table.setPreferredScrollableViewportSize(new Dimension(w, h));
    int minH = dataViewportHeightForRows(table, minDataRows);
    scroll.getViewport().setMinimumSize(new Dimension(80, minH));
    scroll.setPreferredSize(null);
    scroll.setMinimumSize(null);
    table.setFillsViewportHeight(false);
    scroll.revalidate();
    table.revalidate();
  }

  /**
   * Matches {@code ArgumentsPanel}/{@code HTTPFileArgsPanel} for Parameters; Files Upload's
   * {@code addFile} omits this so the new row can sit below the fold after our capped viewport.
   */
  private static void scrollInsertedRowIntoView(JTable table, int rowIndex) {
    if (rowIndex < 0 || rowIndex >= table.getRowCount()) {
      return;
    }
    if (!(table.getParent() instanceof JViewport)) {
      return;
    }
    Rectangle visibleRect = table.getVisibleRect();
    int col = 0;
    Rectangle cellRect = table.getCellRect(rowIndex, col, false);
    if (visibleRect.y > cellRect.y) {
      table.scrollRectToVisible(cellRect);
    } else {
      int n = estimateVisibleDataRows(table);
      int bottomRow = Math.min(rowIndex + n, table.getRowCount() - 1);
      Rectangle rect2 = table.getCellRect(bottomRow, col, true);
      int dy = rect2.y - cellRect.y;
      table.scrollRectToVisible(
          new Rectangle(cellRect.x, cellRect.y, cellRect.width, cellRect.height + dy));
    }
  }

  private static int estimateVisibleDataRows(JTable table) {
    Rectangle vr = table.getVisibleRect();
    int first = table.rowAtPoint(vr.getLocation());
    vr.translate(0, vr.height);
    int second = table.rowAtPoint(vr.getLocation());
    if (first < 0 || second < 0) {
      return 1;
    }
    return Math.max(1, second - first);
  }

  private static int resolveRowHeight(JTable table) {
    int rh = table.getRowHeight();
    if (rh > 0) {
      return rh;
    }
    return Math.max(MIN_FALLBACK_ROW_HEIGHT_PX,
        table.getFontMetrics(table.getFont()).getHeight() + table.getRowMargin());
  }

  private static int dataViewportHeightForRows(JTable table, int dataRowCount) {
    if (dataRowCount <= 0) {
      return resolveRowHeight(table) + Math.max(2, table.getRowMargin());
    }
    int defaultRh = resolveRowHeight(table);
    int gap = table.getIntercellSpacing().height;
    int acc = 0;
    for (int i = 0; i < dataRowCount; i++) {
      int rh = i < table.getRowCount() ? table.getRowHeight(i) : 0;
      if (rh <= 0) {
        rh = defaultRh;
      }
      acc += rh;
      if (i + 1 < dataRowCount) {
        acc += gap;
      }
    }
    acc += Math.max(2, table.getRowMargin() * 2);
    acc += 4;
    return acc;
  }
}
