package com.blazemeter.jmeter.http2.control.async;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;

/**
 * Stands in for a "View Results Tree" listener: TestCompiler collects it into every
 * SamplePackage below the node it is attached to, and JMeterThread notifies it through the real
 * ListenerNotifier. What this class records is exactly what a user would see in the GUI, which is
 * the thing issues #108/#110 are really about.
 */
public class RecordingSampleListener extends AbstractTestElement implements SampleListener {

  private final List<SampleEvent> events = Collections.synchronizedList(new ArrayList<>());

  @Override
  public void sampleOccurred(SampleEvent event) {
    events.add(event);
  }

  @Override
  public void sampleStarted(SampleEvent event) {
    // not used by JMeterThread for sample results
  }

  @Override
  public void sampleStopped(SampleEvent event) {
    // not used by JMeterThread for sample results
  }

  public List<SampleEvent> events() {
    synchronized (events) {
      return new ArrayList<>(events);
    }
  }

  /** Top level results in notification order, i.e. the rows of a View Results Tree. */
  public List<SampleResult> topLevelResults() {
    List<SampleResult> results = new ArrayList<>();
    for (SampleEvent event : events()) {
      results.add(event.getResult());
    }
    return results;
  }

  public List<String> topLevelLabels() {
    List<String> labels = new ArrayList<>();
    for (SampleResult result : topLevelResults()) {
      labels.add(result.getSampleLabel());
    }
    return labels;
  }

  /**
   * Every notified result rendered as {@code parent > child > grandchild} paths, so a test can
   * assert on the whole nesting shape in one readable assertion.
   */
  public List<String> resultTree() {
    List<String> lines = new ArrayList<>();
    for (SampleResult result : topLevelResults()) {
      SampleResultTrees.appendPaths(result, "", lines);
    }
    return lines;
  }

  public void reset() {
    events.clear();
  }
}
