package com.blazemeter.jmeter.http2.control;

import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.NextIsNullException;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.threads.TestCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Controller extends GenericController implements Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Controller.class);
  private static final String GENERATE_PARENT_SAMPLE_PREF =
      BzmHttpPluginProperties.CONTROLLER_PREFERRED_PREFIX + "generateParentSample";
  private static final String GENERATE_PARENT_SAMPLE_LEGACY =
      BzmHttpPluginProperties.CONTROLLER_LEGACY_PREFIX + "generateParentSample";
  private static final String LIMIT_MAX_PARALLEL_PREF =
      BzmHttpPluginProperties.CONTROLLER_PREFERRED_PREFIX + "limitMaxParallel";
  private static final String LIMIT_MAX_PARALLEL_LEGACY =
      BzmHttpPluginProperties.CONTROLLER_LEGACY_PREFIX + "limitMaxParallel";
  private static final String MAX_CONCURRENT_PREF =
      BzmHttpPluginProperties.CONTROLLER_PREFERRED_PREFIX + "maxConcurrentAsyncInController";
  private static final String MAX_CONCURRENT_LEGACY =
      BzmHttpPluginProperties.CONTROLLER_LEGACY_PREFIX + "maxConcurrentAsyncInController";
  private static final ThreadLocal<AsyncParentContext> ASYNC_PARENT_CONTEXT =
      new ThreadLocal<>();

  private static final int DEFAULT_MAX_CONCURRENT_ASYNC_IN_CONTROLLER = 100;
  private int maxConcurrentAsyncInController = DEFAULT_MAX_CONCURRENT_ASYNC_IN_CONTROLLER;

  private transient List<HTTP2Sampler> http2SamplesSync = new ArrayList<>();
  private transient List<TestElement> subControllersAndSamplersBackup = new ArrayList();
  private transient boolean controllerSampleEmitted;
  private transient boolean asyncSamplesSeen;
  private transient ParentSample parentSample;
  private boolean generateControllerSample;

  public HTTP2Controller() {
    super();
    maxConcurrentAsyncInController =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.maxConcurrentAsyncInController",
            String.valueOf(maxConcurrentAsyncInController)));
    generateControllerSample =
        BzmHttpPluginProperties.getControllerPropDefault(GENERATE_PARENT_SAMPLE_PREF, false);
  }

  public void setLimitMaxParallel(boolean enabled) {
    removeProperty(LIMIT_MAX_PARALLEL_LEGACY);
    setProperty(LIMIT_MAX_PARALLEL_PREF, enabled);
  }

  public boolean isLimitMaxParallel() {
    return controllerBooleanPreferPreferred(
        LIMIT_MAX_PARALLEL_PREF, LIMIT_MAX_PARALLEL_LEGACY, false);
  }

  public void setMaxConcurrentAsyncInController(int maxConcurrentAsyncInController) {
    removeProperty(MAX_CONCURRENT_LEGACY);
    setProperty(MAX_CONCURRENT_PREF, maxConcurrentAsyncInController);
  }

  public int getMaxConcurrentAsyncInController() {
    return controllerIntPreferPreferred(
        MAX_CONCURRENT_PREF,
        MAX_CONCURRENT_LEGACY,
        getDefaultMaxConcurrentAsyncInController());
  }

  public int getDefaultMaxConcurrentAsyncInController() {
    return maxConcurrentAsyncInController;
  }

  private int getEffectiveMaxConcurrentAsyncInController() {
    return isLimitMaxParallel()
        ? getMaxConcurrentAsyncInController()
        : getDefaultMaxConcurrentAsyncInController();
  }

  public void setGenerateControllerSample(boolean enabled) {
    this.generateControllerSample = enabled;
    removeProperty(GENERATE_PARENT_SAMPLE_LEGACY);
    setProperty(GENERATE_PARENT_SAMPLE_PREF, enabled);
  }

  public boolean isGenerateControllerSample() {
    return controllerBooleanPreferPreferred(
        GENERATE_PARENT_SAMPLE_PREF, GENERATE_PARENT_SAMPLE_LEGACY, generateControllerSample);
  }

  private boolean containsElementPropertyNamed(String propertyName) {
    PropertyIterator it = propertyIterator();
    while (it.hasNext()) {
      JMeterProperty p = it.next();
      if (propertyName.equals(p.getName())) {
        return true;
      }
    }
    return false;
  }

  private boolean controllerBooleanPreferPreferred(String preferredKey, String legacyKey,
                                                   boolean fallback) {
    if (containsElementPropertyNamed(preferredKey)) {
      return getPropertyAsBoolean(preferredKey, fallback);
    }
    if (containsElementPropertyNamed(legacyKey)) {
      return getPropertyAsBoolean(legacyKey, fallback);
    }
    return fallback;
  }

  private int controllerIntPreferPreferred(String preferredKey, String legacyKey, int fallback) {
    if (containsElementPropertyNamed(preferredKey)) {
      return getPropertyAsInt(preferredKey, fallback);
    }
    if (containsElementPropertyNamed(legacyKey)) {
      return getPropertyAsInt(legacyKey, fallback);
    }
    return fallback;
  }

  public static void registerAsyncSampleResult(HTTP2Sampler sampler, SampleResult result,
                                               HTTP2FutureResponseListener listener) {
    AsyncParentContext context = ASYNC_PARENT_CONTEXT.get();
    if (context == null || !context.enabled || result == null || sampler == null
        || !sampler.isAsyncParentSampleEnabled()) {
      return;
    }
    SampleResult copy = deepCopySampleResult(result);
    context.addChild(copy);
  }

  private HTTP2Sampler waitForDoneHTTP2() {
    boolean interrupted = false;
    // Try to check if the first request finish to return again that element first
    if (http2SamplesSync.size() > 0) {
      HTTP2Sampler http2Sam = http2SamplesSync.get(0);
      HTTP2FutureResponseListener http2FListener =
          http2Sam.getFutureResponseListener();
      while (!interrupted && (http2FListener != null)) {
        if (http2FListener.isDone() || http2FListener.isCancelled()) {
          String urlProcesed = http2FListener.getRequest().getURI().toString();
          LOG.debug("HTTP Future Finished, retrying the sample with that data {}", urlProcesed);
          http2SamplesSync.remove(0); // Remove the sample
          http2Sam.suppressPreProcessorsOnce();
          return http2Sam; // The second attempt take the data from the finished listener
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          http2SamplesSync.clear();
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    return null;
  }

  @Override
  protected void setDone(boolean done) {
    // NOPE, dont allow to set the Done on there
    LOG.debug("Set Done:" + done);
    super.setDone(done);
  }

  @Override
  public boolean isDone() {
    boolean done = super.isDone();
    LOG.debug("isDone? " + done);
    return done;
  }

  @Override
  protected TestElement getCurrentElement() throws NextIsNullException {
    LOG.debug("Current {} Size {}", current, subControllersAndSamplers.size());

    if (current == 0) {
      resetAsyncControllerSampleState();
      // The first time, backup the original sub elements
      // On iteration, we need to recover the original list
      if (subControllersAndSamplersBackup.size() == 0) {
        subControllersAndSamplersBackup.addAll(subControllersAndSamplers);
      } else {
        subControllersAndSamplers.clear();
        subControllersAndSamplers.addAll(subControllersAndSamplersBackup);
      }
    }

    if (http2SamplesSync.size() > getEffectiveMaxConcurrentAsyncInController()) {
      HTTP2Sampler http2samDone = waitForDoneHTTP2();
      if (!Objects.isNull(http2samDone)) {
        subControllersAndSamplers.add(current, http2samDone);
        return http2samDone;
      }
    }

    if (current < subControllersAndSamplers.size()) {
      TestElement sam = subControllersAndSamplers.get(current);
      if (sam instanceof HTTP2Sampler) {
        HTTP2Sampler http2Sam = ((HTTP2Sampler) sam);
        http2Sam.setAsyncParentSampleEnabled(isGenerateControllerSample());
        http2Sam.setSyncRequest(false); // Force to run async the first time
        LOG.debug("Convert http2 sample to Async and add to wait list");
        http2SamplesSync.add(http2Sam);
        asyncSamplesSeen = true;
        return http2Sam;
      } else { // Another type of element, use that for checkpoint mark
        HTTP2Sampler http2sam = waitForDoneHTTP2();
        if (Objects.isNull(http2sam)) {
          return sam;
        }
        subControllersAndSamplers.add(current, http2sam);
        return http2sam;
      }
    }
    if (current == (subControllersAndSamplers.size())) {
      // On the last, force a checkpoint moment
      LOG.debug("The last, force checkpoint");
      HTTP2Sampler http2samDone = waitForDoneHTTP2();
      if (!Objects.isNull(http2samDone)) {
        subControllersAndSamplers.add(current, http2samDone);
        return http2samDone;
      }
      if (http2SamplesSync.isEmpty()) {
        LOG.debug("No more elements!");
        Sampler parent = buildParentSamplerIfNeeded();
        if (parent != null) {
          subControllersAndSamplers.add(current, parent);
          return parent;
        }
      }
    }
    return null;
  }

  private void resetAsyncControllerSampleState() {
    controllerSampleEmitted = false;
    asyncSamplesSeen = false;
    parentSample = null;
    if (isGenerateControllerSample()) {
      ASYNC_PARENT_CONTEXT.set(new AsyncParentContext(true));
    } else {
      ASYNC_PARENT_CONTEXT.remove();
    }
  }

  private Sampler buildParentSamplerIfNeeded() {
    if (!isGenerateControllerSample() || controllerSampleEmitted || !asyncSamplesSeen) {
      return null;
    }
    if (parentSample != null) {
      return parentSample;
    }
    AsyncParentContext context = ASYNC_PARENT_CONTEXT.get();
    if (context == null) {
      return null;
    }
    if (context.startTime == 0 || context.endTime == 0 || context.endTime < context.startTime) {
      return null;
    }
    parentSample = new ParentSample();
    parentSample.setName(getName());
    registerParentSamplePackage(parentSample);
    return parentSample;
  }

  private SamplePackage getSamplePackageFromContext() {
    JMeterVariables vars = JMeterContextService.getContext().getVariables();
    if (vars == null) {
      return null;
    }
    Object pack = vars.getObject(JMeterThread.PACKAGE_OBJECT);
    return pack instanceof SamplePackage ? (SamplePackage) pack : null;
  }

  private void registerParentSamplePackage(ParentSample sampler) {
    SamplePackage childPack = getSamplePackageFromContext();
    if (childPack == null) {
      childPack = resolveChildSamplePackageFromCompiler();
    }
    SamplePackage parentPack = childPack != null
        ? createParentExecutionPackage(childPack)
        : createEmptyParentExecutionPackage();
    parentPack.setSampler(sampler);
    putSamplerPackageInCompilerMap(sampler, parentPack);
  }

  private SamplePackage resolveChildSamplePackageFromCompiler() {
    JMeterThread thread = JMeterContextService.getContext().getThread();
    if (thread == null) {
      return null;
    }
    try {
      java.lang.reflect.Field compilerField = JMeterThread.class.getDeclaredField("compiler");
      compilerField.setAccessible(true);
      TestCompiler compiler = (TestCompiler) compilerField.get(thread);
      if (compiler == null) {
        return null;
      }
      java.lang.reflect.Field mapField = TestCompiler.class.getDeclaredField("samplerConfigMap");
      mapField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<Object, SamplePackage> map =
          (Map<Object, SamplePackage>) mapField.get(compiler);
      if (map == null) {
        return null;
      }
      for (TestElement element : subControllersAndSamplers) {
        if (element instanceof HTTP2Sampler) {
          SamplePackage pack = map.get(element);
          if (pack != null) {
            return pack;
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Failed to resolve child SamplePackage from compiler", e);
    }
    return null;
  }

  private void putSamplerPackageInCompilerMap(Object sampler, SamplePackage pack) {
    JMeterThread thread = JMeterContextService.getContext().getThread();
    if (thread == null) {
      return;
    }
    try {
      java.lang.reflect.Field compilerField = JMeterThread.class.getDeclaredField("compiler");
      compilerField.setAccessible(true);
      TestCompiler compiler = (TestCompiler) compilerField.get(thread);
      if (compiler == null) {
        return;
      }
      java.lang.reflect.Field mapField = TestCompiler.class.getDeclaredField("samplerConfigMap");
      mapField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<Object, SamplePackage> map =
          (Map<Object, SamplePackage>) mapField.get(compiler);
      if (map != null) {
        map.put(sampler, pack);
      }
    } catch (Exception e) {
      LOG.debug("Failed to register parent sampler package", e);
    }
  }

  /**
   * Parent synthetic sampler must not inherit child assertions/post-processors: JMeter would run
   * them against the aggregated parent {@link SampleResult}, whose response body is empty.
   */
  private static SamplePackage createParentExecutionPackage(SamplePackage childPack) {
    SamplePackage parentPack = new SamplePackage(
        childPack.getConfigs(),
        childPack.getSampleListeners(),
        childPack.getTimers(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>());
    return parentPack;
  }

  private static SamplePackage createEmptyParentExecutionPackage() {
    return new SamplePackage(
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>());
  }

  private SampleResult buildParentSampleResult() {
    AsyncParentContext context = ASYNC_PARENT_CONTEXT.get();
    if (context == null || context.startTime == 0 || context.endTime == 0
        || context.endTime < context.startTime) {
      return null;
    }
    long duration = context.endTime - context.startTime;
    SampleResult controllerSample = new SampleResult();
    controllerSample.setStampAndTime(context.startTime, duration);
    controllerSample.setSampleLabel(getName());
    controllerSample.setSamplerData("");
    controllerSample.setResponseData(new byte[0]);
    SampleResult firstChild = context.children.isEmpty() ? null : context.children.get(0);
    if (firstChild != null) {
      controllerSample.setThreadName(firstChild.getThreadName());
      controllerSample.setGroupThreads(firstChild.getGroupThreads());
      controllerSample.setAllThreads(firstChild.getAllThreads());
    }
    controllerSample.setSampleCount(1);
    controllerSample.setErrorCount(context.failingSamples > 0 ? 1 : 0);
    controllerSample.setSuccessful(context.failingSamples == 0);
    if (context.failingSamples == 0) {
      controllerSample.setResponseCodeOK();
    } else if (context.responseCode != null) {
      controllerSample.setResponseCode(context.responseCode);
    }
    controllerSample.setResponseMessage("Number of samples in transaction : "
        + context.totalSamples + ", number of failing samples : " + context.failingSamples);
    for (SampleResult child : context.children) {
      controllerSample.addSubResult(child, false);
    }
    controllerSampleEmitted = true;
    ASYNC_PARENT_CONTEXT.remove();
    parentSample = null;
    return controllerSample;
  }

  private class ParentSample extends AbstractSampler {
    @Override
    public SampleResult sample(Entry e) {
      SampleResult result = buildParentSampleResult();
      if (result == null) {
        long now = System.currentTimeMillis();
        SampleResult empty = new SampleResult();
        empty.setStampAndTime(now, 0);
        empty.setSampleLabel(getName());
        empty.setResponseCodeOK();
        empty.setResponseMessage("OK");
        empty.setSuccessful(true);
        empty.setIgnore();
        return empty;
      }
      return result;
    }
  }

  private static final class AsyncParentContext {
    private final List<SampleResult> children = new ArrayList<>();
    private long startTime;
    private long endTime;
    private int totalSamples;
    private int failingSamples;
    private String responseCode;
    private final boolean enabled;

    private AsyncParentContext(boolean enabled) {
      this.enabled = enabled;
    }

    private void addChild(SampleResult result) {
      children.add(result);
      totalSamples += 1;
      if (responseCode == null) {
        responseCode = result.getResponseCode();
      }
      if (!result.isSuccessful()) {
        failingSamples += 1;
      }
      long start = result.getStartTime();
      long end = result.getEndTime();
      if (start > 0 && (startTime == 0 || start < startTime)) {
        startTime = start;
      }
      if (end > 0 && end > endTime) {
        endTime = end;
      }
    }
  }

  private static SampleResult deepCopySampleResult(SampleResult original) {
    SampleResult copy;
    try {
      copy = (SampleResult) original.clone();
    } catch (Exception e) {
      copy = new SampleResult(original);
    }
    copy.setRequestHeaders(original.getRequestHeaders());
    copy.setResponseHeaders(original.getResponseHeaders());
    // Do not copy sampler data here: clone() already copies the raw field. For HTTPSampleResult,
    // getSamplerData() is a computed view (method, URL, queryString, cookies, plus raw field);
    // setSamplerData(getSamplerData()) would store that view in the raw field and duplicate the
    // request body on the next getSamplerData() call. BlazeMeter HTTP keeps the raw field empty.
    copy.setResponseData(original.getResponseData());
    if (original.getURL() != null) {
      copy.setURL(original.getURL());
    }
    copy.removeSubResults();
    for (SampleResult sub : original.getSubResults()) {
      copy.addSubResult(deepCopySampleResult(sub), false);
    }
    copy.removeAssertionResults();
    for (org.apache.jmeter.assertions.AssertionResult assertion
        : original.getAssertionResults()) {
      copy.addAssertionResult(assertion);
    }
    // Sub-samples attached to the async parent must not stay ignored (the child sampler is ignored
    // only so JMeter does not notify listeners twice). JMeter 5.4 exposes only no-arg setIgnore().
    clearIgnoreOnSampleResult(copy);
    return copy;
  }

  private static void clearIgnoreOnSampleResult(SampleResult result) {
    try {
      Field ignoreField = SampleResult.class.getDeclaredField("ignore");
      ignoreField.setAccessible(true);
      ignoreField.setBoolean(result, false);
    } catch (Exception e) {
      LOG.debug("Could not clear ignore flag on async parent sub-sample copy", e);
    }
  }

}
