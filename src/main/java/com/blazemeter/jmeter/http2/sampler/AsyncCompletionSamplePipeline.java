package com.blazemeter.jmeter.http2.sampler;

import java.util.List;
import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.AbstractScopedAssertion;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jorphan.util.JMeterError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the same post-sample steps {@link org.apache.jmeter.threads.JMeterThread} applies when a
 * sampler returns a non-ignored {@link SampleResult}: thread metadata, {@code previousResult},
 * post-processors, and assertions. Used when an async HTTP2 sample completes under "Generate
 * parent sample" and the sampler returns {@code null} to JMeter so listeners stay unchanged, but
 * child-scoped assertions and extractors still run.
 */
final class AsyncCompletionSamplePipeline {

  private static final Logger LOG = LoggerFactory.getLogger(AsyncCompletionSamplePipeline.class);

  private AsyncCompletionSamplePipeline() {}

  /**
   * Mirrors {@code JMeterThread.executeSamplePackage} for the fragment after a successful sample,
   * excluding listener notification and {@code compiler.done}.
   */
  static void runAfterAsyncSample(SampleResult result, SamplePackage pack, JMeterContext ctx) {
    if (result == null || pack == null || ctx == null) {
      return;
    }
    JMeterThread thread = ctx.getThread();
    if (thread == null) {
      LOG.debug("No JMeterThread in context; skipping async completion pipeline");
      return;
    }
    int nbActiveThreadsInThreadGroup = 0;
    if (ctx.getThreadGroup() != null) {
      nbActiveThreadsInThreadGroup = ctx.getThreadGroup().getNumberOfThreads();
    }
    int nbTotalActiveThreads = JMeterContextService.getNumberOfThreads();
    String threadName = thread.getThreadName();
    if (threadName == null || threadName.isEmpty()) {
      threadName = Thread.currentThread().getName();
    }
    fillThreadInformation(result, threadName, nbActiveThreadsInThreadGroup,
        nbTotalActiveThreads);
    SampleResult[] subResults = result.getSubResults();
    if (subResults != null) {
      for (SampleResult sub : subResults) {
        fillThreadInformation(sub, threadName, nbActiveThreadsInThreadGroup,
            nbTotalActiveThreads);
      }
    }
    ctx.setPreviousResult(result);
    runPostProcessors(pack.getPostProcessors());
    checkAssertions(pack.getAssertions(), result, ctx);
  }

  private static void fillThreadInformation(SampleResult result, String threadName,
      int nbActiveThreadsInThreadGroup, int nbTotalActiveThreads) {
    result.setGroupThreads(nbActiveThreadsInThreadGroup);
    result.setAllThreads(nbTotalActiveThreads);
    result.setThreadName(threadName);
  }

  private static void runPostProcessors(List<?> extractors) {
    if (extractors == null) {
      return;
    }
    for (Object exObj : extractors) {
      PostProcessor ex = (PostProcessor) exObj;
      TestBeanHelper.prepare((TestElement) ex);
      ex.process();
    }
  }

  private static void checkAssertions(List<?> assertions, SampleResult parent,
      JMeterContext threadContext) {
    if (assertions == null) {
      return;
    }
    for (Object assObj : assertions) {
      Assertion assertion = (Assertion) assObj;
      TestBeanHelper.prepare((TestElement) assertion);
      if (assertion instanceof AbstractScopedAssertion) {
        AbstractScopedAssertion scopedAssertion = (AbstractScopedAssertion) assertion;
        String scope = scopedAssertion.fetchScope();
        if (scopedAssertion.isScopeParent(scope)
            || scopedAssertion.isScopeAll(scope)
            || scopedAssertion.isScopeVariable(scope)) {
          processAssertion(parent, assertion);
        }
        if (scopedAssertion.isScopeChildren(scope) || scopedAssertion.isScopeAll(scope)) {
          recurseAssertionChecks(parent, assertion, 3);
        }
      } else {
        processAssertion(parent, assertion);
      }
    }
    setLastSampleOk(threadContext.getVariables(), parent.isSuccessful());
  }

  private static void recurseAssertionChecks(SampleResult parent, Assertion assertion, int level) {
    if (level < 0) {
      return;
    }
    SampleResult[] children = parent.getSubResults();
    boolean childError = false;
    if (children != null) {
      for (SampleResult childSampleResult : children) {
        processAssertion(childSampleResult, assertion);
        recurseAssertionChecks(childSampleResult, assertion, level - 1);
        if (!childSampleResult.isSuccessful()) {
          childError = true;
        }
      }
    }
    if (childError && parent.isSuccessful()) {
      AssertionResult assertionResult =
          new AssertionResult(((AbstractTestElement) assertion).getName());
      assertionResult.setResultForFailure("One or more sub-samples failed");
      parent.addAssertionResult(assertionResult);
      parent.setSuccessful(false);
    }
  }

  private static void processAssertion(SampleResult result, Assertion assertion) {
    AssertionResult assertionResult;
    try {
      assertionResult = assertion.getResult(result);
    } catch (AssertionError e) {
      LOG.debug("Error processing Assertion.", e);
      assertionResult = new AssertionResult("Assertion failed! See log file (debug level, only).");
      assertionResult.setFailure(true);
      assertionResult.setFailureMessage(e.toString());
    } catch (JMeterError e) {
      LOG.error("Error processing Assertion.", e);
      assertionResult = new AssertionResult("Assertion failed! See log file.");
      assertionResult.setError(true);
      assertionResult.setFailureMessage(e.toString());
    } catch (Exception e) {
      LOG.error("Exception processing Assertion.", e);
      assertionResult = new AssertionResult("Assertion failed! See log file.");
      assertionResult.setError(true);
      assertionResult.setFailureMessage(e.toString());
    }
    result.setSuccessful(
        result.isSuccessful() && !(assertionResult.isError() || assertionResult.isFailure()));
    result.addAssertionResult(assertionResult);
  }

  private static void setLastSampleOk(org.apache.jmeter.threads.JMeterVariables variables,
      boolean value) {
    variables.put(JMeterThread.LAST_SAMPLE_OK, Boolean.toString(value));
  }
}
