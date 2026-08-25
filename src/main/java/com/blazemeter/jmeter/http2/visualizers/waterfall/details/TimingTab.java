package com.blazemeter.jmeter.http2.visualizers.waterfall.details;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.PhaseBreakdown;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallFormat;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.WaterfallColors;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;

/**
 * The Timing tab: every phase the sample reported, as a stacked bar and as a table of durations
 * and shares.
 *
 * <p>This is where the waterfall stops being a picture and becomes a number, so it is also where
 * the limits of the data have to be stated. The phases listed are exactly the ones JMeter measures:
 * a sample that reported no connect time and no latency gets one row saying so rather than a
 * fabricated split, and the connect figure is labelled as including the TLS handshake on a secure
 * request, because that is what JMeter's {@code connectTime} is.
 */
public final class TimingTab extends DetailTab {

  private static final long serialVersionUID = 1L;

  private static final int BAR_HEIGHT = 18;
  private static final int SWATCH_SIZE = 11;
  private static final String TIME_PATTERN = "HH:mm:ss.SSS";

  /** Creates the tab. */
  public TimingTab() {
    setContent(emptyState("Select a request to see its timing breakdown."));
  }

  @Override
  public String getTitle() {
    return "Timing";
  }

  @Override
  protected void render(SampleRecord record) {
    if (record == null) {
      setContent(emptyState("Select a request to see its timing breakdown."));
      return;
    }
    PhaseBreakdown phases = record.getPhases();
    List<Phase> rows = phasesOf(phases, isSecure(record));
    JPanel stack = new JPanel(new BorderLayout(0, 8));
    stack.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    stack.add(new StackedBar(rows, phases.getSpanMs()), BorderLayout.NORTH);
    stack.add(buildTable(record, rows, phases), BorderLayout.CENTER);
    stack.add(buildFooter(record, rows), BorderLayout.SOUTH);
    // North rather than center: the breakdown is a handful of rows, and stretching it over the
    // whole tab left the bar at the top, the numbers in the middle and the button at the bottom
    // with two bands of empty space between them.
    JPanel content = new JPanel(new BorderLayout());
    content.add(stack, BorderLayout.NORTH);
    setContent(new JScrollPane(content));
  }

  /**
   * The phases that actually have a duration, in the order they occurred.
   *
   * <p>Zero-length phases are dropped rather than listed as {@code 0 ms}: a reused connection has
   * no connect phase at all, and a row claiming otherwise invites the reader to look for a problem
   * that is not there.
   *
   * @param phases the breakdown
   * @return the phases to show
   */
  private static List<Phase> phasesOf(PhaseBreakdown phases, boolean secure) {
    List<Phase> rows = new ArrayList<>();
    addIfPositive(rows, "Queueing / Idle", phases.getIdleMs(), WaterfallColors.idle());
    addIfPositive(rows, secure ? "Connect (TLS handshake included)" : "Connect",
        phases.getConnectMs(), WaterfallColors.connect());
    addIfPositive(rows, "Waiting (TTFB)", phases.getTtfbMs(), WaterfallColors.ttfb());
    addIfPositive(rows, "Content Download", phases.getDownloadMs(), WaterfallColors.download());
    addIfPositive(rows, "Elapsed (no phase data)", phases.getUnbrokenMs(),
        WaterfallColors.unbroken());
    return rows;
  }

  private static void addIfPositive(List<Phase> rows, String name, long millis, Color color) {
    if (millis > 0) {
      rows.add(new Phase(name, millis, color));
    }
  }

  private JPanel buildTable(SampleRecord record, List<Phase> rows, PhaseBreakdown phases) {
    JPanel table = new JPanel(new GridBagLayout());
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.insets = new Insets(2, 0, 2, 12);
    constraints.anchor = GridBagConstraints.WEST;
    int row = 0;
    for (Phase phase : rows) {
      constraints.gridy = row++;
      constraints.gridx = 0;
      table.add(new Swatch(phase.color), constraints);
      constraints.gridx = 1;
      table.add(new JLabel(phase.name), constraints);
      constraints.gridx = 2;
      table.add(rightAligned(WaterfallFormat.duration(phase.millis)), constraints);
      constraints.gridx = 3;
      table.add(rightAligned(WaterfallFormat.percent(phase.millis, phases.getSpanMs())),
          constraints);
    }
    constraints.gridy = row++;
    constraints.gridx = 1;
    table.add(bold("Elapsed"), constraints);
    constraints.gridx = 2;
    table.add(rightAligned(WaterfallFormat.duration(record.getElapsed())), constraints);
    constraints.gridy = row++;
    constraints.gridx = 1;
    table.add(new JLabel("Started at"), constraints);
    constraints.gridx = 2;
    table.add(new JLabel(formatTime(record.getStartTime())), constraints);
    constraints.gridy = row;
    constraints.gridx = 1;
    table.add(new JLabel("Finished at"), constraints);
    constraints.gridx = 2;
    table.add(new JLabel(formatTime(record.getEndTime())), constraints);
    JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.add(table, BorderLayout.WEST);
    return wrapper;
  }

