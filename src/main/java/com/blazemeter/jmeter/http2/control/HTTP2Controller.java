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
import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.NextIsNullException;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.TransactionSampler;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the HTTP requests in its subtree concurrently, and reports them exactly like a Transaction
 * Controller does.
 *
 * <p>Concurrency comes from handing the same sampler to {@code JMeterThread} twice: on the first
 * turn the sampler fires the request and returns {@code null}, and on a later turn, once the
 * response has arrived, it returns the real result. Pre-processors and timers are suppressed on
 * that second turn so they still apply exactly once per request.
 *
 * <p>Reporting is delegated: this class extends {@link TransactionController}, so the parent
 * sample, the nesting of children and grandchildren, controller level assertions, and the loop
 * logical actions are all produced by JMeter's own {@code TransactionSampler} machinery rather than
 * reconstructed here. "Generate parent sample" maps onto
 * {@link TransactionController#isGenerateParentSample()}; when it is off this controller behaves
 * like a Simple Controller and adds no sample of its own.
 */
public class HTTP2Controller extends TransactionController implements Serializable {

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

  private static final int DEFAULT_MAX_CONCURRENT_ASYNC_IN_CONTROLLER = 100;
  private static final long COMPLETION_POLL_INTERVAL_MILLIS = 10;
  private static final long COMPLETION_TIMEOUT_MARGIN_MILLIS = 2_000;
  private static final String COMPLETION_TIMEOUT_PROP =
      "httpJettyClient.asyncControllerCompletionTimeout";
  private static final long DEFAULT_COMPLETION_TIMEOUT_MILLIS = 120_000;
  private int maxConcurrentAsyncInController = DEFAULT_MAX_CONCURRENT_ASYNC_IN_CONTROLLER;

  private transient List<HTTP2Sampler> http2SamplesSync = new ArrayList<>();
  private transient boolean handingOutPendingSampler;
  private transient boolean nestedSequentialRequestWarned;
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

  /**
   * Bridges the plugin's own "Generate parent sample" property onto the one
   * {@link TransactionController} reads, so all of its parent sample machinery is driven by the
   * setting the plugin's GUI and existing JMX files use.
   */
  @Override
  public boolean isGenerateParentSample() {
    return isGenerateControllerSample();
  }

  @Override
  public void setGenerateParentSample(boolean generateParent) {
    setGenerateControllerSample(generateParent);
  }

  /**
   * A stock Transaction Controller with "Generate parent sample" off emits an extra "total" sample
   * after its children. This controller has never done that, and test plans in the field depend on
   * its children being the only rows it produces, so the non-parent path stays a plain
   * {@link org.apache.jmeter.control.GenericController} traversal.
   */
  @Override
  public Sampler next() {
    if (isGenerateParentSample()) {
      Sampler next = super.next();
      // Only after the controller has finished the parent transaction (next == null). Releasing
      // while still returning the done TransactionSampler is pointless: JMeterThread calls
      // configureTransactionSampler(done) next and puts that same instance back on the package.
      // Releasing on the following null turn is what sticks — and runs after listeners/assertions
      // in doEndTransactionSampler (same sequencing as JMeter PR #6386's TestCompiler.done() hook).
      if (next == null) {
        releaseCompletedParentTransactionSample();
      }
      return next;
    }
    return nextWithoutTransactionBookkeeping();
  }

  /**
   * Same workaround as JMeter PR #6386: when the parent transaction has finished, replace the
   * completed {@link TransactionSampler} in this controller's {@link SamplePackage} with a fresh
   * one so the finished sample tree is no longer reachable from the compiler map.
   *
   * <p>Uses the public {@link TransactionSampler} / {@link SamplePackage} API; the map itself is
   * private on {@link TestCompiler}, so it is reached by reflection (there is no public accessor
   * in 5.5–5.6.3).
   *
   * <p>Safe wrt listeners/assertions: callers must invoke this only after
   * {@code JMeterThread.doEndTransactionSampler} has notified listeners (i.e. on the controller
   * {@code next()} that returns {@code null} after a done parent). Does not clear
   * {@code previousResult} — scripts using {@code ${prev}} keep working, same as upstream PR #6386.
   *
   * <p>If a future JMeter already applied #6386, {@code pack.getSampler()} is no longer done and
   * this becomes a no-op.
   */
  void releaseCompletedParentTransactionSample() {
    if (!isGenerateParentSample()) {
      return;
    }
    try {
      SamplePackage pack = transactionSamplePackage();
      if (pack == null) {
        return;
      }
      replaceDoneTransactionSampler(pack);
    } catch (Exception e) {
      LOG.debug("Could not release completed parent transaction sample for '{}'", getName(), e);
    }
  }

  /**
   * @return {@code true} when a completed transaction sampler was replaced
   */
  boolean replaceDoneTransactionSampler(SamplePackage pack) {
    Sampler sampler = pack.getSampler();
    if (!(sampler instanceof TransactionSampler)) {
      return false;
    }
    TransactionSampler transactionSampler = (TransactionSampler) sampler;
    if (!transactionSampler.isTransactionDone()) {
      return false;
    }
    // Public constructor used by TestCompiler.saveTransactionControllerConfigs and by PR #6386.
    pack.setSampler(new TransactionSampler(this, transactionSampler.getName()));
    return true;
  }

  private SamplePackage transactionSamplePackage() throws ReflectiveOperationException {
    JMeterThread thread = JMeterContextService.getContext().getThread();
    if (thread == null) {
      return null;
    }
    Field compilerField = JMeterThread.class.getDeclaredField("compiler");
    compilerField.setAccessible(true);
    TestCompiler compiler = (TestCompiler) compilerField.get(thread);
    if (compiler == null) {
      return null;
    }
    Field mapField = TestCompiler.class.getDeclaredField("transactionControllerConfigMap");
    mapField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<TransactionController, SamplePackage> map =
        (Map<TransactionController, SamplePackage>) mapField.get(compiler);
    return map == null ? null : map.get(this);
  }

  /**
   * {@code GenericController.next()} semantics, written out because Java cannot skip one level of
   * {@code super} and {@link TransactionController} overrides {@code next()}.
   */
  private Sampler nextWithoutTransactionBookkeeping() {
    fireIterEvents();
    if (isDone()) {
      return null;
    }
    try {
      TestElement currentElement = getCurrentElement();
      setCurrentElement(currentElement);
      if (currentElement == null) {
        return nextIsNull();
      }
      if (currentElement instanceof Sampler) {
        return nextIsASampler((Sampler) currentElement);
      }
      return nextIsAController((Controller) currentElement);
    } catch (NextIsNullException e) { // NOSONAR handled the same way GenericController does
      return null;
    }
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

  /**
   * Waits for the oldest pending request to complete and hands its sampler back so the completion
   * turn can materialise the result. Returns {@code null} only when there is nothing left to wait
   * for.
   *
   * <p>The wait is bounded. An unbounded busy-wait here freezes the whole JMeter thread whenever a
   * response never arrives, and it cannot be broken from the outside: this loop runs inside
   * {@code Controller.next()}, so {@code JMeterThread.interrupt()} has no current sampler to
   * interrupt, and the thread's {@code running} flag is not reachable from a controller. On expiry
   * the sampler is handed back anyway, so the completion turn turns it into a failed sample instead
   * of the run stalling with nothing reported.
   */
  private HTTP2Sampler waitForDoneHTTP2() {
    boolean interrupted = false;
    while (!interrupted && !http2SamplesSync.isEmpty()) {
      HTTP2Sampler http2Sam = http2SamplesSync.get(0);
      HTTP2FutureResponseListener http2FListener = http2Sam.getFutureResponseListener();
      if (http2FListener == null) {
        // The request never reached the transport, so no completion can ever arrive for it: the URL
        // failed to build, or the dispatch itself threw. Leaving it at the head of the queue would
        // stall every later completion check for the rest of the thread, silently suppressing the
        // results of every other request in this controller.
        LOG.debug("Discarding queued sample without a response listener: {}", http2Sam.getName());
        http2SamplesSync.remove(0);
        continue;
      }
      long timeout = asyncCompletionTimeoutMillis(http2Sam);
      long deadline = System.currentTimeMillis() + timeout;
      while (!interrupted) {
        if (http2FListener.isDone() || http2FListener.isCancelled()) {
          LOG.debug("HTTP Future Finished, retrying the sample with that data {}",
              describeRequest(http2FListener));
          return dequeueForCompletion(http2Sam);
        }
        if (System.currentTimeMillis() >= deadline) {
          LOG.warn("No response for sampler={} within {} ms; reporting it as a failed sample "
              + "instead of waiting longer", http2Sam.getName(), timeout);
          http2FListener.cancel(true);
          return dequeueForCompletion(http2Sam);
        }
        try {
          Thread.sleep(COMPLETION_POLL_INTERVAL_MILLIS);
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

  private HTTP2Sampler dequeueForCompletion(HTTP2Sampler sampler) {
    http2SamplesSync.remove(0);
    sampler.suppressPreProcessorsOnce();
    return sampler; // The second attempt takes the data from the finished listener
  }

  /**
   * Effective bound for a single async completion: the sampler's response timeout when one is
   * configured, otherwise a finite safety bound so a stalled connection cannot freeze the run.
   */
  private long asyncCompletionTimeoutMillis(HTTP2Sampler sampler) {
    int responseTimeout = sampler.getResponseTimeout();
    if (responseTimeout <= 0) {
      responseTimeout = JMeterUtils.getPropDefault("HTTPSampler.response_timeout", 0);
    }
    if (responseTimeout > 0) {
      return (long) responseTimeout + COMPLETION_TIMEOUT_MARGIN_MILLIS;
    }
    String configured = BzmHttpPluginProperties.getPropDefault(
        COMPLETION_TIMEOUT_PROP, String.valueOf(DEFAULT_COMPLETION_TIMEOUT_MILLIS));
    try {
      return Long.parseLong(configured.trim());
    } catch (NumberFormatException e) {
      LOG.warn("Invalid {}='{}', falling back to {} ms", COMPLETION_TIMEOUT_PROP, configured,
          DEFAULT_COMPLETION_TIMEOUT_MILLIS);
      return DEFAULT_COMPLETION_TIMEOUT_MILLIS;
    }
  }

  private static String describeRequest(HTTP2FutureResponseListener listener) {
    return listener.getRequest() == null
        ? "<no request>"
        : listener.getRequest().getURI().toString();
  }

  @Override
  protected TestElement getCurrentElement() throws NextIsNullException {
    LOG.debug("Current {} Size {}", current, subControllersAndSamplers.size());
    handingOutPendingSampler = false;

    if (http2SamplesSync.size() > getEffectiveMaxConcurrentAsyncInController()) {
      HTTP2Sampler http2samDone = waitForDoneHTTP2();
      if (!Objects.isNull(http2samDone)) {
        return handOutWithoutAdvancing(http2samDone);
      }
    }

    if (current < subControllersAndSamplers.size()) {
      TestElement sam = subControllersAndSamplers.get(current);
      if (sam instanceof HTTP2Sampler) {
        return dispatchAsync((HTTP2Sampler) sam);
      } else { // Another type of element, use that for checkpoint mark
        HTTP2Sampler http2sam = waitForDoneHTTP2();
        if (Objects.isNull(http2sam)) {
          return sam;
        }
        return handOutWithoutAdvancing(http2sam);
      }
    }
    if (current == (subControllersAndSamplers.size())) {
      // On the last, force a checkpoint moment
      LOG.debug("The last, force checkpoint");
      HTTP2Sampler http2samDone = waitForDoneHTTP2();
      if (!Objects.isNull(http2samDone)) {
        return handOutWithoutAdvancing(http2samDone);
      }
    }
    return null;
  }

  /** Switches a sampler to asynchronous mode and queues it for a later completion turn. */
  private HTTP2Sampler dispatchAsync(HTTP2Sampler sampler) {
    sampler.setSyncRequest(false); // Force to run async the first time
    LOG.debug("Convert http2 sample to Async and add to wait list");
    http2SamplesSync.add(sampler);
    return sampler;
  }

  /**
   * Flags a sampler that is being handed out "out of band": it is not the element sitting at
   * {@code current}, so the pointer must stay where it is.
   *
   * <p>This used to be done by splicing the sampler into {@code subControllersAndSamplers} to keep
   * the indexes aligned, which forced the child list to be rebuilt from a backup on every turn
   * where {@code current == 0}. That rebuild resurrected child controllers JMeter had already
   * removed for being done, and {@code GenericController.nextIsAController()} then recursed into
   * them without bound until the stack overflowed.
   */
  private Sampler handOutWithoutAdvancing(Sampler sampler) {
    handingOutPendingSampler = true;
    return sampler;
  }

  @Override
  protected Sampler nextIsASampler(Sampler element) throws NextIsNullException {
    if (handingOutPendingSampler) {
      handingOutPendingSampler = false;
      return element;
    }
    return super.nextIsASampler(element);
  }

  @Override
  protected Sampler nextIsAController(Controller controller) throws NextIsNullException {
    Sampler sampler = super.nextIsAController(controller);
    warnIfNestedRequestRunsSequentially(sampler);
    return sampler;
  }

  /**
   * Requests owned by a nested controller run sequentially, and that is worth saying out loud once
   * rather than leaving as a silent loss of the controller's whole purpose.
   *
   * <p>Overlapping a request needs two turns of {@code JMeterThread}: one to send it and a later
   * one to collect the response. A nested controller delivers each of its samplers exactly once and
   * closes its own {@code TransactionSampler} as soon as it runs out, and the nesting of those
   * results depends on that delivery, so this controller cannot insert a second turn for them.
   * Sending them ahead of their turn is not an option either: it would fire the request before the
   * pre-processors that exist to shape it.
   */
  private void warnIfNestedRequestRunsSequentially(Sampler sampler) {
    if (nestedSequentialRequestWarned
        || !(sampler instanceof HTTP2Sampler)
        || !((HTTP2Sampler) sampler).isSyncRequest()) {
      return;
    }
    nestedSequentialRequestWarned = true;
    LOG.warn("Request '{}' is inside a controller nested in '{}', so it runs sequentially. Only "
            + "the direct children of '{}' are overlapped; move the requests up one level to have "
            + "them run in parallel.", sampler.getName(), getName(), getName());
  }

  @Override
  protected void reInitialize() {
    discardPendingAsyncSamples();
    super.reInitialize();
  }

  /**
   * Cancels and forgets whatever is still in flight. Reached at the end of every iteration, where
   * the queue is normally already empty, and after an aborted one (Start Next Loop, Stop Thread),
   * where it is not: keeping those entries would make the next iteration re-dispatch the same
   * sampler while the previous exchange is still on the wire, doubling the load on the SUT.
   */
  private void discardPendingAsyncSamples() {
    if (http2SamplesSync.isEmpty()) {
      return;
    }
    LOG.warn("Discarding {} in-flight async sample(s) left by an interrupted iteration of {}",
        http2SamplesSync.size(), getName());
    for (HTTP2Sampler pending : http2SamplesSync) {
      HTTP2FutureResponseListener listener = pending.getFutureResponseListener();
      if (listener != null && !listener.isDone()) {
        listener.cancel(true);
      }
      // Done-but-not-collected listeners still hold ContentResponseWrapper / request graph until
      // the next sample() completion turn — which never comes after an aborted iteration.
      pending.clearPendingSampleState();
    }
    http2SamplesSync.clear();
  }
}
