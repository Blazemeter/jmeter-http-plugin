package com.blazemeter.jmeter.http2.visualizers.waterfall;

import com.blazemeter.jmeter.http2.visualizers.waterfall.details.HtmlDoc;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.PhaseBreakdown;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.TimeAxis;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallColumn;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallFormat;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallRow;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallTableModel;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.TimelineHeaderRenderer;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.WaterfallBarRenderer;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.WaterfallCellRenderer;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.WaterfallColors;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.WaterfallHeaderRenderer;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.TableModelEvent;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

/**
 * The waterfall table: the sample columns on the left, the bars on the right, one row per sample.
 *
 * <p>Making the bars the table's last column, rather than a separate component synchronised with
 * the table, is the decision the rest of this class rests on. It means the rows cannot drift out of
 * alignment, the selection and the scroll position are shared by construction, and JTable's own
 * virtualisation covers the bars as well as the text - only the rows on screen are ever painted,
 * whether the model holds fifty samples or fifty thousand.
 *
 * <p>What that leaves this class to do is the interaction a plain table has no notion of: zooming
 * and panning the time axis, dragging a range on the ruler, tooltips that describe a bar's phases,
 * folding group headings, and copying a selection out as text.
 */
public final class WaterfallTable extends JTable {

  private static final long serialVersionUID = 1L;

  private static final int COMPACT_ROW_HEIGHT_PADDING = 4;
  private static final int BIG_ROW_HEIGHT_PADDING = 16;
  private static final double ZOOM_IN_FACTOR = 0.8;
  private static final double ZOOM_OUT_FACTOR = 1.25;
  private static final double PAN_STEP = 0.15;

  /** A ruler drag shorter than this is a click, not a range selection. */
  private static final int MIN_DRAG_PIXELS = 4;

  /** Rows of text height down from the top at which the empty-table hint sits. */
  private static final int EMPTY_HINT_LINES = 3;

  /** Least distance the empty-table hint keeps from an edge. */
  private static final int EMPTY_HINT_INSET = 12;

  /** Width of the disclosure triangle at the start of a group heading's Name cell. */
  private static final int DISCLOSURE_HIT_WIDTH = 22;

  private final WaterfallTableModel waterfallModel;
  private final TimeAxis axis;
  private int dragStartX = -1;
  private int panAnchorX = -1;

  /**
   * Builds the table.
   *
   * @param model the model to show
   */
  public WaterfallTable(WaterfallTableModel model) {
    super(model);
    this.waterfallModel = model;
    this.axis = model.getAxis();
    setAutoResizeMode(AUTO_RESIZE_LAST_COLUMN);
    setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    setShowGrid(false);
    setIntercellSpacing(new Dimension(0, 0));
    setFillsViewportHeight(true);
    setBigRows(false);
    configureColumns();
    installHeaderInteraction();
    installTimelineInteraction();
    installCopyAction();
  }

  /**
   * Installs the renderers and widths. Re-run whenever the model's column set changes, because
   * JTable throws its {@code TableColumn}s away and rebuilds them from scratch on a structure
   * change.
   *
   * <p>Returns early when the fields are not populated yet. {@code JTable(TableModel)} installs the
   * model from inside its own constructor, which fires a structure change and reaches
   * {@link #tableChanged} before {@code super(...)} has returned and before any field declared here
   * has been assigned. The constructor calls this again once it can.
   */
  private void configureColumns() {
    if (waterfallModel == null) {
      return;
    }
    TableColumnModel columns = getColumnModel();
    for (int i = 0; i < columns.getColumnCount() && i < waterfallModel.getColumnCount(); i++) {
      WaterfallColumn column = waterfallModel.getColumn(i);
      TableColumn tableColumn = columns.getColumn(i);
      tableColumn.setPreferredWidth(column.getPreferredWidth());
      tableColumn.setMinWidth(column.getMinimumWidth());
      if (column == WaterfallColumn.WATERFALL) {
        tableColumn.setCellRenderer(new WaterfallBarRenderer(axis));
        tableColumn.setHeaderRenderer(new TimelineHeaderRenderer(axis));
      } else {
        tableColumn.setCellRenderer(new WaterfallCellRenderer(column, axis));
        tableColumn.setHeaderRenderer(new WaterfallHeaderRenderer(
            getTableHeader().getDefaultRenderer(), waterfallModel));
      }
    }
  }

