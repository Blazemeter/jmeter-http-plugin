package com.blazemeter.jmeter.http2.control.async;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;

/**
 * Post-processor that records every invocation together with the label of
 * {@code ctx.getPreviousResult()}, which is how a real JSR223/Regex post-processor sees the sample
 * it is supposed to act on. Wrong ordering shows up here as a wrong or null previous result.
 */
public class CountingPostProcessor extends AbstractTestElement implements PostProcessor {

  private static final long serialVersionUID = 1L;

  private final AtomicInteger invocations = new AtomicInteger();
  private final List<String> previousResultLabels =
      Collections.synchronizedList(new ArrayList<>());

  public CountingPostProcessor(String name) {
    setName(name);
  }

  @Override
  public void process() {
    invocations.incrementAndGet();
    SampleResult previous = JMeterContextService.getContext().getPreviousResult();
    previousResultLabels.add(previous == null ? "<null>" : previous.getSampleLabel());
  }

  public int invocations() {
    return invocations.get();
  }

  public List<String> previousResultLabels() {
    synchronized (previousResultLabels) {
      return new ArrayList<>(previousResultLabels);
    }
  }
}
