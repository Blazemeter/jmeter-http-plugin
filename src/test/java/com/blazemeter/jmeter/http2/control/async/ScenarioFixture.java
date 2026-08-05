package com.blazemeter.jmeter.http2.control.async;

import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.threads.ThreadGroup;

/**
 * Builds one scenario worth of instrumented elements, so the very same tree shape can be executed
 * with the stock JMeter Transaction Controller and with the bzm HTTP Async Controller and the two
 * outcomes compared. Comparing against a live stock controller, rather than against numbers written
 * from memory, is what makes the parity claims in these tests trustworthy.
 */
public final class ScenarioFixture implements Closeable {

  private final StubAsyncTransport transport = new StubAsyncTransport();
  private final RecordingSampleListener recorder = new RecordingSampleListener();
  private final RecordingSampleMonitor monitor = new RecordingSampleMonitor();
  private final Map<String, CountingTimer> timers = new LinkedHashMap<>();
  private final Map<String, CountingPreProcessor> preProcessors = new LinkedHashMap<>();
  private final Map<String, CountingPostProcessor> postProcessors = new LinkedHashMap<>();
  private final Map<String, CountingAssertion> countingAssertions = new LinkedHashMap<>();
  private final Map<String, ThrowingPostProcessor> throwingPostProcessors = new LinkedHashMap<>();
  private final ControllerKind kind;

  public ScenarioFixture(ControllerKind kind) {
    this.kind = kind;
    recorder.setName("recorder");
    monitor.setName("monitor");
  }

  /** Which controller implementation the scenario is executed with. */
  public enum ControllerKind {
    /**
     * Stock JMeter Simple Controller: the parity baseline for the async controller with "Generate
     * parent sample" off, since in that mode the async controller is also expected to report its
     * children individually and add no sample of its own.
     */
    STOCK_SIMPLE(false),
    /** Stock JMeter Transaction Controller, "Generate parent sample" off. */
    STOCK_TX_FLAT(false),
    /** Stock JMeter Transaction Controller, "Generate parent sample" on: the parity baseline. */
    STOCK_TX_PARENT(true),
    /** bzm HTTP Async Controller, "Generate parent sample" off. */
    ASYNC_FLAT(false),
    /** bzm HTTP Async Controller, "Generate parent sample" on. */
    ASYNC_PARENT(true);

    private final boolean parent;

    ControllerKind(boolean parent) {
      this.parent = parent;
    }

    public boolean generatesParentSample() {
      return parent;
    }

    public boolean isAsync() {
      return this == ASYNC_FLAT || this == ASYNC_PARENT;
    }
  }

  /** A scenario tree, built once per controller kind so each run gets fresh elements. */
  public interface TreeShape {
    AsyncScenarioRunner.Node[] build(ScenarioFixture fixture);
  }

  /** Everything worth asserting on, snapshotted so the fixture can be closed straight away. */
  public static final class Result {

    private final Map<String, Integer> timerInvocations;
    private final Map<String, List<String>> timerSamplers;
    private final Map<String, Integer> preProcessorInvocations;
    private final Map<String, Integer> postProcessorInvocations;
    private final Map<String, List<String>> postProcessorPreviousResults;
    private final Map<String, Integer> assertionInvocations;
    private final Map<String, List<String>> assertionEvaluatedFor;
    private final Map<String, Integer> throwingPostInvocations;
    private final List<String> resultTree;
    private final List<String> topLevelLabels;
    private final List<org.apache.jmeter.samplers.SampleResult> topLevelResults;
    private final List<String> dispatchOrder;
    private final List<String> completionOrder;
    private final List<String> syncSampled;
    private final int maxConcurrentInFlight;
    private final boolean hung;
    private final long elapsedMillis;
    private final List<String> engineSignals;
    private final List<String> compiledSamplers;
    private final Throwable uncaught;
    private final org.apache.jorphan.collections.HashTree tree;
    private final List<org.apache.jmeter.samplers.Sampler> executedSamplers;

