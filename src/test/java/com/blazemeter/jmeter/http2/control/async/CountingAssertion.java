package com.blazemeter.jmeter.http2.control.async;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractScopedAssertion;

/**
 * Response-contains assertion that also records how many times it was evaluated and against which
 * sample. Scope is settable through {@link AbstractScopedAssertion} exactly like the "Apply to"
 * radio group of the Response Assertion GUI, which is what decides whether JMeter runs it against
 * the main sample, the sub-samples, or both.
 */
public class CountingAssertion extends AbstractScopedAssertion implements Assertion {

  private static final long serialVersionUID = 1L;

  private final AtomicInteger invocations = new AtomicInteger();
  private final List<String> evaluatedFor = Collections.synchronizedList(new ArrayList<>());
  private final String expected;

  public CountingAssertion(String name, String expected) {
    setName(name);
    this.expected = expected;
  }

  @Override
  public AssertionResult getResult(SampleResult response) {
    invocations.incrementAndGet();
    String label = response.getSampleLabel();
    evaluatedFor.add(label == null ? "<null>" : label);
    AssertionResult result = new AssertionResult(getName());
    byte[] data = response.getResponseData();
    String body = data == null ? "" : new String(data, StandardCharsets.UTF_8);
    if (body.contains(expected)) {
      result.setFailure(false);
      return result;
    }
    result.setFailure(true);
    result.setFailureMessage("body of '" + label + "' does not contain '" + expected + "'");
    return result;
  }

  public int invocations() {
    return invocations.get();
  }

  /** Labels of the samples this assertion was evaluated against, in order. */
  public List<String> evaluatedFor() {
    synchronized (evaluatedFor) {
      return new ArrayList<>(evaluatedFor);
    }
  }
}
