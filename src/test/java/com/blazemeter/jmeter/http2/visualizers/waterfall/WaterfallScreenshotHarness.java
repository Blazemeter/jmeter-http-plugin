package com.blazemeter.jmeter.http2.visualizers.waterfall;

import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.GroupMode;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallColumn;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Renders the Waterfall Viewer to PNG files so its appearance can actually be looked at.
 *
 * <p>Not a unit test and not run by the build. The pixel assertions in
 * {@link WaterfallBarRendererTest} can prove a bar is the right colour in the right place, but they
 * cannot show that a toolbar wraps badly, that a column is too narrow for its heading, or that the
 * ruler labels collide - and those are the defects a waterfall actually suffers from. This harness
 * builds a set of scenarios, lays each one out at a realistic size and writes an image, so the
 * result can be reviewed and re-reviewed after a change.
 *
 * <p>Run it with the plugin's test classpath plus a real JMeter installation's {@code lib}, which is
 * what makes the Response tab show the actual renderers and lets the dark theme be JMeter's own:
 *
 * <pre>
 * java -cp "target/test-classes;target/classes;$JMETER_HOME/lib/*;$JMETER_HOME/lib/ext/*" \
 *   com.blazemeter.jmeter.http2.visualizers.waterfall.WaterfallScreenshotHarness &lt;outDir&gt; [dark]
 * </pre>
 *
 * <p>Layout needs a real display: Swing only lays a component tree out once it is attached to a
 * displayable window. The frames here are packed but never shown, so nothing appears on screen.
 */
public final class WaterfallScreenshotHarness {

  private static final long BASE = 1_700_000_000_000L;
  private static final int WIDE = 1600;
  private static final int TALL = 900;
  private static final int EMBEDDED_WIDTH = 1000;
  private static final int EMBEDDED_HEIGHT = 420;

  /** Narrow enough that the toolbar has to wrap, which is the point of the scenario. */
  private static final int NARROW_WIDTH = 640;

  private final Path outDir;

  private WaterfallScreenshotHarness(Path outDir) {
    this.outDir = outDir;
  }

  /**
   * Writes every scenario as a PNG.
   *
   * @param args the output directory, then optionally {@code dark} to use JMeter's dark theme
   * @throws Exception when a scenario cannot be rendered or written
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      throw new IllegalArgumentException(
          "usage: -Djmeter.home=<dir> WaterfallScreenshotHarness <outDir> [dark]");
    }
    bootstrapJmeter();
    boolean dark = args.length > 1 && "dark".equalsIgnoreCase(args[1]);
    if (dark) {
      installDarkTheme();
    }
    Path outDir = Path.of(args[0]);
    Files.createDirectories(outDir);
    new WaterfallScreenshotHarness(outDir).renderAll(dark ? "dark" : "light");
  }

  /**
   * Starts JMeter the way a real installation does, when one is pointed at.
   *
   * <p>Worth the extra argument: with a real {@code JMETER_HOME}, {@code lib/ext} is where JMeter's
   * own component jars live, so the Response tab discovers the genuine renderer set instead of an
   * empty one, and the dark theme is the Darklaf build JMeter ships. Without it the harness still
   * runs, but the Response tab will have nothing to show.
   */
  private static void bootstrapJmeter() {
    String home = System.getProperty("jmeter.home");
    if (home == null || home.isEmpty()) {
      System.out.println("No -Djmeter.home given: the Response tab will be empty.");
      JMeterTestUtils.setupJmeterEnv();
      return;
    }
    JMeterUtils.setJMeterHome(home);
    JMeterUtils.loadJMeterProperties(home + "/bin/jmeter.properties");
    JMeterUtils.initLocale();
  }

  /** Installs Darklaf, the look and feel JMeter itself ships for its dark theme. */
  private static void installDarkTheme() {
    try {
      Class<?> lafClass = Class.forName("com.github.weisj.darklaf.LafManager");
      Class<?> themeClass = Class.forName("com.github.weisj.darklaf.theme.OneDarkTheme");
      lafClass.getMethod("setTheme", Class.forName("com.github.weisj.darklaf.theme.Theme"))
          .invoke(null, themeClass.getDeclaredConstructor().newInstance());
      lafClass.getMethod("install").invoke(null);
    } catch (ReflectiveOperationException e) {
      System.out.println("Darklaf unavailable, falling back to a darkened Metal: " + e);
      UIManager.put("Panel.background", new java.awt.Color(0x2B2B2B));
      UIManager.put("Table.background", new java.awt.Color(0x2B2B2B));
      UIManager.put("Table.foreground", new java.awt.Color(0xDDDDDD));
      UIManager.put("TableHeader.background", new java.awt.Color(0x3C3F41));
      UIManager.put("TableHeader.foreground", new java.awt.Color(0xDDDDDD));
    }
  }

