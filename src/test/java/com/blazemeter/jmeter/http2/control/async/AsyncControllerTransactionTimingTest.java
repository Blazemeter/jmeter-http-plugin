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
import java.util.function.Consumer;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * Issue #155 — "Think time is included in Transaction Time + Spurious Averages/Mins + Negative Last
 * Sample Time", a v3.1.0 regression against v3.0.1.
 *
 * <p>What the parent sample of this controller must measure is the wall clock the controller spent
 * on its requests: for a sequential controller that is the sum of the child times, and for a
 * parallel one it is the span from the first dispatch to the last response. Timers and pre/post
 * processors are pauses, not request time, so they are excluded — which is what v3.0.1 did by
 * building the parent sample from {@code min(child start)} to {@code max(child end)}.
 *
 * <p>The stock Transaction Controller is deliberately NOT the baseline for the times here, unlike
 * the rest of this suite: it runs its children sequentially, so span and sum coincide for it, and
 * its own {@code includeTimers} defaults to {@code true}, so its parent sample counts the think
 * time on purpose. Only {@link #t4ATransactionCutShortMustNotReportANegativeSampleTime()} compares
 * against it, because there stock defines what "not broken" looks like.
 */
public class AsyncControllerTransactionTimingTest extends HTTP2TestBase {

  private static final long LATENCY = 120L;
  private static final long THINK_TIME = 800L;

  @Test
  public void t1ThinkTimeMustNotBeCountedInTheParentSampleTime() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", LATENCY), node(fixture.timer("think-time", THINK_TIME))),
            node(fixture.http("S2", LATENCY)))
    };
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape);
    SampleResult parent = actual.topLevelResult("controller");
    report("T1 controller[S1[think time " + THINK_TIME + "ms], S2]", actual, parent);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(parent).as("a parent sample must be reported").isNotNull();
    if (parent != null) {
      softly.assertThat(parent.getTime())
          .as("the %s ms think time of S1 is a pause, not request time, so it must stay out of the "
              + "controller's own sample time", THINK_TIME)
          .isLessThan(THINK_TIME);
      softly.assertThat(parent.getTime())
          .as("the parent sample must measure the span of its requests")
          .isEqualTo(requestSpan(parent));
      softly.assertThat(parent.getTime())
          .as("a sample time can never be negative")
          .isNotNegative();
    }
    softly.assertAll();
  }

  @Test
  public void t2ParentSampleTimeMustBeTheSpanOfTheOverlappedRequestsNotTheirSum() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", 300)),
            node(fixture.http("S2", 300)),
            node(fixture.http("S3", 300)))
    };
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape);
    SampleResult parent = actual.topLevelResult("controller");
    report("T2 controller[S1, S2, S3] all 300ms", actual, parent);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.maxConcurrentInFlight())
        .as("precondition: the requests really overlapped")
        .isGreaterThan(1);
    softly.assertThat(parent).as("a parent sample must be reported").isNotNull();
    if (parent != null) {
      softly.assertThat(parent.getTime())
          .as("three requests that ran at the same time took as long as the slowest of them, not "
              + "as long as all of them one after another (%s ms)", sumOfChildTimes(parent))
          .isLessThan(sumOfChildTimes(parent));
      softly.assertThat(parent.getTime())
          .as("the parent sample must measure the span of its requests")
          .isEqualTo(requestSpan(parent));
    }
    softly.assertAll();
  }

  @Test
  public void t3ThinkTimeMustNotBubbleUpToAnEnclosingTransactionController() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(enclosingTransaction(fixture),
            node(fixture.controller("controller"),
                node(fixture.http("S1", LATENCY), node(fixture.timer("think-time", THINK_TIME))),
                node(fixture.http("S2", LATENCY))))
    };
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape);
    SampleResult outer = actual.topLevelResult("tx");
    SampleResult inner = outer == null ? null : firstChild(outer);
    report("T3 tx(parent, includeTimers=false)[controller[S1[think time], S2]]", actual, outer);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(outer).as("the enclosing transaction must be reported").isNotNull();
    softly.assertThat(inner).as("the controller sample must be nested in it").isNotNull();
    if (outer != null && inner != null) {
      softly.assertThat(inner.getTime())
          .as("the controller's own sample time must exclude the think time")
          .isLessThan(THINK_TIME);
      softly.assertThat(outer.getTime())
          .as("an enclosing transaction sums the times of its children, so a think time counted by "
              + "the controller bubbles straight up into it")
          .isLessThan(THINK_TIME);
      softly.assertThat(outer.getTime())
          .as("the enclosing transaction has a single child, so it must report that child's time")
          .isEqualTo(inner.getTime());
    }
    softly.assertAll();
  }

  /**
   * A Stop goes straight onto the running flag, so unlike the scheduled end of
   * {@link #t5RequestsAlreadyOnTheWireMustSurviveTheScheduledEnd()} it cannot be held off: whatever
   * this controller had on the wire is lost. What must not happen is a poisoned row getting out -
   * the transaction that was open when the Stop landed reporting minus the epoch. Landing while the
   * controller waits for a response, the Stop is seen by {@code JMeterThread}'s own loop before
   * {@code processSampler} runs again, so usually nothing is reported at all; that the run stops,
   * and that nothing bogus reaches a listener either way, is what is asserted.
   */
  @Test
  public void t4ATransactionCutShortMustNotReportANegativeSampleTime() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(enclosingTransaction(fixture),
            node(fixture.controller("controller"),
                node(fixture.http("S1", LATENCY)),
                node(fixture.http("S2", LATENCY))))
    };
    Result baseline = execute(ControllerKind.STOCK_TX_PARENT, 1, shape,
        AsyncScenarioRunner.stopAfter(LATENCY / 4));
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape,
        AsyncScenarioRunner.stopAfter(LATENCY / 4));
    System.out.println("\n=== T4 stopped from the outside, mid-flight ==="
        + "\n  stock: " + describeAll(baseline)
        + "\n  async: " + describeAll(actual));

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(baseline.hung()).as("the stock run must stop").isFalse();
    softly.assertThat(actual.hung()).as("the async run must stop").isFalse();
    assertReportsDurationsNotEpochs(softly, "stock", baseline);
    assertReportsDurationsNotEpochs(softly, "async", actual);
    softly.assertAll();
  }

  /**
   * The defect is a transaction that was never given an end time: {@code elapsed = end - start} then
   * reports {@code 0 - startTime}, an epoch, and that single row wrecks the Average and the Min of
   * every aggregate over the run. Both halves are asserted, since the missing end time is the cause
   * and the negative duration is what a report shows.
   */
  private static void assertReportsDurationsNotEpochs(SoftAssertions softly, String kind,
                                                      Result result) {
    for (SampleResult sample : allResults(result)) {
      softly.assertThat(sample.getEndTime())
          .as("%s: '%s' was ended without an end time being stamped, which is what turns into "
              + "0 - startTime", kind, sample.getSampleLabel())
          .isPositive();
      softly.assertThat(sample.getTime())
          .as("%s: '%s' was reported with %s ms", kind, sample.getSampleLabel(), sample.getTime())
          .isNotNegative();
    }
  }

  /**
   * A scheduled end that falls while the requests are on the wire must not throw their responses
   * away. A stock sampler never loses one: {@code stopSchedulerIfNeeded} runs after the sample
   * returns, so a request that is already out always finishes and is reported, and its transaction
   * closes with it inside. This controller keeps its requests out across turns, so without holding
   * the deadline off the thread stopped between them - the responses were dropped and the
   * transaction was reported empty, at 0 ms.
   */
  @Test
  public void t5RequestsAlreadyOnTheWireMustSurviveTheScheduledEnd() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", LATENCY)),
            node(fixture.http("S2", LATENCY)))
    };
    // The end lands after the requests went out and well before they come back.
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape,
        AsyncScenarioRunner.schedulerEndingIn(LATENCY / 4));
    SampleResult parent = actual.topLevelResult("controller");
    report("T5 scheduled end falls mid-flight", actual, parent);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the thread must still stop, not outlive its schedule")
        .isFalse();
    softly.assertThat(parent).as("a parent sample must be reported").isNotNull();
    if (parent != null) {
      softly.assertThat(labelsOf(children(parent)))
          .as("both responses were already on their way back, so both must be reported")
          .containsExactly("S1", "S2");
      softly.assertThat(parent.getTime())
          .as("and the transaction must measure them, not come out as an empty 0 ms row")
          .isGreaterThanOrEqualTo(LATENCY);
    }
    softly.assertAll();
  }

  /**
   * Holding the scheduled end off is for the responses this controller already asked for, not a
   * licence to keep asking: the children it had not dispatched yet when the end went by must stay
   * undispatched, or a duration-limited run keeps putting load on the system under test after the
   * time it was given, and the thread outlives its schedule by a whole controller block instead of
   * by the responses that were on the wire.
   */
  @Test
  public void t6NothingNewIsDispatchedOnceTheScheduledEndHasGoneBy() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", LATENCY)),
            node(fixture.http("S2", LATENCY)),
            node(fixture.http("S3", LATENCY)),
            node(fixture.http("S4", LATENCY)),
            node(fixture.http("S5", LATENCY)))
    };
    // Over by the time the controller gets its second turn, whatever the machine's speed: dispatches
    // cost microseconds, so a scheduled end a few milliseconds out is a race the test would lose.
    Result actual = execute(ControllerKind.ASYNC_PARENT, 1, shape,
        AsyncScenarioRunner.expiredScheduler());
    SampleResult parent = actual.topLevelResult("controller");
    report("T6 scheduled end falls after the first dispatches", actual, parent);
    System.out.println("  dispatched: " + actual.dispatchOrder());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the thread must still stop").isFalse();
    softly.assertThat(parent).as("a parent sample must be reported").isNotNull();
    if (parent != null) {
      softly.assertThat(labelsOf(children(parent)))
          .as("the request that did go out must still be collected and reported")
          .containsExactlyElementsOf(actual.dispatchOrder());
      softly.assertThat(actual.dispatchOrder())
          .as("the run was already over on the controller's second turn, so only the request that "
              + "was on the wire by then may exist")
          .containsExactly("S1");
    }
    softly.assertAll();
  }

  private static List<String> labelsOf(List<SampleResult> results) {
    List<String> labels = new ArrayList<>();
    for (SampleResult result : results) {
      labels.add(result.getSampleLabel());
    }
    return labels;
  }

  /** Mirrors issue #155's test plan: parent sample on, think time excluded. */
  private static TransactionController enclosingTransaction(ScenarioFixture fixture) {
    TransactionController transaction = fixture.stockTransaction("tx", true);
    transaction.setIncludeTimers(false);
    return transaction;
  }

  /** From the first dispatch to the last response, i.e. what a parallel block really took. */
  private static long requestSpan(SampleResult parent) {
    long firstStart = Long.MAX_VALUE;
    long lastEnd = 0;
    for (SampleResult child : children(parent)) {
      if (child.getStartTime() > 0) {
        firstStart = Math.min(firstStart, child.getStartTime());
      }
      lastEnd = Math.max(lastEnd, child.getEndTime());
    }
    return firstStart == Long.MAX_VALUE ? 0 : lastEnd - firstStart;
  }

  private static long sumOfChildTimes(SampleResult parent) {
    long total = 0;
    for (SampleResult child : children(parent)) {
      total += child.getTime();
    }
    return total;
  }

  private static SampleResult firstChild(SampleResult parent) {
    List<SampleResult> children = children(parent);
    return children.isEmpty() ? null : children.get(0);
  }

  private static List<SampleResult> children(SampleResult parent) {
    SampleResult[] subs = parent == null ? null : parent.getSubResults();
    return subs == null ? new ArrayList<>() : Arrays.asList(subs);
  }

  private static List<SampleResult> allResults(Result result) {
    List<SampleResult> all = new ArrayList<>();
    for (SampleResult top : result.topLevelResults()) {
      collect(top, all);
    }
    return all;
  }

  private static void collect(SampleResult result, List<SampleResult> out) {
    out.add(result);
    for (SampleResult child : children(result)) {
      collect(child, out);
    }
  }

  private static String describeAll(Result result) {
    StringBuilder out = new StringBuilder();
    for (SampleResult sample : allResults(result)) {
      out.append("\n         ").append(sample.getSampleLabel())
          .append(": time=").append(sample.getTime())
          .append(" start=").append(sample.getStartTime())
          .append(" end=").append(sample.getEndTime())
          .append(" idle=").append(sample.getIdleTime());
    }
    return out.length() == 0 ? "<no sample reported>" : out.toString();
  }

  private static void report(String label, Result result, SampleResult parent) {
    System.out.println("\n=== " + label + " ==="
        + "\n  reported: " + describeAll(result)
        + "\n  span=" + requestSpan(parent) + " sum=" + sumOfChildTimes(parent)
        + " maxInFlight=" + result.maxConcurrentInFlight());
  }

  private Result execute(ControllerKind kind, int loops, TreeShape shape) {
    return execute(kind, loops, shape, thread -> {
    });
  }

  private Result execute(ControllerKind kind, int loops, TreeShape shape,
                         Consumer<JMeterThread> threadSetup) {
    try (ScenarioFixture fixture = new ScenarioFixture(kind)) {
      ThreadGroup group = threadGroup("tg", loops);
      List<AsyncScenarioRunner.Node> nodes =
          new ArrayList<>(Arrays.asList(shape.build(fixture)));
      nodes.add(fixture.recorderNode());
      return fixture.run(group, AsyncScenarioRunner.DEFAULT_TIMEOUT_MILLIS, threadSetup,
          nodes.toArray(new AsyncScenarioRunner.Node[0]));
    }
  }
}
