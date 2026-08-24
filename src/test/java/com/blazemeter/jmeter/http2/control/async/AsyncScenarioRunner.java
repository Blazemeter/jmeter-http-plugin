package com.blazemeter.jmeter.http2.control.async;

import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

/**
 * Runs a scenario on a REAL {@link JMeterThread} over a real element tree, which is the only way to
 * observe what issues #108 and #110 are actually about: how many times JMeter applies timers and
 * pre/post processors, which results reach the listeners and with what parent/child nesting, and
 * whether the thread ever finishes.
 *
 * <p>Two deliberate design points:
 *
 * <ul>
 *   <li>The tree is a {@link ListedHashTree} so sibling order is preserved. A plain
 *       {@code HashTree} iterates in hash order, which would make execution order random.
 *   <li>The thread is started as a plain Java thread and joined with a timeout. One of the
 *       behaviours under test is an unbounded busy-wait inside the controller, so a reproduction
 *       must report {@code hung} and fail fast instead of blocking the build forever.
 * </ul>
 */
public final class AsyncScenarioRunner {

  public static final long DEFAULT_TIMEOUT_MILLIS = 20_000L;

  private AsyncScenarioRunner() {
  }

  /** A tree node: one test element plus its children, mirroring the JMeter test plan tree. */
  public static final class Node {

    private final TestElement element;
    private final List<Node> children = new ArrayList<>();

    private Node(TestElement element, Node... children) {
      this.element = element;
      Collections.addAll(this.children, children);
    }
  }

  /** What the run produced, beyond what the recording listeners already captured. */
  public static final class Outcome {

    private final boolean hung;
    private final long elapsedMillis;
    private final List<String> engineSignals;
    private final HashTree tree;
    private final List<String> compiledSamplers;
    private final Throwable uncaught;

    private Outcome(boolean hung, long elapsedMillis, List<String> engineSignals, HashTree tree,
                    List<String> compiledSamplers, Throwable uncaught) {
      this.hung = hung;
      this.elapsedMillis = elapsedMillis;
      this.engineSignals = engineSignals;
      this.tree = tree;
      this.compiledSamplers = compiledSamplers;
      this.uncaught = uncaught;
    }

    /**
     * Anything that escaped {@code JMeterThread.run()}. Its handler catches only
     * {@code Exception | JMeterError}, so an {@code Error} such as {@code StackOverflowError} kills
     * the thread with no JMeter-level message at all; without capturing it here a test would see a
     * thread that simply finished.
     */
    public Throwable uncaught() {
      return uncaught;
    }

    /**
     * Simple class names of the keys left in {@code TestCompiler.samplerConfigMap} when the thread
     * finished. Samplers synthesised at run time and registered there by reflection show up here,
     * so a growing count across iterations is visible.
     */
    public List<String> compiledSamplers() {
      return compiledSamplers;
    }

    /** True when the JMeter thread had to be killed because it never finished on its own. */
    public boolean hung() {
      return hung;
    }

    public long elapsedMillis() {
      return elapsedMillis;
    }

    /** Engine-level stop requests the thread issued, e.g. {@code askThreadsToStop}. */
    public List<String> engineSignals() {
      return engineSignals;
    }

    public HashTree tree() {
      return tree;
    }
  }

  public static Node node(TestElement element, Node... children) {
    return new Node(element, children);
  }

  public static ThreadGroup threadGroup(String name, int loops) {
    return threadGroup(name, loops, AbstractThreadGroup.ON_SAMPLE_ERROR_CONTINUE);
  }

  /**
   * @param onSampleError one of the {@code AbstractThreadGroup.ON_SAMPLE_ERROR_*} constants, i.e.
   *     the "Action to be taken after a Sampler error" radio group of the Thread Group.
   */
  public static ThreadGroup threadGroup(String name, int loops, String onSampleError) {
    LoopController loop = new LoopController();
    loop.setName(name + " loop");
    loop.setLoops(loops);
    ThreadGroup group = new ThreadGroup();
    group.setName(name);
    group.setNumThreads(1);
    group.setRampUp(0);
    group.setScheduler(false);
    group.setSamplerController(loop);
    group.setProperty(AbstractThreadGroup.ON_SAMPLE_ERROR, onSampleError);
    return group;
  }

  public static HTTP2Controller asyncController(String name, boolean generateParentSample) {
    HTTP2Controller controller = new HTTP2Controller();
    controller.setName(name);
    controller.setGenerateControllerSample(generateParentSample);
    return controller;
  }

  /** Stock JMeter Transaction Controller, used as the parity baseline for the async controller. */
  public static TransactionController transactionController(String name,
                                                            boolean generateParentSample) {
    TransactionController controller = new TransactionController();
    controller.setName(name);
    controller.setGenerateParentSample(generateParentSample);
    return controller;
  }

