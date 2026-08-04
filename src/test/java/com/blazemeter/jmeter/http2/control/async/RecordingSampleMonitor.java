package com.blazemeter.jmeter.http2.control.async;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.samplers.SampleMonitor;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.AbstractTestElement;

/**
 * Records the actual {@link Sampler} instances JMeterThread executed. JMeterThread collects every
 * {@link SampleMonitor} in the tree at construction time and calls it around each sample, so this is
 * the only way to get hold of samplers a controller synthesises at run time.
 */
public class RecordingSampleMonitor extends AbstractTestElement implements SampleMonitor {

  private static final long serialVersionUID = 1L;

  private final List<Sampler> started = Collections.synchronizedList(new ArrayList<>());

  @Override
  public void sampleStarting(Sampler sampler) {
    started.add(sampler);
  }

  @Override
  public void sampleEnded(Sampler sampler) {
    // not needed
  }

  /** Executed samplers in order, including duplicates when the same instance runs twice. */
  public List<Sampler> started() {
    synchronized (started) {
      return new ArrayList<>(started);
    }
  }

  /** Distinct executed sampler instances, preserving first-execution order. */
  public List<Sampler> distinctStarted() {
    List<Sampler> distinct = new ArrayList<>();
    for (Sampler sampler : started()) {
      boolean seen = false;
      for (Sampler known : distinct) {
        if (known == sampler) {
          seen = true;
          break;
        }
      }
      if (!seen) {
        distinct.add(sampler);
      }
    }
    return distinct;
  }
}