  private void renderAll(String themeName) throws Exception {
    shoot(themeName + "-01-page-load", WIDE, TALL, panel -> {
      feed(panel, pageLoad());
      panel.setDetailsVisible(false);
    });
    shoot(themeName + "-02-embedded-mini", EMBEDDED_WIDTH, EMBEDDED_HEIGHT, panel -> {
      feed(panel, pageLoad());
      panel.setDetailsVisible(false);
    });
    shoot(themeName + "-03-details-timing", WIDE, TALL, panel -> {
      feed(panel, pageLoad());
      panel.selectRow(1);
      panel.selectDetailsTab("Timing");
    });
    shoot(themeName + "-04-details-response", WIDE, TALL, panel -> {
      feed(panel, pageLoad());
      panel.selectRow(0);
      panel.selectDetailsTab("Response");
    });
    shoot(themeName + "-05-details-headers", WIDE, TALL, panel -> {
      feed(panel, pageLoad());
      panel.selectRow(0);
      panel.selectDetailsTab("Headers");
    });
    shoot(themeName + "-06-grouped-threads", WIDE, TALL, panel -> {
      feed(panel, loadTest());
      panel.setDetailsVisible(false);
      panel.getToolBar().applyGroupMode(GroupMode.THREAD_GROUP);
    });
    shoot(themeName + "-07-errors-only", WIDE, TALL, panel -> {
      feed(panel, loadTest());
      panel.setDetailsVisible(false);
      panel.getToolBar().applyErrorsOnly(true);
    });
    shoot(themeName + "-08-big-rows-all-columns", WIDE, TALL, panel -> {
      feed(panel, pageLoad());
      panel.setDetailsVisible(false);
      panel.getToolBar().applyBigRows(true);
      panel.getModel().setColumnVisible(WaterfallColumn.TYPE, true);
      panel.getModel().setColumnVisible(WaterfallColumn.THREAD, true);
    });
    shoot(themeName + "-09-zoomed", WIDE, TALL, panel -> {
      feed(panel, pageLoad());
      panel.setDetailsVisible(false);
      panel.getModel().getAxis().setView(BASE + 200, BASE + 700);
    });
    shoot(themeName + "-10-no-phase-data", WIDE, TALL, panel -> {
      feed(panel, nonHttpSamples());
      panel.setDetailsVisible(false);
    });
    shoot(themeName + "-11-empty", WIDE, TALL, panel -> {
    });
    shoot(themeName + "-13-narrow-toolbar-wrap", NARROW_WIDTH, EMBEDDED_HEIGHT, panel -> {
      feed(panel, pageLoad());
      panel.setDetailsVisible(false);
    });
    shoot(themeName + "-12-long-run", WIDE, TALL, panel -> {
      feed(panel, longRun());
      panel.setDetailsVisible(false);
    });
    System.out.println("Wrote screenshots to " + outDir.toAbsolutePath());
  }

  /**
   * Builds a panel, applies a scenario, lays it out and writes it to a PNG.
   *
   * @param name      the file name, without extension
   * @param width     the width to lay out at
   * @param height    the height to lay out at
   * @param scenario  what to put in the panel
   * @throws Exception when the image cannot be produced or written
   */
  private void shoot(String name, int width, int height, Consumer<WaterfallPanel> scenario)
      throws Exception {
    BufferedImage[] holder = new BufferedImage[1];
    runOnEventThread(() -> {
      JPanel slot = new JPanel(new java.awt.BorderLayout());
      WaterfallPanel panel = new WaterfallPanel(slot);
      slot.add(panel, java.awt.BorderLayout.CENTER);
      scenario.accept(panel);
      holder[0] = paint(slot, width, height);
      panel.dispose();
    });
    File file = outDir.resolve(name + ".png").toFile();
    ImageIO.write(holder[0], "png", file);
    System.out.println("  " + file.getName() + "  " + width + "x" + height);
  }

  /**
   * Lays a component tree out and paints it into an image.
   *
   * <p>The frame is the only way to get Swing to lay the tree out - an unattached component reports
   * a zero size and paints nothing. It is packed so the peers exist, sized explicitly, validated,
   * and disposed; {@code setVisible} is never called, so no window appears.
   *
   * @param content the component to render
   * @param width   the width to lay out at
   * @param height  the height to lay out at
   * @return the painted image
   */
  private static BufferedImage paint(Container content, int width, int height) {
    JFrame frame = new JFrame("waterfall-harness");
    try {
      frame.setContentPane((JPanel) content);
      frame.pack();
      frame.setSize(width, height);
      frame.validate();
      // A second pass: the first validate settles split-pane and viewport sizes that the layout
      // managers only learn once their parents have a real size.
      frame.doLayout();
      frame.validate();
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = image.createGraphics();
      try {
        content.paint(graphics);
      } finally {
        graphics.dispose();
      }
      return image;
    } finally {
      frame.dispose();
    }
  }

