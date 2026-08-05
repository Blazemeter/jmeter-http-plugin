package com.blazemeter.jmeter.http2.control.async;

import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.node;
import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.threadGroup;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.ControllerKind;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.Result;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.TreeShape;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * Issue #110 — "JMeter halts when 'Async Controller - Generate Parent Sample True' and Assertion
 * Fails" — plus the wider question the halt exposes: with "Generate parent sample" on, the async
 * sampler returns {@code null} to JMeterThread and runs its own assertions, so everything
 * JMeterThread derives from a sample result is bypassed.
 *
 * <p>Two families of scenarios:
 *
 * <ul>
 *   <li>A failing assertion combined with each Thread Group "Action to be taken after a Sampler
 *       error", compared against the identical tree driven by a stock Transaction Controller.
 *   <li>An HTTP request that never produces a response, which is what a dropped connection or a
 *       rejected dispatch looks like. The controller waits for it in an unbounded busy-wait, so the
 *       expectation is simply that the JMeter thread still finishes.
 * </ul>
 *
 * <p>The runner joins the JMeter thread with a timeout and reports {@code hung}, so a reproduction
 * fails fast instead of blocking the build.
 */
public class AsyncControllerAssertionFailureTest extends HTTP2TestBase {

  private static final String BODY_OK = "<html><body>expected marker</body></html>";
  private static final String BODY_WRONG = "<html><body>something else</body></html>";
  private static final long HANG_TIMEOUT_MILLIS = 4_000L;