  public static Outcome run(ThreadGroup group, Node... children) {
    return run(group, DEFAULT_TIMEOUT_MILLIS, children);
  }

  /**
   * Thread Group scheduler that has already run out. The first {@code stopSchedulerIfNeeded()} call
   * inside {@code JMeterThread.processSampler} then clears the running flag, so the run is cut
   * short right after the first sampler was handed out — which is what a duration-limited test
   * (or a manual Stop) does to whatever transaction happens to be open at that moment. With the
   * async controller that moment is a dispatch turn, so the open transaction has collected no
   * child result at all yet.
   */
  public static Consumer<JMeterThread> expiredScheduler() {
    return thread -> {
      thread.setScheduled(true);
      thread.setEndTime(System.currentTimeMillis() - 1);
    };
  }

  public static Outcome run(ThreadGroup group, long timeoutMillis, Node... children) {
    return run(group, timeoutMillis, thread -> {
    }, children);
  }

  /**
   * @param threadSetup applied to the {@link JMeterThread} just before it is started, for the
   *     settings a Thread Group normally pushes onto it (scheduler, start/end time)
   */
  public static Outcome run(ThreadGroup group, long timeoutMillis,
                            Consumer<JMeterThread> threadSetup, Node... children) {
    JMeterTestUtils.setupJmeterEnv();
    ListedHashTree tree = new ListedHashTree();
    HashTree groupTree = tree.add(group);
    for (Node child : children) {
      attach(groupTree, child);
    }

    TestCompiler.initialize();
    JMeterContextService.startTest();
    RecordingEngine engine = new RecordingEngine();
    JMeterThread jmeterThread =
        new JMeterThread(tree, thread -> { }, new ListenerNotifier());
    jmeterThread.setThreadGroup(group);
    jmeterThread.setThreadNum(0);
    jmeterThread.setThreadName(group.getName() + " 1-1");
    jmeterThread.setEngine(engine);
    jmeterThread.setOnErrorStopTest(group.getOnErrorStopTest());
    jmeterThread.setOnErrorStopTestNow(group.getOnErrorStopTestNow());
    jmeterThread.setOnErrorStopThread(group.getOnErrorStopThread());
    jmeterThread.setOnErrorStartNextLoop(group.getOnErrorStartNextLoop());
    threadSetup.accept(jmeterThread);

    Thread runner = new Thread(jmeterThread, group.getName() + " 1-1");
    runner.setDaemon(true);
    java.util.concurrent.atomic.AtomicReference<Throwable> uncaught =
        new java.util.concurrent.atomic.AtomicReference<>();
    runner.setUncaughtExceptionHandler((thread, throwable) -> uncaught.set(throwable));
    long start = System.currentTimeMillis();
    boolean hung = false;
    runner.start();
    try {
      runner.join(timeoutMillis);
      if (runner.isAlive()) {
        hung = true;
        jmeterThread.stop();
        jmeterThread.interrupt();
        runner.interrupt();
        runner.join(5_000L);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Scenario runner was interrupted", e);
    } finally {
      JMeterContextService.endTest();
    }
    return new Outcome(hung, System.currentTimeMillis() - start, engine.signals(), tree,
        readCompiledSamplers(jmeterThread), uncaught.get());
  }

  private static List<String> readCompiledSamplers(JMeterThread jmeterThread) {
    List<String> kinds = new ArrayList<>();
    try {
      java.lang.reflect.Field compilerField = JMeterThread.class.getDeclaredField("compiler");
      compilerField.setAccessible(true);
      TestCompiler compiler = (TestCompiler) compilerField.get(jmeterThread);
      java.lang.reflect.Field mapField =
          TestCompiler.class.getDeclaredField("samplerConfigMap");
      mapField.setAccessible(true);
      Object map = mapField.get(compiler);
      if (map instanceof java.util.Map) {
        for (Object key : ((java.util.Map<?, ?>) map).keySet()) {
          kinds.add(key.getClass().getSimpleName());
        }
      }
    } catch (Exception e) {
      kinds.add("<unavailable: " + e.getClass().getSimpleName() + ">");
    }
    return kinds;
  }

  private static void attach(HashTree parent, Node node) {
    HashTree subTree = parent.add(node.element);
    for (Node child : node.children) {
      attach(subTree, child);
    }
  }

  /**
   * Records the engine-level stop requests instead of actually tearing down a global test, so
   * "Stop Test" and "Stop Test Now" can be told apart from a plain "Stop Thread".
   */
  private static final class RecordingEngine extends StandardJMeterEngine {

    private final List<String> signals = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void askThreadsToStop() {
      signals.add("askThreadsToStop");
    }

    @Override
    public void stopTest() {
      signals.add("stopTest");
    }

    @Override
    public void stopTest(boolean now) {
      signals.add("stopTest(" + now + ")");
    }

    private List<String> signals() {
      synchronized (signals) {
        return new ArrayList<>(signals);
      }
    }
  }
}
