package com.blazemeter.jmeter.http2.visualizers.waterfall;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.FilterCriteria;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.GroupMode;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallColumn;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallTableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The controls above the waterfall: the filters on one line, the view options on the next.
 *
 * <p>Two lines rather than one because the embedded view inside JMeter's listener panel is narrow,
 * and a single line of controls would either wrap unpredictably or scroll out of reach. The split
 * also matches how the controls get used - filters while looking for something, view options while
 * looking at it.
 */
public final class WaterfallToolBar extends JPanel {

  private static final long serialVersionUID = 1L;

  private static final int FILTER_COLUMNS = 18;
  private static final int MAX_MIN_TIME_MS = 600_000;
  private static final int MIN_TIME_STEP_MS = 50;

  /** Pixels between two logical groups of controls. */
  private static final int GROUP_SEPARATION = 14;

  /** Status classes offered in the status filter, {@code 0} standing for a non-numeric code. */
  private static final int[] STATUS_CLASSES = {2, 3, 4, 5, 0};

  private final WaterfallTableModel model;
  private final WaterfallTable table;
  private final WaterfallHost host;
  private final JTextField filterField = new JTextField(FILTER_COLUMNS);
  private final JCheckBox regexCheckBox = new JCheckBox("regex");
  private final JCheckBox errorsOnlyCheckBox = new JCheckBox("Errors only");
  private final JSpinner minTimeSpinner =
      new JSpinner(new SpinnerNumberModel(0, 0, MAX_MIN_TIME_MS, MIN_TIME_STEP_MS));
  private final JComboBox<GroupMode> groupCombo = new JComboBox<>(GroupMode.values());
  private final JCheckBox bigRowsCheckBox = new JCheckBox("Big rows");
  private final JCheckBox subSamplesCheckBox = new JCheckBox("Sub-samples", true);
  private final JCheckBox detailsCheckBox = new JCheckBox("Details", true);
  private final JButton expandButton = new JButton();
  private final Color filterFieldForeground;

