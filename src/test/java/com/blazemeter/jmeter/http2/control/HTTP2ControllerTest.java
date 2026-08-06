package com.blazemeter.jmeter.http2.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.control.NextIsNullException;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSampler;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import java.lang.reflect.Method;
import java.net.URL;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.Request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HTTP2ControllerTest extends HTTP2TestBase {


  private static final String MAX_CONCURRENT_ASYNC_IN_CONTROLLER =
      "httpJettyClient.maxConcurrentAsyncInController";
  private HTTP2Controller http2Controller;
  private HTTP2Sampler firstSampler;
  private HTTP2Sampler secondSampler;
  private final HTTPSampler otherSamplerType = new HTTPSampler();
  @Mock
  private Request request;
  @Mock
  private HTTP2FutureResponseListener firstSamplerListener;
  @Mock
  private HTTP2FutureResponseListener secondSamplerListener;
  private final List<ScheduledExecutorService> simulationExecutors = new ArrayList<>();

  @Before
  public void setUp() {
    firstSampler = new HTTP2Sampler();
    secondSampler = new HTTP2Sampler();
    firstSampler.setFutureResponseListener(firstSamplerListener);
    secondSampler.setFutureResponseListener(secondSamplerListener);
    JMeterTestUtils.setupJmeterEnv();
  }

  @After
  public void tearDownSimulations() {
    for (ScheduledExecutorService executor : simulationExecutors) {
      executor.shutdownNow();
    }
    simulationExecutors.clear();
  }


  @Test
  public void shouldModifyAndRetrieveSamplerToRunAsyncWhenProvideSyncSampler() throws Exception {
    setupHttp2Controller(false, 1);
    HTTP2Sampler currentElement = (HTTP2Sampler) http2Controller.next();
    assertThat(currentElement.isSyncRequest()).isFalse();
  }

  @Test(timeout = 7000)
  public void shouldBusyWaitOnlyForFirstSamplerWhenMaxConcurrentAsyncInControllerOvercome()
      throws Exception {
    setupHttp2Controller(false, 1);
    // Second stays not-done (default mock); first completes soon. Proves we do not wait on second
    // without leaving an 80s non-daemon timer that outlives the class and noise later tests.
    simulateSamplerExecution(firstSamplerListener, 100);
    http2Controller.next();
    http2Controller.next();
  }

  private void setupHttp2Controller(boolean otherTypeOfRequest,
                                    int maxConcurrentAsyncInController) throws URISyntaxException {
    JMeterUtils.setProperty(MAX_CONCURRENT_ASYNC_IN_CONTROLLER,
        String.valueOf(maxConcurrentAsyncInController));
    http2Controller = new HTTP2Controller();
    http2Controller.addTestElement(firstSampler);
    http2Controller.addTestElement(secondSampler);
    when(request.getURI()).thenReturn(new URI("https://test.com"));
    when(firstSamplerListener.getRequest()).thenReturn(request);
    when(secondSamplerListener.getRequest()).thenReturn(request);
    if (otherTypeOfRequest) {
      http2Controller.addTestElement(otherSamplerType);
    }
  }

  private void simulateSamplerExecution(HTTP2FutureResponseListener samplerListener,
                                        int delayInMillis) {
    //Thanks to mockito by default when(samplerListener.isDone()).thenReturn(false)
    //
    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "http2-controller-sim");
      thread.setDaemon(true);
      return thread;
    });
    simulationExecutors.add(executorService);
    executorService.schedule(() -> {
      when(samplerListener.isDone()).thenReturn(true);
      return null;
    }, delayInMillis, TimeUnit.MILLISECONDS);
  }

  @Test(timeout = 5000)
  public void shouldBusyWaitForAsyncSamplersWhenControllerReachOtherSamplerType()
      throws URISyntaxException {
    setupHttp2Controller(true, 2);
    simulateSamplerExecution(secondSamplerListener, 200);
    simulateSamplerExecution(firstSamplerListener, 50);
    Sampler next = null;
    for (int i = 0; i < 5; i++) {
      next = http2Controller.next();
    }
    assertThat(next).isInstanceOf(HTTPSampler.class);
  }

  @Test
  public void shouldSuppressPreProcessorsOnAsyncCompletionBeforeReturningSampler()
      throws Exception {
    FlagHTTP2Sampler flaggedSampler = new FlagHTTP2Sampler();
    HTTP2FutureResponseListener flaggedListener = mock(HTTP2FutureResponseListener.class);
    flaggedSampler.setFutureResponseListener(flaggedListener);

    JMeterUtils.setProperty(MAX_CONCURRENT_ASYNC_IN_CONTROLLER, "1000");
    http2Controller = new HTTP2Controller();
    http2Controller.addTestElement(flaggedSampler);
    http2Controller.addTestElement(otherSamplerType);

    when(request.getURI()).thenReturn(new URI("https://test.com"));
    when(flaggedListener.getRequest()).thenReturn(request);
    when(flaggedListener.isDone()).thenReturn(true);

    http2Controller.next();
    Sampler next = http2Controller.next();

    assertThat(next).isSameAs(flaggedSampler);
    assertThat(flaggedSampler.wasSuppressed()).isTrue();
  }

  @Test
  public void shouldResolveGenerateParentSampleFromPreferredJvmPropertyBeforeLegacy() {
    JMeterTestUtils.setupJmeterEnv();
    Properties props = JMeterUtils.getJMeterProperties();
    String pref =
        BzmHttpPluginProperties.CONTROLLER_PREFERRED_PREFIX + "generateParentSample";
    String leg = BzmHttpPluginProperties.CONTROLLER_LEGACY_PREFIX + "generateParentSample";
    String prevPref = props.getProperty(pref);
    String prevLeg = props.getProperty(leg);
    try {
      props.remove(pref);
      props.remove(leg);
      JMeterUtils.setProperty(leg, "false");
      JMeterUtils.setProperty(pref, "true");
      HTTP2Controller c = new HTTP2Controller();
      assertThat(c.isGenerateControllerSample()).isTrue();
    } finally {
      if (prevPref == null) {
        props.remove(pref);
      } else {
        JMeterUtils.setProperty(pref, prevPref);
      }
      if (prevLeg == null) {
        props.remove(leg);
      } else {
        JMeterUtils.setProperty(leg, prevLeg);
      }
    }
  }

  @Test
  public void shouldReadGenerateParentSampleFromLegacyJvmPropertyWhenPreferredUnset() {
    JMeterTestUtils.setupJmeterEnv();
    Properties props = JMeterUtils.getJMeterProperties();
    String pref =
        BzmHttpPluginProperties.CONTROLLER_PREFERRED_PREFIX + "generateParentSample";
    String leg = BzmHttpPluginProperties.CONTROLLER_LEGACY_PREFIX + "generateParentSample";
    String prevPref = props.getProperty(pref);
    String prevLeg = props.getProperty(leg);
    try {
      props.remove(pref);
      props.remove(leg);
      JMeterUtils.setProperty(leg, "true");
      HTTP2Controller c = new HTTP2Controller();
      assertThat(c.isGenerateControllerSample()).isTrue();
    } finally {
      if (prevPref == null) {
        props.remove(pref);
      } else {
        JMeterUtils.setProperty(pref, prevPref);
      }
      if (prevLeg == null) {
        props.remove(leg);
      } else {
        JMeterUtils.setProperty(leg, prevLeg);
      }
    }
  }

  @Test
  public void elementLegacyLimitMaxParallelPropertyIsHonored() {
    JMeterTestUtils.setupJmeterEnv();
    HTTP2Controller c = new HTTP2Controller();
    c.setProperty(BzmHttpPluginProperties.CONTROLLER_LEGACY_PREFIX + "limitMaxParallel", true);
    assertThat(c.isLimitMaxParallel()).isTrue();
  }

  @Test
  public void preferredElementPropertyOverridesLegacyForLimitMaxParallel() {
    JMeterTestUtils.setupJmeterEnv();
    HTTP2Controller c = new HTTP2Controller();
    c.setProperty(BzmHttpPluginProperties.CONTROLLER_LEGACY_PREFIX + "limitMaxParallel", false);
    c.setProperty(BzmHttpPluginProperties.CONTROLLER_PREFERRED_PREFIX + "limitMaxParallel", true);
    assertThat(c.isLimitMaxParallel()).isTrue();
  }

  private static class FlagHTTP2Sampler extends HTTP2Sampler {
    private boolean suppressed;

    @Override
    public void suppressPreProcessorsOnce() {
      suppressed = true;
    }

    boolean wasSuppressed() {
      return suppressed;
    }
  }

  // TODO:
  // Tests using isDone are discussed because it is a misconception.
  //isDone is more related to the row of the entire test and not to the controller itself.
  //The isDone setting is removed, because it is something that apparently manages the iterators
  // and thread group.
  //Analyze if it does not require refacoring incorporating any of these to maintain the tests.

  /*
  @Test(expected = NextIsNullException.class)
  public void shouldThrowNextIsNullWhenNextWithoutElementsLeft() throws NextIsNullException {
    http2Controller = new HTTP2Controller();
    http2Controller.getCurrentElement();
  }
  */

  /*
  @Test
  public void shouldSetControllerDoneWhenNoMoreElementsToBeProcessed() {
    http2Controller = new HTTP2Controller();
    http2Controller.next();
    assertThat(http2Controller.isDone()).isTrue();
  }
  */


  /*
  @Test
  public void shouldControllerDoneWhenSamplesProcessed() throws URISyntaxException {
    http2Controller = new HTTP2Controller();

    http2Controller.addTestElement(firstSampler);
    http2Controller.addTestElement(secondSampler);
    when(request.getURI()).thenReturn(new URI("https://test.com"));
    when(firstSamplerListener.isDone()).thenReturn(true);
    when(firstSamplerListener.getRequest()).thenReturn(request);
    when(secondSamplerListener.isDone()).thenReturn(true);
    when(secondSamplerListener.getRequest()).thenReturn(request);
    http2Controller.initialize();
    int resultCount = 0;
    while (!http2Controller.isDone()) {
      Sampler next = http2Controller.next();
      if (next == null) {
        continue;
      }
      SampleResult sampleResult = next.sample(null);
      if (sampleResult != null) {
        resultCount += 1;
      }
    }
    // NOTE: The mocking make an error in the results, because the async execution return a value
    // and is expected to return null in that case, and the real response in the second sample
    // execution
    assertThat(resultCount).isEqualTo(4);
  }
  */
}
