package com.blazemeter.jmeter.http2.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.jmeter.control.TransactionSampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.SamplePackage;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the workaround for Apache JMeter #6237 / PR #6386: with "Generate parent sample", a finished
 * {@link TransactionSampler} keeps every child {@code responseData} reachable from
 * {@code TestCompiler.transactionControllerConfigMap} until the next transaction starts. Replacing
 * that sampler after the parent is done lets the finished tree be collected.
 */
public class ParentTransactionSampleReleaseTest extends HTTP2TestBase {

  @Before
  public void setUp() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Test
  public void replaceDoneTransactionSamplerDropsFinishedParentTree() throws Exception {
    HTTP2Controller controller = new HTTP2Controller();
    controller.setName("TG1 - Parallel HTTP2 Flow");
    controller.setGenerateParentSample(true);

    TransactionSampler finished = new TransactionSampler(controller, controller.getName());
    SampleResult heavyChild = new SampleResult();
    heavyChild.setSampleLabel("http2 GET Home (embedded)");
    heavyChild.setResponseData("x".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8));
    finished.addSubSamplerResult(heavyChild);
    markTransactionDone(finished);

    SamplePackage pack = newSamplePackage(finished);
    assertThat(finished.getTransactionResult().getSubResults())
        .as("precondition: the finished parent still holds the heavy child")
        .isNotEmpty();
    assertThat(finished.getTransactionResult().getSubResults()[0].getResponseData().length)
        .isEqualTo(1024 * 1024);

    assertThat(controller.replaceDoneTransactionSampler(pack)).isTrue();

    Sampler replacement = pack.getSampler();
    assertThat(replacement)
        .as("the package must point at a fresh TransactionSampler, not the finished one")
        .isInstanceOf(TransactionSampler.class)
        .isNotSameAs(finished);
    assertThat(((TransactionSampler) replacement).isTransactionDone()).isFalse();
    assertThat(((TransactionSampler) replacement).getTransactionResult().getSubResults())
        .as("the replacement must not inherit the finished parent's sub-results")
        .isEmpty();
  }

  @Test
  public void replaceSkipsInFlightTransactionSampler() {
    HTTP2Controller controller = new HTTP2Controller();
    controller.setName("in-flight");
    controller.setGenerateParentSample(true);

    TransactionSampler inFlight = new TransactionSampler(controller, controller.getName());
    SamplePackage pack = newSamplePackage(inFlight);

    assertThat(controller.replaceDoneTransactionSampler(pack)).isFalse();
    assertThat(pack.getSampler()).isSameAs(inFlight);
  }

  private static SamplePackage newSamplePackage(Sampler sampler) {
    SamplePackage pack = new SamplePackage(
        new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
        new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    pack.setSampler(sampler);
    return pack;
  }

  private static void markTransactionDone(TransactionSampler sampler) throws Exception {
    Method setDone = TransactionSampler.class.getDeclaredMethod("setTransactionDone");
    setDone.setAccessible(true);
    setDone.invoke(sampler);
    Field calls = TransactionSampler.class.getDeclaredField("calls");
    calls.setAccessible(true);
    calls.setInt(sampler, Math.max(1, calls.getInt(sampler)));
  }
}