    private Result(ScenarioFixture fixture, AsyncScenarioRunner.Outcome outcome) {
      this.timerInvocations = new LinkedHashMap<>();
      this.timerSamplers = new LinkedHashMap<>();
      for (Map.Entry<String, CountingTimer> entry : fixture.timers.entrySet()) {
        timerInvocations.put(entry.getKey(), entry.getValue().invocations());
        timerSamplers.put(entry.getKey(), entry.getValue().invokedForSampler());
      }
      this.preProcessorInvocations = new LinkedHashMap<>();
      for (Map.Entry<String, CountingPreProcessor> entry : fixture.preProcessors.entrySet()) {
        preProcessorInvocations.put(entry.getKey(), entry.getValue().invocations());
      }
      this.postProcessorInvocations = new LinkedHashMap<>();
      this.postProcessorPreviousResults = new LinkedHashMap<>();
      for (Map.Entry<String, CountingPostProcessor> entry : fixture.postProcessors.entrySet()) {
        postProcessorInvocations.put(entry.getKey(), entry.getValue().invocations());
        postProcessorPreviousResults.put(entry.getKey(), entry.getValue().previousResultLabels());
      }
      this.assertionInvocations = new LinkedHashMap<>();
      this.assertionEvaluatedFor = new LinkedHashMap<>();
      for (Map.Entry<String, CountingAssertion> entry : fixture.countingAssertions.entrySet()) {
        assertionInvocations.put(entry.getKey(), entry.getValue().invocations());
        assertionEvaluatedFor.put(entry.getKey(), entry.getValue().evaluatedFor());
      }
      this.throwingPostInvocations = new LinkedHashMap<>();
      for (Map.Entry<String, ThrowingPostProcessor> entry
          : fixture.throwingPostProcessors.entrySet()) {
        throwingPostInvocations.put(entry.getKey(), entry.getValue().invocations());
      }
      this.resultTree = fixture.recorder.resultTree();
      this.topLevelLabels = fixture.recorder.topLevelLabels();
      this.topLevelResults = fixture.recorder.topLevelResults();
      this.dispatchOrder = fixture.transport.dispatchOrder();
      this.completionOrder = fixture.transport.completionOrder();
      this.syncSampled = fixture.transport.syncSampled();
      this.maxConcurrentInFlight = fixture.transport.maxConcurrentInFlight();
      this.hung = outcome.hung();
      this.elapsedMillis = outcome.elapsedMillis();
      this.engineSignals = outcome.engineSignals();
      this.compiledSamplers = outcome.compiledSamplers();
      this.uncaught = outcome.uncaught();
      this.tree = outcome.tree();
      this.executedSamplers = fixture.monitor.distinctStarted();
    }

    /** Simple class names of the keys left in {@code TestCompiler.samplerConfigMap}. */
    public List<String> compiledSamplers() {
      return compiledSamplers;
    }

    /** Anything that escaped {@code JMeterThread.run()}, e.g. a {@code StackOverflowError}. */
    public Throwable uncaught() {
      return uncaught;
    }

    public org.apache.jorphan.collections.HashTree tree() {
      return tree;
    }

    /** Distinct sampler instances JMeterThread executed; needs {@code monitorNode()} in the tree. */
    public List<org.apache.jmeter.samplers.Sampler> executedSamplers() {
      return executedSamplers;
    }

    public int timerInvocations(String name) {
      Integer value = timerInvocations.get(name);
      return value == null ? 0 : value;
    }

    public Map<String, Integer> timerInvocations() {
      return timerInvocations;
    }

    public List<String> timerSamplers(String name) {
      List<String> value = timerSamplers.get(name);
      return value == null ? new ArrayList<>() : value;
    }

    public int preProcessorInvocations(String name) {
      Integer value = preProcessorInvocations.get(name);
      return value == null ? 0 : value;
    }

    public int postProcessorInvocations(String name) {
      Integer value = postProcessorInvocations.get(name);
      return value == null ? 0 : value;
    }

    public List<String> postProcessorPreviousResults(String name) {
      List<String> value = postProcessorPreviousResults.get(name);
      return value == null ? new ArrayList<>() : value;
    }

    public int assertionInvocations(String name) {
      Integer value = assertionInvocations.get(name);
      return value == null ? 0 : value;
    }

    /** Labels of the samples a counting assertion was evaluated against, in order. */
    public List<String> assertionEvaluatedFor(String name) {
      List<String> value = assertionEvaluatedFor.get(name);
      return value == null ? new ArrayList<>() : value;
    }

    public int throwingPostInvocations(String name) {
      Integer value = throwingPostInvocations.get(name);
      return value == null ? 0 : value;
    }

    /** All notified results as {@code parent > child > grandchild} paths. */
    public List<String> resultTree() {
      return resultTree;
    }

