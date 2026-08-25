package com.blazemeter.jmeter.http2.visualizers.waterfall.details;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallFormat;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.jmeter.samplers.SampleResult;

/**
 * The Headers tab: a General section, then the request headers, then the response headers.
 *
 * <p>Follows the layout browsers settled on because the ordering answers questions in the order
 * they get asked - what was requested, what was sent, what came back. The filter box narrows all
 * three sections at once, which is how you find one header across a request that carries thirty.
 */
public final class HeadersTab extends DetailTab {

  private static final long serialVersionUID = 1L;

  private static final int FILTER_COLUMNS = 20;

  private final JEditorPane view = new JEditorPane();
  private final JTextField filterField = new JTextField(FILTER_COLUMNS);

  /** Creates the tab. */
  public HeadersTab() {
    view.setEditable(false);
    view.setContentType("text/html");
    // A JEditorPane will happily fetch whatever a link points at when it is clicked. This one only
    // ever shows text, so no hyperlink listener is installed and nothing can be followed.
    view.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    add(buildToolBar(), BorderLayout.NORTH);
    add(new JScrollPane(view), BorderLayout.CENTER);
    filterField.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent event) {
        render(getRecord());
      }

      @Override
      public void removeUpdate(DocumentEvent event) {
        render(getRecord());
      }

      @Override
      public void changedUpdate(DocumentEvent event) {
        render(getRecord());
      }
    });
  }

  private JPanel buildToolBar() {
    JPanel bar = new JPanel(new BorderLayout(6, 0));
    bar.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
    JPanel left = new JPanel(new BorderLayout(4, 0));
    left.add(new JLabel("Filter headers:"), BorderLayout.WEST);
    filterField.setToolTipText("Show only headers whose name or value contains this text");
    left.add(filterField, BorderLayout.CENTER);
    bar.add(left, BorderLayout.WEST);
    Box right = Box.createHorizontalBox();
    right.add(copyButton("Copy request headers", true));
    right.add(Box.createHorizontalStrut(6));
    right.add(copyButton("Copy response headers", false));
    bar.add(right, BorderLayout.EAST);
    return bar;
  }

  private JButton copyButton(String text, boolean request) {
    JButton button = new JButton(text);
    button.addActionListener(event -> {
      SampleRecord record = getRecord();
      if (record == null) {
        return;
      }
      String block = request ? record.getResult().getRequestHeaders()
          : record.getResult().getResponseHeaders();
      Toolkit.getDefaultToolkit().getSystemClipboard()
          .setContents(new StringSelection(block == null ? "" : block), null);
    });
    return button;
  }

  @Override
  public String getTitle() {
    return "Headers";
  }

  @Override
  protected void render(SampleRecord record) {
    if (record == null) {
      view.setText(HtmlDoc.wrap("<i>Select a request to see its headers.</i>"));
      return;
    }
    String needle = filterField.getText().trim().toLowerCase(Locale.ROOT);
    StringBuilder body = new StringBuilder();
    appendGeneral(body, record);
    SampleResult result = record.getResult();
    appendSection(body, "Request Headers", HttpHeaders.parse(result.getRequestHeaders(), false),
        needle, "This sample recorded no request headers.");
    appendSection(body, "Response Headers", HttpHeaders.parse(result.getResponseHeaders(), true),
        needle, "This sample recorded no response headers.");
    view.setText(HtmlDoc.wrap(body.toString()));
    view.setCaretPosition(0);
  }

  /**
   * Writes the General section, whose fields come from the sample itself rather than from a header.
   *
   * @param body   the document being built
   * @param record the selected sample
   */
  private void appendGeneral(StringBuilder body, SampleRecord record) {
    SampleResult result = record.getResult();
    body.append("<h2>General</h2><table>");
    appendRow(body, "Request URL", record.getUrl());
    appendRow(body, "Request Method", record.getMethod());
    appendRow(body, "Status Code",
        record.getResponseCode() + " " + orEmpty(result.getResponseMessage()));
    appendRow(body, "Protocol", record.getProtocol());
    appendRow(body, "Sample Label", record.getLabel());
    appendRow(body, "Thread", record.getThreadName());
    appendRow(body, "Elapsed", WaterfallFormat.duration(record.getElapsed()));
    appendRow(body, "Response Size", WaterfallFormat.size(record.getBytes())
        + " (headers " + WaterfallFormat.size(result.getHeadersSize())
        + ", body " + WaterfallFormat.size(result.getBodySizeAsLong()) + ")");
    appendRow(body, "Request Size", WaterfallFormat.size(record.getSentBytes()));
    appendRow(body, "Successful", String.valueOf(record.isSuccess()));
    body.append("</table>");
  }

  /**
   * Writes one header section, or a note when it is empty or entirely filtered away.
   *
   * @param body     the document being built
   * @param title    the section heading
   * @param headers  the headers in the section
   * @param needle   the lower-cased filter text, empty when not filtering
   * @param ifAbsent what to say when the section has no headers at all
   */
  private void appendSection(StringBuilder body, String title, List<String[]> headers,
      String needle, String ifAbsent) {
    body.append("<h2>").append(HtmlDoc.escape(title)).append("</h2>");
    if (headers.isEmpty()) {
      body.append("<span class='muted'>").append(HtmlDoc.escape(ifAbsent)).append("</span>");
      return;
    }
    int shown = 0;
    body.append("<table>");
    for (String[] header : headers) {
      if (!matches(header, needle)) {
        continue;
      }
      appendRow(body, header[0], header[1]);
      shown++;
    }
    body.append("</table>");
    if (shown == 0) {
      body.append("<span class='muted'>No header here matches the filter.</span>");
    }
  }

  private static boolean matches(String[] header, String needle) {
    return needle.isEmpty() || header[0].toLowerCase(Locale.ROOT).contains(needle)
        || header[1].toLowerCase(Locale.ROOT).contains(needle);
  }

  private static void appendRow(StringBuilder body, String name, String value) {
    body.append("<tr><td class='name'>").append(HtmlDoc.escape(name))
        .append("</td><td class='value'>").append(HtmlDoc.escape(value)).append("</td></tr>");
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
