package com.blazemeter.jmeter.http2.control.async;

import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.asyncController;
import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.node;
import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.threadGroup;
import static com.blazemeter.jmeter.http2.control.async.AsyncScenarioRunner.transactionController;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Validates the harness itself before it is used to characterise defects: a real JMeterThread runs,
 * the stub transport completes real listeners, results reach a real listener, and the stock
 * Transaction Controller baseline behaves as documented in the JMeter sources.
 */
public class AsyncScenarioRunnerSmokeTest extends HTTP2TestBase {

  private StubAsyncTransport transport;
  private RecordingSampleListener listener;

  @Before
  public void setUp() {
    transport = new StubAsyncTransport();
    listener = new RecordingSampleListener();
    listener.setName("recorder");
  }

  @After
  public void tearDown() {
    transport.close();
  }

  @Test
  public void asyncControllerRunsBothSamplesInParallelAndReportsThemIndividually() {
    HTTP2Sampler first = transport.sampler("S1");
    HTTP2Sampler second = transport.sampler("S2");
    transport.spec("S1").latency(200);
    transport.spec("S2").latency(200);
    HTTP2Controller controller = asyncController("async", false);
    ThreadGroup group = threadGroup("tg", 1);

    AsyncScenarioRunner.Outcome outcome = AsyncScenarioRunner.run(group,
        node(controller, node(first), node(second)),
        node(listener));

    assertThat(outcome.hung()).isFalse();
    assertThat(transport.dispatchOrder()).containsExactly("S1", "S2");
    assertThat(transport.maxConcurrentInFlight())
        .as("both requests must be in flight at the same time")
        .isEqualTo(2);
    assertThat(listener.topLevelLabels()).containsExactly("S1", "S2");
  }

  @Test
  public void stockTransactionControllerParentSampleNestsItsChildren() {
    HTTP2Sampler first = transport.sampler("S1");
    HTTP2Sampler second = transport.sampler("S2");
    transport.spec("S1").latency(10);
    transport.spec("S2").latency(10);
    TransactionController controller = transactionController("tx", true);
    ThreadGroup group = threadGroup("tg", 1);

    AsyncScenarioRunner.Outcome outcome = AsyncScenarioRunner.run(group,
        node(controller, node(first), node(second)),
        node(listener));

    assertThat(outcome.hung()).isFalse();
    assertThat(listener.topLevelLabels())
        .as("only the transaction parent reaches the listener")
        .containsExactly("tx");
    assertThat(listener.resultTree()).containsExactly("tx", "tx > S1", "tx > S2");
    assertThat(transport.syncSampled()).containsExactly("S1", "S2");
  }

  @Test
  public void embeddedResourceSubResultsSurviveIntoTheStockTransactionParent() {
    HTTP2Sampler first = transport.sampler("S1");
    transport.spec("S1").latency(5).embeddedResources("S1-logo.png");
    TransactionController controller = transactionController("tx", true);
    ThreadGroup group = threadGroup("tg", 1);

    AsyncScenarioRunner.run(group,
        node(controller, node(first)),
        node(listener));

    assertThat(listener.resultTree())
        .containsExactly("tx", "tx > S1", "tx > S1 > S1-logo.png");
  }
}
