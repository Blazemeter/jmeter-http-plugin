package com.blazemeter.jmeter.http2.control.async;

import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.node;
import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.threadGroup;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.ControllerKind;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.Result;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.TreeShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * How elements attached to the async controller itself are scoped: an assertion and a post-processor
 * placed under the controller, and a Loop Controller nested inside it. TestCompiler pushes such
 * elements down into every descendant sampler's SamplePackage, so they must reach every request the
 * controller runs, including the ones owned by nested controllers.
 */
public class AsyncControllerElementScopeTest extends HTTP2TestBase {

  private static final String BODY = "<html><body>expected marker</body></html>";

  @Test
  public void assertionUnderTheControllerMustBeAppliedToEveryChild() {
    TreeShape shape = fixture -> {
      fixture.transport().spec("S1").body(BODY);
      fixture.transport().spec("S2").body(BODY);
      return new AsyncScenarioRunner.Node[]{
          node(fixture.controller("controller"),
              node(fixture.containsAssertion("assert-marker", "expected marker")),
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 40)))
      };
    };
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, 1, shape);
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape);
    reportAssertions("assertion attached to the controller", baseline, actual);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    SampleResult parent = actual.topLevelResult("controller");
    SampleResult stockParent = baseline.topLevelResult("controller");
    softly.assertThat(parent).as("a parent sample must be reported").isNotNull();
    if (parent != null) {
      SampleResult[] children = parent.getSubResults();
      softly.assertThat(children)
          .as("both requests must be under the parent sample")
          .hasSize(2);
      if (children != null) {
        for (SampleResult child : children) {
          softly.assertThat(child.getAssertionResults())
              .as("child '%s' must carry the result of the assertion attached to the controller",
                  child.getSampleLabel())
              .isNotEmpty();
          softly.assertThat(child.isSuccessful())
              .as("child '%s' matches the assertion, so it must stay successful",
                  child.getSampleLabel())
              .isTrue();
        }
      }
      // Parity, deliberately, including JMeter's own quirk: a controller level assertion is also
      // evaluated against the parent row, whose body is empty, so a "contains" assertion marks it
      // failed with "Response was null". A stock Transaction Controller behaves identically; the
      // async controller must not silently differ from it.
      softly.assertThat(parent.isSuccessful())
          .as("the parent row must reach the same verdict as a stock Transaction Controller")
          .isEqualTo(stockParent != null && stockParent.isSuccessful());
    }
    softly.assertAll();
  }

  @Test
  public void postProcessorUnderTheControllerMustSeeEachSampleAsPreviousResult() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.post("post-under-controller")),
            node(fixture.http("S1", 20)),
            node(fixture.http("S2", 40)))
    };
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, 1, shape);
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape);
    System.out.println("\n=== post-processor under the controller ===\n  stock: runs="
        + baseline.postProcessorInvocations("post-under-controller")
        + " previousResult=" + baseline.postProcessorPreviousResults("post-under-controller")
        + "\n  async: runs=" + actual.postProcessorInvocations("post-under-controller")
        + " previousResult=" + actual.postProcessorPreviousResults("post-under-controller"));

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.postProcessorInvocations("post-under-controller"))
        .as("the post-processor must run once per request, as in stock JMeter")
        .isEqualTo(baseline.postProcessorInvocations("post-under-controller"));
    softly.assertThat(actual.postProcessorPreviousResults("post-under-controller"))
        .as("each run must see its own sample as ctx.getPreviousResult()")
        .isEqualTo(baseline.postProcessorPreviousResults("post-under-controller"));
    softly.assertAll();
  }

  @Test
  public void loopControllerNestedInsideMustKeepItsRequestsInTheParentSample() {
    TreeShape shape = fixture -> {
      LoopController loop = new LoopController();
      loop.setName("inner-loop");
      loop.setLoops(2);
      return new AsyncScenarioRunner.Node[]{
          node(fixture.controller("controller"),
              node(fixture.http("S1", 20)),
              node(loop,
                  node(fixture.http("S2", 40)),
                  node(fixture.http("S3", 60))))
      };
    };
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, 1, shape);
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape);
    System.out.println("\n=== loop controller nested inside the async controller ==="
        + "\n  stock tree: " + baseline.resultTree()
        + "\n  async tree: " + actual.resultTree()
        + "\n  async syncSampled=" + actual.syncSampled()
        + " maxConcurrent=" + actual.maxConcurrentInFlight());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.resultTree())
        .as("a Loop Controller inside the async controller must keep its requests nested in the "
            + "parent sample, exactly as it does inside a Transaction Controller")
        .isEqualTo(baseline.resultTree());
    softly.assertAll();
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

  private void reportAssertions(String label, Result baseline, Result actual) {
    System.out.println("\n=== " + label + " ==="
        + "\n  stock tree: " + baseline.resultTree()
        + "\n  stock parent: " + describeAssertions(baseline.topLevelResult("controller"))
        + "\n  async tree: " + actual.resultTree()
        + "\n  async parent: " + describeAssertions(actual.topLevelResult("controller")));
  }

  private static String describeAssertions(SampleResult result) {
    if (result == null) {
      return "<none>";
    }
    StringBuilder out = new StringBuilder();
    out.append("success=").append(result.isSuccessful())
        .append(" assertions=").append(result.getAssertionResults().length)
        .append(" firstFailure=").append(result.getFirstAssertionFailureMessage());
    SampleResult[] subs = result.getSubResults();
    if (subs != null) {
      for (SampleResult sub : subs) {
        out.append("\n      child ").append(sub.getSampleLabel())
            .append(": success=").append(sub.isSuccessful())
            .append(" assertions=").append(sub.getAssertionResults().length);
      }
    }
    return out.toString();
  }
}
