package com.blazemeter.jmeter.http2.control.async;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.timers.Timer;

/**
 * Timer that counts how many times JMeterThread asked it for a delay, and returns 0 so the suite
 * stays fast. Issue #108 is exactly a question of how many times {@link #delay()} runs, so this is
 * the measuring instrument for it.
 *
 * <p>JMeterThread.delay(List) calls TestBeanHelper.prepare then timer.delay() for every timer in
 * the SamplePackage; a total delay of 0 skips the sleep entirely.
 */
public class CountingTimer extends AbstractTestElement implements Timer {

  private static final long serialVersionUID = 1L;

  private final AtomicInteger invocations = new AtomicInteger();
  private final List<String> invokedForSampler = Collections.synchronizedList(new ArrayList<>());
  private long delayMillis;

  public CountingTimer(String name) {
    setName(name);
  }

  public CountingTimer(String name, long delayMillis) {
    setName(name);
    this.delayMillis = delayMillis;
  }

  public void setDelay(long delayMillis) {
    this.delayMillis = delayMillis;
  }

  @Override
  public long delay() {
    invocations.incrementAndGet();
    org.apache.jmeter.samplers.Sampler current =
        JMeterContextService.getContext().getCurrentSampler();
    invokedForSampler.add(current == null ? "<none>" : current.getName());
    return delayMillis;
  }

  public int invocations() {
    return invocations.get();
  }

  /** Names of the samplers this timer was applied to, in order. */
  public List<String> invokedForSampler() {
    synchronized (invokedForSampler) {
      return new ArrayList<>(invokedForSampler);
    }
  }

  public void reset() {
    invocations.set(0);
    invokedForSampler.clear();
  }
}
