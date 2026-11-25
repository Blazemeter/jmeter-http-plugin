package com.blazemeter.jmeter.http2.visualizers.waterfall.details;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.WaterfallColors;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/**
 * The details panel: the tabs that describe one selected sample.
 *
 * <p>The split between the tabs is a division of labour with JMeter. **Response** is JMeter's own
 * result-renderer stack, unmodified, so everything to do with reading a response body - HTML, JSON,
 * XML, images, Tika-extracted documents, the request view, the metadata table - behaves exactly as
 * it does in View Results Tree and gains whatever a future JMeter release adds. **Headers**,
 * **Timing** and **Cookies** are the things a waterfall needs and View Results Tree has no
 * equivalent for.
 *
 * <p>Tabs are built once and reused. When the selection changes every tab is told which sample it
 * now describes, but only the visible one does the work; the rest catch up when they are next
 * shown. That is what keeps clicking through a waterfall of megabyte responses as cheap as clicking
 * through a waterfall of empty ones.
 */
public final class SampleDetailsPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private final List<DetailTab> tabs = new ArrayList<>();
  private final JTabbedPane tabbedPane = new JTabbedPane();
  private final JLabel titleLabel = new JLabel();
  private final JLabel subtitleLabel = new JLabel();
  private final JButton closeButton = new JButton("Hide");
  private SampleRecord record;

  /** Creates the panel with no sample selected. */
  public SampleDetailsPanel() {
    super(new BorderLayout());
    addTab(new HeadersTab());
    addTab(new ResponseRendererTab());
    addTab(new TimingTab());
    addTab(new CookiesTab());
    tabbedPane.addChangeListener(event -> refreshSelectedTab());
    add(buildHeader(), BorderLayout.NORTH);
    add(tabbedPane, BorderLayout.CENTER);
    setRecord(null);
  }

  private void addTab(DetailTab tab) {
    tabs.add(tab);
    tabbedPane.addTab(tab.getTitle(), tab);
  }

  private JPanel buildHeader() {
    JPanel header = new JPanel(new BorderLayout(8, 0));
    header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
    titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
    subtitleLabel.setForeground(WaterfallColors.mutedForeground());
    JPanel text = new JPanel(new BorderLayout());
    text.add(titleLabel, BorderLayout.NORTH);
    text.add(subtitleLabel, BorderLayout.SOUTH);
    header.add(text, BorderLayout.CENTER);
    Box buttons = Box.createHorizontalBox();
    JButton copyUrl = new JButton("Copy URL");
    copyUrl.addActionListener(event -> copyUrl());
    buttons.add(copyUrl);
    buttons.add(Box.createHorizontalStrut(6));
    closeButton.setToolTipText("Hide the details panel");
    buttons.add(closeButton);
    header.add(buttons, BorderLayout.EAST);
    return header;
  }

  private void copyUrl() {
    if (record == null) {
      return;
    }
    Toolkit.getDefaultToolkit().getSystemClipboard()
        .setContents(new StringSelection(record.getUrl()), null);
  }

  /**
   * Runs when the Hide button is pressed, so the owner can collapse the split pane.
   *
   * @param action what to do
   */
  public void setCloseAction(Runnable action) {
    for (ActionListener listener : closeButton.getActionListeners()) {
      closeButton.removeActionListener(listener);
    }
    closeButton.addActionListener(event -> action.run());
  }

  /**
   * Shows a sample, or clears the panel.
   *
   * @param sample the sample to describe, or {@code null} to clear
   */
  public void setRecord(SampleRecord sample) {
    this.record = sample;
    if (sample == null) {
      titleLabel.setText("No request selected");
      subtitleLabel.setText("Click a row in the waterfall to inspect it.");
    } else {
      titleLabel.setText(sample.getLabel());
      subtitleLabel.setText(summaryOf(sample));
    }
    for (DetailTab tab : tabs) {
      tab.setRecord(sample);
    }
    refreshSelectedTab();
  }

  private static String summaryOf(SampleRecord sample) {
    StringBuilder summary = new StringBuilder();
    if (!sample.getMethod().isEmpty()) {
      summary.append(sample.getMethod()).append(' ');
    }
    summary.append(sample.getUrl().isEmpty() ? "(no URL)" : sample.getUrl());
    if (!sample.getResponseCode().isEmpty()) {
      summary.append("   -   ").append(sample.getResponseCode());
    }
    if (!sample.getProtocol().isEmpty()) {
      summary.append("   -   ").append(sample.getProtocol());
    }
    return summary.toString();
  }

  private void refreshSelectedTab() {
    int index = tabbedPane.getSelectedIndex();
    if (index >= 0 && index < tabs.size()) {
      tabs.get(index).refreshIfStale();
    }
  }

  /**
   * Brings one tab to the front, so a double click on a bar can open Timing directly.
   *
   * @param title the tab's title
   */
  public void selectTab(String title) {
    for (int i = 0; i < tabs.size(); i++) {
      if (tabs.get(i).getTitle().equals(title)) {
        tabbedPane.setSelectedIndex(i);
        return;
      }
    }
  }
}
