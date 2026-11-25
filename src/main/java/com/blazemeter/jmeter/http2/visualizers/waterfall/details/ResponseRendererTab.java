package com.blazemeter.jmeter.http2.visualizers.waterfall.details;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.SampleRecord;
import java.awt.BorderLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.ResultRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Response tab: JMeter's own result renderers, hosted inside the waterfall's details panel.
 *
 * <p>This tab deliberately contains no code that looks at a content type. Deciding how to present a
 * response body is a problem JMeter already solved, dynamically and extensibly, through the
 * {@link ResultRenderer} interface: {@code RenderAsHTML} for markup, {@code RenderAsJSON} and
 * {@code RenderAsXML} for structured payloads, {@code RenderAsDocument} which runs the bytes
 * through Apache Tika so a PDF or a Word document comes out as text, {@code RenderInBrowser}, the
 * extractor testers, and whatever a third-party plugin has dropped into {@code lib/ext}.
 * Reimplementing any of that here would mean a second, worse copy that drifts from View Results
 * Tree at every JMeter release.
 *
 * <p>So the renderers are discovered the same way View Results Tree discovers them - through
 * {@link JMeterUtils#findClassesThatExtend(Class)}, ordered by the same
 * {@value #RENDERERS_ORDER_PROPERTY} property, defaulting to the same {@code RenderAsText} - and
 * driven through the same call sequence. What the user sees in this tab is what they would see on
 * the right-hand side of View Results Tree, including the request view, the response metadata table
 * and JMeter's own search box.
 *
 * <p>Discovery scans the classpath, which is slow, so it happens once per JMeter session and only
 * when a response is first shown. Doing it in a constructor would charge every JMeter startup for
 * it, since JMeter builds one instance of every listener GUI while assembling its Add menu.
 */
public final class ResponseRendererTab extends DetailTab {

  /** Same property View Results Tree uses to order the renderer list. */
  public static final String RENDERERS_ORDER_PROPERTY = "view.results.tree.renderers_order";

  private static final long serialVersionUID = 1L;

  private static final Logger LOG = LoggerFactory.getLogger(ResponseRendererTab.class);

  private static final String RENDERER_PACKAGE = "org.apache.jmeter.visualizers";

  /** The renderer selected until the user picks another, matching View Results Tree's default. */
  private static final String DEFAULT_RENDERER = RENDERER_PACKAGE + ".RenderAsText";

  private static List<ResultRenderer> discovered;

  private final JTabbedPane rendererTabs = new JTabbedPane();
  private final JComboBox<ResultRenderer> rendererSelector = new JComboBox<>();
  private final JPanel content = new JPanel(new BorderLayout());
  private ResultRenderer renderer;
  private boolean built;

  /** Creates the tab without touching the classpath. */
  public ResponseRendererTab() {
    setContent(emptyState("Select a request to see its response."));
  }

  @Override
  public String getTitle() {
    return "Response";
  }

  @Override
  protected void render(SampleRecord record) {
    if (record == null) {
      setContent(emptyState("Select a request to see its response."));
      return;
    }
    if (!buildOnce()) {
      setContent(emptyState("No JMeter result renderer could be loaded, so the response cannot be"
          + " displayed. See jmeter.log."));
      return;
    }
    setContent(content);
    show(record.getResult());
  }

  /**
   * Builds the selector and the renderer pane on first use.
   *
   * @return {@code true} when at least one renderer is available
   */
  private boolean buildOnce() {
    if (built) {
      return renderer != null;
    }
    built = true;
    List<ResultRenderer> renderers = getRenderers();
    if (renderers.isEmpty()) {
      return false;
    }
    for (ResultRenderer candidate : renderers) {
      rendererSelector.addItem(candidate);
      if (DEFAULT_RENDERER.equals(candidate.getClass().getName())) {
        rendererSelector.setSelectedItem(candidate);
      }
    }
    rendererSelector.addActionListener(event -> selectRenderer());
    JPanel selectorRow = new JPanel(new BorderLayout(6, 0));
    selectorRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
    selectorRow.add(new JLabel(JMeterUtils.getResString("view_results_render")),
        BorderLayout.WEST);
    selectorRow.add(rendererSelector, BorderLayout.CENTER);
    content.add(selectorRow, BorderLayout.NORTH);
    content.add(rendererTabs, BorderLayout.CENTER);
    activate((ResultRenderer) rendererSelector.getSelectedItem());
    return renderer != null;
  }

  /** Switches to the renderer the user picked and re-renders the current sample. */
  private void selectRenderer() {
    ResultRenderer selected = (ResultRenderer) rendererSelector.getSelectedItem();
    if (selected == null || selected == renderer) {
      return;
    }
    activate(selected);
    SampleRecord record = getRecord();
    if (record != null) {
      show(record.getResult());
    }
  }

  /**
   * Hands a renderer the tab pane it owns, in the order {@link ResultRenderer} expects.
   *
   * @param selected the renderer to activate
   */
  private void activate(ResultRenderer selected) {
    renderer = selected;
    if (selected == null) {
      return;
    }
    selected.setBackgroundColor(getBackground());
    selected.setRightSide(rendererTabs);
    selected.init();
  }

  /**
   * Renders one sample, following the same call sequence View Results Tree uses.
   *
   * <p>The order matters and is not obvious: the result has to be set before the tabs are laid out,
   * because {@code setupTabPane} rebuilds the pane from whatever result is currently held. The
   * choice between {@code renderResult} and {@code renderImage} is JMeter's own - a sample whose
   * data type is not text goes down the image path.
   *
   * @param result the sample to show
   */
  private void show(SampleResult result) {
    if (renderer == null) {
      return;
    }
    int lastTab = rendererTabs.getSelectedIndex();
    if (lastTab >= 0) {
      renderer.setLastSelectedTab(lastTab);
    }
    renderer.setSamplerResult(result);
    renderer.setupTabPane();
    if (isTextDataType(result)) {
      renderer.renderResult(result);
    } else {
      renderer.renderImage(result);
    }
  }

  /**
   * Whether a sample's data type routes it to the text renderer rather than the image one.
   *
   * <p>Mirrors View Results Tree's own test, which is on the sample's data type and not on its
   * content type: an empty data type counts as text, because most samplers never set one.
   *
   * @param result the sample
   * @return {@code true} when the text path applies
   */
  private static boolean isTextDataType(SampleResult result) {
    String dataType = result.getDataType();
    return dataType == null || dataType.isEmpty() || SampleResult.TEXT.equals(dataType);
  }

  /**
   * The renderers available in this JMeter, discovered once and shared.
   *
   * <p>Each is instantiated eagerly because that is the only way to know whether it can be used:
   * {@code RenderInBrowser} needs JavaFX and throws {@link NoClassDefFoundError} from its
   * constructor when it is absent, which View Results Tree also swallows rather than letting one
   * unavailable renderer remove the rest.
   *
   * @return the renderers, in the configured display order
   */
  private static synchronized List<ResultRenderer> getRenderers() {
    if (discovered != null) {
      return discovered;
    }
    discovered = instantiate(findRendererClassNames());
    return discovered;
  }

  private static List<String> findRendererClassNames() {
    try {
      return JMeterUtils.findClassesThatExtend(ResultRenderer.class);
    } catch (IOException e) {
      LOG.warn("Could not scan the classpath for JMeter result renderers", e);
      return List.of();
    }
  }

  private static List<ResultRenderer> instantiate(List<String> classNames) {
    Map<String, ResultRenderer> byName = new LinkedHashMap<>();
    for (String className : classNames) {
      try {
        byName.put(className, Class.forName(className).asSubclass(ResultRenderer.class)
            .getDeclaredConstructor().newInstance());
      } catch (NoClassDefFoundError e) {
        // RenderInBrowser without JavaFX is the expected case; log it the way JMeter does.
        LOG.info("Skipping result renderer {}: {}", className, e.getMessage());
      } catch (ReflectiveOperationException | RuntimeException e) {
        LOG.warn("Could not load result renderer {}", className, e);
      }
    }
    List<ResultRenderer> renderers = new ArrayList<>(byName.values());
    renderers.sort(Comparator.comparingInt(renderer -> orderOf(renderer.getClass().getName())));
    return renderers;
  }

  /**
   * The display position of a renderer, from the same property View Results Tree reads.
   *
   * <p>Renderers the property does not mention keep their discovery order behind the ones it does,
   * so a third-party renderer still appears rather than being dropped for being unlisted.
   *
   * @param className the renderer's class name
   * @return its sort key
   */
  private static int orderOf(String className) {
    String order = JMeterUtils.getPropDefault(RENDERERS_ORDER_PROPERTY, "");
    if (order.isEmpty()) {
      return Integer.MAX_VALUE;
    }
    String[] names = order.split(",");
    for (int i = 0; i < names.length; i++) {
      if (expandToClassName(names[i].trim()).equals(className)) {
        return i;
      }
    }
    return Integer.MAX_VALUE;
  }

  /**
   * Expands the abbreviated form the order property uses, where a leading dot stands for JMeter's
   * own visualizers package.
   *
   * @param name a possibly abbreviated class name, such as {@code .RenderAsText}
   * @return the fully qualified class name
   */
  private static String expandToClassName(String name) {
    return name.startsWith(".") ? RENDERER_PACKAGE + name : name;
  }
}
