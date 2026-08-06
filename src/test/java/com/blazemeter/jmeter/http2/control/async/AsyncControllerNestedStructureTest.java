package com.blazemeter.jmeter.http2.control.async;

import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.node;
import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.threadGroup;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.ControllerKind;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.Result;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.TreeShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * The result tree a user sees in View Results Tree, for async controllers holding realistic content:
 * transaction controllers with their own children, non-HTTP samplers, embedded resources, several
 * nesting levels, more than one loop iteration and a nested async controller.
 *
 * <p>The reference is a live stock JMeter controller running the identical tree: a Transaction
 * Controller with "Generate parent sample" on, which nests children and grandchildren to arbitrary
 * depth, and a Simple Controller for the "Generate parent sample" off mode. The bzm HTTP Async
 * Controller is expected to produce the same tree, only faster, because it dispatches the HTTP
 * requests in parallel.
 *
 * <p>Latencies are staggered so completion order equals dispatch order and the expected tree is
 * deterministic.
 */
public class AsyncControllerNestedStructureTest extends HTTP2TestBase {

  @Test
  public void nestedTransactionControllerMustNestInsideTheAsyncParentSample() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", 20)),
            node(fixture.stockTransaction("tx", true),
                node(fixture.http("S3", 40)),
                node(fixture.http("S4", 60))),
            node(fixture.http("S2", 80)))
    };
    assertSameResultTree("transaction controller nested in the async controller", 1, shape);
  }

  @Test
  public void nonHttpSamplerMustBeReportedInsideTheAsyncParentSample() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", 20)),
            node(new MarkerSampler("marker")),
            node(fixture.http("S2", 40)))
    };
    assertSameResultTree("non-HTTP sampler between two requests", 1, shape);
  }

  @Test
  public void threeNestingLevelsMustBePreserved() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.stockTransaction("tx-outer", true),
                node(fixture.http("S1", 20)),
                node(fixture.stockTransaction("tx-inner", true),
                    node(fixture.http("S2", 40)),
                    node(fixture.http("S3", 60)))))
    };
    assertSameResultTree("transaction controller inside transaction controller", 1, shape);
  }

  @Test
  public void embeddedResourceGrandchildrenMustSurviveTheAsyncParentSample() {
    TreeShape shape = fixture -> {
      fixture.transport().spec("S1").embeddedResources("S1-logo.png", "S1-app.css");
      return new AsyncScenarioRunner.Node[]{
          node(fixture.controller("controller"),
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 40)))
      };
    };
    assertSameResultTree("request with embedded resources", 1, shape);
  }

  @Test
  public void twoLoopIterationsMustProduceIndependentParentSamples() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", 20)),
            node(fixture.http("S2", 40)))
    };
    Result actual = assertSameResultTree("two loop iterations", 2, shape);

    SoftAssertions softly = new SoftAssertions();
    List<SampleResult> parents = new ArrayList<>();
    for (SampleResult result : actual.topLevelResults()) {
      if ("controller".equals(result.getSampleLabel())) {
        parents.add(result);
      }
    }
    softly.assertThat(parents)
        .as("one parent sample per loop iteration")
        .hasSize(2);
    for (SampleResult parent : parents) {
      softly.assertThat(SampleResultTrees.childLabels(parent))
          .as("each iteration's parent sample carries only its own two children")
          .containsExactly("S1", "S2");
    }
    softly.assertAll();
  }

  @Test
  public void asyncControllerNestedInAnAsyncControllerMustNestItsParentSample() {
    ScenarioFixture.Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      ThreadGroup group = threadGroup("tg", 1);
      actual = fixture.run(group,
          node(fixture.controller("outer"),
              node(fixture.http("S1", 20)),
              node(fixture.asyncController("inner", true),
                  node(fixture.http("S2", 40)),
                  node(fixture.http("S3", 60)))),
          fixture.recorderNode());
    }
    System.out.println("\n=== async controller nested in async controller ===\n  tree: "
        + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.resultTree())
        .as("nesting an async controller must nest its parent sample, exactly as nesting a "
            + "transaction controller inside a transaction controller does")
        .containsExactly(
            "outer",
            "outer > S1",
            "outer > inner",
            "outer > inner > S2",
            "outer > inner > S3");
    softly.assertAll();
  }

  /**
   * Pins the controller's concurrency contract: its own children overlap, and requests owned by a
   * nested controller do not.
   *
   * <p>The limitation is structural, not an oversight. Overlapping a request needs two turns of
   * {@code JMeterThread} — one to send, a later one to collect — and a nested controller delivers
   * each of its samplers exactly once, closing its own {@code TransactionSampler} as soon as it runs
   * out; the correct nesting of those results depends on that delivery. Sending them ahead of their
   * turn would fire the request before the pre-processors meant to shape it. The controller logs a
   * warning when it sees such a request so the loss of parallelism is visible.
   *
   * <p>Note the request order in the tree: anything that is not a direct HTTP request — a nested
   * controller, a non-HTTP sampler — is a barrier, because the controller has to finish collecting
   * what it already sent before that element runs, or the results would come out of tree order. So
   * only requests that sit next to each other overlap.
   */
  @Test
  public void directChildrenOverlapAndNestedControllerRequestsDoNot() {
    Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      ThreadGroup group = threadGroup("tg", 1);
      actual = fixture.run(group,
          node(fixture.controller("controller"),
              node(fixture.http("S1", 150)),
              node(fixture.http("S2", 150)),
              node(fixture.stockTransaction("tx", true),
                  node(fixture.http("S3", 30)),
                  node(fixture.http("S4", 30)))),
          fixture.recorderNode());
    }
    System.out.println("\n=== concurrency contract with a nested controller ===\n  maxConcurrent="
        + actual.maxConcurrentInFlight() + " elapsed=" + actual.elapsedMillis()
        + "ms sync=" + actual.syncSampled() + " async=" + actual.dispatchOrder());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.dispatchOrder())
        .as("the controller's own children are dispatched asynchronously")
        .containsExactly("S1", "S2");
    softly.assertThat(actual.syncSampled())
        .as("requests owned by a nested controller run sequentially, by design")
        .containsExactly("S3", "S4");
    softly.assertThat(actual.maxConcurrentInFlight())
        .as("S1 and S2 must still be in flight at the same time")
        .isGreaterThanOrEqualTo(2);
    softly.assertAll();
  }

  @Test
  public void limitMaxParallelMustNotSkipOrDuplicateElements() {
    Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      HTTP2Controller controller = fixture.asyncController("controller", true);
      controller.setLimitMaxParallel(true);
      controller.setMaxConcurrentAsyncInController(1);
      ThreadGroup group = threadGroup("tg", 1);
      actual = fixture.run(group,
          node(controller,
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 30)),
              node(new MarkerSampler("marker")),
              node(fixture.http("S3", 40)),
              node(fixture.http("S4", 50))),
          fixture.recorderNode());
    }
    System.out.println("\n=== limitMaxParallel=1 ===\n  dispatch=" + actual.dispatchOrder()
        + "\n  tree=" + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.dispatchOrder())
        .as("each request is dispatched exactly once, in tree order")
        .containsExactly("S1", "S2", "S3", "S4");
    softly.assertThat(actual.resultTree())
        .as("throttling concurrency must not change what is reported")
        .containsExactly(
            "controller",
            "controller > S1",
            "controller > S2",
            "controller > marker",
            "controller > S3",
            "controller > S4");
    softly.assertAll();
  }

  @Test
  public void flatModeMustMatchASimpleControllerForAComplexSubtree() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", 20)),
            node(fixture.stockTransaction("tx", true),
                node(fixture.http("S3", 40))),
            node(new MarkerSampler("marker")),
            node(fixture.http("S2", 60)))
    };
    Result baseline = execute(ControllerKind.STOCK_SIMPLE, 1, shape);
    Result actual = execute(ControllerKind.ASYNC_FLAT, 1, shape);
    report("flat mode, complex subtree", ControllerKind.STOCK_SIMPLE, baseline,
        ControllerKind.ASYNC_FLAT, actual);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.resultTree())
        .as("with Generate parent sample off the async controller must report exactly what a "
            + "Simple Controller reports")
        .isEqualTo(baseline.resultTree());
    softly.assertAll();
  }

  private Result assertSameResultTree(String label, int loops, TreeShape shape) {
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, loops, shape);
    Result actual = execute(ControllerKind.ASYNC_PARENT, loops, shape);
    report(label, ControllerKind.STOCK_TX_PARENT, baseline, ControllerKind.ASYNC_PARENT, actual);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("%s: the JMeter thread must finish", label).isFalse();
    softly.assertThat(actual.topLevelLabels())
        .as("%s: the listener must receive exactly the controller's parent samples, since every "
            + "child is meant to be nested inside them", label)
        .isEqualTo(baseline.topLevelLabels());
    softly.assertThat(actual.resultTree())
        .as("%s: the whole parent/child/grandchild tree must match stock JMeter", label)
        .isEqualTo(baseline.resultTree());
    softly.assertAll();
    return actual;
  }

  private Result execute(ControllerKind kind, int loops, TreeShape shape) {
    try (ScenarioFixture fixture = new ScenarioFixture(kind)) {
      ThreadGroup group = threadGroup("tg", loops);
      List<AsyncScenarioRunner.Node> nodes =
          new ArrayList<>(Arrays.asList(shape.build(fixture)));
      nodes.add(fixture.recorderNode());
      return fixture.run(group, nodes.toArray(new AsyncScenarioRunner.Node[0]));
    }
  }

  private void report(String label, ControllerKind baselineKind, Result baseline,
                      ControllerKind asyncKind, Result actual) {
    StringBuilder out = new StringBuilder();
    out.append("\n=== ").append(label).append(" ===\n");
    out.append("  ").append(baselineKind).append(" tree:\n");
    for (String line : baseline.resultTree()) {
      out.append("      ").append(line).append('\n');
    }
    out.append("  ").append(asyncKind).append(" tree:\n");
    for (String line : actual.resultTree()) {
      out.append("      ").append(line).append('\n');
    }
    out.append("  async: hung=").append(actual.hung())
        .append(" maxConcurrent=").append(actual.maxConcurrentInFlight())
        .append(" syncSampled=").append(actual.syncSampled())
        .append('\n');
    System.out.println(out);
  }
}
