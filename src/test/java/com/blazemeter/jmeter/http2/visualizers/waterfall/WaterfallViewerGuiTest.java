package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.apache.jmeter.reporters.ResultCollector;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A smoke test for the listener itself.
 *
 * <p>Worth having despite testing so little, because of when the constructor runs: JMeter builds one
 * instance of every listener GUI on the classpath while assembling its Add menu, before any test
 * plan exists. A constructor that throws there does not produce a broken viewer - it produces a
 * plugin that appears not to be installed, with the reason buried in {@code jmeter.log}.
 *
 * <p>Runs headless, which is the point of it running in CI: nothing in the viewer needs a display
 * to be built, only to be shown, and keeping it that way is what lets this test catch a constructor
 * that would break the Add menu.
 */
public class WaterfallViewerGuiTest {

  @BeforeClass
  public static void bootstrapJmeter() {
    JMeterTestUtils.setupJmeterEnv();
  }

  private static WaterfallViewerGui buildOnEventThread() throws Exception {
    AtomicReference<WaterfallViewerGui> built = new AtomicReference<>();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(() -> {
        try {
          built.set(new WaterfallViewerGui());
        } catch (RuntimeException e) {
          failure.set(e);
        }
      });
    } catch (InvocationTargetException e) {
      throw new IllegalStateException(e.getCause());
    }
    if (failure.get() != null) {
      throw failure.get();
    }
    return built.get();
  }

  @Test
  public void buildsWithoutATestPlanTheWayTheAddMenuDoes() throws Exception {
    WaterfallViewerGui gui = buildOnEventThread();

    assertThat(gui.getStaticLabel()).isEqualTo("bzm - Waterfall Viewer");
    assertThat(gui.getLabelResource()).isNull();
    assertThat(gui.isStats()).isFalse();
  }

  @Test
  public void producesAResultCollectorSoJmeterCanSaveAndReloadResults() throws Exception {
    WaterfallViewerGui gui = buildOnEventThread();

    assertThat(gui.createTestElement()).isInstanceOf(ResultCollector.class);
  }

  @Test
  public void acceptsSamplesAndClearsWithoutTouchingTheCallingThread() throws Exception {
    WaterfallViewerGui gui = buildOnEventThread();

    gui.add(SampleResultBuilder.http().label("home").timing(1000, 50).build());
    gui.clearData();
    gui.add(SampleResultBuilder.http().label("home again").timing(1100, 50).build());

    // Nothing to assert beyond "no exception": add() only enqueues, and the drain happens on the
    // event dispatch thread later. What this rules out is a listener that throws on a test thread.
    assertThat(gui.getStaticLabel()).isNotEmpty();
  }
}
