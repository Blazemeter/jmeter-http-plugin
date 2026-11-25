package com.blazemeter.jmeter.http2.visualizers.waterfall;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallColumn;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallStore;
import com.blazemeter.jmeter.http2.visualizers.waterfall.model.WaterfallTableModel;
import com.blazemeter.jmeter.http2.visualizers.waterfall.render.WaterfallColors;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.table.TableCellRenderer;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.Test;

/**
 * Paints the waterfall column into an image and reads the pixels back.
 *
 * <p>The bars are the feature, and their correctness is geometric: the right phase colour has to
 * land in the right place, scaled by the shared axis, starting at the sample's real start time. No
 * assertion on the model can show that, and by eye it is exactly the kind of thing that looks
 * plausible while being wrong by one segment.
 *
 * <p>Every sample is stamped from {@link #BASE} rather than from zero, because
 * {@code SampleResult.setEndTime} reads a start time of zero as "never started" and leaves the
 * elapsed time at zero. The axis is relative, so the base is invisible to the assertions.
 */
public class WaterfallBarRendererTest {

  /** Any non-zero epoch; see the class comment. */
  private static final long BASE = 1_700_000_000_000L;

  private static final int WIDTH = 200;
  private static final int HEIGHT = 20;

  /**
   * Paints one row's waterfall cell at a known size.
   *
   * @param rowIndex which row to paint
   * @param samples  the samples to load
   * @return the painted cell, and the table it was painted for
   */
  private static Painted paint(int rowIndex, SampleResult... samples) {
    WaterfallStore store = new WaterfallStore(-1);
    for (SampleResult sample : samples) {
      store.offer(sample);
    }
    store.drainPending(1000);
    WaterfallTableModel model = new WaterfallTableModel(store);
    model.rebuild();
    WaterfallTable table = new WaterfallTable(model);
    int columnIndex = -1;
    for (int i = 0; i < model.getColumnCount(); i++) {
      if (model.getColumn(i) == WaterfallColumn.WATERFALL) {
        columnIndex = i;
      }
    }
    assertThat(columnIndex).isNotNegative();
    TableCellRenderer renderer = table.getColumnModel().getColumn(columnIndex).getCellRenderer();
    Component cell = renderer.getTableCellRendererComponent(table,
        model.getValueAt(rowIndex, columnIndex), false, false, rowIndex, columnIndex);
    cell.setBounds(0, 0, WIDTH, HEIGHT);
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      cell.paint(graphics);
    } finally {
      graphics.dispose();
    }
    return new Painted(image, table.getBackground());
  }

  @Test
  public void phasesArePaintedInOrderAndScaledToTheAxis() {
    // One sample filling the whole timeline: 100 ms connect, 400 ms waiting, 500 ms download over
    // 200 pixels, so the boundaries fall at x=20 and x=100.
    Painted painted = paint(0, SampleResultBuilder.http()
        .timing(BASE, 1000)
        .connect(100)
        .latency(500)
        .build());

    assertThat(painted.at(10)).isEqualTo(WaterfallColors.connect());
    assertThat(painted.at(60)).isEqualTo(WaterfallColors.ttfb());
    assertThat(painted.at(150)).isEqualTo(WaterfallColors.download());
  }

  @Test
  public void aBarStartsAtTheSamplesRealStartTime() {
    // The second sample starts halfway through the timeline, so its row is empty before x=100.
    // Sampled well left of the midpoint: this bar runs to the right edge, so its duration label is
    // placed to the left of it instead, and the pixels just before x=100 are that text.
    Painted painted = paint(1,
        SampleResultBuilder.http().timing(BASE, 100).build(),
        SampleResultBuilder.http().timing(BASE + 500, 500).connect(0).latency(250).build());

    assertThat(painted.at(20)).isEqualTo(painted.background);
    assertThat(painted.at(40)).isEqualTo(painted.background);
    assertThat(painted.at(120)).isEqualTo(WaterfallColors.ttfb());
    assertThat(painted.at(180)).isEqualTo(WaterfallColors.download());
  }

  @Test
  public void aSampleWithNoPhaseDataIsOneUnbrokenBar() {
    Painted painted = paint(0, SampleResultBuilder.http().timing(BASE, 1000).build());

    assertThat(painted.at(10)).isEqualTo(WaterfallColors.unbroken());
    assertThat(painted.at(190)).isEqualTo(WaterfallColors.unbroken());
  }

  @Test
  public void idleTimeIsTheGreyLeadingSegment() {
    // 500 ms idle then 500 ms of measured time, over a 1000 ms timeline.
    Painted painted = paint(0, SampleResultBuilder.http()
        .timing(BASE, 500)
        .idle(500)
        .connect(0)
        .latency(250)
        .build());

    assertThat(painted.at(50)).isEqualTo(WaterfallColors.idle());
    assertThat(painted.at(130)).isEqualTo(WaterfallColors.ttfb());
    assertThat(painted.at(180)).isEqualTo(WaterfallColors.download());
  }

  @Test
  public void aSampleTooShortToFillAPixelIsStillVisible() {
    // One millisecond out of ten seconds is a fiftieth of a pixel; it must not vanish.
    Painted painted = paint(0,
        SampleResultBuilder.http().timing(BASE, 1).connect(0).latency(1).build(),
        SampleResultBuilder.http().timing(BASE, 10_000).build());

    assertThat(painted.at(0)).isEqualTo(WaterfallColors.ttfb());
  }

  /** A painted cell plus the background it was painted over. */
  private static final class Painted {

    private final BufferedImage image;
    private final Color background;

    private Painted(BufferedImage image, Color background) {
      this.image = image;
      this.background = background;
    }

    private Color at(int x) {
      return new Color(image.getRGB(x, HEIGHT / 2));
    }
  }
}
