package com.blazemeter.jmeter.http2.visualizers.waterfall.details;

import com.blazemeter.jmeter.http2.visualizers.waterfall.render.WaterfallColors;
import java.awt.Color;

/**
 * Builds the small HTML documents the Headers tab renders in a {@code JEditorPane}.
 *
 * <p>HTML is a good fit for a header list specifically: the content is tiny, it wants a name in
 * bold next to a value that wraps, and Swing's renderer gives that for free with text that stays
 * selectable and copyable. It is a bad fit for a response body, which is why the body tabs use a
 * plain text area instead.
 *
 * <p>Everything that comes from a sample goes through {@link #escape(String)}. Header values are
 * whatever the server under test chose to send; a value containing markup would otherwise be
 * interpreted as part of the document and could rewrite the panel around it.
 */
public final class HtmlDoc {

  private HtmlDoc() {
  }

  /**
   * Escapes text for inclusion in a document.
   *
   * @param text the raw text, {@code null} treated as empty
   * @return the escaped text
   */
  public static String escape(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&':
          out.append("&amp;");
          break;
        case '<':
          out.append("&lt;");
          break;
        case '>':
          out.append("&gt;");
          break;
        case '"':
          out.append("&quot;");
          break;
        default:
          out.append(c);
          break;
      }
    }
    return out.toString();
  }

  /**
   * Wraps body markup in a document styled to match the current look and feel.
   *
   * <p>Declares colours and spacing but deliberately no font. The panes that render this set
   * {@code JEditorPane.HONOR_DISPLAY_PROPERTIES}, so the component's own font applies - which is
   * JMeter's font, at whatever size the look and feel and the display scaling settled on. A
   * hard-coded {@code font-size} here would override that and come out wrong on a high-density
   * screen.
   *
   * @param body the body markup, already escaped where it came from a sample
   * @return a complete document
   */
  public static String wrap(String body) {
    String foreground = hex(WaterfallColors.foreground());
    String muted = hex(WaterfallColors.mutedForeground());
    String background = hex(WaterfallColors.background());
    return "<html><head><style>"
        + "body { background: " + background + "; color: " + foreground + "; margin: 6px; }"
        + " h2 { margin: 10px 0 4px 0; }"
        + " .name { font-weight: bold; }"
        + " .value { color: " + foreground + "; }"
        + " .muted { color: " + muted + "; }"
        + " td { vertical-align: top; padding: 1px 8px 1px 0; }"
        + "</style></head><body>" + body + "</body></html>";
  }

  /**
   * Wraps markup for a tooltip.
   *
   * <p>Separate from {@link #wrap(String)} and deliberately plainer: a tooltip is painted by a
   * light-weight HTML view that does not carry a stylesheet, so a document with a {@code <style>}
   * block would have its rules ignored and its colours silently lost. Tooltips therefore use only
   * structural tags, and let the look and feel supply the colours.
   *
   * @param body the body markup, already escaped where it came from a sample
   * @return a tooltip document
   */
  public static String tooltip(String body) {
    return "<html>" + body + "</html>";
  }

  private static String hex(Color color) {
    return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
  }
}
