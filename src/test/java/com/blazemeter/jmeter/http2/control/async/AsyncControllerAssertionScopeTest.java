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
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * Assertion scoping ("Apply to" in the Response Assertion GUI) for an assertion attached to the
 * controller, and what happens when a post-processor throws during the async completion pass.
 *
 * <p>Stock JMeter puts a controller-level assertion in two places: every descendant sampler's
 * SamplePackage and the transaction controller's own package, and evaluates the latter against the
 * transaction result. The async controller gives its synthesised parent sampler an empty assertion
 * list, so only the first of those two happens.
 */
public class AsyncControllerAssertionScopeTest extends HTTP2TestBase {

  private static final String GOOD = "<html><body>expected marker</body></html>";
  private static final String BAD = "<html><body>something else</body></html>";

  private TreeShape twoRequestsOneFailing(boolean scopeChildren) {
    return fixture -> {
      fixture.transport().spec("S1").body(GOOD);
      fixture.transport().spec("S2").body(BAD);
      CountingAssertion assertion = fixture.countingAssertion("assert-marker", "expected marker");
      if (scopeChildren) {
        assertion.setScopeChildren();
      }
      return new AsyncScenarioRunner.Node[]{
          node(fixture.controller("controller"),
              node(assertion),
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 40)))
      };
    };
  }

  @Test
  public void controllerLevelAssertionWithMainSampleScopeMustAlsoBeEvaluatedOnTheParentSample() {
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, twoRequestsOneFailing(false));
    Result actual = execute(ControllerKind.ASYNC_PARENT, twoRequestsOneFailing(false));
    report("controller-level assertion, scope = Main sample only", baseline, actual);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.assertionEvaluatedFor("assert-marker"))
        .as("stock JMeter evaluates a controller-level assertion once per child AND once against "
            + "the parent row (N+1 times). Note the tension: the async parent row has an empty "
            + "body, so full parity here means inheriting JMeter's spurious 'does not contain' "
            + "failure on the parent. Decide deliberately between parity and skipping the parent; "
            + "what is not defensible is the current silent skip with no way to opt in")
        .isEqualTo(baseline.assertionEvaluatedFor("assert-marker"));
    softly.assertAll();
  }

  @Test
  public void controllerLevelAssertionWithSubSampleScopeMustFailTheParentSample() {
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, twoRequestsOneFailing(true));
    Result actual = execute(ControllerKind.ASYNC_PARENT, twoRequestsOneFailing(true));
    report("controller-level assertion, scope = Sub-samples only", baseline, actual);

    SampleResult stockParent = baseline.topLevelResult("controller");
    SampleResult asyncParent = actual.topLevelResult("controller");

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(asyncParent).as("a parent sample must be reported").isNotNull();
    if (asyncParent != null && stockParent != null) {
      softly.assertThat(asyncParent.isSuccessful())
          .as("a sub-sample scoped assertion that fails on a child must fail the parent row, as it "
              + "does with a stock Transaction Controller")
          .isEqualTo(stockParent.isSuccessful());
      softly.assertThat(firstFailureMessages(asyncParent))
          .as("the parent row must carry the same assertion outcome as stock JMeter")
          .isEqualTo(firstFailureMessages(stockParent));
    }
    softly.assertAll();
  }

  @Test
  public void aThrowingPostProcessorMustNotRunTheCompletionPipelineTwice() {
    TreeShape shape = fixture -> {
      fixture.transport().spec("S1").body(GOOD);
      return new AsyncScenarioRunner.Node[]{
          node(fixture.controller("controller"),
              node(fixture.http("S1", 20),
                  node(fixture.countingAssertion("assert-marker", "expected marker")),
                  node(fixture.throwingPost("boom"))))
      };
    };
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, shape);
    Result actual = execute(ControllerKind.ASYNC_PARENT, shape);
    System.out.println("\n=== post-processor throws during completion ==="
        + "\n  stock: post=" + baseline.throwingPostInvocations("boom")
        + " assertion=" + baseline.assertionEvaluatedFor("assert-marker")
        + " tree=" + baseline.resultTree()
        + "\n  async: post=" + actual.throwingPostInvocations("boom")
        + " assertion=" + actual.assertionEvaluatedFor("assert-marker")
        + " tree=" + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.throwingPostInvocations("boom"))
        .as("a post-processor that throws must be invoked once, like in stock JMeter, not have the "
            + "whole completion pipeline replayed around it")
        .isEqualTo(baseline.throwingPostInvocations("boom"));
    softly.assertThat(actual.assertionInvocations("assert-marker"))
        .as("assertions must not be evaluated twice because of the failed post-processor; stock "
            + "JMeter never reaches them at all (%d) because the post-processor throws first",
            baseline.assertionInvocations("assert-marker"))
        .isLessThanOrEqualTo(1);
    softly.assertAll();
  }

  private static List<String> firstFailureMessages(SampleResult result) {
    List<String> messages = new ArrayList<>();
    for (AssertionResult assertion : result.getAssertionResults()) {
      messages.add((assertion.isFailure() || assertion.isError())
          ? String.valueOf(assertion.getFailureMessage())
          : "<passed>");
    }
    return messages;
  }

  private Result execute(ControllerKind kind, TreeShape shape) {
    try (ScenarioFixture fixture = new ScenarioFixture(kind)) {
      ThreadGroup group = threadGroup("tg", 1);
      List<AsyncScenarioRunner.Node> nodes =
          new ArrayList<>(Arrays.asList(shape.build(fixture)));
      nodes.add(fixture.recorderNode());
      return fixture.run(group, nodes.toArray(new AsyncScenarioRunner.Node[0]));
    }
  }

  private void report(String label, Result baseline, Result actual) {
    System.out.println("\n=== " + label + " ==="
        + "\n  stock: evaluatedFor=" + baseline.assertionEvaluatedFor("assert-marker")
        + "\n         parent=" + describe(baseline.topLevelResult("controller"))
        + "\n  async: evaluatedFor=" + actual.assertionEvaluatedFor("assert-marker")
        + "\n         parent=" + describe(actual.topLevelResult("controller")));
  }

  private static String describe(SampleResult result) {
    if (result == null) {
      return "<none>";
    }
    return "success=" + result.isSuccessful()
        + " assertions=" + firstFailureMessages(result)
        + " children=" + SampleResultTrees.childLabels(result);
  }
}