  /**
   * The explanatory notes and the copy button.
   *
   * @param record the sample
   * @param rows   the phases being shown
   * @return the footer
   */
  private JPanel buildFooter(SampleRecord record, List<Phase> rows) {
    JPanel footer = new JPanel(new BorderLayout(0, 6));
    Box notes = Box.createVerticalBox();
    if (!record.getPhases().isDetailed()) {
      notes.add(note("This sample reported neither a connect time nor a latency, so its elapsed"
          + " time cannot be split. Only a start time and a duration are required for a bar."));
    }
    if (record.getPhases().getIdleMs() > 0) {
      notes.add(note("Idle time is wall-clock time the sample did not measure, such as the pauses"
          + " between the children of a transaction controller."));
    }
    footer.add(notes, BorderLayout.CENTER);
    JButton copy = new JButton("Copy timing");
    copy.addActionListener(event -> copyTiming(record, rows));
    JPanel buttons = new JPanel(new BorderLayout());
    buttons.add(copy, BorderLayout.WEST);
    footer.add(buttons, BorderLayout.SOUTH);
    return footer;
  }

  private static boolean isSecure(SampleRecord record) {
    return record.getUrl().regionMatches(true, 0, "https", 0, 5);
  }

  private void copyTiming(SampleRecord record, List<Phase> rows) {
    StringBuilder text = new StringBuilder(record.getLabel()).append(System.lineSeparator());
    for (Phase phase : rows) {
      text.append(phase.name).append(": ").append(phase.millis).append(" ms")
          .append(System.lineSeparator());
    }
    text.append("Elapsed: ").append(record.getElapsed()).append(" ms");
    Toolkit.getDefaultToolkit().getSystemClipboard()
        .setContents(new StringSelection(text.toString()), null);
  }

  private static String formatTime(long epochMillis) {
    return new SimpleDateFormat(TIME_PATTERN, Locale.ROOT).format(new Date(epochMillis));
  }

  private static JLabel rightAligned(String text) {
    JLabel label = new JLabel(text, SwingConstants.RIGHT);
    label.setHorizontalAlignment(SwingConstants.RIGHT);
    return label;
  }

  private static JLabel bold(String text) {
    JLabel label = new JLabel(text);
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    return label;
  }

  private static JLabel note(String text) {
    JLabel label = new JLabel("<html><body style='width: 380px'>" + HtmlDoc.escape(text)
        + "</body></html>");
    label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
    label.setEnabled(false);
    return label;
  }

  /** One phase of a sample. */
  private static final class Phase {

    private final String name;
    private final long millis;
    private final Color color;

    private Phase(String name, long millis, Color color) {
      this.name = name;
      this.millis = millis;
      this.color = color;
    }
  }

  /** A small colour square, matching the corresponding segment of the waterfall bar. */
  private static final class Swatch extends JComponent {

    private static final long serialVersionUID = 1L;

    private final Color color;

    private Swatch(Color color) {
      this.color = color;
      setPreferredSize(new Dimension(SWATCH_SIZE, SWATCH_SIZE));
    }

    @Override
    protected void paintComponent(Graphics g) {
      g.setColor(color);
      g.fillRect(0, 0, getWidth(), getHeight());
    }
  }

  /** The phases as one full-width bar, so their relative sizes are visible at a glance. */
  private static final class StackedBar extends JComponent {

    private static final long serialVersionUID = 1L;

    private final transient List<Phase> phases;
    private final long totalMs;

    private StackedBar(List<Phase> phases, long totalMs) {
      this.phases = phases;
      this.totalMs = Math.max(1, totalMs);
      setPreferredSize(new Dimension(0, BAR_HEIGHT));
    }

    @Override
    protected void paintComponent(Graphics g) {
      int width = getWidth();
      double cursor = 0;
      for (Phase phase : phases) {
        double next = cursor + phase.millis * (double) width / totalMs;
        int x = (int) Math.round(cursor);
        g.setColor(phase.color);
        g.fillRect(x, 0, Math.max(1, (int) Math.round(next) - x), getHeight());
        cursor = next;
      }
    }
  }
}