  /**
   * Builds the toolbar.
   *
   * @param model the model whose filter, grouping and columns these controls drive
   * @param table the table whose timeline and row height these controls drive
   * @param host  the panel or window hosting the waterfall
   */
  public WaterfallToolBar(WaterfallTableModel model, WaterfallTable table, WaterfallHost host) {
    this.model = model;
    this.table = table;
    this.host = host;
    this.filterFieldForeground = filterField.getForeground();
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
    add(buildFilterRow());
    add(Box.createVerticalStrut(3));
    add(buildViewRow());
    updateExpandButton();
    // A width change alters how many rows the wrapping layout needs, and a layout manager is never
    // asked to re-measure on its own; without this the toolbar keeps the height it was first given
    // and the wrapped controls are laid out below its bottom edge, where nothing paints them.
    addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent event) {
        revalidate();
      }
    });
  }

  private JPanel buildFilterRow() {
    JPanel row = new JPanel(new WrapLayout(4, 2));
    row.add(new JLabel("Filter:"));
    filterField.setToolTipText("Keep rows whose label or URL matches this text");
    filterField.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent event) {
        applyTextFilter();
      }

      @Override
      public void removeUpdate(DocumentEvent event) {
        applyTextFilter();
      }

      @Override
      public void changedUpdate(DocumentEvent event) {
        applyTextFilter();
      }
    });
    row.add(filterField);
    regexCheckBox.setToolTipText("Treat the filter as a regular expression");
    regexCheckBox.addActionListener(event -> applyTextFilter());
    row.add(regexCheckBox);
    row.add(separator());
    row.add(statusMenuButton());
    row.add(protocolMenuButton());
    row.add(separator());
    errorsOnlyCheckBox.addActionListener(event -> {
      model.getFilter().setErrorsOnly(errorsOnlyCheckBox.isSelected());
      refilter();
    });
    row.add(errorsOnlyCheckBox);
    row.add(separator());
    row.add(new JLabel("Min time (ms):"));
    minTimeSpinner.setToolTipText("Hide samples faster than this");
    minTimeSpinner.addChangeListener(event -> {
      model.getFilter().setMinTimeMs(((Number) minTimeSpinner.getValue()).longValue());
      refilter();
    });
    row.add(minTimeSpinner);
    row.add(separator());
    JButton clear = new JButton("Clear filters");
    clear.addActionListener(event -> clearFilters());
    row.add(clear);
    return row;
  }

  private JPanel buildViewRow() {
    JPanel row = new JPanel(new WrapLayout(4, 2));
    row.add(new JLabel("Group by:"));
    groupCombo.addActionListener(event ->
        model.setGroupMode((GroupMode) groupCombo.getSelectedItem()));
    row.add(groupCombo);
    row.add(separator());
    bigRowsCheckBox.setToolTipText("Taller rows with thicker bars");
    bigRowsCheckBox.addActionListener(event -> table.setBigRows(bigRowsCheckBox.isSelected()));
    row.add(bigRowsCheckBox);
    subSamplesCheckBox.setToolTipText("Show embedded resources, redirect hops and transaction"
        + " children as rows of their own. Applies to samples arriving from now on.");
    subSamplesCheckBox.addActionListener(event ->
        model.getStore().setIncludeSubSamples(subSamplesCheckBox.isSelected()));
    row.add(subSamplesCheckBox);
    detailsCheckBox.addActionListener(
        event -> host.setDetailsVisible(detailsCheckBox.isSelected()));
    row.add(detailsCheckBox);
    row.add(separator());
    row.add(new JLabel("Timeline:"));
    row.add(timelineButton("-", "Zoom out (Ctrl and the mouse wheel over the bars)",
        () -> table.zoomStep(false)));
    row.add(timelineButton("+", "Zoom in (Ctrl and the mouse wheel over the bars)",
        () -> table.zoomStep(true)));
    row.add(timelineButton("Fit", "Show the whole timeline (double click the ruler)",
        table::fitTimeline));
    row.add(timelineButton("<", "Pan earlier (Shift and the mouse wheel over the bars)",
        () -> table.panStep(false)));
    row.add(timelineButton(">", "Pan later (Shift and the mouse wheel over the bars)",
        () -> table.panStep(true)));
    row.add(separator());
    row.add(columnsMenuButton());
    expandButton.addActionListener(event -> {
      host.toggleExpandedWindow();
      updateExpandButton();
    });
    row.add(expandButton);
    return row;
  }

  /**
   * A fixed gap between two logical groups of controls.
   *
   * <p>The wrapping layout gives every child the same small gap, which ran "Details" straight into
   * "Timeline:" and made each row read as one undifferentiated strip of widgets.
   *
   * @return a rigid spacer
   */
  private static Component separator() {
    return Box.createHorizontalStrut(GROUP_SEPARATION);
  }

  private JButton timelineButton(String text, String tooltip, Runnable action) {
    JButton button = new JButton(text);
    button.setToolTipText(tooltip);
    button.addActionListener(event -> action.run());
    return button;
  }

  /**
   * The status filter, as a menu of checkboxes.
   *
   * <p>Nothing ticked means everything passes, which is the state a fresh viewer starts in; ticking
   * a class narrows to it. That is the opposite of starting with all five ticked, and it keeps
   * "show me the failures" one click away.
   *
   * @return the button that opens the menu
   */
  private JButton statusMenuButton() {
    JPopupMenu menu = new JPopupMenu();
    for (int statusClass : STATUS_CLASSES) {
      JCheckBoxMenuItem item = new JCheckBoxMenuItem(labelForStatusClass(statusClass));
      item.addActionListener(event -> {
        model.getFilter().setStatusClassAccepted(statusClass, item.isSelected());
        refilter();
      });
      menu.add(item);
    }
    return menuButton("Status", "Keep only the ticked status classes", menu);
  }

  private static String labelForStatusClass(int statusClass) {
    return statusClass == 0 ? "Other / non-numeric" : statusClass + "xx";
  }

  /**
   * The protocol filter. Rebuilt each time it opens, because which protocols exist is only known
   * once samples have arrived.
   *
   * @return the button that opens the menu
   */
  private JButton protocolMenuButton() {
    JPopupMenu menu = new JPopupMenu();
    JButton button = menuButton("Protocol", "Keep only the ticked protocols", menu);
    button.addActionListener(event -> {
      menu.removeAll();
      FilterCriteria filter = model.getFilter();
      if (model.getKnownProtocols().isEmpty()) {
        JCheckBoxMenuItem none = new JCheckBoxMenuItem("(no samples yet)");
        none.setEnabled(false);
        menu.add(none);
      }
      for (String protocol : model.getKnownProtocols()) {
        String label = protocol.isEmpty() ? "(not reported)" : protocol;
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(label,
            filter.getProtocols().contains(protocol));
        item.addActionListener(inner -> {
          filter.setProtocolAccepted(protocol, item.isSelected());
          refilter();
        });
        menu.add(item);
      }
    });
    return button;
  }

  /**
   * The column chooser.
   *
   * @return the button that opens the menu
   */
  private JButton columnsMenuButton() {
    JPopupMenu menu = new JPopupMenu();
    for (WaterfallColumn column : WaterfallColumn.values()) {
      JCheckBoxMenuItem item = new JCheckBoxMenuItem(column.getTitle(),
          model.isColumnVisible(column));
      item.addActionListener(event -> model.setColumnVisible(column, item.isSelected()));
      menu.add(item);
    }
    return menuButton("Columns", "Choose which columns are shown", menu);
  }

  private JButton menuButton(String text, String tooltip, JPopupMenu menu) {
    JButton button = new JButton(text);
    button.setToolTipText(tooltip);
    button.addActionListener(event -> menu.show(button, 0, button.getHeight()));
    return button;
  }

  private void applyTextFilter() {
    FilterCriteria filter = model.getFilter();
    filter.setRegex(regexCheckBox.isSelected());
    filter.setText(filterField.getText());
    // A half-typed regular expression is not an error to shout about, but the field should not look
    // like it is filtering when it cannot.
    filterField.setForeground(filter.isRegexValid() ? filterFieldForeground : Color.RED);
    refilter();
  }

  private void refilter() {
    model.filterChanged();
    table.repaintTimeline();
  }

  /** Returns every filter to its pass-through state. */
  public void clearFilters() {
    model.getFilter().reset();
    filterField.setText("");
    regexCheckBox.setSelected(false);
    errorsOnlyCheckBox.setSelected(false);
    minTimeSpinner.setValue(0);
    filterField.setForeground(filterFieldForeground);
    refilter();
  }

  /** Re-reads the host's expanded state, after the window was opened or closed elsewhere. */
  public void updateExpandButton() {
    expandButton.setText(host.isExpanded() ? "Return to panel" : "Expand to window");
    expandButton.setToolTipText(host.isExpanded()
        ? "Put the waterfall back into the JMeter listener panel"
        : "Open the waterfall in a maximisable window of its own");
  }

  /**
   * Reflects the details panel's visibility, after it was toggled from the panel's own Hide button.
   *
   * @param visible whether the details panel is showing
   */
  public void setDetailsSelected(boolean visible) {
    detailsCheckBox.setSelected(visible);
  }

  /**
   * Applies a grouping through the widget, so the selector shows what is actually in force.
   *
   * <p>Package-private for the screenshot harness: setting the model's mode directly left the
   * selector reading "No grouping" over a plainly grouped table, which is exactly the kind of lie a
   * screenshot must not tell.
   *
   * @param mode the grouping to apply
   */
  void applyGroupMode(GroupMode mode) {
    groupCombo.setSelectedItem(mode);
  }

  /**
   * Applies the errors-only filter through the widget.
   *
   * @param errorsOnly whether to hide successful samples
   */
  void applyErrorsOnly(boolean errorsOnly) {
    errorsOnlyCheckBox.setSelected(errorsOnly);
    model.getFilter().setErrorsOnly(errorsOnly);
    refilter();
  }

  /**
   * Applies the big-rows option through the widget.
   *
   * @param big whether rows are taller
   */
  void applyBigRows(boolean big) {
    bigRowsCheckBox.setSelected(big);
    table.setBigRows(big);
  }

  /**
   * The component that should get the focus when the viewer opens.
   *
   * @return the filter field
   */
  public Component getInitialFocusComponent() {
    return filterField;
  }
}
