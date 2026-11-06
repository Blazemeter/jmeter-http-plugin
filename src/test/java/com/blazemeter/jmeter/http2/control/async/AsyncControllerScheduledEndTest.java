package com.blazemeter.jmeter.http2.control.async;

import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.node;
import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.threadGroup;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.ControllerKind;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * BlazeMeter HTTP issue 169: with the Thread Group looping (as every duration-limited test does),
 * a scheduled end that falls while this controller holds it off must still end the thread.
 *
 * <p>The hold is given back when the iteration ends ({@code reInitialize}), and the Thread Group's
 * {@code LoopController} starts the next iteration inside that same {@code next()} call
 * ({@code nextIsNull} returns {@code next()}). The controller's first dispatch of the new
 * iteration then found the end already gone by and held it again, before {@code JMeterThread}
 * could check it: the thread never stopped, one request per iteration past the end. A stock
 * sampler is checked after every sample, so a stock thread stops right after the sample the end
 * fell in; that is the behaviour asserted here. The single-iteration scenarios
 * of {@code AsyncControllerTransactionTimingTest} (T5, T6) cannot show it: their loop ends anyway.
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
   */
  @Test
  public void samplerBeforeTheControllerMustNotRunAgainAfterTheScheduledEnd() {
    Result actual;
    MarkerSampler before = new MarkerSampler("X");
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_FLAT)) {
      ThreadGroup group = threadGroup("tg", -1);
      List<AsyncScenarioRunner.Node> nodes = new ArrayList<>(Arrays.asList(
          node(before),
          node(fixture.controller("controller"),
              node(fixture.http("S1", LATENCY)),
              node(fixture.http("S2", LATENCY)))));
      nodes.add(fixture.recorderNode());
      actual = fixture.run(group, RUN_TIMEOUT_MILLIS,
          AsyncScenarioRunner.schedulerEndingIn(LATENCY / 4),
          nodes.toArray(new AsyncScenarioRunner.Node[0]));
    }
    System.out.println("X first dispatched: " + actual.dispatchOrder() + " X runs: "
        + before.invocations() + " hung: " + actual.hung());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the thread must stop at its scheduled end").isFalse();
    softly.assertThat(actual.dispatchOrder()).as("only the first iteration's requests")
        .isIn(FIRST_ITERATION_ONLY);
    softly.assertThat(before.invocations())
        .as("the sampler before the controller ran before the end only, as with a stock "
            + "Transaction Controller")
        .isEqualTo(1);
    softly.assertAll();
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
    List<String> reported = new ArrayList<>();
    for (SampleResult result : actual.topLevelResults()) {
      reported.add(result.getSampleLabel());
    }
    System.out.println(kind + " dispatched: " + actual.dispatchOrder() + " reported: " + reported
        + " hung: " + actual.hung());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung())
        .as("%s: the thread must stop at its scheduled end, not loop on past it", kind)
        .isFalse();
    if (kind.isAsync()) {
      softly.assertThat(actual.dispatchOrder())
          .as("%s: only the iteration the end fell in may send requests", kind)
          .isIn(FIRST_ITERATION_ONLY);
    } else {
      // A stock thread runs S1 (the end falls during it) and is stopped by the check after it.
      softly.assertThat(reported)
          .as("%s: stock stops after the sample the end fell in", kind)
          .containsExactly("S1");
    }
    softly.assertAll();
  }
}
