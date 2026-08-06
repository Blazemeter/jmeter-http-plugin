package com.blazemeter.jmeter.http2.control.async;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.Closeable;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;

/**
 * Network-free stand-in for {@link HTTP2JettyClient} that still exercises the real asynchronous
 * handshake of the plugin: the sampler creates its own {@link HTTP2FutureResponseListener}, and this
 * stub completes it later from a scheduler thread through the public
 * {@link HTTP2FutureResponseListener#completeWith} entry point. That means
 * {@code HTTP2Controller.waitForDoneHTTP2()} really busy-waits on a real latch, so concurrency,
 * completion order and hangs are all reproduced faithfully and deterministically.
 */
public final class StubAsyncTransport implements Closeable {

  private final HTTP2JettyClient client = mock(HTTP2JettyClient.class);
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "stub-async-transport");
        thread.setDaemon(true);
        return thread;
      });
  private final Map<String, ResponseSpec> specs = new LinkedHashMap<>();
  private final List<String> dispatchOrder = Collections.synchronizedList(new ArrayList<>());
  private final List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
  private final List<String> syncSampled = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger maxInFlight = new AtomicInteger();

  public StubAsyncTransport() {
    try {
      when(client.getMaxBufferSize()).thenReturn(2 * 1024 * 1024);
      when(client.getRequestTimeout()).thenReturn(60_000);
      when(client.sampleAsync(any(), any(), any())).thenAnswer(invocation -> onDispatch(
          invocation.getArgument(0), invocation.getArgument(2)));
      // Production async path goes through dispatchAsync (HTTP/3 race); sampleAsync alone is no
      // longer what HTTP2Sampler calls on the first pass.
      when(client.dispatchAsync(any(), any(), any())).thenAnswer(invocation -> {
        Request request = onDispatch(invocation.getArgument(0), invocation.getArgument(2));
        // Same second step as HTTP2JettyClient.dispatchAsync: fire the listener (or throw when
        // the stub is configured with SEND_THROWS).
        request.send(invocation.getArgument(2));
        return request;
      });
      when(client.sampleFromListener(any(), any(), anyBoolean(), anyInt(), any()))
          .thenAnswer(invocation -> onCompletion(
              invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(4)));
      when(client.sample(any(), any(), anyBoolean(), anyInt()))
          .thenAnswer(invocation -> onSyncSample(
              invocation.getArgument(0), invocation.getArgument(1)));
    } catch (Exception e) {
      throw new IllegalStateException("Could not set up the stub transport", e);
    }
  }

  /** How a stubbed request behaves; mutate before running the scenario. */
  public static final class ResponseSpec {

    private final List<String> embeddedResources = new ArrayList<>();
    private String uri;
    private String body = "<html><body>stub OK</body></html>";
    private String responseCode = "200";
    private String responseMessage = "OK";
    private boolean success = true;
    private long latencyMillis = 40;
    private Failure failure = Failure.NONE;
    private int clientFactoryFailsFromCall;

    private ResponseSpec(String uri) {
      this.uri = uri;
    }

    public ResponseSpec uri(String value) {
      this.uri = value;
      return this;
    }

    public ResponseSpec body(String value) {
      this.body = value;
      return this;
    }

    public ResponseSpec responseCode(String value) {
      this.responseCode = value;
      return this;
    }

    public ResponseSpec failing(String code, String message, String responseBody) {
      this.responseCode = code;
      this.responseMessage = message;
      this.body = responseBody;
      this.success = false;
      return this;
    }

    public ResponseSpec latency(long millis) {
      this.latencyMillis = millis;
      return this;
    }

    public ResponseSpec embeddedResources(String... labels) {
      Collections.addAll(this.embeddedResources, labels);
      return this;
    }

    public ResponseSpec failure(Failure value) {
      this.failure = value;
      return this;
    }

    /**
     * Makes the sampler's client factory throw from the n-th call on. Call 1 is the async dispatch
     * pass and call 2 the completion pass, so {@code 2} reproduces a client that becomes
     * unavailable between dispatch and completion.
     */
    public ResponseSpec clientFactoryFailsFromCall(int call) {
      this.clientFactoryFailsFromCall = call;
      return this;
    }
  }

  /** Ways a stubbed request can go wrong, all observed in the field. */
  public enum Failure {
    /** Normal completion. */
    NONE,
    /** {@code client.sampleAsync} throws, e.g. unsupported method or auth manager failure. */
    DISPATCH_THROWS,
    /** {@code request.send(listener)} throws, e.g. connection refused at dispatch time. */
    SEND_THROWS,
    /** The request is accepted but its listener is never completed, e.g. a dropped connection. */
    NEVER_COMPLETES
  }

  public HTTP2JettyClient client() {
    return client;
  }

  /** Creates an HTTP2Sampler wired to this stub, registering a default spec under its name. */
  public HTTP2Sampler sampler(String name) {
    ResponseSpec spec = spec(name);
    AtomicInteger factoryCalls = new AtomicInteger();
    HTTP2Sampler sampler = new HTTP2Sampler(() -> {
      int call = factoryCalls.incrementAndGet();
      int failFrom = spec(name).clientFactoryFailsFromCall;
      if (failFrom > 0 && call >= failFrom) {
        throw new IllegalStateException("stub transport: client unavailable for " + name);
      }
      return client;
    });
    sampler.setName(name);
    sampler.setMethod(HTTPConstants.GET);
    URI uri = URI.create(spec.uri);
    sampler.setProtocol(uri.getScheme());
    sampler.setDomain(uri.getHost());
    sampler.setPath(uri.getPath());
    return sampler;
  }

  /** Spec for a sampler name, created with defaults on first access. */
  public ResponseSpec spec(String name) {
    synchronized (specs) {
      return specs.computeIfAbsent(name, key -> new ResponseSpec("https://stub.local/" + slug(key)));
    }
  }

  public List<String> dispatchOrder() {
    synchronized (dispatchOrder) {
      return new ArrayList<>(dispatchOrder);
    }
  }

  public List<String> completionOrder() {
    synchronized (completionOrder) {
      return new ArrayList<>(completionOrder);
    }
  }

  public List<String> syncSampled() {
    synchronized (syncSampled) {
      return new ArrayList<>(syncSampled);
    }
  }

  /** Peak number of requests in flight at the same time; 1 means no parallelism happened. */
  public int maxConcurrentInFlight() {
    return maxInFlight.get();
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }

  private Request onDispatch(HTTP2Sampler sampler, HTTP2FutureResponseListener listener)
      throws Exception {
    String name = sampler.getName();
    ResponseSpec spec = spec(name);
    dispatchOrder.add(name);
    if (spec.failure == Failure.DISPATCH_THROWS) {
      throw new ConnectException("stub transport: dispatch refused for " + name);
    }
    Request request = mock(Request.class);
    when(request.getURI()).thenReturn(URI.create(spec.uri));
    listener.setRequest(request);
    if (spec.failure == Failure.SEND_THROWS) {
      doThrow(new IllegalStateException("stub transport: send failed for " + name))
          .when(request).send(any(Response.CompleteListener.class));
      return request;
    }
    trackInFlight(1);
    if (spec.failure == Failure.NEVER_COMPLETES) {
      return request;
    }
    long start = System.currentTimeMillis();
    scheduler.schedule(() -> {
      listener.completeWith(null, start, System.currentTimeMillis());
      completionOrder.add(name);
      trackInFlight(-1);
    }, spec.latencyMillis, TimeUnit.MILLISECONDS);
    return request;
  }

  private HTTPSampleResult onCompletion(HTTP2Sampler sampler, HTTPSampleResult result,
                                        HTTP2FutureResponseListener listener)
      throws TimeoutException {
    if (listener.isCancelled() || !listener.isDone()) {
      // Mirrors the real client: sampleFromListener goes through listener.get(timeout), which throws
      // rather than inventing a response when the exchange never completed or was aborted.
      throw new TimeoutException("stub transport: no response for " + sampler.getName());
    }
    ResponseSpec spec = spec(sampler.getName());
    long start = listener.getResponseStart() > 0
        ? listener.getResponseStart()
        : System.currentTimeMillis();
    long end = listener.getResponseEnd() > start ? listener.getResponseEnd() : start + 1;
    return fill(result, spec, start, end);
  }

  private HTTPSampleResult onSyncSample(HTTP2Sampler sampler, HTTPSampleResult result)
      throws InterruptedException {
    String name = sampler.getName();
    ResponseSpec spec = spec(name);
    syncSampled.add(name);
    trackInFlight(1);
    long start = System.currentTimeMillis();
    if (spec.latencyMillis > 0) {
      Thread.sleep(spec.latencyMillis);
    }
    trackInFlight(-1);
    return fill(result, spec, start, Math.max(start + 1, System.currentTimeMillis()));
  }

  private HTTPSampleResult fill(HTTPSampleResult result, ResponseSpec spec, long start, long end) {
    if (result.getStartTime() == 0 && result.getEndTime() == 0) {
      result.setStampAndTime(start, Math.max(1, end - start));
    }
    result.setResponseCode(spec.responseCode);
    result.setResponseMessage(spec.responseMessage);
    result.setSuccessful(spec.success);
    result.setDataType(SampleResult.TEXT);
    result.setContentType("text/html; charset=UTF-8");
    result.setEncodingAndType("text/html; charset=UTF-8");
    result.setResponseData(spec.body, StandardCharsets.UTF_8.name());
    result.setRequestHeaders("X-Stub-Request: " + result.getSampleLabel());
    result.setResponseHeaders("X-Stub-Response: " + spec.responseCode);
    for (String resource : spec.embeddedResources) {
      HTTPSampleResult sub = new HTTPSampleResult();
      sub.setSampleLabel(resource);
      sub.setStampAndTime(start, 1);
      sub.setResponseCodeOK();
      sub.setResponseMessage("OK");
      sub.setSuccessful(true);
      sub.setDataType(SampleResult.TEXT);
      sub.setResponseData("stub embedded resource", StandardCharsets.UTF_8.name());
      result.addSubResult(sub, false);
    }
    return result;
  }

  private void trackInFlight(int delta) {
    int now = inFlight.addAndGet(delta);
    maxInFlight.accumulateAndGet(now, Math::max);
  }

  private static String slug(String name) {
    return name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
  }
}
