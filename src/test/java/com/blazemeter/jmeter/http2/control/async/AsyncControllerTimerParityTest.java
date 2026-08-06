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
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * Issue #108 — "Timers are doubled (sometimes tripled) inside 'bzm - HTTP Async Controller'".
 *
 * <p>Every scenario is executed twice over the very same tree shape: once with the stock JMeter
 * Transaction Controller, which defines correct behaviour, and once with the bzm HTTP Async
 * Controller. A timer must fire exactly as many times in the second run as in the first, because
 * both controllers run each sampler once. The async controller reaches its result through two
 * JMeterThread passes over the same sampler (dispatch, then completion), so any hole in the
 * suppression of the second pass shows up here as extra invocations.
 *
 * <p>Each scenario is checked with "Generate parent sample" both off and on, because the two modes
 * take different code paths through HTTP2Sampler.sample and are reported to behave differently.
 *
 * <p>These tests are a specification of the intended behaviour: failures are the reproduction.
 */
public class AsyncControllerTimerParityTest extends HTTP2TestBase {

  private static final long FAST = 20L;

  @Test
  public void t1TimerDirectlyUnderTheControllerAppliesOncePerSampler() {
    assertParity("T1 controller[timer, S1, S2]", 1, fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.timer("timer-under-controller")),
            node(fixture.http("S1", FAST)),
            node(fixture.http("S2", FAST)))
    });
  }

  @Test
  public void t2TimerUnderASingleSamplerAppliesOnlyToThatSampler() {
    assertParity("T2 controller[S1[timer], S2]", 1, fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.http("S1", FAST), node(fixture.timer("timer-under-S1"))),
            node(fixture.http("S2", FAST)))
    });
  }

  @Test
  public void t3TimerUnderControllerAlsoAppliesToSamplersOfANestedTransactionController() {
    assertParity("T3 controller[timer, S1, tx(parent)[S3, S4]]", 1,
        fixture -> new AsyncScenarioRunner.Node[]{
            node(fixture.controller("controller"),
                node(fixture.timer("timer-under-controller")),
                node(fixture.http("S1", FAST)),
                node(fixture.stockTransaction("tx", true),
                    node(fixture.http("S3", FAST)),
                    node(fixture.http("S4", FAST))))
        });
  }

  @Test
  public void t4TimerAboveTheControllerAppliesOncePerSampler() {
    assertParity("T4 threadGroup[timer, controller[S1, S2]]", 1,
        fixture -> new AsyncScenarioRunner.Node[]{
            node(fixture.timer("timer-above-controller")),
            node(fixture.controller("controller"),
                node(fixture.http("S1", FAST)),
                node(fixture.http("S2", FAST)))
        });
  }

  @Test
  public void t5TimerSurvivesTwoLoopIterationsWithoutBeingLostOrDuplicated() {
    assertParity("T5 loop x2 controller[timer, S1]", 2,
        fixture -> new AsyncScenarioRunner.Node[]{
            node(fixture.controller("controller"),
                node(fixture.timer("timer-under-controller")),
                node(fixture.http("S1", FAST)))
        });
  }

  @Test
  public void t6TimersAtTwoLevelsBothApplyTheRightNumberOfTimes() {
    assertParity("T6 controller[S1[timerA], timerB, S2]", 1,
        fixture -> new AsyncScenarioRunner.Node[]{
            node(fixture.controller("controller"),
                node(fixture.http("S1", FAST), node(fixture.timer("timer-under-S1"))),
                node(fixture.timer("timer-under-controller")),
                node(fixture.http("S2", FAST)))
        });
  }

  @Test
  public void t7NonHttpSamplerInTheMiddleDoesNotChangeTimerCounts() {
    assertParity("T7 controller[timer, S1, marker, S2]", 1,
        fixture -> new AsyncScenarioRunner.Node[]{
            node(fixture.controller("controller"),
                node(fixture.timer("timer-under-controller")),
                node(fixture.http("S1", FAST)),
                node(new MarkerSampler("marker")),
                node(fixture.http("S2", FAST)))
        });
  }

  @Test
  public void t8TimerUnderANestedTransactionControllerAppliesOncePerNestedSampler() {
    assertParity("T8 controller[S1, tx(parent)[timer, S3, S4]]", 1,
        fixture -> new AsyncScenarioRunner.Node[]{
            node(fixture.controller("controller"),
                node(fixture.http("S1", FAST)),
                node(fixture.stockTransaction("tx", true),
                    node(fixture.timer("timer-under-tx")),
                    node(fixture.http("S3", FAST)),
                    node(fixture.http("S4", FAST))))
        });
  }

  @Test
  public void t9TimerIsNotLostWhenAnIterationRestartsAfterAFailedAssertion() {
    assertParity("T9 loop x2, failing assertion restarts the iteration",
        2, AbstractThreadGroup.ON_SAMPLE_ERROR_START_NEXT_LOOP,
        fixture -> {
          fixture.transport().spec("S2").body("<html>something else</html>");
          return new AsyncScenarioRunner.Node[]{
              node(fixture.controller("controller"),
                  node(fixture.timer("timer-under-controller")),
                  node(fixture.http("S1", FAST)),
                  node(fixture.http("S2", FAST),
                      node(fixture.containsAssertion("assert-marker", "expected marker"))))
          };
        });
  }

  @Test
  public void preProcessorUnderTheControllerRunsOncePerSampler() {
    TreeShape shape = fixture -> new AsyncScenarioRunner.Node[]{
        node(fixture.controller("controller"),
            node(fixture.pre("pre-under-controller")),
            node(fixture.http("S1", FAST)),
            node(fixture.http("S2", FAST)))
    };
    SoftAssertions softly = new SoftAssertions();
    comparePreProcessors(softly, "pre-processor under controller", shape,
        ControllerKind.STOCK_SIMPLE, ControllerKind.ASYNC_FLAT);
    comparePreProcessors(softly, "pre-processor under controller", shape,
        ControllerKind.STOCK_TX_PARENT, ControllerKind.ASYNC_PARENT);
    softly.assertAll();
  }

  private void assertParity(String label, int loops, TreeShape shape) {
    assertParity(label, loops, AbstractThreadGroup.ON_SAMPLE_ERROR_CONTINUE, shape);
  }

  private void assertParity(String label, int loops, String onSampleError, TreeShape shape) {
    SoftAssertions softly = new SoftAssertions();
    compareTimers(softly, label, loops, onSampleError, shape,
        ControllerKind.STOCK_SIMPLE, ControllerKind.ASYNC_FLAT);
    compareTimers(softly, label, loops, onSampleError, shape,
        ControllerKind.STOCK_TX_PARENT, ControllerKind.ASYNC_PARENT);
    softly.assertAll();
  }

  private void compareTimers(SoftAssertions softly, String label, int loops, String onSampleError,
                             TreeShape shape, ControllerKind baselineKind,
                             ControllerKind asyncKind) {
    Result baseline = execute(baselineKind, loops, onSampleError, shape);
    Result actual = execute(asyncKind, loops, onSampleError, shape);
    report(label, baselineKind, baseline, asyncKind, actual);

    softly.assertThat(actual.hung())
        .as("%s [%s]: the JMeter thread must finish", label, asyncKind)
        .isFalse();
    for (String timer : baseline.timerInvocations().keySet()) {
      softly.assertThat(actual.timerInvocations(timer))
          .as("%s [%s]: timer '%s' fired for %s; stock JMeter %s fires it for %s",
              label, asyncKind, timer, actual.timerSamplers(timer), baselineKind,
              baseline.timerSamplers(timer))
          .isEqualTo(baseline.timerInvocations(timer));
    }
  }

  private void comparePreProcessors(SoftAssertions softly, String label, TreeShape shape,
                                    ControllerKind baselineKind, ControllerKind asyncKind) {
    Result baseline = execute(baselineKind, 1,
        AbstractThreadGroup.ON_SAMPLE_ERROR_CONTINUE, shape);
    Result actual = execute(asyncKind, 1,
        AbstractThreadGroup.ON_SAMPLE_ERROR_CONTINUE, shape);
    report(label, baselineKind, baseline, asyncKind, actual);
    softly.assertThat(actual.preProcessorInvocations("pre-under-controller"))
        .as("%s [%s]: pre-processor invocations", label, asyncKind)
        .isEqualTo(baseline.preProcessorInvocations("pre-under-controller"));
  }

  private Result execute(ControllerKind kind, int loops, String onSampleError, TreeShape shape) {
    try (ScenarioFixture fixture = new ScenarioFixture(kind)) {
      ThreadGroup group = threadGroup("tg", loops, onSampleError);
      List<AsyncScenarioRunner.Node> nodes =
          new ArrayList<>(Arrays.asList(shape.build(fixture)));
      nodes.add(fixture.recorderNode());
      return fixture.run(group, nodes.toArray(new AsyncScenarioRunner.Node[0]));
    }
  }

  private void report(String label, ControllerKind baselineKind, Result baseline,
                      ControllerKind asyncKind, Result actual) {
    StringBuilder out = new StringBuilder();
    out.append("\n=== ").append(label).append(" | ").append(asyncKind)
        .append(" vs ").append(baselineKind).append(" ===\n");
    for (String timer : baseline.timerInvocations().keySet()) {
      out.append("  timer ").append(timer)
          .append(": stock=").append(baseline.timerInvocations(timer))
          .append(" async=").append(actual.timerInvocations(timer))
          .append("  stockFor=").append(baseline.timerSamplers(timer))
          .append("  asyncFor=").append(actual.timerSamplers(timer))
          .append('\n');
    }
    out.append("  stock tree: ").append(baseline.resultTree()).append('\n');
    out.append("  async tree: ").append(actual.resultTree()).append('\n');
    out.append("  async hung=").append(actual.hung())
        .append(" maxConcurrent=").append(actual.maxConcurrentInFlight()).append('\n');
    System.out.println(out);
  }
}