  private static void runOnEventThread(Runnable task) throws Exception {
    RuntimeException[] failure = new RuntimeException[1];
    try {
      SwingUtilities.invokeAndWait(() -> {
        try {
          task.run();
        } catch (RuntimeException e) {
          failure[0] = e;
        }
      });
    } catch (InvocationTargetException e) {
      throw new IllegalStateException(e.getCause());
    }
    if (failure[0] != null) {
      throw failure[0];
    }
  }

  /**
   * Pushes samples in and drains them, so the panel is populated without waiting for its timer.
   *
   * @param panel   the panel to fill
   * @param samples the samples to add
   */
  private static void feed(WaterfallPanel panel, List<SampleResult> samples) {
    for (SampleResult sample : samples) {
      panel.addResult(sample);
    }
    panel.drainNow();
  }

  /** A browser-like page load: a document, then its embedded resources, then an API call. */
  private static List<SampleResult> pageLoad() {
    List<SampleResult> samples = new ArrayList<>();
    samples.add(SampleResultBuilder.http()
        .label("GET /shop")
        .method("GET")
        .url("https://shop.example.com/")
        .code("200")
        .contentType("text/html; charset=utf-8")
        .responseHeaders("HTTP/2.0 200 OK\nContent-Type: text/html; charset=utf-8\n"
            + "Server: nginx\nSet-Cookie: sid=8f21ac; Path=/; HttpOnly; Secure; SameSite=Lax\n")
        .requestHeaders("Host: shop.example.com\nUser-Agent: Mozilla/5.0\nAccept: text/html\n")
        .body(catalogueHtml(60))
        .thread("Shoppers 1-1")
        .timing(BASE, 260)
        .connect(48)
        .latency(180)
        .child(embedded("GET /static/app.css", "text/css", 4_096, 300, 90, 12, 70))
        .child(embedded("GET /static/app.js", "application/javascript", 128_874, 320, 210, 0, 60))
        .child(embedded("GET /static/logo.png", "image/png", 22_140, 340, 120, 0, 40))
        .child(embedded("GET /static/hero.jpg", "image/jpeg", 512_300, 350, 480, 0, 55))
        .child(embedded("GET /static/missing.svg", "text/html", 512, 380, 40, 0, 38))
        .build());
    samples.add(SampleResultBuilder.http()
        .label("POST /api/cart")
        .method("POST")
        .url("https://api.example.com/v1/cart")
        .code("201")
        .contentType("application/json")
        .responseHeaders("HTTP/3.0 201 Created\nContent-Type: application/json\n")
        .requestHeaders("Host: api.example.com\nContent-Type: application/json\n")
        .body(cartJson(8))
        .thread("Shoppers 1-1")
        .timing(BASE + 900, 640)
        .connect(120)
        .latency(590)
        .build());
    samples.add(SampleResultBuilder.http()
        .label("GET /api/recommendations")
        .method("GET")
        .url("https://api.example.com/v1/recommendations?sku=AB-1")
        .code("503")
        .success(false)
        .contentType("application/json")
        .responseHeaders("HTTP/2.0 503 Service Unavailable\nRetry-After: 30\n")
        .body("{\"error\":\"upstream unavailable\",\"retryAfter\":30}")
        .thread("Shoppers 1-1")
        .timing(BASE + 1600, 3_040)
        .connect(0)
        .latency(3_020)
        .build());
    samples.add(SampleResultBuilder.http()
        .label("GET /legacy/report")
        .method("GET")
        .url("http://legacy.example.com/report")
        .code("301")
        .contentType("text/html")
        .responseHeaders("HTTP/1.1 301 Moved Permanently\nLocation: https://example.com/report\n")
        .thread("Shoppers 1-1")
        .bytes(310)
        .timing(BASE + 4_800, 90)
        .connect(30)
        .latency(85)
        .build());
    return samples;
  }

  /**
   * A product-listing page of roughly realistic size, so the Size column and JMeter"s own byte
   * count agree: {@code getBytesAsLong} is derived from the header and body sizes, so an explicit
   * byte count set alongside a short body is simply ignored.
   *
   * @param products how many product blocks to emit
   * @return the page markup
   */
  private static String catalogueHtml(int products) {
    StringBuilder html = new StringBuilder(
        "<html><head><title>Shop - Catalogue</title></head><body><h1>Catalogue</h1><ul>");
    for (int i = 1; i <= products; i++) {
      html.append("<li class=\"product\"><a href=\"/product/AB-").append(i).append("\">Product AB-")
          .append(i).append("</a> <span class=\"price\">EUR ").append(9 + i).append(".99</span>")
          .append("<p class=\"blurb\">A dependable item, in stock and ready to ship.</p></li>");
    }
    return html.append("</ul></body></html>").toString();
  }

