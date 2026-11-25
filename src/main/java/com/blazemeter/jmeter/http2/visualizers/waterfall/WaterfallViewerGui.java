package com.blazemeter.jmeter.http2.visualizers.waterfall;

import com.blazemeter.jmeter.commons.BlazemeterLabsLogo;
import java.awt.BorderLayout;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.event.ChangeEvent;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Waterfall Viewer listener: a network waterfall for a JMeter test, live or from a
 * {@code .jtl}.
 *
 * <p>Extending {@link AbstractVisualizer} is what makes reading a {@code .jtl} free rather than
 * something to implement: the base class contributes the result-file panel, and JMeter's own
 * {@link ResultCollector} parses both the CSV and the XML flavours and feeds the rows in through
 * {@link #add(SampleResult)} - the same entry point a live run uses. So a run recorded in non-GUI
 * mode opens in exactly the same view as one being watched.
 *
 * <p>Two things this class does differently from a plain visualizer, both to do with not blocking:
 * {@link #add(SampleResult)} does nothing but queue, because it runs on sampler threads and must
 * never make a test wait on the UI; and loading a file happens on a worker thread rather than on
 * the event dispatch thread, because the base class's own implementation parses the whole file
 * inline and a large result file would freeze JMeter until it finished.
 */
public class WaterfallViewerGui extends AbstractVisualizer {

  private static final long serialVersionUID = 1L;

  private static final Logger LOG = LoggerFactory.getLogger(WaterfallViewerGui.class);

  private static final String PLUGIN_REPOSITORY_URL =
      "https://github.com/Blazemeter/jmeter-http2-plugin";

  /** Holds the waterfall while it is embedded, and the "open elsewhere" note while it is not. */
  private final JPanel viewerSlot = new JPanel(new BorderLayout());

  private final WaterfallPanel waterfallPanel = new WaterfallPanel(viewerSlot);

  /** Builds the listener GUI. */
  public WaterfallViewerGui() {
    viewerSlot.add(waterfallPanel, BorderLayout.CENTER);
    setLayout(new BorderLayout(0, 5));
    setBorder(makeBorder());
    add(makeTitlePanel(), BorderLayout.NORTH);
    add(viewerSlot, BorderLayout.CENTER);
    add(new BlazemeterLabsLogo(PLUGIN_REPOSITORY_URL), BorderLayout.PAGE_END);
  }

  @Override
  public String getStaticLabel() {
    return "bzm - Waterfall Viewer";
  }

  @Override
  public String getLabelResource() {
    return null;
  }

  /**
   * Receives one sample. Called on sampler threads during a run, and on the loader thread while a
   * result file is being read.
   *
   * <p>Only queues: no Swing call, no allocation beyond the queue node, no lock a sampler thread
   * could contend on. Sub-samples are expanded later, when the queue is drained.
   *
   * @param sample the finished sample
   */
  @Override
  public void add(SampleResult sample) {
    waterfallPanel.addResult(sample);
  }

  /**
   * Loads the result file named in the file panel.
   *
   * <p>Overrides the base class, which parses the file on the event dispatch thread; a result file
   * from a real run is large enough that doing so locks up JMeter for the duration. Reading it on a
   * worker thread is safe here precisely because {@link #add(SampleResult)} only queues.
   *
   * @param event the file panel's change event
   */
  @Override
  public void stateChanged(ChangeEvent event) {
    collector = (ResultCollector) createTestElement();
    ResultCollector loader = collector;
    waterfallPanel.reset();
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() {
        loader.loadExistingFile();
        return null;
      }

      @Override
      protected void done() {
        LOG.debug("Finished loading results from {}", loader.getFilename());
      }
    }.execute();
  }

  /**
   * Discards every sample, for JMeter's Clear action and for the clear that precedes a run.
   *
   * <p>Guarded with {@code runSafe} because a clear can be triggered from the thread starting the
   * test rather than from the event dispatch thread, and everything it touches is Swing state.
   */
  @Override
  public void clearData() {
    JMeterUtils.runSafe(false, waterfallPanel::reset);
  }

  @Override
  public void clearGui() {
    super.clearGui();
    JMeterUtils.runSafe(false, waterfallPanel::reset);
  }
}
