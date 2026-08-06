package com.blazemeter.jmeter.http2.control.async;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;

/** Pre-processor that records every invocation and the sampler it ran for. */
public class CountingPreProcessor extends AbstractTestElement implements PreProcessor {

  private static final long serialVersionUID = 1L;

  private final AtomicInteger invocations = new AtomicInteger();
  private final List<String> invokedForSampler = Collections.synchronizedList(new ArrayList<>());

  public CountingPreProcessor(String name) {
    setName(name);
  }

  @Override
  public void process() {
    invocations.incrementAndGet();
    org.apache.jmeter.samplers.Sampler current =
        JMeterContextService.getContext().getCurrentSampler();
    invokedForSampler.add(current == null ? "<none>" : current.getName());
  }

  public int invocations() {
    return invocations.get();
  }

  public List<String> invokedForSampler() {
    synchronized (invokedForSampler) {
      return new ArrayList<>(invokedForSampler);
    }
  }
}