  /**
   * A cart response with enough items to be worth expanding in the JSON renderer.
   *
   * @param items how many line items to emit
   * @return the JSON body
   */
  private static String cartJson(int items) {
    StringBuilder json = new StringBuilder("{\"cartId\":\"c-9931\",\"currency\":\"EUR\",\"items\":[");
    for (int i = 1; i <= items; i++) {
      json.append(i > 1 ? "," : "").append("{\"sku\":\"AB-").append(i).append("\",\"name\":\"Product AB-")
          .append(i).append("\",\"qty\":").append(1 + i % 3).append(",\"unitPrice\":").append(9 + i)
          .append(".99}");
    }
    return json.append("],\"total\":218.91,\"vat\":45.97}").toString();
  }

  private static SampleResult embedded(String label, String contentType, long bytes, long offset,
      long elapsed, long connect, long latency) {
    return SampleResultBuilder.http()
        .label(label)
        .method("GET")
        .url("https://shop.example.com" + label.substring(label.indexOf('/')))
        .code(label.endsWith("missing.svg") ? "404" : "200")
        .success(!label.endsWith("missing.svg"))
        .contentType(contentType)
        .responseHeaders("HTTP/2.0 200 OK\nContent-Type: " + contentType + "\n")
        .thread("Shoppers 1-1")
        .bytes(bytes)
        .timing(BASE + offset, elapsed)
        .connect(connect)
        .latency(latency)
        .build();
  }

  /** Several thread groups running the same labels, for the grouping scenarios. */
  private static List<SampleResult> loadTest() {
    List<SampleResult> samples = new ArrayList<>();
    String[] groups = {"Shoppers", "Admins", "Search Bots"};
    String[] labels = {"GET /", "POST /login", "GET /search", "POST /checkout"};
    for (int g = 0; g < groups.length; g++) {
      for (int i = 0; i < 6; i++) {
        String label = labels[i % labels.length];
        boolean failing = g == 1 && i % 3 == 0;
        samples.add(SampleResultBuilder.http()
            .label(label)
            .method(label.startsWith("POST") ? "POST" : "GET")
            .url("https://example.com" + label.substring(label.indexOf('/')))
            .code(failing ? "500" : "200")
            .success(!failing)
            .contentType("text/html")
            .responseHeaders((i % 2 == 0 ? "HTTP/2.0 " : "HTTP/1.1 ")
                + (failing ? "500 Internal Server Error" : "200 OK") + "\n")
            .thread(groups[g] + " 1-" + (i + 1))
            .bytes(2_048L * (i + 1))
            .timing(BASE + g * 400L + i * 220L, 120 + i * 90L)
            .connect(i == 0 ? 40 : 0)
            .latency(80 + i * 60L)
            .build());
      }
    }
    return samples;
  }

  /** Samples with no connect time and no latency, as a JDBC or JSR223 sampler produces. */
  private static List<SampleResult> nonHttpSamples() {
    List<SampleResult> samples = new ArrayList<>();
    String[] labels = {"JDBC select orders", "JDBC insert audit", "JMS publish event",
        "Transaction: checkout"};
    for (int i = 0; i < labels.length; i++) {
      SampleResultBuilder builder = SampleResultBuilder.http()
          .label(labels[i])
          .code("200")
          .thread("Batch 1-1")
          .bytes(400L * (i + 1))
          .timing(BASE + i * 500L, 200 + i * 300L);
      if (i == labels.length - 1) {
        builder.idle(700);
      }
      samples.add(builder.build());
    }
    return samples;
  }

  /** Enough samples, spread over minutes, to exercise the ruler and the row density. */
  private static List<SampleResult> longRun() {
    List<SampleResult> samples = new ArrayList<>();
    for (int i = 0; i < 120; i++) {
      samples.add(SampleResultBuilder.http()
          .label("GET /page/" + (i % 7))
          .method("GET")
          .url("https://example.com/page/" + (i % 7))
          .code(i % 17 == 0 ? "404" : "200")
          .success(i % 17 != 0)
          .responseHeaders("HTTP/2.0 200 OK\n")
          .thread("Users 1-" + (1 + i % 5))
          .bytes(1_500L + i * 40L)
          .timing(BASE + i * 2_500L, 90 + (i % 11) * 130L)
          .connect(i % 5 == 0 ? 35 : 0)
          .latency(60 + (i % 11) * 90L)
          .build());
    }
    return samples;
  }
}
