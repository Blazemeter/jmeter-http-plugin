package com.blazemeter.jmeter.http2.visualizers.waterfall.details;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import java.awt.BorderLayout;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * Base class for the tabs of the details panel.
 *
 * <p>Carries the one behaviour they all need: a tab is told which sample is selected, but only
 * builds its contents when it is actually the visible tab. Selecting a row in a waterfall of large
 * responses would otherwise decode the body six times over - once per tab - for five tabs nobody
 * was looking at.
 */
public abstract class DetailTab extends JPanel {

  private static final long serialVersionUID = 1L;

  private SampleRecord record;
  private boolean stale = true;

  /** Creates a tab with a border layout. */
  protected DetailTab() {
    super(new BorderLayout());
  }

  /**
   * The tab's title.
   *
   * @return the text shown on the tab
   */
  public abstract String getTitle();

  /**
   * Builds the tab's contents for a sample.
   *
   * @param sample the selected sample, or {@code null} when the selection was cleared
   */
  protected abstract void render(SampleRecord sample);

  /**
   * Notes which sample is selected, without doing any work yet.
   *
   * @param sample the selected sample, or {@code null}
   */
  public final void setRecord(SampleRecord sample) {
    if (this.record != sample) {
      this.record = sample;
      this.stale = true;
    }
  }

  /** Builds the contents if the selection changed since the last time this tab was shown. */
  public final void refreshIfStale() {
    if (stale) {
      stale = false;
      render(record);
    }
  }

  /**
   * The selected sample.
   *
   * @return the sample, or {@code null}
   */
  protected final SampleRecord getRecord() {
    return record;
  }

  /**
   * A centred message, for a tab that has nothing to show.
   *
   * <p>Always a specific sentence rather than a blank panel: "this sample has no response body" and
   * "this tab is broken" look identical when both are empty.
   *
   * @param message what is missing and why
   * @return the placeholder
   */
  protected static JPanel emptyState(String message) {
    JPanel panel = new JPanel(new BorderLayout());
    JLabel label = new JLabel(message, SwingConstants.CENTER);
    label.setBorder(BorderFactory.createEmptyBorder(20, 12, 20, 12));
    label.setEnabled(false);
    panel.add(label, BorderLayout.CENTER);
    return panel;
  }

  /**
   * Replaces the tab's contents.
   *
   * @param content the new contents
   */
  protected final void setContent(Component content) {
    removeAll();
    add(content, BorderLayout.CENTER);
    revalidate();
    repaint();
  }
}
