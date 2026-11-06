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
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
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

    private SchedulerArmingSampler(String name, long millisFromNow) {
      super(name);
      this.millisFromNow = millisFromNow;
    }

    @Override
    public SampleResult sample(Entry entry) {
      SampleResult result = super.sample(entry);
      if (invocations() == 1) {
        JMeterContextService.getContext().getThread()
            .setEndTime(System.currentTimeMillis() + millisFromNow);
      }
      return result;
    }
  }
}