  /** Two requests, the second one's body failing a response assertion scoped to the sampler. */
  private TreeShape failingAssertionShape() {
    return fixture -> {
      fixture.transport().spec("S1").body(BODY_OK);
      fixture.transport().spec("S2").body(BODY_WRONG);
      fixture.transport().spec("S3").body(BODY_OK);
      return new AsyncScenarioRunner.Node[]{
          node(fixture.controller("controller"),
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 40),
                  node(fixture.containsAssertion("assert-marker", "expected marker"))),
              node(fixture.http("S3", 60)))
      };
    };
  }

  @Test
  public void failingChildAssertionMustMarkTheParentSampleAsFailed() {
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT,
        AbstractThreadGroup.ON_SAMPLE_ERROR_CONTINUE, 1, failingAssertionShape());
    Result actual = execute(ControllerKind.ASYNC_PARENT,
        AbstractThreadGroup.ON_SAMPLE_ERROR_CONTINUE, 1, failingAssertionShape());
    report("failing child assertion, action=continue", baseline, actual);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();

    SampleResult baselineParent = baseline.topLevelResult("controller");
    SampleResult actualParent = actual.topLevelResult("controller");
    softly.assertThat(actualParent).as("a parent sample must be reported").isNotNull();
    if (actualParent != null && baselineParent != null) {
      softly.assertThat(actualParent.isSuccessful())
          .as("a failing child must fail the parent sample, as in stock JMeter")
          .isEqualTo(baselineParent.isSuccessful());
      softly.assertThat(actualParent.getResponseMessage())
          .as("the parent sample must report the same sample/failure counts as stock JMeter")
          .isEqualTo(baselineParent.getResponseMessage());
      softly.assertThat(SampleResultTrees.childLabels(actualParent))
          .as("all three requests must still be reported under the parent")
          .isEqualTo(SampleResultTrees.childLabels(baselineParent));
      SampleResult failedChild = childOf(actualParent, "S2");
      softly.assertThat(failedChild).as("the failing child must be present").isNotNull();
      if (failedChild != null) {
        softly.assertThat(failedChild.isSuccessful())
            .as("the failing child must be marked as failed")
            .isFalse();
        softly.assertThat(failedChild.getAssertionResults())
            .as("the failing child must carry its assertion result")
            .isNotEmpty();
      }
    }
    softly.assertAll();
  }

  @Test
  public void stopThreadOnErrorMustStopTheThreadAtTheFailingSample() {
    assertErrorActionParity("action=Stop Thread",
        AbstractThreadGroup.ON_SAMPLE_ERROR_STOPTHREAD);
  }

  @Test
  public void stopTestOnErrorMustAskTheEngineToStop() {
    assertErrorActionParity("action=Stop Test",
        AbstractThreadGroup.ON_SAMPLE_ERROR_STOPTEST);
  }

  @Test
  public void stopTestNowOnErrorMustAskTheEngineToStopNow() {
    assertErrorActionParity("action=Stop Test Now",
        AbstractThreadGroup.ON_SAMPLE_ERROR_STOPTEST_NOW);
  }

  @Test
  public void startNextThreadLoopOnErrorMustRestartTheIterationWithoutLeakingState() {
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT,
        AbstractThreadGroup.ON_SAMPLE_ERROR_START_NEXT_LOOP, 2, failingAssertionShape());
    Result actual = execute(ControllerKind.ASYNC_PARENT,
        AbstractThreadGroup.ON_SAMPLE_ERROR_START_NEXT_LOOP, 2, failingAssertionShape());
    report("failing child assertion, action=Start Next Thread Loop, 2 iterations",
        baseline, actual);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.resultTree())
        .as("restarting the iteration must not throw away the samples that already ran, and each "
            + "iteration must report exactly what stock JMeter reports")
        .isEqualTo(baseline.resultTree());
    for (SampleResult parent : actual.topLevelResults()) {
      if ("controller".equals(parent.getSampleLabel())) {
        softly.assertThat(SampleResultTrees.childLabels(parent))
            .as("each iteration's parent sample must carry only that iteration's children")
            .containsExactly("S1", "S2");
      }
    }
    softly.assertAll();
  }

  @Test
  public void flatModeStopThreadOnErrorStillWorks() {
    Result baseline = execute(ControllerKind.STOCK_SIMPLE,
        AbstractThreadGroup.ON_SAMPLE_ERROR_STOPTHREAD, 1, failingAssertionShape());
    Result actual = execute(ControllerKind.ASYNC_FLAT,
        AbstractThreadGroup.ON_SAMPLE_ERROR_STOPTHREAD, 1, failingAssertionShape());
    report("failing child assertion, Generate parent sample OFF, action=Stop Thread",
        baseline, actual);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.topLevelLabels())
        .as("with Generate parent sample off the thread must stop like stock JMeter does")
        .isEqualTo(baseline.topLevelLabels());
    softly.assertAll();
  }

  @Test
  public void aRequestWhoseDispatchIsRejectedMustNotHangTheThread() {
    Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      fixture.transport().spec("S1").failure(StubAsyncTransport.Failure.DISPATCH_THROWS);
      ThreadGroup group = threadGroup("tg", 1);
      actual = fixture.run(group, HANG_TIMEOUT_MILLIS,
          node(fixture.controller("controller"),
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 40))),
          fixture.recorderNode());
    }
    System.out.println("\n=== dispatch rejected for S1 ===\n  hung=" + actual.hung()
        + " elapsed=" + actual.elapsedMillis() + "ms tree=" + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung())
        .as("a request that fails at dispatch must not leave the controller busy-waiting for a "
            + "response that can never arrive")
        .isFalse();
    softly.assertThat(actual.topLevelLabels())
        .as("the failure must still be reported to the listeners")
        .isNotEmpty();
    softly.assertAll();
  }

  @Test
  public void aRequestWhoseSendFailsMustNotHangTheThread() {
    Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      fixture.transport().spec("S1").failure(StubAsyncTransport.Failure.SEND_THROWS);
      ThreadGroup group = threadGroup("tg", 1);
      actual = fixture.run(group, HANG_TIMEOUT_MILLIS,
          node(fixture.controller("controller"),
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 40))),
          fixture.recorderNode());
    }
    System.out.println("\n=== send failed for S1 ===\n  hung=" + actual.hung()
        + " elapsed=" + actual.elapsedMillis() + "ms tree=" + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung())
        .as("a request that throws while being sent must not leave the controller busy-waiting")
        .isFalse();
    softly.assertThat(actual.topLevelLabels())
        .as("the failure must still be reported to the listeners")
        .isNotEmpty();
    softly.assertAll();
  }

  @Test
  public void aResponseThatNeverArrivesMustBeReportedAsATimedOutSample() {
    Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      fixture.transport().spec("S2").failure(StubAsyncTransport.Failure.NEVER_COMPLETES);
      ThreadGroup group = threadGroup("tg", 1);
      HTTP2Sampler first = fixture.http("S1", 20);
      HTTP2Sampler stalled = fixture.http("S2", 40);
      // A configured response timeout is what bounds the wait; without one the controller can only
      // fall back to its safety bound, which is deliberately long.
      first.setResponseTimeout("300");
      stalled.setResponseTimeout("300");
      actual = fixture.run(group, HANG_TIMEOUT_MILLIS,
          node(fixture.controller("controller"),
              node(first),
              node(stalled)),
          fixture.recorderNode());
    }
    System.out.println("\n=== response never arrives for S2 ===\n  hung=" + actual.hung()
        + " elapsed=" + actual.elapsedMillis() + "ms tree=" + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung())
        .as("a request that exceeds its response timeout must be given up on, not busy-waited for "
            + "indefinitely: this loop runs inside Controller.next(), so neither the thread's "
            + "running flag nor JMeterThread.interrupt() can break it")
        .isFalse();
    SampleResult parent = actual.topLevelResult("controller");
    softly.assertThat(parent).as("the controller must still report its parent sample").isNotNull();
    if (parent != null) {
      softly.assertThat(SampleResultTrees.childLabels(parent))
          .as("the stalled request must be reported as a failed child, not silently dropped")
          .contains("S2");
      softly.assertThat(parent.isSuccessful())
          .as("a timed-out child must fail the parent sample")
          .isFalse();
    }
    softly.assertAll();
  }

  private void assertErrorActionParity(String label, String onSampleError) {
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, onSampleError, 1,
        failingAssertionShape());
    Result actual = execute(ControllerKind.ASYNC_PARENT, onSampleError, 1,
        failingAssertionShape());
    report("failing child assertion, " + label, baseline, actual);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("%s: the JMeter thread must finish", label).isFalse();
    softly.assertThat(actual.resultTree())
        .as("%s: the error action must take effect at the failing sample, so the run must report "
            + "exactly what stock JMeter reports", label)
        .isEqualTo(baseline.resultTree());
    softly.assertThat(actual.engineSignals())
        .as("%s: the async controller must raise the same engine level stop requests as stock "
            + "JMeter", label)
        .isEqualTo(baseline.engineSignals());
    // Dispatch is deliberately not compared with stock. An async controller fires its children
    // ahead of collecting them, so a request already on the wire when the failure is detected
    // cannot be un-sent; stock sampled %s while this one had already dispatched more. What must
    // match is what gets reported, asserted above. The in-flight surplus is bounded by
    // maxConcurrentAsyncInController when limitMaxParallel is on.
    softly.assertThat(actual.dispatchOrder())
        .as("%s: no request may be dispatched more than once per iteration", label)
        .doesNotHaveDuplicates();
    softly.assertAll();
  }

  private static SampleResult childOf(SampleResult parent, String label) {
    SampleResult[] subs = parent.getSubResults();
    if (subs == null) {
      return null;
    }
    for (SampleResult sub : subs) {
      if (label.equals(sub.getSampleLabel())) {
        return sub;
      }
    }
    return null;
  }

  private Result execute(ControllerKind kind, String onSampleError, int loops, TreeShape shape) {
    try (ScenarioFixture fixture = new ScenarioFixture(kind)) {
      ThreadGroup group = threadGroup("tg", loops, onSampleError);
      List<AsyncScenarioRunner.Node> nodes =
          new ArrayList<>(Arrays.asList(shape.build(fixture)));
      nodes.add(fixture.recorderNode());
      return fixture.run(group, nodes.toArray(new AsyncScenarioRunner.Node[0]));
    }
  }

  private void report(String label, Result baseline, Result actual) {
    StringBuilder out = new StringBuilder();
    out.append("\n=== ").append(label).append(" ===\n");
    out.append("  stock  : tree=").append(baseline.resultTree())
        .append("\n           sampled=").append(baseline.syncSampled())
        .append(" engine=").append(baseline.engineSignals()).append('\n');
    out.append("  async  : tree=").append(actual.resultTree())
        .append("\n           dispatched=").append(actual.dispatchOrder())
        .append(" engine=").append(actual.engineSignals())
        .append(" hung=").append(actual.hung()).append('\n');
    SampleResult stockParent = baseline.topLevelResult("controller");
    SampleResult asyncParent = actual.topLevelResult("controller");
    out.append("  stock parent: ").append(describe(stockParent)).append('\n');
    out.append("  async parent: ").append(describe(asyncParent)).append('\n');
    System.out.println(out);
  }

  private static String describe(SampleResult result) {
    if (result == null) {
      return "<none>";
    }
    return "success=" + result.isSuccessful()
        + " code=" + result.getResponseCode()
        + " message='" + result.getResponseMessage() + "'"
        + " children=" + SampleResultTrees.childLabels(result);
  }
}
