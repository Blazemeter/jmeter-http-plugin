package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.TransactionSampler;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterThreadMonitor;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jorphan.collections.HashTree;
import org.eclipse.jetty.client.Request;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Reproduces GitHub issue #93: async controller with generate parent sample and a child response
 * assertion must not report {@link AssertionResult#RESPONSE_WAS_NULL}.
 */
public class HTTP2AsyncParentSampleAssertionTest extends HTTP2TestBase {

  @org.junit.Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock
  private HTTP2JettyClient client;
  @Mock
  private Request jettyRequest;

  private HTTP2Sampler sampler;
  private HTTP2Controller asyncController;
  private ResponseAssertion assertion;
  private JMeterThread jmeterThread;
  private JMeterContext jmeterContext;
  private JMeterVariables threadVars;

  @Before
  public void setUp() throws Exception {
    sampler = new HTTP2Sampler(() -> client);
    sampler.setDomain("demoblaze.com");
    sampler.setProtocol("https");
    sampler.setPath("/");
    sampler.setMethod(HTTPConstants.GET);

    assertion = new ResponseAssertion();
    assertion.setName("Response Assertion");
    assertion.addTestString("About Us");
    assertion.setTestFieldResponseData();
    assertion.setToContainsType();

    HTTP2Controller asyncController = new HTTP2Controller();
    asyncController.setGenerateControllerSample(true);
    this.asyncController = asyncController;

    LoopController loop = new LoopController();
    loop.setLoops(1);
    loop.initialize();

    HashTree testTree = new HashTree();
    HashTree loopTree = testTree.add(loop);
    HashTree controllerTree = loopTree.add(asyncController);
    controllerTree.add(sampler);
    controllerTree.add(sampler, assertion);

    JMeterThreadMonitor monitor = thread -> { };
    jmeterThread = new JMeterThread(testTree, monitor, new ListenerNotifier());
    threadVars = getThreadVars(jmeterThread);
    jmeterContext = JMeterContextService.getContext();
    jmeterContext.setThread(jmeterThread);
    jmeterContext.setVariables(threadVars);

    AbstractThreadGroup threadGroup = mock(AbstractThreadGroup.class);
    when(threadGroup.getNumberOfThreads()).thenReturn(1);
    Field threadGroupField = JMeterThread.class.getDeclaredField("threadGroup");
    threadGroupField.setAccessible(true);
    threadGroupField.set(jmeterThread, threadGroup);
    jmeterContext.setThreadGroup(threadGroup);

    TestCompiler compiler = getCompiler(jmeterThread);
    TestCompiler.initialize();
    testTree.traverse(compiler);

    lenient().when(client.getMaxBufferSize()).thenReturn(2 * 1024 * 1024);
    lenient().when(client.getRequestTimeout()).thenReturn(60_000);
    lenient().when(client.sampleAsync(any(), any(), any())).thenReturn(jettyRequest);
    lenient().when(jettyRequest.getURI())
        .thenReturn(new URI("https://demoblaze.com/"));
    lenient().when(client.sampleFromListener(any(), any(), anyBoolean(), anyInt(), any()))
        .thenAnswer(invocation -> {
          HTTPSampleResult result = invocation.getArgument(1);
          if (result.getStartTime() == 0) {
            result.sampleStart();
          }
          result.setResponseData("<html>About Us</html>", null);
          result.setResponseCode("200");
          result.setResponseMessage("OK");
          result.setSuccessful(true);
          if (result.getEndTime() == 0) {
            result.sampleEnd();
          }
          return result;
        });

    asyncController.initialize();
    Sampler fromController = asyncController.next();
    assertThat(fromController).isSameAs(sampler);
    assertThat(sampler.isAsyncParentSampleEnabled()).isTrue();
    assertThat(sampler.isSyncRequest()).isFalse();
  }

  @Test
  public void asyncParentSampleRunsChildAssertionOnCompletionPass() throws Exception {
    runAsyncParentCompletionScenario(true);
  }

  /**
   * Guards {@link HTTP2Sampler#resolveSamplePackageForAsyncCompletion}: when the compiler map no
   * longer contains the sampler, the pack must still be resolved from {@code PACKAGE_OBJECT}.
   */
  @Test
  public void asyncParentSampleResolvesSamplePackageFromThreadVarsWhenCompilerMapMisses()
      throws Exception {
    executeSamplePackage(sampler);
    assertThat(sampler.getFutureResponseListener()).isNotNull();

    SamplePackage pack = (SamplePackage) threadVars.getObject(JMeterThread.PACKAGE_OBJECT);
    assertThat(pack).isNotNull();
    removeSamplerFromCompilerMap(sampler);
    threadVars.putObject(JMeterThread.PACKAGE_OBJECT, pack);

    assertThat(sampler.sample(null)).isNull();

    SampleResult registeredChild = getRegisteredAsyncParentChild();
    assertThat(registeredChild.getAssertionResults()).isNotEmpty();
    assertThat(registeredChild.getFirstAssertionFailureMessage())
        .isNotEqualTo(AssertionResult.RESPONSE_WAS_NULL);
    assertThat(registeredChild.isSuccessful()).isTrue();
  }

  private void runAsyncParentCompletionScenario(boolean suppressPreProcessorsBeforeCompletion)
      throws Exception {
    executeSamplePackage(sampler);
    assertThat(sampler.getFutureResponseListener()).isNotNull();

    if (suppressPreProcessorsBeforeCompletion) {
      sampler.suppressPreProcessorsOnce();
    } else {
      removeSamplerFromCompilerMap(sampler);
    }

    executeSamplePackage(sampler);

    SampleResult registeredChild = getRegisteredAsyncParentChild();
    assertThat(registeredChild).isNotNull();
    assertThat(registeredChild.getAssertionResults()).isNotEmpty();
    assertThat(registeredChild.getFirstAssertionFailureMessage())
        .isNotEqualTo(AssertionResult.RESPONSE_WAS_NULL);
    assertThat(registeredChild.isSuccessful()).isTrue();
  }

  private void removeSamplerFromCompilerMap(HTTP2Sampler target) throws Exception {
    TestCompiler compiler = getCompiler(jmeterThread);
    Field mapField = TestCompiler.class.getDeclaredField("samplerConfigMap");
    mapField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.Map<Object, SamplePackage> map =
        (java.util.Map<Object, SamplePackage>) mapField.get(compiler);
    map.remove(target);
    assertThat(map.get(target)).isNull();
  }

  @Test
  public void parentSampleExecutionDoesNotReRunChildAssertionsOnEmptyParentBody() throws Exception {
    runAsyncParentCompletionScenario(true);
    threadVars.putObject(JMeterThread.PACKAGE_OBJECT, null);

    Method buildParent = HTTP2Controller.class.getDeclaredMethod("buildParentSamplerIfNeeded");
    buildParent.setAccessible(true);
    Sampler parentSampler = (Sampler) buildParent.invoke(asyncController);
    assertThat(parentSampler).isNotNull();
    assertThat(parentSampler.getClass().getName())
        .contains("HTTP2Controller$ParentSample");

    executeSamplePackage(parentSampler);

    SamplePackage parentPack = getCompiler(jmeterThread).configureSampler(parentSampler);
    assertThat(parentPack.getAssertions()).isEmpty();

    SampleResult parentResult = jmeterContext.getPreviousResult();
    assertThat(parentResult).isNotNull();
    assertThat(parentResult.getFirstAssertionFailureMessage()).isNull();
    assertThat(parentResult.isSuccessful()).isTrue();
  }

  @Test
  public void childAssertionsRunAgainstEmptyParentBodyReportResponseWasNull() throws Exception {
    HTTPSampleResult parentBody = new HTTPSampleResult();
    parentBody.setSampleLabel("parent");
    parentBody.sampleStart();
    parentBody.setResponseData(new byte[0]);
    parentBody.setSuccessful(true);
    parentBody.sampleEnd();

    SamplePackage childPack = getCompiler(jmeterThread).configureSampler(sampler);
    AsyncCompletionSamplePipeline.runAfterAsyncSample(parentBody, childPack, jmeterContext);

    assertThat(parentBody.getFirstAssertionFailureMessage())
        .isEqualTo(AssertionResult.RESPONSE_WAS_NULL);
    assertThat(parentBody.isSuccessful()).isFalse();
  }

  @Test
  public void withoutSamplePackageAssertionsReportResponseWasNull() throws Exception {
    HTTPSampleResult bareResult = new HTTPSampleResult();
    bareResult.setSampleLabel("child");
    bareResult.setHTTPMethod(HTTPConstants.GET);
    bareResult.sampleStart();
    bareResult.setResponseData("<html>About Us</html>", null);
    bareResult.setSuccessful(true);
    bareResult.sampleEnd();

    AsyncCompletionSamplePipeline.runAfterAsyncSample(
        bareResult, new SamplePackage(null, null, null, null, null, null, null),
        jmeterContext);

    assertThat(bareResult.getAssertionResults()).isEmpty();

    AssertionResult direct = assertion.getResult(bareResult);
    assertThat(direct.isFailure()).isFalse();

    bareResult.setResponseData(new byte[0]);
    direct = assertion.getResult(bareResult);
    assertThat(direct.isFailure()).isTrue();
    assertThat(direct.getFailureMessage()).isEqualTo(AssertionResult.RESPONSE_WAS_NULL);
  }

  private void executeSamplePackage(Sampler current) throws Exception {
    Method executeSamplePackage = JMeterThread.class.getDeclaredMethod(
        "executeSamplePackage",
        Sampler.class,
        TransactionSampler.class,
        SamplePackage.class,
        JMeterContext.class);
    executeSamplePackage.setAccessible(true);
    executeSamplePackage.invoke(jmeterThread, current, null, null, jmeterContext);
  }

  @SuppressWarnings("unchecked")
  private SampleResult getRegisteredAsyncParentChild() throws Exception {
    Field contextField = HTTP2Controller.class.getDeclaredField("ASYNC_PARENT_CONTEXT");
    contextField.setAccessible(true);
    ThreadLocal<Object> contextHolder = (ThreadLocal<Object>) contextField.get(null);
    Object context = contextHolder.get();
    assertThat(context).isNotNull();

    Field childrenField = context.getClass().getDeclaredField("children");
    childrenField.setAccessible(true);
    List<SampleResult> children = (List<SampleResult>) childrenField.get(context);
    assertThat(children).hasSize(1);
    return children.get(0);
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
