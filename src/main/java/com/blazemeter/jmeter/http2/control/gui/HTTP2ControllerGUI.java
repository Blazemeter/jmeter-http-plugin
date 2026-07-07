package com.blazemeter.jmeter.http2.control.gui;

import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ItemEvent;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import org.apache.jmeter.control.gui.AbstractControllerGui;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.testelement.TestElement;

public class HTTP2ControllerGUI extends AbstractControllerGui implements Scrollable {
  private static final long serialVersionUID = 240L;
  private final JCheckBox generateControllerSample;
  private final JCheckBox limitMaxParallel;
  private final JTextField maxParallelField;
  private int defaultMaxParallel;

  public HTTP2ControllerGUI() {
    generateControllerSample = new JCheckBox("Generate Parent Sample");
    limitMaxParallel = new JCheckBox("Limit max number of parallel executions");
    maxParallelField = new JTextField(8);
    init();
  }

  @Override
  public String getStaticLabel() {
    return "bzm - HTTP Async Controller";
  }

  @Override
  public TestElement createTestElement() {
    HTTP2Controller lc = new HTTP2Controller();
    configureTestElement(lc);
    return lc;
  }

  @Override
  public void modifyTestElement(TestElement el) {
    configureTestElement(el);
    if (el instanceof HTTP2Controller) {
      HTTP2Controller controller = (HTTP2Controller) el;
      controller.setGenerateControllerSample(generateControllerSample.isSelected());
      controller.setLimitMaxParallel(limitMaxParallel.isSelected());
      if (limitMaxParallel.isSelected()) {
        controller.setMaxConcurrentAsyncInController(parseMaxParallelValue());
      }
    }
  }

  @Override
  public void configure(TestElement element) {
    super.configure(element);
    if (element instanceof HTTP2Controller) {
      HTTP2Controller controller = (HTTP2Controller) element;
      generateControllerSample.setSelected(controller.isGenerateControllerSample());
      limitMaxParallel.setSelected(controller.isLimitMaxParallel());
      maxParallelField.setText(String.valueOf(controller.getMaxConcurrentAsyncInController()));
      updateMaxParallelFieldState(controller);
    }
  }

  @Override
  public String getLabelResource() {
    return null;
  }

  private void init() {
    // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
    setLayout(new BorderLayout(0, 5));
    setBorder(makeBorder());
    add(makeTitlePanel(), BorderLayout.NORTH);
    add(buildOptionsPanel(), BorderLayout.CENTER);

    limitMaxParallel.addItemListener(event -> {
      boolean enabled = event.getStateChange() == ItemEvent.SELECTED;
      updateMaxParallelFieldState(enabled);
      if (!enabled && defaultMaxParallel > 0) {
        maxParallelField.setText(String.valueOf(defaultMaxParallel));
      }
    });
  }

  private JPanel buildOptionsPanel() {
    VerticalPanel panel = new VerticalPanel();
    JLabel disclaimer = new JLabel(
        "<html><div style='text-align:center'>All direct child elements of this controller that "
            + "can run in HTTP Async will run in parallel.</div></html>",
        JLabel.CENTER);
    disclaimer.setAlignmentX(JLabel.CENTER_ALIGNMENT);
    panel.add(disclaimer);

    generateControllerSample.setAlignmentX(JCheckBox.LEFT_ALIGNMENT);
    panel.add(generateControllerSample);

    limitMaxParallel.setAlignmentX(JCheckBox.LEFT_ALIGNMENT);
    panel.add(limitMaxParallel);

    JPanel maxRow = new JPanel(new BorderLayout(8, 0));
    maxRow.add(new JLabel("Max parallel: "), BorderLayout.WEST);
    maxRow.add(maxParallelField, BorderLayout.CENTER);
    panel.add(maxRow);

    return panel;
  }

  private void updateMaxParallelFieldState(HTTP2Controller controller) {
    defaultMaxParallel = controller.getDefaultMaxConcurrentAsyncInController();
    updateMaxParallelFieldState(controller.isLimitMaxParallel());
    if (!controller.isLimitMaxParallel()) {
      maxParallelField.setText(
          String.valueOf(defaultMaxParallel));
    }
  }

  private void updateMaxParallelFieldState(boolean enabled) {
    maxParallelField.setEnabled(enabled);
    maxParallelField.setEditable(enabled);
  }

  private int parseMaxParallelValue() {
    try {
      int value = Integer.parseInt(maxParallelField.getText().trim());
      return Math.max(1, value);
    } catch (Exception e) {
      return defaultMaxParallel > 0 ? defaultMaxParallel : 1;
    }
  }

  /**
   * Same as {@link com.blazemeter.jmeter.http2.sampler.gui.HTTP2SamplerGui}: JMeter embeds
   * controller settings in a {@link javax.swing.JScrollPane}; tracking viewport width allows the
   * form to narrow when the window is resized.
   */
  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return getPreferredSize();
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
    return orientation == SwingConstants.VERTICAL
        ? Math.max(1, visibleRect.height / 10)
        : Math.max(1, visibleRect.width / 10);
  }

  @Override
  public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
    return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return false;
  }
}
