package com.blazemeter.jmeter.http2.control.async;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.testelement.AbstractTestElement;

/**
 * Post-processor that throws on its first invocation, standing in for a JSR223/Regex extractor with
 * a bug in it. Stock JMeter lets that exception propagate to JMeterThread.processSampler, which logs
 * it and moves on; the point of this element is to see what the async completion path does instead.
 */
public class ThrowingPostProcessor extends AbstractTestElement implements PostProcessor {

  private static final long serialVersionUID = 1L;

  private final AtomicInteger invocations = new AtomicInteger();
  private final int throwOnInvocation;

  public ThrowingPostProcessor(String name) {
    this(name, 1);
  }

  public ThrowingPostProcessor(String name, int throwOnInvocation) {
    setName(name);
    this.throwOnInvocation = throwOnInvocation;
  }

  @Override
  public void process() {
    int invocation = invocations.incrementAndGet();
    if (invocation == throwOnInvocation) {
      throw new IllegalStateException(
          "post-processor '" + getName() + "' failed on invocation " + invocation);
    }
  }

  public int invocations() {
    return invocations.get();
  }
}
