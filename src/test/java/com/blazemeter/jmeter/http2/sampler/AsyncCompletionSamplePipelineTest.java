package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterThreadMonitor;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jorphan.collections.HashTree;
import org.junit.Test;

public class AsyncCompletionSamplePipelineTest {

  private static class VarPostProcessor extends AbstractTestElement implements PostProcessor {
    @Override
    public void process() {
      JMeterContextService.getContext().getVariables().put("ranPost", "1");
    }
  }

  @Test
  public void runsAssertionsAndPostProcessorsLikeJMeterThread() throws Exception {
    JMeterTestUtils.setupJmeterEnv();

    HTTP2Sampler sampler = new HTTP2Sampler();
    VarPostProcessor post = new VarPostProcessor();

    ResponseAssertion assertion = new ResponseAssertion();
    assertion.addTestString("About Us");
    assertion.setTestFieldResponseData();

    HashTree testTree = new HashTree();
    LoopController controller = new LoopController();
    controller.setLoops(1);
    controller.initialize();
    HashTree controllerTree = testTree.add(controller);
    controllerTree.add(sampler);
    controllerTree.add(sampler, post);
    controllerTree.add(sampler, assertion);

    JMeterThreadMonitor monitor = thread -> { };
    JMeterThread thread = new JMeterThread(testTree, monitor, new ListenerNotifier());

    JMeterVariables threadVars = getThreadVars(thread);
    JMeterContextService.getContext().setThread(thread);
    JMeterContextService.getContext().setVariables(threadVars);

    TestCompiler compiler = getCompiler(thread);
    testTree.traverse(compiler);
    SamplePackage samplePackage = compiler.configureSampler(sampler);

    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("t");
    result.setHTTPMethod(HTTPConstants.GET);
    result.sampleStart();
    result.setResponseData("About Us and more", null);
    result.setSuccessful(true);
    result.sampleEnd();

    AsyncCompletionSamplePipeline.runAfterAsyncSample(
        result, samplePackage, JMeterContextService.getContext());

    assertThat(threadVars.get("ranPost")).isEqualTo("1");
    assertThat(threadVars.get(JMeterThread.LAST_SAMPLE_OK)).isEqualTo("true");
    assertThat(result.getAssertionResults()).isNotEmpty();
    assertThat(result.isSuccessful()).isTrue();
  }

  private static JMeterVariables getThreadVars(JMeterThread thread) throws Exception {
    Field f = JMeterThread.class.getDeclaredField("threadVars");
    f.setAccessible(true);
    return (JMeterVariables) f.get(thread);
  }

  private static TestCompiler getCompiler(JMeterThread thread) throws Exception {
    Field compilerField = JMeterThread.class.getDeclaredField("compiler");
    compilerField.setAccessible(true);
    return (TestCompiler) compilerField.get(thread);
  }
}
