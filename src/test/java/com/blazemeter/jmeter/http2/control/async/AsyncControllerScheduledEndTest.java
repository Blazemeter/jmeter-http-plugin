package com.blazemeter.jmeter.http2.control.async;

import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.node;
import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.threadGroup;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.ControllerKind;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * BlazeMeter HTTP issue 169: with the Thread Group looping (as every duration-limited test does),
 * a scheduled end that falls while this controller holds it off must still end the thread.
 *
 * <p>The hold used to be given back only when the iteration ended ({@code reInitialize}), and the
 * Thread Group's {@code LoopController} starts the next iteration inside that same {@code next()}
 * call ({@code nextIsNull} returns {@code next()}). The controller's first dispatch of the new
 * iteration then found the end already gone by and held it again, before {@code JMeterThread}
 * could check it: the thread never stopped, one request per iteration past the end. A stock
 * sampler is checked after every sample, so a stock thread stops right after the sample the end
 * fell in; that is the behaviour asserted here, along with every response that was sent being
 * reported. The single-iteration scenarios of {@code AsyncControllerTransactionTimingTest} (T5,
 * T6) cannot show it: their loop ends anyway.
 */
public class AsyncControllerScheduledEndTest extends HTTP2TestBase {

  private static final long LATENCY = 120L;
  /** Far shorter than the runner's default: a thread that outlives its schedule is the failure. */
  private static final long RUN_TIMEOUT_MILLIS = 5_000L;
  /**
   * Where the end falls in the post-processor scenarios: under the controller's hold
   * ({@code blazemeter.http.schedulerHoldMillis}, 2 s), so it is held while the requests are on the
   * wire, and well past {@link #LATENCY}, so the last response is collected before it.
   */
  private static final long CROSSING_END_MILLIS = 8 * LATENCY;
  /**
   * The end falls a few milliseconds into the run: on a cold JVM it can come before S2 is
   * dispatched, and then S2 must not be (T6). Either way nothing may be sent twice.
   */
  private static final List<List<String>> FIRST_ITERATION_ONLY = Arrays.asList(
      Arrays.asList("S1", "S2"), Arrays.asList("S1"));

  @Test
  public void flatControllerMustStopTheThreadAtTheScheduledEndWhenTheThreadGroupLoops() {
    assertStopsAtTheScheduledEnd(ControllerKind.ASYNC_FLAT);
  }

  @Test
  public void parentSampleControllerMustStopTheThreadAtTheScheduledEndWhenTheThreadGroupLoops() {
    assertStopsAtTheScheduledEnd(ControllerKind.ASYNC_PARENT);
  }

  @Test
  public void stockTransactionControllerBaseline() {
    assertStopsAtTheScheduledEnd(ControllerKind.STOCK_TX_FLAT);
  }

