package com.blazemeter.jmeter.http2.control.async;

import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.node;
import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.threadGroup;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.ControllerKind;
import com.blazemeter.jmeter.http2.control.async.ScenarioFixture.Result;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.FindTestElementsUpToRootTraverser;
import org.apache.jmeter.threads.ThreadGroup;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;

/**
 * Control-flow integrity of the async controller: iterating over a child controller that has already
 * finished, a sampler that fails before the plugin's {@code sample(URL,…)} is ever entered, queue
 * state left behind by an aborted iteration, and whether the samplers the controller hands to
 * JMeterThread can still be located in the test tree.
 *
 * <p>Those are the paths where the controller keeps its own bookkeeping (the pending-request queue,
 * the backup of its child list, the synthesised parent sampler) alongside the bookkeeping
 * {@link GenericController} already does, and the two can disagree.
 *
 * <p>Timeouts here are short on purpose: a runaway control flow must fail fast.
 */
public class AsyncControllerControlFlowTest extends HTTP2TestBase {

  private static final long SHORT_TIMEOUT_MILLIS = 6_000L;

  @Test
  public void anEmptyChildControllerAsFirstElementMustNotKillTheThread() {
    Result actual;
    HTTP2Controller controller;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      controller = fixture.asyncController("controller", true);
      GenericController empty = new GenericController();
      empty.setName("empty");
      ThreadGroup group = threadGroup("tg", 1);
      actual = fixture.run(group, SHORT_TIMEOUT_MILLIS,
          node(controller,
              node(empty),
              node(fixture.http("S1", 20))),
          fixture.recorderNode(),
          fixture.monitorNode());
    }
    System.out.println("\n=== empty Simple Controller as first child ==="
        + "\n  hung=" + actual.hung()
        + " uncaught=" + describe(actual.uncaught())
        + "\n  tree=" + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.uncaught())
        .as("an empty child controller must not blow up the thread; stock JMeter just removes it "
            + "once and moves on to the next child")
        .isNull();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.resultTree())
        .as("S1 must still run and be reported under the parent sample")
        .containsExactly("controller", "controller > S1");
    softly.assertAll();
  }

  @Test
  public void aFinishedChildControllerAsFirstElementMustNotKillTheThread() {
    Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      LoopController innerLoop = new LoopController();
      innerLoop.setName("inner-loop");
      innerLoop.setLoops(1);
      innerLoop.setContinueForever(false);
      ThreadGroup group = threadGroup("tg", 2);
      actual = fixture.run(group, SHORT_TIMEOUT_MILLIS,
          node(fixture.controller("controller"),
              node(innerLoop, node(new MarkerSampler("marker"))),
              node(fixture.http("S1", 20))),
          fixture.recorderNode(),
          fixture.monitorNode());
    }
    System.out.println("\n=== finished Loop Controller as first child, 2 iterations ==="
        + "\n  hung=" + actual.hung()
        + " uncaught=" + describe(actual.uncaught())
        + "\n  tree=" + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.uncaught())
        .as("a child controller that reports done must be skipped, not resurrected by the child "
            + "list restore")
        .isNull();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(actual.dispatchOrder())
        .as("S1 must run once per thread-group iteration")
        .containsExactly("S1", "S1");
    softly.assertAll();
  }

  @Test
  public void aSamplerThatFailsBeforeDispatchMustNotDisableTheControllerForTheRestOfTheThread() {
    Result actual;
    HTTP2Controller controller;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      controller = fixture.asyncController("controller", true);
      HTTP2Sampler bad = fixture.http("S_BAD", 20);
      // An unknown protocol makes HTTPSamplerBase.getUrl() throw, so the plugin's
      // sample(URL, ...) is never entered and the sampler never gets a response listener,
      // exactly like an unresolved ${variable} in the domain.
      bad.setProtocol("bzm-unknown");
      ThreadGroup group = threadGroup("tg", 3);
      actual = fixture.run(group, SHORT_TIMEOUT_MILLIS,
          node(controller,
              node(bad),
              node(fixture.http("S_GOOD", 20)),
              node(new MarkerSampler("marker"))),
          fixture.recorderNode(),
          fixture.monitorNode());
    }
    List<String> pending = pendingQueueLabels(controller);
    System.out.println("\n=== sampler failing before dispatch, 3 iterations ==="
        + "\n  hung=" + actual.hung() + " uncaught=" + describe(actual.uncaught())
        + "\n  dispatched=" + actual.dispatchOrder()
        + "\n  tree=" + actual.resultTree()
        + "\n  pending queue at end=" + pending);

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(pending)
        .as("a sampler that never got a response listener must not stay queued forever, because "
            + "the queue head is what every later completion check looks at")
        .isEmpty();
    softly.assertThat(countOf(actual.resultTree(), "controller > S_GOOD"))
        .as("S_GOOD must be reported in each of the 3 iterations; a bad sibling must not silently "
            + "disable the controller")
        .isEqualTo(3);
    softly.assertAll();
  }

  @Test
  public void anAbortedIterationMustNotLeaveRequestsQueuedForTheNextOne() {
    Result actual;
    HTTP2Controller controller;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      controller = fixture.asyncController("controller", true);
      fixture.transport().spec("S1").body("<html>expected marker</html>");
      fixture.transport().spec("S2").body("<html>something else</html>");
      fixture.transport().spec("S3").body("<html>expected marker</html>");
      ThreadGroup group =
          threadGroup("tg", 2, AbstractThreadGroup.ON_SAMPLE_ERROR_START_NEXT_LOOP);
      actual = fixture.run(group, SHORT_TIMEOUT_MILLIS,
          node(controller,
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 30),
                  node(fixture.containsAssertion("assert-marker", "expected marker"))),
              node(fixture.http("S3", 40))),
          fixture.recorderNode(),
          fixture.monitorNode());
    }
    List<String> pending = pendingQueueLabels(controller);
    System.out.println("\n=== aborted iteration (Start Next Thread Loop), 2 iterations ==="
        + "\n  dispatched=" + actual.dispatchOrder()
        + "\n  completed=" + actual.completionOrder()
        + "\n  pending queue at end=" + pending
        + "\n  tree=" + actual.resultTree());

    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(actual.hung()).as("the JMeter thread must finish").isFalse();
    softly.assertThat(pending)
        .as("aborting an iteration must drain or discard the in-flight queue; leftovers get "
            + "re-dispatched next iteration, doubling the load on the system under test")
        .isEmpty();
    softly.assertThat(actual.dispatchOrder())
        .as("each iteration dispatches its children once, in tree order")
        .containsExactly("S1", "S2", "S3", "S1", "S2", "S3");
    // Completion order is deliberately not asserted here: the request abandoned by the aborted
    // iteration completes on the transport's own schedule, so it can be recorded before or after the
    // next iteration's first completion. The invariant that matters is the empty queue above.
    softly.assertAll();
  }

  @Test
  public void everySamplerHandedToJmeterThreadMustBeLocatableInTheTestTree() {
    Result actual;
    try (ScenarioFixture fixture = new ScenarioFixture(ControllerKind.ASYNC_PARENT)) {
      ThreadGroup group = threadGroup("tg", 1);
      actual = fixture.run(group, SHORT_TIMEOUT_MILLIS,
          node(fixture.controller("controller"),
              node(fixture.http("S1", 20)),
              node(fixture.http("S2", 40))),
          fixture.recorderNode(),
          fixture.monitorNode());
    }

    SoftAssertions softly = new SoftAssertions();
    List<String> unreachable = new ArrayList<>();
    for (Sampler sampler : actual.executedSamplers()) {
      FindTestElementsUpToRootTraverser traverser =
          new FindTestElementsUpToRootTraverser(sampler);
      actual.tree().traverse(traverser);
      List<Controller> toRoot = traverser.getControllersToRoot();
      if (toRoot.isEmpty()) {
        unreachable.add(sampler.getName() + " (" + sampler.getClass().getSimpleName() + ")");
      }
    }
    System.out.println("\n=== samplers reachable from the test tree ==="
        + "\n  executed=" + describeSamplers(actual.executedSamplers())
        + "\n  unreachable=" + unreachable);

    softly.assertThat(actual.executedSamplers())
        .as("the monitor must have seen the requests and the synthesised parent sampler")
        .isNotEmpty();
    softly.assertThat(unreachable)
        .as("JMeterThread resolves break / continue / Start Next Loop by finding the current "
            + "sampler in the test tree (FindTestElementsUpToRootTraverser matches by identity), "
            + "so a sampler that is not in the tree makes those actions no-ops")
        .isEmpty();
    softly.assertAll();
  }

  @SuppressWarnings("unchecked")
  private static List<String> pendingQueueLabels(HTTP2Controller controller) {
    List<String> labels = new ArrayList<>();
    try {
      Field field = HTTP2Controller.class.getDeclaredField("http2SamplesSync");
      field.setAccessible(true);
      Object value = field.get(controller);
      if (value instanceof List) {
        for (Object sampler : (List<Object>) value) {
          labels.add(sampler instanceof Sampler
              ? ((Sampler) sampler).getName()
              : String.valueOf(sampler));
        }
      }
    } catch (Exception e) {
      labels.add("<unavailable: " + e.getClass().getSimpleName() + ">");
    }
    return labels;
  }

  private static int countOf(List<String> lines, String value) {
    int count = 0;
    for (String line : lines) {
      if (value.equals(line)) {
        count++;
      }
    }
    return count;
  }

  private static String describe(Throwable throwable) {
    if (throwable == null) {
      return "<none>";
    }
    return throwable.getClass().getName() + ": " + throwable.getMessage();
  }

  private static String describeSamplers(List<Sampler> samplers) {
    List<String> out = new ArrayList<>();
    for (Sampler sampler : samplers) {
      out.add(sampler.getName() + "(" + sampler.getClass().getSimpleName() + ")");
    }
    return out.toString();
  }
}
