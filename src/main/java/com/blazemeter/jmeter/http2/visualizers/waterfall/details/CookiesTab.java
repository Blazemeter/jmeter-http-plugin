package com.blazemeter.jmeter.http2.visualizers.waterfall.details;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;

/**
 * The Cookies tab: what the request sent and what the response set.
 *
 * <p>Two sources have to be reconciled. The request side comes from JMeter's own cookie field when
 * the Cookie Manager filled it in, and from the {@code Cookie} request header otherwise - a request
 * built with an explicit header manager has one and not the other. The response side is parsed out
 * of the {@code Set-Cookie} headers, attribute by attribute, because that is where the answers to
 * "why did my session not stick" live: a wrong path, a domain that does not match, an expiry in the
 * past.
 */
public final class CookiesTab extends DetailTab {

  private static final long serialVersionUID = 1L;

  private static final String[] REQUEST_COLUMNS = {"Name", "Value"};
  private static final String[] RESPONSE_COLUMNS = {"Name", "Value", "Domain", "Path", "Expires",
      "Max-Age", "HttpOnly", "Secure", "SameSite"};

  /** Creates the tab. */
  public CookiesTab() {
    setContent(emptyState("Select a request to see its cookies."));
  }

  @Override
  public String getTitle() {
    return "Cookies";
  }

  @Override
  protected void render(SampleRecord record) {
    if (record == null) {
      setContent(emptyState("Select a request to see its cookies."));
      return;
    }
    SampleResult result = record.getResult();
    List<String[]> requestCookies = requestCookiesOf(result);
    List<String[]> responseCookies = responseCookiesOf(result);
    if (requestCookies.isEmpty() && responseCookies.isEmpty()) {
      setContent(emptyState("This sample neither sent nor received any cookie."));
      return;
    }
    Box content = Box.createVerticalBox();
    content.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    content.add(section("Request Cookies", REQUEST_COLUMNS, requestCookies,
        "No cookie was sent with this request."));
    content.add(Box.createVerticalStrut(10));
    content.add(section("Response Cookies", RESPONSE_COLUMNS, responseCookies,
        "This response set no cookie."));
    setContent(new JScrollPane(content));
  }

  /**
   * The cookies the request carried.
   *
   * @param result the sample
   * @return name and value pairs
   */
  private static List<String[]> requestCookiesOf(SampleResult result) {
    String raw = result instanceof HTTPSampleResult
        ? ((HTTPSampleResult) result).getCookies()
        : null;
    if (raw == null || raw.isEmpty()) {
      List<String> headers = HttpHeaders.valuesOf(result.getRequestHeaders(), false, "Cookie");
      raw = headers.isEmpty() ? "" : String.join("; ", headers);
    }
    List<String[]> cookies = new ArrayList<>();
    for (String pair : raw.split(";")) {
      String trimmed = pair.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int equals = trimmed.indexOf('=');
      cookies.add(equals < 0 ? new String[] {trimmed, ""}
          : new String[] {trimmed.substring(0, equals).trim(),
              trimmed.substring(equals + 1).trim()});
    }
    return cookies;
  }

  /**
   * The cookies the response set, one row per {@code Set-Cookie} header.
   *
   * @param result the sample
   * @return a row per cookie, in the order of {@link #RESPONSE_COLUMNS}
   */
  private static List<String[]> responseCookiesOf(SampleResult result) {
    List<String[]> cookies = new ArrayList<>();
    for (String header : HttpHeaders.valuesOf(result.getResponseHeaders(), true, "Set-Cookie")) {
      cookies.add(parseSetCookie(header));
    }
    return cookies;
  }

  /**
   * Splits one {@code Set-Cookie} value into its name, value and attributes.
   *
   * @param header the header value
   * @return a row in the order of {@link #RESPONSE_COLUMNS}
   */
  private static String[] parseSetCookie(String header) {
    String[] row = new String[RESPONSE_COLUMNS.length];
    for (int i = 0; i < row.length; i++) {
      row[i] = "";
    }
    String[] parts = header.split(";");
    if (parts.length > 0) {
      int equals = parts[0].indexOf('=');
      row[0] = equals < 0 ? parts[0].trim() : parts[0].substring(0, equals).trim();
      row[1] = equals < 0 ? "" : parts[0].substring(equals + 1).trim();
    }
    for (int i = 1; i < parts.length; i++) {
      applyAttribute(row, parts[i].trim());
    }
    return row;
  }

  private static void applyAttribute(String[] row, String attribute) {
    int equals = attribute.indexOf('=');
    String name = (equals < 0 ? attribute : attribute.substring(0, equals))
        .trim().toLowerCase(Locale.ROOT);
    String value = equals < 0 ? "yes" : attribute.substring(equals + 1).trim();
    switch (name) {
      case "domain":
        row[2] = value;
        break;
      case "path":
        row[3] = value;
        break;
      case "expires":
        row[4] = value;
        break;
      case "max-age":
        row[5] = value;
        break;
      case "httponly":
        row[6] = "yes";
        break;
      case "secure":
        row[7] = "yes";
        break;
      case "samesite":
        row[8] = value;
        break;
      default:
        break;
    }
  }

  /**
   * A titled table, or the title plus a note when there is nothing to put in it.
   *
   * @param title    the heading
   * @param columns  the column names
   * @param rows     the rows
   * @param ifAbsent what to say when there are none
   * @return the section
   */
  private static JPanel section(String title, String[] columns, List<String[]> rows,
      String ifAbsent) {
    JPanel panel = new JPanel(new BorderLayout(0, 4));
    JLabel heading = new JLabel(title);
    heading.setFont(heading.getFont().deriveFont(Font.BOLD));
    panel.add(heading, BorderLayout.NORTH);
    if (rows.isEmpty()) {
      JLabel note = new JLabel(ifAbsent);
      note.setEnabled(false);
      panel.add(note, BorderLayout.CENTER);
      return panel;
    }
    DefaultTableModel model = new DefaultTableModel(columns, 0) {
      private static final long serialVersionUID = 1L;

      @Override
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
    for (String[] row : rows) {
      model.addRow(row);
    }
    JTable table = new JTable(model);
    table.setAutoCreateRowSorter(true);
    table.setFillsViewportHeight(false);
    JScrollPane scrollPane = new JScrollPane(table);
    scrollPane.setPreferredSize(new Dimension(0,
        table.getRowHeight() * (Math.min(rows.size(), 8) + 1) + 4));
    panel.add(scrollPane, BorderLayout.CENTER);
    return panel;
  }
}