  /**
   * A sampler that comes before the controller in the Thread Group must not run again either: the
   * thread has to stop when this controller's iteration ends, not when the next iteration's first
   * sample is checked, or every thread samples it once more past the end.
   *
   * <p>The end is armed when X is sampled, not when the thread is started: JMeter checks the end
   * right after X too, so an end armed before a slow thread start could already have gone by there,
   * and the thread would stop before the controller is ever reached.
   */
  @Test
  public void samplerBeforeTheControllerMustNotRunAgainAfterTheScheduledEnd() {
    Result actual;
    MarkerSampler before = new SchedulerArmingSampler("X", LATENCY / 4);
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_FLAT)) {
      ThreadGroup group = threadGroup("tg", -1);
      List<AsyncScenarioRunner.Node> nodes = new ArrayList<>(Arrays.asList(
          node(before),
          node(fixture.controller("controller"),
              node(fixture.http("S1", LATENCY)),
              node(fixture.http("S2", LATENCY)))));
      nodes.add(fixture.recorderNode());
      actual = fixture.run(group, RUN_TIMEOUT_MILLIS, scheduledButNotYetArmed(),
          nodes.toArray(new AsyncScenarioRunner.Node[0]));
    }
    List<String> expectedReported = new ArrayList<>();
    expectedReported.add("X");
    expectedReported.addAll(actual.dispatchOrder());
    System.out.println("X first dispatched: " + actual.dispatchOrder() + " X runs: "
        + before.invocations() + " reported: " + actual.topLevelLabels() + " hung: "
        + actual.hung());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the thread must stop at its scheduled end").isFalse();
    softly.assertThat(actual.dispatchOrder()).as("only the first iteration's requests")
        .isIn(FIRST_ITERATION_ONLY);
    softly.assertThat(before.invocations())
        .as("the sampler before the controller ran before the end only, as with a stock "
            + "Transaction Controller")
        .isEqualTo(1);
    softly.assertThat(actual.topLevelLabels())
        .as("X and every request that was sent are reported before the thread stops")
        .isEqualTo(expectedReported);
    softly.assertAll();
  }

  /**
   * A Transaction Controller with "Generate parent sample" on, enclosing a flat async controller,
   * holds the async controller's results until it closes. Ending the thread from inside the
   * controller tree unwinds past that enclosing transaction, and it is never reported: neither the
   * transaction nor any of the responses in it. A stock thread stopped by its schedule closes and
   * reports whatever transaction is open; so must this one.
   */
  @Test
  public void enclosingParentTransactionMustBeReportedWhenTheScheduledEndStopsTheThread() {
    Result stock = runInsideParentTransaction(ControllerKind.STOCK_SIMPLE);
    Result async = runInsideParentTransaction(ControllerKind.ASYNC_FLAT);
    System.out.println("stock tree: " + stock.resultTree() + " async dispatched: "
        + async.dispatchOrder() + " async tree: " + async.resultTree() + " hung: "
        + async.hung());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(stock.topLevelLabels()).as("stock baseline reports the enclosing transaction")
        .containsExactly("outer");
    softly.assertThat(async.hung()).as("the thread must stop at its scheduled end").isFalse();
    softly.assertThat(async.dispatchOrder()).as("only the first iteration's requests")
        .isIn(FIRST_ITERATION_ONLY);
    softly.assertThat(async.topLevelLabels())
        .as("the enclosing transaction is reported, as with a stock controller")
        .isEqualTo(stock.topLevelLabels());
    SampleResult outer = async.topLevelResult("outer");
    if (outer != null) {
      softly.assertThat(SampleResultTrees.childLabels(outer))
          .as("the enclosing transaction holds every request that was sent")
          .isEqualTo(async.dispatchOrder());
    }
    softly.assertAll();
  }

  /**
   * A request whose dispatch fails never gets a response listener, so it has no completion turn:
   * the controller drops it from its queue on a later turn. When it is the last one queued, no
   * collected response follows it for {@code JMeterThread} to check the end after, and the end was
   * handed back only when the pass ended, too late to stop the Thread Group's next iteration. With
   * every dispatch failing, each new iteration held the end again and the thread never stopped.
   * A stock thread whose sampler keeps failing is stopped by the check after the failed sample.
   */
  @Test
  public void failedLastDispatchMustNotKeepTheThreadPastTheScheduledEnd() {
    Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_FLAT)) {
      // SEND_THROWS, not DISPATCH_THROWS: both leave the sampler without a response listener, but
      // a ConnectException makes the error mapper resolve the stub's host name, which on some
      // machines takes longer than the hold and would end the run for a reason unrelated to this.
      fixture.transport().spec("S1").failure(StubAsyncTransport.Failure.SEND_THROWS);
      actual = fixture.run(threadGroup("tg", -1), RUN_TIMEOUT_MILLIS,
          AsyncScenarioRunner.schedulerEndingIn(LATENCY / 4),
          node(fixture.controller("controller"),
              node(fixture.http("S1", LATENCY))),
          fixture.recorderNode());
    }
    System.out.println("failed dispatch: dispatched " + actual.dispatchOrder().size()
        + " reported " + actual.topLevelLabels().size() + " hung: " + actual.hung());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.uncaught()).as("the thread ended normally").isNull();
    softly.assertThat(actual.dispatchOrder()).as("the failing request was dispatched").isNotEmpty();
    softly.assertThat(actual.hung())
        .as("a failing request must not keep the thread past its scheduled end").isFalse();
    softly.assertThat(actual.topLevelLabels())
        .as("every failed dispatch is reported").hasSameSizeAs(actual.dispatchOrder());
    softly.assertAll();
  }

  /**
   * Same, with a request still on the wire ahead of the failed one: the end may only go back once
   * that response has been collected and reported, and then it must stop the thread.
   */
  @Test
  public void failedLastDispatchAfterALiveRequestMustStopAfterCollectingIt() {
    long slowLatency = 12 * LATENCY;
    Result actual;
    MarkerSampler before = new MarkerSampler("X");
    // Armed by S2's own failure, so S2 has always been dispatched and has failed before the end,
    // and S1, far slower than the end is short, is still on the wire when it falls.
    EndArmingPostProcessor armOnFailure = new EndArmingPostProcessor(LATENCY / 2);
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_FLAT)) {
      fixture.transport().spec("S2").failure(StubAsyncTransport.Failure.SEND_THROWS);
      actual = fixture.run(threadGroup("tg", -1), RUN_TIMEOUT_MILLIS, scheduledButNotYetArmed(),
          node(before),
          node(fixture.controller("controller"),
              node(fixture.http("S1", slowLatency)),
              node(fixture.http("S2", slowLatency), node(armOnFailure))),
          fixture.recorderNode());
    }
    System.out.println("failed after live: dispatched " + actual.dispatchOrder() + " reported "
        + actual.topLevelLabels() + " completed " + actual.completionOrder() + " hung: "
        + actual.hung());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.uncaught()).as("the thread ended normally").isNull();
    softly.assertThat(armOnFailure.armedEnd()).as("the end was armed by S2's failure")
        .isPositive();
    softly.assertThat(actual.hung()).as("the thread must stop at its scheduled end").isFalse();
    softly.assertThat(actual.dispatchOrder()).as("only the first iteration's requests")
        .containsExactly("S1", "S2");
    softly.assertThat(before.invocations()).as("no second iteration").isEqualTo(1);
    softly.assertThat(actual.topLevelLabels())
        .as("the failed dispatch is reported when it fails, the live response once collected")
        .containsExactly("X", "S2", "S1");
    softly.assertAll();
  }

  /**
   * The last response is collected before the end, and the end goes by while its post-processor
   * runs. A stock thread is stopped by the check right after that sample. Here the end was still
   * held at that check, so the Thread Group started another iteration before the end was given
   * back.
   */
  @Test
  public void endCrossedByThePostProcessorOfTheLastResponseMustStopTheThread() {
    Result actual;
    // The end is close enough for the controller to hold it while S1 and S2 are on the wire, and
    // far enough past their latency for S2 to be collected before it; the post-processor then
    // waits for it to go by, and says whether it really started before.
    SchedulerArmingSampler before = new SchedulerArmingSampler("X", CROSSING_END_MILLIS);
    SleepPastArmedEndPostProcessor crossing = new SleepPastArmedEndPostProcessor(before, 20);
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_FLAT)) {
      actual = fixture.run(threadGroup("tg", -1), RUN_TIMEOUT_MILLIS, scheduledButNotYetArmed(),
          node(before),
          node(fixture.controller("controller"),
              node(fixture.http("S1", LATENCY)),
              node(fixture.http("S2", LATENCY), node(crossing))),
          fixture.recorderNode());
    }
    System.out.println("post-processor crossing: dispatched " + actual.dispatchOrder()
        + " reported " + actual.topLevelLabels() + " hung: " + actual.hung());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.uncaught()).as("the thread ended normally").isNull();
    softly.assertThat(crossing.startedBeforeTheEnd())
        .as("S2 was collected before the end, and its post-processor crossed it").isTrue();
    softly.assertThat(actual.hung()).as("the thread must stop at its scheduled end").isFalse();
    softly.assertThat(actual.dispatchOrder()).as("only the first iteration's requests")
        .containsExactly("S1", "S2");
    softly.assertThat(before.invocations()).as("no second iteration").isEqualTo(1);
    softly.assertThat(actual.topLevelLabels()).containsExactly("X", "S1", "S2");
    softly.assertAll();
  }

  /**
   * Same, with the responses marked as ignored by a post-processor ({@code prev.setIgnore()}, as a
   * JSR223 post-processor does to drop a sample from the results). JMeter then notifies no
   * listener of them, but still checks the scheduled end right after, so the end has to be given
   * back without waiting for a notification.
   */
  @Test
  public void endCrossedWhileTheLastResponseIsIgnoredMustStopTheThread() {
    Result actual;
    SchedulerArmingSampler before = new SchedulerArmingSampler("X", CROSSING_END_MILLIS);
    SleepPastArmedEndPostProcessor crossing = new SleepPastArmedEndPostProcessor(before, 20);
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_FLAT)) {
      actual = fixture.run(threadGroup("tg", -1), RUN_TIMEOUT_MILLIS, scheduledButNotYetArmed(),
          node(before),
          node(fixture.controller("controller"),
              node(new IgnoringPostProcessor()),
              node(fixture.http("S1", LATENCY)),
              node(fixture.http("S2", LATENCY), node(crossing))),
          fixture.recorderNode());
    }
    System.out.println("ignored crossing: dispatched " + actual.dispatchOrder() + " reported "
        + actual.topLevelLabels() + " X runs: " + before.invocations() + " hung: "
        + actual.hung());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.uncaught()).as("the thread ended normally").isNull();
    softly.assertThat(crossing.startedBeforeTheEnd())
        .as("S2 was collected before the end, and its post-processor crossed it").isTrue();
    softly.assertThat(actual.hung()).as("the thread must stop at its scheduled end").isFalse();
    softly.assertThat(actual.dispatchOrder()).as("only the first iteration's requests")
        .containsExactly("S1", "S2");
    softly.assertThat(before.invocations()).as("no second iteration").isEqualTo(1);
    softly.assertThat(actual.topLevelLabels()).as("the ignored responses are not reported")
        .containsExactly("X");
    softly.assertAll();
  }

  private Result runInsideParentTransaction(ControllerKind kind) {
    try (ScenarioFixture fixture = new ScenarioFixture(kind)) {
      // The end falls while the first iteration's requests are on the wire.
      return fixture.run(threadGroup("tg", -1), RUN_TIMEOUT_MILLIS,
          AsyncScenarioRunner.schedulerEndingIn(LATENCY / 4),
          node(fixture.stockTransaction("outer", true),
              node(fixture.controller("controller"),
                  node(fixture.http("S1", LATENCY)),
                  node(fixture.http("S2", LATENCY)))),
          fixture.recorderNode());
    }
  }

  private void assertStopsAtTheScheduledEnd(ControllerKind kind) {
    Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(kind)) {
      ThreadGroup group = threadGroup("tg", -1);
      List<AsyncScenarioRunner.Node> nodes = new ArrayList<>(Arrays.asList(
          node(fixture.controller("controller"),
              node(fixture.http("S1", LATENCY)),
              node(fixture.http("S2", LATENCY)))));
      nodes.add(fixture.recorderNode());
      // The end falls while the first iteration's requests are on the wire.
      actual = fixture.run(group, RUN_TIMEOUT_MILLIS,
          AsyncScenarioRunner.schedulerEndingIn(LATENCY / 4),
          nodes.toArray(new AsyncScenarioRunner.Node[0]));
    }
    List<String> reported = actual.topLevelLabels();
    System.out.println(kind + " dispatched: " + actual.dispatchOrder() + " reported: "
        + actual.resultTree() + " hung: " + actual.hung());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung())
        .as("%s: the thread must stop at its scheduled end, not loop on past it", kind)
        .isFalse();
    if (kind.isAsync()) {
      softly.assertThat(actual.dispatchOrder())
          .as("%s: only the iteration the end fell in may send requests", kind)
          .isIn(FIRST_ITERATION_ONLY);
      if (kind.generatesParentSample()) {
        softly.assertThat(reported).as("%s: one parent sample", kind)
            .containsExactly("controller");
        SampleResult parent = actual.topLevelResult("controller");
        if (parent != null) {
          softly.assertThat(SampleResultTrees.childLabels(parent))
              .as("%s: the parent sample holds every request that was sent", kind)
              .isEqualTo(actual.dispatchOrder());
        }
      } else {
        softly.assertThat(reported)
            .as("%s: every request that was sent is reported before the thread stops", kind)
            .isEqualTo(actual.dispatchOrder());
      }
    } else {
      // A stock thread runs S1 (the end falls during it) and is stopped by the check after it.
      softly.assertThat(reported)
          .as("%s: stock stops after the sample the end fell in", kind)
          .containsExactly("S1");
    }
    softly.assertAll();
  }

  /**
   * A scheduled thread whose end is far enough away not to land before
   * {@link SchedulerArmingSampler} moves it, and close enough that a thread nothing stops still
   * shows up as hung.
   */
  private static Consumer<JMeterThread> scheduledButNotYetArmed() {
    return AsyncScenarioRunner.schedulerEndingIn(RUN_TIMEOUT_MILLIS * 2);
  }

  /** Moves the thread's scheduled end to {@code millisFromNow} the first time it is sampled. */
  private static final class SchedulerArmingSampler extends MarkerSampler {

    private static final long serialVersionUID = 1L;

    private final long millisFromNow;
    private volatile long armedEnd;

    private SchedulerArmingSampler(String name, long millisFromNow) {
      super(name);
      this.millisFromNow = millisFromNow;
    }

    @Override
    public SampleResult sample(Entry entry) {
      SampleResult result = super.sample(entry);
      if (invocations() == 1) {
        armedEnd = System.currentTimeMillis() + millisFromNow;
        JMeterContextService.getContext().getThread().setEndTime(armedEnd);
      }
      return result;
    }

    long armedEnd() {
      return armedEnd;
    }
  }

  /**
   * Keeps its sample busy until a little after the end {@link SchedulerArmingSampler} armed, and
   * records whether it started before that end - i.e. whether the scenario really was the end
   * going by during post-processing.
   */
  private static final class SleepPastArmedEndPostProcessor extends AbstractTestElement
      implements PostProcessor {

    private static final long serialVersionUID = 1L;

    private final transient SchedulerArmingSampler arming;
    private final long marginMillis;
    private volatile Boolean startedBeforeTheEnd;

    private SleepPastArmedEndPostProcessor(SchedulerArmingSampler arming, long marginMillis) {
      this.arming = arming;
      this.marginMillis = marginMillis;
      setName("sleep past the end");
    }

    @Override
    public void process() {
      long now = System.currentTimeMillis();
      if (startedBeforeTheEnd == null) {
        startedBeforeTheEnd = now < arming.armedEnd();
      }
      long wait = arming.armedEnd() + marginMillis - now;
      if (wait <= 0) {
        return;
      }
      try {
        Thread.sleep(wait);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    boolean startedBeforeTheEnd() {
      return Boolean.TRUE.equals(startedBeforeTheEnd);
    }
  }

  /** Moves the thread's scheduled end to {@code millisFromNow} the first time it runs. */
  private static final class EndArmingPostProcessor extends AbstractTestElement
      implements PostProcessor {

    private static final long serialVersionUID = 1L;

    private final long millisFromNow;
    private volatile long armedEnd;

    private EndArmingPostProcessor(long millisFromNow) {
      this.millisFromNow = millisFromNow;
      setName("arm the end");
    }

    @Override
    public void process() {
      if (armedEnd == 0) {
        armedEnd = System.currentTimeMillis() + millisFromNow;
        JMeterContextService.getContext().getThread().setEndTime(armedEnd);
      }
    }

    long armedEnd() {
      return armedEnd;
    }
  }

  /** Drops every sample in its scope from the results, as {@code prev.setIgnore()} does. */
  private static final class IgnoringPostProcessor extends AbstractTestElement
      implements PostProcessor {

    private static final long serialVersionUID = 1L;

    private IgnoringPostProcessor() {
      setName("ignore");
    }

    @Override
    public void process() {
      SampleResult previous = JMeterContextService.getContext().getPreviousResult();
      if (previous != null) {
        previous.setIgnore();
      }
    }
  }
}
