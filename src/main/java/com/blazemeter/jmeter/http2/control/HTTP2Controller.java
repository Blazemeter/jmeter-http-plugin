package com.blazemeter.jmeter.http2.control;

import static com.blazemeter.jmeter.http2.core.LowLevelDebugLog.lowLevelDebug;

import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.core.SampleClock;
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
import org.apache.jmeter.samplers.SampleResult;
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
  /** JMeter's own property name, so a value set on a stock Transaction Controller still reads. */
  private static final String INCLUDE_TIMERS = "TransactionController.includeTimers";

  private static final int DEFAULT_MAX_CONCURRENT_ASYNC_IN_CONTROLLER = 100;
  private static final long COMPLETION_POLL_INTERVAL_MILLIS = 10;
  private static final long COMPLETION_TIMEOUT_MARGIN_MILLIS = 2_000;
  private static final String COMPLETION_TIMEOUT_PROP =
      "httpJettyClient.asyncControllerCompletionTimeout";
  private static final long DEFAULT_COMPLETION_TIMEOUT_MILLIS = 120_000;
  // New setting, so it carries the preferred name; getPropDefault still takes the legacy prefixes.
  private static final String SCHEDULER_HOLD_PROP = "blazemeter.http.schedulerHoldMillis";
  private static final long DEFAULT_SCHEDULER_HOLD_MILLIS = 2_000;
  private int maxConcurrentAsyncInController = DEFAULT_MAX_CONCURRENT_ASYNC_IN_CONTROLLER;
  private long schedulerHoldMillis = DEFAULT_SCHEDULER_HOLD_MILLIS;

  private transient List<HTTP2Sampler> http2SamplesSync = new ArrayList<>();
  private transient boolean handingOutPendingSampler;
  private transient boolean nestedSequentialRequestWarned;
  private boolean generateControllerSample;
  /**
   * The parent transaction currently open, so {@link #triggerEndOfLoop()} can still reach it: the
   * field {@link TransactionController} keeps it in is private and is already null by the time
   * {@code super.triggerEndOfLoop()} returns.
   */
  private transient TransactionSampler openParentTransaction;
  /**
   * The thread's own scheduled end while this controller is holding it off, or {@code null} when it
   * is not. See {@link #holdSchedulerWhileRequestsAreInFlight()}.
   */
  private transient Long heldSchedulerEndTime;
  /** How many children the open transaction had when its span was last measured. */
  private transient int measuredChildren;

  public HTTP2Controller() {
    super();
    maxConcurrentAsyncInController =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.maxConcurrentAsyncInController",
            String.valueOf(maxConcurrentAsyncInController)));
    generateControllerSample =
        BzmHttpPluginProperties.getControllerPropDefault(GENERATE_PARENT_SAMPLE_PREF, false);
    schedulerHoldMillis = resolveSchedulerHoldMillis();
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
    try {
      if (isGenerateParentSample()) {
        Sampler next = super.next();
        measureParentSample(next);
        // Only after the controller has finished the parent transaction (next == null). Releasing
        // while still returning the done TransactionSampler is pointless: JMeterThread calls
        // configureTransactionSampler(done) next and puts that same instance back on the package.
        // Releasing on the following null turn is what sticks — and runs after listeners/assertions
        // in doEndTransactionSampler (same sequencing as JMeter PR #6386's TestCompiler.done()
        // hook).
        if (next == null) {
          releaseCompletedParentTransactionSample();
        }
        return next;
      }
      return nextWithoutTransactionBookkeeping();
    } finally {
      // After the turn, not before it: the request this turn dispatched is only on the wire once
      // getCurrentElement has run, and what the scheduler checks right after this call returns is
      // whether anything is out there now.
      holdSchedulerWhileRequestsAreInFlight();
    }
  }

  /**
   * Keeps the thread's scheduled end from landing while this controller still has requests on the
   * wire, and gives it back the moment they are all collected.
   *
   * <p>A stock sampler never loses a response to the schedule: {@code JMeterThread} checks the
   * scheduled end in {@code stopSchedulerIfNeeded}, which runs after {@code executeSamplePackage}
   * returns, so a request that is already out always gets to finish and be reported, and the
   * transaction it belongs to closes with it inside. This controller has requests out across
   * turns instead of inside one, so the same check lands between them: the thread stops with
   * responses already on their way back, their samples are never reported, and the transaction they
   * belonged to closes empty - a 0 ms row with no children, one per thread per run, which is enough
   * to drag a label's Min to zero.
   *
   * <p>What is held off is only the scheduler's own deadline, through {@link JMeterThread}'s public
   * end time, and only forward: a Stop, a shutdown or an interrupt do not go through it and are not
   * affected. The push is small and renewed on every turn and on every completion poll, so the
   * overrun is the time the pending responses need and nothing more; if this controller ever failed
   * to give the deadline back, the last push expires on its own within
   * {@code blazemeter.http.schedulerHoldMillis}.
   *
   * <p>Only the responses already asked for are waited on: once the real end has gone by,
   * {@link #getCurrentElement()} stops dispatching the rest of the children rather than sending
   * requests the run was over before making.
   *
   * <p>An empty queue is not the moment to give the deadline back: the response collected last has
   * been handed to {@code JMeterThread} but not yet turned into a sample, and giving the deadline
   * back on that same turn lets the scheduler stop the thread before it does - which loses exactly
   * the response this was holding the thread for. The deadline goes back at the end of the
   * iteration instead (see {@link #discardPendingAsyncSamples()}, reached through
   * {@code reInitialize}), by which point every collected response has been reported; and since it
   * stops being renewed as soon as the queue empties, a push left behind by any path that does not
   * reach that point expires on its own.
   */
  private void holdSchedulerWhileRequestsAreInFlight() {
    if (http2SamplesSync.isEmpty()) {
      return;
    }
    JMeterThread thread = JMeterContextService.getContext().getThread();
    if (thread == null || thread.getEndTime() <= 0) {
      return; // Not a scheduled run: nothing is going to cut this thread short.
    }
    long hold = System.currentTimeMillis() + schedulerHoldMillis;
    if (heldSchedulerEndTime == null) {
      if (hold <= thread.getEndTime()) {
        return; // The scheduled end is far enough away to collect what is out there.
      }
      heldSchedulerEndTime = thread.getEndTime();
      lowLevelDebug("Holding the scheduled end of {} while {} request(s) are in flight in '{}'",
          thread.getThreadName(), http2SamplesSync.size(), getName());
    }
    thread.setEndTime(hold);
  }

  /**
   * Whether the thread's own scheduled end has already gone by while this controller was holding it
   * off. Read from the end time that was saved, not from the thread, whose end time is the held one
   * while the hold is on.
   */
  private boolean scheduledEndPassed() {
    return heldSchedulerEndTime != null && System.currentTimeMillis() >= heldSchedulerEndTime;
  }

  /** Gives the thread back its own scheduled end, so the next check can stop it. */
  private void releaseScheduler() {
    if (heldSchedulerEndTime == null) {
      return;
    }
    JMeterThread thread = JMeterContextService.getContext().getThread();
    if (thread != null) {
      thread.setEndTime(heldSchedulerEndTime);
      lowLevelDebug("Scheduled end of {} restored: nothing left in flight in '{}'",
          thread.getThreadName(), getName());
    }
    heldSchedulerEndTime = null;
  }

  /**
   * Resolved once, at construction, like the other settings of this controller: this is read from
   * the completion poll loop, which runs every {@value #COMPLETION_POLL_INTERVAL_MILLIS} ms for
   * every thread that has a request out, and a JMeter property read is a synchronized map lookup
   * per accepted name.
   */
  private static long resolveSchedulerHoldMillis() {
    String configured = BzmHttpPluginProperties.getPropDefault(
        SCHEDULER_HOLD_PROP, String.valueOf(DEFAULT_SCHEDULER_HOLD_MILLIS));
    try {
      return Math.max(0, Long.parseLong(configured.trim()));
    } catch (NumberFormatException e) {
      LOG.warn("Invalid {}='{}', falling back to {} ms", SCHEDULER_HOLD_PROP, configured,
          DEFAULT_SCHEDULER_HOLD_MILLIS);
      return DEFAULT_SCHEDULER_HOLD_MILLIS;
    }
  }

  /**
   * What the parent sample must report is the time this controller spent on requests, which for
   * overlapped requests is the span from the first one leaving to the last one arriving.
   *
   * <p>Neither of {@link TransactionSampler}'s two modes measures that. With "include timers" on it
   * leaves the sample stretching from the moment the transaction opened - before the timers and
   * pre-processors of the first request ran - to the last response, so a think time lands inside
   * the transaction time (issue #155). With it off, {@code setTransactionDone} reports the sum of
   * the children instead, which double counts requests that ran at the same time. So the span is
   * measured here, out of the children's own stamps, exactly as this controller did up to v3.0.1.
   *
   * <p>Measured on every turn, not only when the transaction is closed here, because it is not
   * always closed here: {@code JMeterThread} ends whatever transaction is open when a run is cut
   * short, without going through {@code setTransactionDone} and without asking this controller
   * again. A transaction that has never been measured reports {@code 0 - startTime} - an epoch,
   * which an enclosing Transaction Controller then folds in through
   * {@code SampleResult.addSubResult}, whose {@code Math.max} keeps the zero - and one that was
   * only measured when it closed would report, on that path, the wall clock this exists to correct.
   * Keeping the open transaction measured as it goes means that however it ends, it already says
   * what it ran.
   */
  private void measureParentSample(Sampler next) {
    if (!(next instanceof TransactionSampler)) {
      return;
    }
    TransactionSampler transactionSampler = (TransactionSampler) next;
    openParentTransaction = transactionSampler.isTransactionDone() ? null : transactionSampler;
    SampleResult parent = transactionSampler.getTransactionResult();
    if (parent == null) {
      return;
    }
    // Only when there is something new to measure. A sample's stamps are final once it is reported,
    // so the span can only move when a child joins - and walking every child on every turn of every
    // request would be quadratic on a controller holding many of them.
    int children = parent.getSubResults().length;
    if (children == measuredChildren && parent.getEndTime() != 0) {
      return;
    }
    measuredChildren = children;
    applyRequestSpan(transactionSampler);
  }

  /**
   * Rewrites the finished parent sample as {@code lastResponse - firstRequestSent}, leaving what
   * came before the first request (timers, pre-processors) in {@code idleTime}, which is the field
   * JMeter itself uses for it. Keeps the wall clock reading when the user asked for the timers to
   * be included.
   *
   * @see #isIncludeTimers()
   */
  private void applyRequestSpan(TransactionSampler transactionSampler) {
    SampleResult parent = transactionSampler.getTransactionResult();
    if (parent == null) {
      return;
    }
    if (isIncludeTimers()) {
      if (parent.getEndTime() == 0) {
        // The wall clock is what was asked for, but an end time there has to be: a transaction
        // ended from the outside before its first child arrived would otherwise report the epoch.
        parent.setEndTime(parent.getStartTime());
      }
      return;
    }
    long firstStart = Long.MAX_VALUE;
    long lastEnd = 0;
    for (SampleResult child : parent.getSubResults()) {
      // Each child's stamps are on its own clock, put on the transaction's the same way
      // SampleResult.addSubResult does when it extends a parent's end time (Bug 51855).
      if (child.getStartTime() > 0) {
        firstStart = Math.min(firstStart,
            SampleClock.fromResultClock(parent, child, child.getStartTime()));
      }
      if (child.getEndTime() > 0) {
        lastEnd = Math.max(lastEnd,
            SampleClock.fromResultClock(parent, child, child.getEndTime()));
      }
    }
    if (firstStart == Long.MAX_VALUE || lastEnd < firstStart) {
      // Nothing was measured: an iteration that was cut short, or children that never got stamps.
      parent.setIdleTime(0);
      parent.setEndTime(parent.getStartTime());
      return;
    }
    // elapsed = endTime - startTime - idleTime, so this reads exactly lastEnd - firstStart. Going
    // through idleTime rather than a synthetic end time leaves the sample ending when its last
    // request did, and holding what came before the first one in the field JMeter's own Transaction
    // Controller keeps a pause in (Bug 50080).
    parent.setIdleTime(firstStart - parent.getStartTime());
    parent.setEndTime(lastEnd);
  }

  /**
   * The children of an aborted iteration - Start Next Loop, Stop Thread - are attached by
   * {@code super.triggerEndOfLoop()}, which also closes the transaction, so the span can only be
   * measured after it.
   */
  @Override
  public void triggerEndOfLoop() {
    TransactionSampler ending = openParentTransaction;
    openParentTransaction = null;
    super.triggerEndOfLoop();
    if (ending != null) {
      applyRequestSpan(ending);
    }
  }

  /**
   * Same question a stock Transaction Controller asks, with the opposite default: a think time is a
   * pause, not request time, and this controller has never counted it. Answering {@code true} by
   * inheritance - which is what JMeter's own default does, for compatibility with test plans older
   * than its checkbox - is what put the think time inside the transaction time in v3.1.0. An
   * explicit value, from this element's GUI or from a JMX written against a stock Transaction
   * Controller, is honoured.
   */
  @Override
  public boolean isIncludeTimers() {
    return containsElementPropertyNamed(INCLUDE_TIMERS)
        && getPropertyAsBoolean(INCLUDE_TIMERS, false);
  }

  /**
   * Always writes the property. {@code TransactionController.setIncludeTimers} drops it when it
   * matches JMeter's default of {@code true}, which would leave this controller reading its own
   * default of {@code false} and silently discard the user's choice.
   */
  @Override
  public void setIncludeTimers(boolean includeTimers) {
    setProperty(INCLUDE_TIMERS, includeTimers);
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
          lowLevelDebug("HTTP Future Finished, retrying the sample with that data {}",
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
          // Renewed from inside the wait too: the scheduled end is only ever read between turns,
          // and this loop is what makes a turn last as long as a response does.
          holdSchedulerWhileRequestsAreInFlight();
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
    lowLevelDebug("Current {} Size {}", current, subControllersAndSamplers.size());
    handingOutPendingSampler = false;

    if (http2SamplesSync.size() > getEffectiveMaxConcurrentAsyncInController()) {
      HTTP2Sampler http2samDone = waitForDoneHTTP2();
      if (!Objects.isNull(http2samDone)) {
        return handOutWithoutAdvancing(http2samDone);
      }
    }

    if (scheduledEndPassed()) {
      // The run is over: collect what is already on the wire and end the iteration on it. Holding
      // the thread alive is for the responses this controller already asked for, not a licence to
      // keep asking - the remaining children would be load applied past the end of the test.
      lowLevelDebug("Scheduled end reached with {} request(s) in flight in '{}'; collecting them "
          + "and skipping the rest of the controller", http2SamplesSync.size(), getName());
      HTTP2Sampler http2samDone = waitForDoneHTTP2();
      return Objects.isNull(http2samDone) ? null : handOutWithoutAdvancing(http2samDone);
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
      lowLevelDebug("The last, force checkpoint");
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
    lowLevelDebug("Convert http2 sample to Async and add to wait list");
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
      releaseScheduler();
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
    // Nothing is on the wire any more, so the thread gets its own scheduled end back even on the
    // paths that gave up on the queue rather than draining it.
    releaseScheduler();
  }
}
