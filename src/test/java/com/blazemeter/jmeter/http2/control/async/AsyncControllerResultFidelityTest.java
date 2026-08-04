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
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * Fidelity of the synthetic parent sample and of the state the controller leaves behind: the byte
 * and size counters the parent reports, whether the run-time-synthesised parent sampler accumulates
 * in {@code TestCompiler.samplerConfigMap} across iterations, and whether a client failure between
 * the dispatch pass and the completion pass leaves the sampler's SamplePackage stripped of its
 * timers.
 *
 * <p>These come from reading the code rather than from a field report, so a green test here is a
 * useful result too: it rules the mechanism out.
 */
public class AsyncControllerResultFidelityTest extends HTTP2TestBase {

  @Test
  public void parentSampleSizeCountersMustMatchStockJMeter() {
    TreeShape shape = fixture -> {
      fixture.transport().spec("S1").embeddedResources("S1-logo.png", "S1-app.css");
      fixture.transport().spec("S2").embeddedResources("S2-logo.png");
      return new AsyncScenarioRunner.Node[]{
          node(fixture.controller("controller"),
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 40)))
      };
    };
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, 1, shape);
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape);

    SampleResult stockParent = baseline.topLevelResult("controller");
    SampleResult asyncParent = actual.topLevelResult("controller");
    System.out.println("\n=== parent sample size counters ==="
        + "\n  stock: " + describeSizes(stockParent)
        + "\n  async: " + describeSizes(asyncParent));

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(asyncParent).as("a parent sample must be reported").isNotNull();
    if (asyncParent != null && stockParent != null) {
      softly.assertThat(asyncParent.getBytesAsLong())
          .as("received bytes of the parent sample")
          .isEqualTo(stockParent.getBytesAsLong());
      softly.assertThat(asyncParent.getSentBytes())
          .as("sent bytes of the parent sample")
          .isEqualTo(stockParent.getSentBytes());
      softly.assertThat(asyncParent.getBodySizeAsLong())
          .as("body size of the parent sample")
          .isEqualTo(stockParent.getBodySizeAsLong());
      softly.assertThat(asyncParent.getHeadersSize())
          .as("headers size of the parent sample")
          .isEqualTo(stockParent.getHeadersSize());
      SampleResult[] asyncChildren = asyncParent.getSubResults();
      SampleResult[] stockChildren = stockParent.getSubResults();
      if (asyncChildren != null && stockChildren != null
          && asyncChildren.length == stockChildren.length) {
        for (int i = 0; i < asyncChildren.length; i++) {
          softly.assertThat(asyncChildren[i].getBytesAsLong())
              .as("received bytes of child '%s', which already includes its embedded resources",
                  asyncChildren[i].getSampleLabel())
              .isEqualTo(stockChildren[i].getBytesAsLong());
        }
      }
    }
    softly.assertAll();
  }

  @Test
  public void syntheticParentSamplerMustNotAccumulateInTheCompilerMap() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", 20)),
            node(fixture.http("S2", 40)))
    };
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, 4, shape);
    Result actual = execute(ControllerKind.ASYNC_PARENT, 4, shape);
    System.out.println("\n=== TestCompiler.samplerConfigMap after 4 iterations ==="
        + "\n  stock: " + baseline.compiledSamplers()
        + "\n  async: " + actual.compiledSamplers());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.compiledSamplers())
        .as("the compiler map must hold one entry per sampler in the plan, not one extra entry per "
            + "loop iteration; stock JMeter keeps %s", baseline.compiledSamplers())
        .hasSameSizeAs(baseline.compiledSamplers());
    softly.assertAll();
  }

  @Test
  public void clientFailureOnTheCompletionPassMustNotStripTheSamplersTimers() {
    TreeShape shape = fixture -> {
      fixture.transport().spec("S1").clientFactoryFailsFromCall(2);
      return new AsyncScenarioRunner.Node[]{
          node(fixture.controller("controller"),
              node(fixture.timer("timer-under-controller")),
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 40)))
      };
    };
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, 2, shape);
    Result actual = execute(ControllerKind.ASYNC_PARENT, 2, shape);
    System.out.println("\n=== client fails on the completion pass of S1, 2 iterations ==="
        + "\n  stock: timer=" + baseline.timerInvocations("timer-under-controller")
        + " for " + baseline.timerSamplers("timer-under-controller")
        + "\n  async: timer=" + actual.timerInvocations("timer-under-controller")
        + " for " + actual.timerSamplers("timer-under-controller")
        + "\n  async: hung=" + actual.hung() + " tree=" + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.timerInvocations("timer-under-controller"))
        .as("a client failure between dispatch and completion must not lose or duplicate timer "
            + "applications in this or the next iteration")
        .isEqualTo(baseline.timerInvocations("timer-under-controller"));
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

  private static String describeSizes(SampleResult result) {
    if (result == null) {
      return "<none>";
    }
    StringBuilder out = new StringBuilder();
    out.append("bytes=").append(result.getBytesAsLong())
        .append(" sent=").append(result.getSentBytes())
        .append(" body=").append(result.getBodySizeAsLong())
        .append(" headers=").append(result.getHeadersSize());
    SampleResult[] subs = result.getSubResults();
    if (subs != null) {
      for (SampleResult sub : subs) {
        out.append("\n         child ").append(sub.getSampleLabel())
            .append(": bytes=").append(sub.getBytesAsLong())
            .append(" subResults=").append(sub.getSubResults() == null
                ? 0 : sub.getSubResults().length);
      }
    }
    return out.toString();
  }
}