    public List<String> topLevelLabels() {
      return topLevelLabels;
    }

    public List<org.apache.jmeter.samplers.SampleResult> topLevelResults() {
      return topLevelResults;
    }

    public org.apache.jmeter.samplers.SampleResult topLevelResult(String label) {
      for (org.apache.jmeter.samplers.SampleResult result : topLevelResults) {
        if (label.equals(result.getSampleLabel())) {
          return result;
        }
      }
      return null;
    }

    public List<String> dispatchOrder() {
      return dispatchOrder;
    }

    public List<String> completionOrder() {
      return completionOrder;
    }

    public List<String> syncSampled() {
      return syncSampled;
    }

    public int maxConcurrentInFlight() {
      return maxConcurrentInFlight;
    }

    public boolean hung() {
      return hung;
    }

    public long elapsedMillis() {
      return elapsedMillis;
    }

    public List<String> engineSignals() {
      return engineSignals;
    }
  }

  public StubAsyncTransport transport() {
    return transport;
  }

  public RecordingSampleListener recorder() {
    return recorder;
  }

  public AsyncScenarioRunner.Node recorderNode() {
    return AsyncScenarioRunner.node(recorder);
  }

  public RecordingSampleMonitor monitor() {
    return monitor;
  }

  /**
   * Attach next to the recorder to capture the sampler instances JMeterThread really executed,
   * including any the controller synthesises at run time.
   */
  public AsyncScenarioRunner.Node monitorNode() {
    return AsyncScenarioRunner.node(monitor);
  }

  public CountingAssertion countingAssertion(String name, String expected) {
    return countingAssertions.computeIfAbsent(name, key -> new CountingAssertion(key, expected));
  }

  public ThrowingPostProcessor throwingPost(String name) {
    return throwingPostProcessors.computeIfAbsent(name, ThrowingPostProcessor::new);
  }

  /** An HTTP2Sampler wired to the stub transport, answering after {@code latencyMillis}. */
  public HTTP2Sampler http(String name, long latencyMillis) {
    transport.spec(name).latency(latencyMillis);
    return transport.sampler(name);
  }

  public CountingTimer timer(String name) {
    return timers.computeIfAbsent(name, CountingTimer::new);
  }

  public CountingPreProcessor pre(String name) {
    return preProcessors.computeIfAbsent(name, CountingPreProcessor::new);
  }

  public CountingPostProcessor post(String name) {
    return postProcessors.computeIfAbsent(name, CountingPostProcessor::new);
  }

  /** The controller under test: async controller, or the stock controller it is compared against. */
  public GenericController controller(String name) {
    if (kind.isAsync()) {
      HTTP2Controller controller = new HTTP2Controller();
      controller.setName(name);
      controller.setGenerateControllerSample(kind.generatesParentSample());
      return controller;
    }
    if (kind == ControllerKind.STOCK_SIMPLE) {
      GenericController controller = new GenericController();
      controller.setName(name);
      return controller;
    }
    return stockTransaction(name, kind.generatesParentSample());
  }

  /** A stock transaction controller, for nesting inside the controller under test. */
  public TransactionController stockTransaction(String name, boolean generateParentSample) {
    TransactionController controller = new TransactionController();
    controller.setName(name);
    controller.setGenerateParentSample(generateParentSample);
    return controller;
  }

  /** An additional async controller, for the nested-async-controller scenario. */
  public HTTP2Controller asyncController(String name, boolean generateParentSample) {
    HTTP2Controller controller = new HTTP2Controller();
    controller.setName(name);
    controller.setGenerateControllerSample(generateParentSample);
    return controller;
  }

  public ResponseAssertion containsAssertion(String name, String expected) {
    ResponseAssertion assertion = new ResponseAssertion();
    assertion.setName(name);
    assertion.setTestFieldResponseData();
    assertion.setToContainsType();
    assertion.addTestString(expected);
    return assertion;
  }

  public Result run(ThreadGroup group, AsyncScenarioRunner.Node... children) {
    return run(group, AsyncScenarioRunner.DEFAULT_TIMEOUT_MILLIS, children);
  }

  public Result run(ThreadGroup group, long timeoutMillis,
                    AsyncScenarioRunner.Node... children) {
    return new Result(this, AsyncScenarioRunner.run(group, timeoutMillis, children));
  }

  @Override
  public void close() {
    transport.close();
  }
}
