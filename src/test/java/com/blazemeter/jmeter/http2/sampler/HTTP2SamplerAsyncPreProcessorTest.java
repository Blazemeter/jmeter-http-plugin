package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterThreadMonitor;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jorphan.collections.HashTree;
import org.junit.Test;

public class HTTP2SamplerAsyncPreProcessorTest {

  private static class NoOpPreProcessor extends AbstractTestElement implements PreProcessor {
    @Override
    public void process() {
      // no-op
    }
  }

  @Test
  public void shouldSuppressAndRestorePreProcessorsForAsyncCompletion() throws Exception {
    JMeterTestUtils.setupJmeterEnv();

    HTTP2Sampler sampler = new HTTP2Sampler();
    PreProcessor preProcessor = new NoOpPreProcessor();

    HashTree testTree = new HashTree();
    LoopController controller = new LoopController();
    controller.setLoops(1);
    controller.initialize();
    HashTree controllerTree = testTree.add(controller);
    controllerTree.add(sampler);
    controllerTree.add(sampler, preProcessor);

    JMeterThreadMonitor monitor = thread -> {
      // no-op
    };
    JMeterThread thread = new JMeterThread(testTree, monitor, new ListenerNotifier());
    JMeterContextService.getContext().setThread(thread);

    TestCompiler compiler = getCompiler(thread);
    testTree.traverse(compiler);
    SamplePackage samplePackage = compiler.configureSampler(sampler);
    assertThat(samplePackage.getPreProcessors()).hasSize(1);

    sampler.suppressPreProcessorsOnce();
    assertThat(samplePackage.getPreProcessors()).isEmpty();

    restoreSuppressedPreProcessors(sampler);
    assertThat(samplePackage.getPreProcessors()).hasSize(1);
  }

  private TestCompiler getCompiler(JMeterThread thread) throws Exception {
    Field compilerField = JMeterThread.class.getDeclaredField("compiler");
    compilerField.setAccessible(true);
    return (TestCompiler) compilerField.get(thread);
  }

  private void restoreSuppressedPreProcessors(HTTP2Sampler sampler) throws Exception {
    Method restoreMethod = HTTP2Sampler.class.getDeclaredMethod("restoreSuppressedPreProcessors");
    restoreMethod.setAccessible(true);
    restoreMethod.invoke(sampler);
  }
}