  @Override
  public void tableChanged(TableModelEvent event) {
    super.tableChanged(event);
    // A header event means the columns were rebuilt, taking the renderers with them.
    if (event == null || event.getFirstRow() == TableModelEvent.HEADER_ROW) {
      configureColumns();
    }
  }

  /**
   * Header behaviour: a click on a text column sorts, a drag across the ruler zooms to the dragged
   * range, and a double click on the ruler returns to the full extent.
   */
  private void installHeaderInteraction() {
    JTableHeader header = getTableHeader();
    header.setReorderingAllowed(false);
    header.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        dragStartX = isOverTimeline(event.getPoint()) ? event.getX() : -1;
      }

      @Override
      public void mouseReleased(MouseEvent event) {
        if (dragStartX >= 0 && Math.abs(event.getX() - dragStartX) >= MIN_DRAG_PIXELS) {
          zoomToDraggedRange(dragStartX, event.getX());
        }
        dragStartX = -1;
      }

      @Override
      public void mouseClicked(MouseEvent event) {
        if (isOverTimeline(event.getPoint())) {
          if (event.getClickCount() >= 2) {
            axis.fit();
            repaintTimeline();
          }
          return;
        }
        int index = header.columnAtPoint(event.getPoint());
        if (index >= 0) {
          waterfallModel.cycleSort(waterfallModel.getColumn(index));
        }
      }
    });
  }

  /**
   * Zooms so the dragged pixel range fills the waterfall column.
   *
   * @param fromX the pixel the drag started at, in header coordinates
   * @param toX   the pixel the drag ended at
   */
  private void zoomToDraggedRange(int fromX, int toX) {
    Rectangle bounds = timelineBounds();
    if (bounds == null) {
      return;
    }
    long from = axis.timeAt(fromX - bounds.x, bounds.width);
    long to = axis.timeAt(toX - bounds.x, bounds.width);
    axis.setView(from, to);
    repaintTimeline();
  }

  /**
   * Body behaviour over the waterfall column: control and the wheel zooms about the pointer, shift
   * and the wheel pans, and a middle-button drag pans.
   *
   * <p>Plain wheel scrolling and plain clicks are left alone: a waterfall that hijacked the wheel
   * would be unusable for its main job, which is scrolling a long list of requests.
   */
  private void installTimelineInteraction() {
    addMouseWheelListener(event -> {
      if (!isOverTimeline(event.getPoint())) {
        return;
      }
      if (event.isControlDown()) {
        zoomAt(event.getPoint(), event.getWheelRotation() < 0);
        event.consume();
      } else if (event.isShiftDown()) {
        axis.pan(event.getWheelRotation() * PAN_STEP);
        repaintTimeline();
        event.consume();
      }
    });
    addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        panAnchorX = isPanGesture(event) && isOverTimeline(event.getPoint()) ? event.getX() : -1;
      }

      @Override
      public void mouseReleased(MouseEvent event) {
        panAnchorX = -1;
      }

      @Override
      public void mouseClicked(MouseEvent event) {
        handleRowClick(event);
      }
    });
    addMouseMotionListener(new MouseMotionAdapter() {
      @Override
      public void mouseDragged(MouseEvent event) {
        if (panAnchorX < 0) {
          return;
        }
        Rectangle bounds = timelineBounds();
        if (bounds != null && bounds.width > 0) {
          axis.pan((panAnchorX - event.getX()) / (double) bounds.width);
          panAnchorX = event.getX();
          repaintTimeline();
        }
      }
    });
  }

  private static boolean isPanGesture(MouseEvent event) {
    return event.getButton() == MouseEvent.BUTTON2
        || (event.getButton() == MouseEvent.BUTTON1 && event.isShiftDown());
  }

  /**
   * Folds a group heading: a single click on its disclosure triangle, or a double click anywhere on
   * the row.
   *
   * <p>Not on a single click anywhere in the Name column, which would make a group impossible to
   * select without collapsing it.
   *
   * @param event the click
   */
  private void handleRowClick(MouseEvent event) {
    if (waterfallModel == null) {
      return;
    }
    WaterfallRow row = waterfallModel.getRow(rowAtPoint(event.getPoint()));
    if (row == null || !row.isGroup()) {
      return;
    }
    if (event.getClickCount() >= 2 || onDisclosureTriangle(event)) {
      waterfallModel.toggleGroup(row.getGroupName());
    }
  }

  /**
   * Whether a click landed on the leading icon of the Name column.
   *
   * @param event the click
   * @return {@code true} when it is within the disclosure area
   */
  private boolean onDisclosureTriangle(MouseEvent event) {
    int columnIndex = columnAtPoint(event.getPoint());
    if (columnIndex < 0 || waterfallModel.getColumn(columnIndex) != WaterfallColumn.NAME) {
      return false;
    }
    int columnStart = 0;
    for (int i = 0; i < columnIndex; i++) {
      columnStart += getColumnModel().getColumn(i).getWidth();
    }
    return event.getX() - columnStart <= DISCLOSURE_HIT_WIDTH;
  }

  /** Copies the selected row as tab-separated text, so it can go straight into a report. */
  private void installCopyAction() {
    KeyStroke copy = KeyStroke.getKeyStroke(KeyEvent.VK_C, menuShortcutMask());
    getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(copy, "waterfallCopy");
    getActionMap().put("waterfallCopy", new AbstractAction() {
      private static final long serialVersionUID = 1L;

      @Override
      public void actionPerformed(ActionEvent event) {
        copySelectionToClipboard();
      }
    });
  }

  /**
   * The platform's copy modifier - command on macOS, control elsewhere.
   *
   * <p>Falls back to control when there is no display. That path never runs in JMeter's GUI, but
   * asking the toolkit for it throws when headless, and a constructor that cannot complete outside
   * a GUI cannot be covered by a test either.
   *
   * @return the modifier mask for the copy shortcut
   */
  private static int menuShortcutMask() {
    if (GraphicsEnvironment.isHeadless()) {
      return InputEvent.CTRL_DOWN_MASK;
    }
    return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
  }

  /** Puts the selected row's visible columns on the clipboard, headings included. */
  public void copySelectionToClipboard() {
    WaterfallRow row = waterfallModel.getRow(getSelectedRow());
    if (row == null) {
      return;
    }
    StringBuilder headings = new StringBuilder();
    StringBuilder values = new StringBuilder();
    for (int i = 0; i < waterfallModel.getColumnCount(); i++) {
      WaterfallColumn column = waterfallModel.getColumn(i);
      if (column == WaterfallColumn.WATERFALL) {
        continue;
      }
      if (headings.length() > 0) {
        headings.append('\t');
        values.append('\t');
      }
      headings.append(column.getTitle());
      values.append(WaterfallCellRenderer.textFor(row, column, axis));
    }
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
        new StringSelection(headings + System.lineSeparator() + values), null);
  }

  /**
   * Zooms one step about a point.
   *
   * @param point the pointer position in this table's coordinates
   * @param in    {@code true} to zoom in
   */
  private void zoomAt(Point point, boolean in) {
    Rectangle bounds = timelineBounds();
    if (bounds == null || bounds.width <= 0) {
      return;
    }
    double anchor = (point.x - bounds.x) / (double) bounds.width;
    axis.zoom(in ? ZOOM_IN_FACTOR : ZOOM_OUT_FACTOR, anchor);
    repaintTimeline();
  }

  /**
   * Zooms one step about the middle of the visible range, for the toolbar buttons.
   *
   * @param in {@code true} to zoom in
   */
  public void zoomStep(boolean in) {
    axis.zoom(in ? ZOOM_IN_FACTOR : ZOOM_OUT_FACTOR, 0.5);
    repaintTimeline();
  }

  /**
   * Pans one step, for the toolbar buttons.
   *
   * @param forward {@code true} to move later in time
   */
  public void panStep(boolean forward) {
    axis.pan(forward ? PAN_STEP : -PAN_STEP);
    repaintTimeline();
  }

  /** Returns the axis to the full extent of the timeline. */
  public void fitTimeline() {
    axis.fit();
    repaintTimeline();
  }

  /** Repaints the bars and the ruler together, since they share the axis. */
  public void repaintTimeline() {
    if (getTableHeader() != null) {
      getTableHeader().repaint();
    }
    repaint();
  }

  /**
   * Switches between compact and roomy rows.
   *
   * @param big {@code true} for taller rows, which leaves space for a thicker bar and is easier to
   *            follow across a wide window
   */
  public void setBigRows(boolean big) {
    int base = getFont() != null ? getFontMetrics(getFont()).getHeight() : 16;
    setRowHeight(base + (big ? BIG_ROW_HEIGHT_PADDING : COMPACT_ROW_HEIGHT_PADDING));
  }

  /**
   * Whether a point is inside the waterfall column.
   *
   * @param point a point in this table's - or its header's - coordinates
   * @return {@code true} when it falls in the bars
   */
  private boolean isOverTimeline(Point point) {
    Rectangle bounds = timelineBounds();
    return bounds != null && point.x >= bounds.x && point.x <= bounds.x + bounds.width;
  }

  /**
   * The horizontal extent of the waterfall column.
   *
   * @return the column's x offset and width, or {@code null} when the column is hidden
   */
  private Rectangle timelineBounds() {
    if (waterfallModel == null) {
      return null;
    }
    TableColumnModel columns = getColumnModel();
    int x = 0;
    for (int i = 0; i < columns.getColumnCount() && i < waterfallModel.getColumnCount(); i++) {
      int width = columns.getColumn(i).getWidth();
      if (waterfallModel.getColumn(i) == WaterfallColumn.WATERFALL) {
        return new Rectangle(x, 0, width, getHeight());
      }
      x += width;
    }
    return null;
  }

  /**
   * A tooltip describing the row under the pointer: the phase breakdown over the bars, and the
   * label and URL elsewhere.
   *
   * @param event the pointer position
   * @return the tooltip markup, or {@code null} when there is nothing to say
   */
  @Override
  public String getToolTipText(MouseEvent event) {
    if (waterfallModel == null) {
      return null;
    }
    WaterfallRow row = waterfallModel.getRow(rowAtPoint(event.getPoint()));
    if (row == null) {
      return null;
    }
    if (row.isGroup()) {
      return HtmlDoc.tooltip("<b>" + HtmlDoc.escape(row.getGroupName()) + "</b><br>"
          + row.getSampleCount() + " samples, " + row.getErrorCount() + " failed<br>"
          + "span " + WaterfallFormat.duration(row.getEndTime() - row.getStartTime()) + ", "
          + WaterfallFormat.size(row.getBytes()));
    }
    return HtmlDoc.tooltip(describe(row.getRecord()));
  }

  /**
   * Builds the rich tooltip for one sample.
   *
   * @param record the sample
   * @return the tooltip body markup
   */
  private String describe(SampleRecord record) {
    StringBuilder text = new StringBuilder();
    text.append("<b>").append(HtmlDoc.escape(record.getLabel())).append("</b>");
    if (!record.getUrl().isEmpty()) {
      text.append("<br><i>").append(HtmlDoc.escape(record.getUrl())).append("</i>");
    }
    text.append("<br>");
    if (!record.getMethod().isEmpty()) {
      text.append(HtmlDoc.escape(record.getMethod())).append(' ');
    }
    text.append(HtmlDoc.escape(record.getResponseCode()));
    if (!record.getProtocol().isEmpty()) {
      text.append(" over ").append(HtmlDoc.escape(record.getProtocol()));
    }
    text.append("<br>").append(WaterfallFormat.size(record.getBytes())).append(" in ")
        .append(WaterfallFormat.duration(record.getElapsed()));
    text.append("<br>starts at +")
        .append(WaterfallFormat.duration(record.getStartTime() - axis.getBoundsStart()));
    appendPhases(text, record.getPhases());
    if (!record.isSuccess()) {
      text.append("<br><br><b>Failed:</b> ")
          .append(HtmlDoc.escape(record.getResult().getResponseMessage()));
    }
    return text.toString();
  }

  private void appendPhases(StringBuilder text, PhaseBreakdown phases) {
    text.append("<br><br>");
    appendPhase(text, "Queueing / Idle", phases.getIdleMs());
    appendPhase(text, "Connect", phases.getConnectMs());
    appendPhase(text, "Waiting (TTFB)", phases.getTtfbMs());
    appendPhase(text, "Download", phases.getDownloadMs());
    if (phases.getUnbrokenMs() > 0) {
      text.append("No phase breakdown reported by this sampler");
    }
  }

  private static void appendPhase(StringBuilder text, String name, long millis) {
    if (millis > 0) {
      text.append(name).append(": ").append(WaterfallFormat.duration(millis)).append("<br>");
    }
  }

  /**
   * Paints a hint over the empty table.
   *
   * <p>A listener that has just been added shows an empty grid, and an empty grid is
   * indistinguishable from a broken one. Saying which of the two it is - waiting for a run, or
   * filtered down to nothing - is the difference between a user starting their test and a user
   * checking whether the plugin installed correctly.
   *
   * @param graphics the target
   */
  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    if (waterfallModel == null || waterfallModel.getRowCount() > 0) {
      return;
    }
    String hint = waterfallModel.getFilter().isActive()
        ? "No sample matches the current filters."
        : "No samples yet. Start the test, or load a .jtl with the file panel above.";
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
          RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setFont(getFont());
      g.setColor(WaterfallColors.mutedForeground());
      FontMetrics metrics = g.getFontMetrics();
      g.drawString(hint, Math.max(EMPTY_HINT_INSET,
              (getWidth() - metrics.stringWidth(hint)) / 2),
          Math.min(getHeight() - EMPTY_HINT_INSET, metrics.getHeight() * EMPTY_HINT_LINES));
    } finally {
      g.dispose();
    }
  }

  /**
   * Keeps the ruler in step with a column resize. Widening the window changes how many pixels a
   * millisecond gets, so the labels have to be redrawn even though no data changed.
   *
   * @param event the resize
   */
  @Override
  public void columnMarginChanged(ChangeEvent event) {
    super.columnMarginChanged(event);
    repaintTimeline();
  }

  /**
   * The keyboard shortcuts the toolbar duplicates, so the timeline can be driven without the
   * mouse.
   *
   * @param stroke    the key pressed
   * @param event     the originating event
   * @param condition the focus condition
   * @param pressed   whether the key went down
   * @return whether the stroke was handled
   */
  @Override
  protected boolean processKeyBinding(KeyStroke stroke, KeyEvent event, int condition,
      boolean pressed) {
    if (pressed && (event.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
      switch (event.getKeyCode()) {
        case KeyEvent.VK_PLUS:
        case KeyEvent.VK_EQUALS:
          zoomStep(true);
          return true;
        case KeyEvent.VK_MINUS:
          zoomStep(false);
          return true;
        case KeyEvent.VK_0:
          fitTimeline();
          return true;
        default:
          break;
      }
    }
    return super.processKeyBinding(stroke, event, condition, pressed);
  }
}
