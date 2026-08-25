package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import com.blazemeter.jmeter.http2.visualizers.waterfall.details.SampleDetailsPanel;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JTabbedPane;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.ResultRenderer;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Checks that the Response tab really is JMeter's own renderer stack, driven correctly.
 *
 * <p>The point of interest is that {@link ResultRenderer} was written for View Results Tree and is
 * being used outside it. Its contract is a call sequence - set the result, lay out the tabs, then
 * render - and getting the order wrong produces an empty pane rather than an exception. So the
 * assertion is on the tabs JMeter's own renderer created inside ours: if they are there, the
 * sequence was accepted.
 */
public class ResponseRendererTabTest {

  /**
   * Points JMeter's class scanner at the jar that holds its renderers.
   *
   * <p>{@code JMeterUtils.findClassesThatExtend} does not scan the JVM classpath: it scans
   * {@code JMETER_HOME/lib/ext} plus whatever the {@code search_paths} property names. A real
   * install has the renderers under that roof, but a surefire run only has them on the classpath,
   * so discovery would come back empty and these tests would pass by asserting nothing. Adding the
   * jar the {@link ResultRenderer} interface itself came from reproduces the real lookup.
   */
  @BeforeClass
  public static void bootstrapJmeter() {
    JMeterTestUtils.setupJmeterEnv();
    JMeterUtils.setProperty("search_paths", rendererJarPath());
  }

  private static String rendererJarPath() {
    try {
      return new File(ResultRenderer.class.getProtectionDomain().getCodeSource().getLocation()
          .toURI()).getAbsolutePath();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Cannot locate the jar holding JMeter's renderers", e);
    }
  }

  private static SampleResult htmlSample() {
    return SampleResultBuilder.http()
        .label("home")
        .method("GET")
        .url("https://example.com/home")
        .code("200")
        .contentType("text/html; charset=utf-8")
        .responseHeaders("HTTP/2.0 200 OK\nContent-Type: text/html\n")
        .requestHeaders("Host: example.com\n")
        .body("<html><body><h1>hi</h1></body></html>")
        .timing(1_700_000_000_000L, 120)
        .build();
  }

  /**
   * Collects the titles of every nested tabbed pane, so the assertions can look for JMeter's own
   * tab names without depending on how deeply its panels happen to nest.
   *
   * @param container the component tree to walk
   * @return every tab title found below it
   */
  private static List<String> nestedTabTitles(Container container) {
    List<String> titles = new ArrayList<>();
    collectTabTitles(container, titles);
    return titles;
  }

  private static void collectTabTitles(Container container, List<String> titles) {
    for (Component child : container.getComponents()) {
      if (child instanceof JTabbedPane) {
        JTabbedPane tabs = (JTabbedPane) child;
        for (int i = 0; i < tabs.getTabCount(); i++) {
          titles.add(tabs.getTitleAt(i));
        }
      }
      if (child instanceof Container) {
        collectTabTitles((Container) child, titles);
      }
    }
  }

  @Test
  public void jmeterShipsRenderersThisTabCanUse() throws IOException {
    List<String> renderers = JMeterUtils.findClassesThatExtend(ResultRenderer.class);

    assertThat(renderers).contains("org.apache.jmeter.visualizers.RenderAsText",
        "org.apache.jmeter.visualizers.RenderAsHTML",
        "org.apache.jmeter.visualizers.RenderAsJSON",
        "org.apache.jmeter.visualizers.RenderAsXML",
        // RenderAsDocument is the Tika-backed one: it is what turns a PDF or a Word response into
        // readable text, and the reason no content-type handling is written here.
        "org.apache.jmeter.visualizers.RenderAsDocument");
  }

  @Test
  public void theResponseTabIsPopulatedByJmetersOwnRenderer() {
    SampleDetailsPanel panel = new SampleDetailsPanel();
    panel.selectTab("Response");

    panel.setRecord(new SampleRecord(htmlSample(), 0, 0));

    // The outer tabs are the details panel's own; the inner ones can only have been created by
    // JMeter's RenderAsText, which is what proves the renderer accepted our call sequence.
    assertThat(nestedTabTitles(panel))
        .containsSequence("Headers", "Response", "Timing", "Cookies")
        .contains("Sampler result", "Request", "Response data");
  }

  @Test
  public void switchingSampleRerendersWithoutLosingTheRendererTabs() {
    SampleDetailsPanel panel = new SampleDetailsPanel();
    panel.selectTab("Response");
    panel.setRecord(new SampleRecord(htmlSample(), 0, 0));

    panel.setRecord(new SampleRecord(SampleResultBuilder.http()
        .label("api")
        .code("500")
        .success(false)
        .contentType("application/json")
        .body("{\"error\":true}")
        .timing(1_700_000_001_000L, 40)
        .build(), 1, 0));

    assertThat(nestedTabTitles(panel)).contains("Sampler result", "Request", "Response data");
  }

  @Test
  public void aSampleWithNoBodyStillRenders() {
    SampleDetailsPanel panel = new SampleDetailsPanel();
    panel.selectTab("Response");

    panel.setRecord(new SampleRecord(
        SampleResultBuilder.http().label("head").code("204").timing(1_700_000_000_000L, 5).build(),
        0, 0));

    assertThat(nestedTabTitles(panel)).contains("Sampler result");
  }

  @Test
  public void clearingTheSelectionEmptiesTheTab() {
    SampleDetailsPanel panel = new SampleDetailsPanel();
    panel.selectTab("Response");
    panel.setRecord(new SampleRecord(htmlSample(), 0, 0));

    panel.setRecord(null);

    assertThat(nestedTabTitles(panel)).doesNotContain("Sampler result");
  }
}
