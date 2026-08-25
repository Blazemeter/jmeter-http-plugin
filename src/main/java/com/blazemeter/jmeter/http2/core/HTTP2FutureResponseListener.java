package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.LowLevelDebugLog.lowLevelDebug;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.jmeter.samplers.SampleResult;
import org.eclipse.jetty.client.AbstractResponseListener;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2FutureResponseListener extends BufferingResponseListener
    implements Future<ContentResponse> {

  protected static final Logger LOG = LoggerFactory.getLogger(HTTP2FutureResponseListener.class);
  
  // Track if onComplete was called
  private volatile boolean onCompleteCalled = false;
  private final CountDownLatch latch = new CountDownLatch(1);
  private Request request;
  private ContentResponse response;
  private Throwable failure;
  private volatile boolean cancelled;
  /**
   * Set once a result has been handed to this listener from the outside via
   * {@link #completeWith}, after which callbacks from its own request are ignored. See that method.
   */
  private volatile boolean sealed;
  /**
   * Protocol label when this listener belongs to one side of a protocol race ("HTTP/3", "HTTP/2").
   * A failure on a raced attempt is not an error: it means that protocol was not negotiated for the
   * origin, while the competing attempt still serves the request. See {@link #onComplete}.
   */
  private volatile String raceProtocol;
  /**
   * When the request went out and when it completed. Written on a Jetty thread and read on the
   * JMeter one, so each end is a single immutable value published through one volatile write:
   * whoever reads a {@link Stamp} sees all of it, and never a wall-clock reading paired with a
   * monotonic one that has not been taken yet.
   */
  private volatile Stamp responseStartStamp;
  private volatile Stamp responseEndStamp;
  /**
   * {@link #releaseTransportBuffers()} is invoked from several completion paths (wrapper build,
   * sealed HE abort {@code onFailure}/{@code onComplete}, {@code cancel}, sample materialisation).
   * Jetty may also have released the accumulator already on abort — a second {@code clear()} then
   * throws {@code IllegalStateException: Already released} and poisons the sample.
   */
  private final AtomicBoolean transportBuffersReleased = new AtomicBoolean();

  public HTTP2FutureResponseListener() {
    this(-1);
  }

  public HTTP2FutureResponseListener(int maxLength) {
    super(maxLength);
    setStart();
    lowLevelDebug("=== HTTP2FutureResponseListener CREATED ===");
    lowLevelDebug("maxLength: {}", maxLength);
    lowLevelDebug("Thread: {}", Thread.currentThread().getName());
  }

  public void setRequest(Request request) {
    this.request = request;
    lowLevelDebug("=== setRequest() called ===");
    lowLevelDebug("Request URI: {}", request != null ? request.getURI() : "null");
    lowLevelDebug("Thread: {}", Thread.currentThread().getName());
  }

  public Request getRequest() {
    return request;
  }

  /** Marks this listener as one side of a protocol race. See {@link #raceProtocol}. */
  public void setRaceProtocol(String raceProtocol) {
    this.raceProtocol = raceProtocol;
  }

  public String getRaceProtocol() {
    return raceProtocol;
  }

  protected void setStart() {
    if (this.responseStartStamp == null) {
      this.responseStartStamp = Stamp.now();
    }
  }

  protected void setEnd() {
    this.responseEndStamp = Stamp.now();
  }

  /**
   * When the request went out as a {@link System#currentTimeMillis()} time, or 0. An absolute time
   * to report or to log; to stamp it on a sample use {@link #getResponseStartOn}, and to measure a
   * wait from it use {@link #getResponseStartNanos}.
   */
  public long getResponseStart() {
    return Stamp.wallClockOf(this.responseStartStamp);
  }

  /**
   * When the exchange completed as a {@link System#currentTimeMillis()} time, or 0. See
   * {@link #getResponseStart()} for which of these three readings to use.
   */
  public long getResponseEnd() {
    return Stamp.wallClockOf(this.responseEndStamp);
  }

  /**
   * The {@link System#nanoTime()} reading of when the request went out, empty only when the request
   * never went out at all.
   *
   * <p>For measuring how long something has been waiting, which must not be thrown off by the
   * machine clock moving under it.
   */
  public OptionalLong getResponseStartNanos() {
    Stamp stamp = this.responseStartStamp;
    return stamp == null ? OptionalLong.empty() : OptionalLong.of(stamp.nanoTime);
  }

  /**
   * When the request went out, on {@code result}'s own clock, or 0 when that was never recorded.
   *
   * <p>This, and never {@link #getResponseStart()}, is what a {@link SampleResult} must be stamped
   * from: a sample whose start comes from {@code sampleStart()} and whose end comes from the wall
   * clock reports the distance between those two clocks as part of its duration. See
   * {@link SampleClock}.
   */
  public long getResponseStartOn(SampleResult result) {
    return Stamp.on(result, this.responseStartStamp);
  }

  /**
   * When the exchange completed, on {@code result}'s own clock, or 0 when that was never recorded.
   * See {@link #getResponseStartOn}.
   */
  public long getResponseEndOn(SampleResult result) {
    return Stamp.on(result, this.responseEndStamp);
  }

  /**
   * Adopts a result produced by a different request, used when a protocol race resolves in favour
   * of a competing attempt and the winner's response is reported through this listener.
   *
   * <p>Seals the listener: whoever calls this then aborts the losing request, and that abort makes
   * Jetty invoke {@link #onComplete}/{@link #onFailure} here with a CancellationException. Letting
   * those through would overwrite {@link #failure} after the winning response was already stored,
   * and {@link #getResult()} reports "failed after response received" whenever a failure is present
   * - so a won race would surface as an error to anyone reading this listener afterwards. The
   * synchronous caller never noticed because it returns the winner directly instead of reading back
   * from here; a consumer that polls {@link #isDone()} and then calls {@link #get()} does.
   */
  public void completeWith(ContentResponse response, HTTP2FutureResponseListener source) {
    if (source.responseStartStamp != null) {
      this.responseStartStamp = source.responseStartStamp;
    }
    this.responseEndStamp =
        source.responseEndStamp != null ? source.responseEndStamp : Stamp.now();
    seal(response);
  }

  /**
   * Adopts a result timed only on the wall clock. Prefer
   * {@link #completeWith(ContentResponse, HTTP2FutureResponseListener)}, which is what the protocol
   * race uses: it carries the winner's monotonic readings over as well, so the sample stamped from
   * this listener is translated from the exchange it actually reports.
   */
  public void completeWith(ContentResponse response, long responseStart, long responseEnd) {
    if (responseStart > 0) {
      // These stamps describe another exchange, so this listener's own monotonic readings do not
      // match them and must not be used to translate them.
      this.responseStartStamp = Stamp.ofWallClock(responseStart);
    }
    this.responseEndStamp =
        responseEnd > 0 ? Stamp.ofWallClock(responseEnd) : Stamp.now();
    seal(response);
  }

  /** Stores the adopted response and seals this listener: common tail of {@code completeWith}. */
  private void seal(ContentResponse response) {
    this.response = response;
    this.failure = null;
    this.onCompleteCalled = true;
    this.sealed = true;
    // Drop any partial body this listener may have buffered for its own (losing) attempt — the
    // adopted response already carries the winner's bytes.
    releaseTransportBuffers();
    this.latch.countDown();
  }

  /**
   * Called when the request fails before completion.
   * This method is called BEFORE onComplete() when there's a failure.
   * This is our opportunity to intercept protocol_error early.
   */
  @Override
  public void onFailure(Response response, Throwable failure) {
    if (sealed) {
      // Losing side of a resolved race being aborted; see completeWith.
      lowLevelDebug("onFailure() ignored, listener already completed by a competing attempt");
      releaseTransportBuffers();
      return;
    }
    lowLevelDebug("=== onFailure() CALLED ===");
    lowLevelDebug("Thread: {}", Thread.currentThread().getName());
    lowLevelDebug("Response: {}", response != null ? "present" : "null");
    String failureInfo = failure != null
        ? failure.getClass().getName() + ": " + failure.getMessage()
        : "null";
    lowLevelDebug("Failure: {}", failureInfo);
    
    // Store the failure immediately. If HPACK decode failed earlier,
    // avoid protocol_error handling.
    this.failure = failure;
    if (failure != null && HpackFailureDetector.indicatesHpackFailure(failure)) {
      lowLevelDebug("HPACK-related failure detected for request");
      super.onFailure(response, failure);
      return;
    }

    // Check if this is a protocol_error
    if (failure != null) {
      lowLevelDebug("Checking isProtocolError in onFailure() for: {}",
          failure.getClass().getName());
      boolean isProtocolError = ProtocolErrorException.isProtocolError(failure);
      lowLevelDebug("isProtocolError returned: {}", isProtocolError);

      if (isProtocolError) {
        lowLevelDebug("=== PROTOCOL_ERROR DETECTED IN onFailure() ===");
        lowLevelDebug("Original failure: {}: {}",
            failure.getClass().getName(), failure.getMessage());
        lowLevelDebug("HTTP/2 protocol_error detected in onFailure() - "
            + "replacing with ProtocolErrorException");
        String message = failure.getMessage();
        // Replace failure with ProtocolErrorException so it can be caught specifically
        this.failure = new ProtocolErrorException(
            message != null ? message : "protocol_error",
            failure);
        lowLevelDebug("Replaced with ProtocolErrorException: {}",
            this.failure.getClass().getName());
      }
    }

    // Call super to maintain normal behavior
    super.onFailure(response, failure);
  }

  @Override
  public void onComplete(Result result) {
    if (sealed) {
      // Losing side of a resolved race being aborted; see completeWith.
      lowLevelDebug("onComplete() ignored, listener already completed by a competing attempt");
      releaseTransportBuffers();
      return;
    }
    // CRITICAL: Mark that onComplete was called
    onCompleteCalled = true;
    
    lowLevelDebug("=== onComplete() CALLED ===");
    lowLevelDebug("Thread: {}", Thread.currentThread().getName());
    lowLevelDebug("Result: {}", result != null ? "present" : "null");
    
    if (result != null) {
      String failureInfo = result.getFailure() != null
          ? result.getFailure().getClass().getName() + ": "
              + result.getFailure().getMessage()
          : "null";
      lowLevelDebug("Result.getFailure(): {}", failureInfo);
      lowLevelDebug("Result.getResponse(): {}", 
          result.getResponse() != null ? "present" : "null");
      if (result.getResponse() != null) {
        lowLevelDebug("Response status: {}, version: {}", 
            result.getResponse().getStatus(), result.getResponse().getVersion());
      }
    }
    
    setEnd();
    failure = result != null ? result.getFailure() : null;
    
    lowLevelDebug("failure set: {}",
        failure != null ? failure.getClass().getName() + ": " + failure.getMessage() : "null");
    
    // CRITICAL: Detect protocol_error immediately when failure is set
    // This allows us to replace it with ProtocolErrorException before it propagates
    if (failure != null) {
      lowLevelDebug("Checking isProtocolError for: {}", failure.getClass().getName());
      boolean isProtocolError = ProtocolErrorException.isProtocolError(failure);
      lowLevelDebug("isProtocolError returned: {}", isProtocolError);
      
      if (isProtocolError && !HpackFailureDetector.indicatesHpackFailure(failure)) {
        lowLevelDebug("=== PROTOCOL_ERROR DETECTED IN onComplete() ===");
        lowLevelDebug("Original failure: {}: {}",
            failure.getClass().getName(), failure.getMessage());
        lowLevelDebug("HTTP/2 protocol_error detected in onComplete() - "
            + "replacing with ProtocolErrorException");
        String message = failure.getMessage();
        failure = new ProtocolErrorException(
            message != null ? message : "protocol_error",
            failure);
        lowLevelDebug("Replaced with ProtocolErrorException: {}", failure.getClass().getName());
      }
    }
    
    if (result != null && result.getResponse() != null) {
      Response httpResponse = result.getResponse();
      lowLevelDebug("Response completed: status={}, version={}, reason={}, failure={}",
          httpResponse.getStatus(), httpResponse.getVersion(), httpResponse.getReason(),
          failure != null ? failure.getClass().getName() : "none");
      
      if (httpResponse.getVersion() != null) {
        lowLevelDebug("HTTP version negotiated: {}", httpResponse.getVersion());
      }
      
      // In Jetty 12, ContentResponse is abstract - create a wrapper implementation
      response = new ContentResponseWrapper(httpResponse, getContent(),
          getMediaType(), getEncoding());
      // Wrapper owns the body bytes now; drop Jetty's accumulator / cached content array so a
      // still-referenced listener (async controller wait, HE race) does not keep a second copy.
      releaseTransportBuffers();
    } else {
      // Failure / cancel with no response: Jetty may still hold a partial accumulator (common for
      // Happy-Eyeballs losers aborted via request.abort rather than listener.cancel).
      lowLevelDebug("Response is null in onComplete()");
      releaseTransportBuffers();
    }
    
    if (failure != null) {
      if (failure instanceof CancellationException) {
        // The losing side of a resolved protocol race, or an explicit cancel(): expected, and the
        // winner's response is reported elsewhere. Logging it as an error filled the log with
        // failures that are not failures, one per raced request.
        lowLevelDebug("{} attempt cancelled: {}",
            raceProtocol != null ? raceProtocol : "Request", failure.getMessage());
      } else if (raceProtocol != null) {
        // One side of a race failing on its own is the expected outcome when the origin does not
        // support that protocol: the other attempt serves the request, and the race only gives up
        // once both sides fail - at which point the caller reports the real failure. Saying "not
        // negotiated" rather than "failed" keeps this from reading as a broken request.
        lowLevelDebug("{} not negotiated for {}: {}", raceProtocol,
            request != null ? request.getURI() : "unknown", failure.getMessage());
      } else {
        // Internal transport detail; raise the logger to DEBUG to diagnose. The caller still
        // observes the failure via get()/getResult() and decides fallback or sample error.
        LOG.debug("Request failed with exception: type={}, message={}",
            failure.getClass().getName(), failure.getMessage());
        if (failure instanceof IOException) {
          IOException ioException = (IOException) failure;
          String message = ioException.getMessage();
          LOG.debug("IOException message: {}", message);
          if (message != null && message.contains("protocol_error")) {
            LOG.debug("HTTP/2 protocol_error in onComplete() - ALPN negotiation likely failed");
          }
        }
        lowLevelDebug("Full failure stack trace:", failure);
      }
    }
    
    latch.countDown();
  }
  
  /**
   * Wrapper class to implement ContentResponse interface in Jetty 12
   * where ContentResponse is abstract.
   */
  private static class ContentResponseWrapper implements ContentResponse {
    private final Response response;
    private final byte[] content;
    private final String mediaType;
    private final String encoding;
    
    ContentResponseWrapper(Response response, byte[] content, String mediaType, String encoding) {
      this.response = response;
      this.content = content != null ? content : new byte[0];
      this.mediaType = mediaType;
      this.encoding = encoding;
    }
    
    @Override
    public int getStatus() {
      return response.getStatus();
    }
    
    @Override
    public String getReason() {
      return response.getReason();
    }
    
    @Override
    public org.eclipse.jetty.http.HttpVersion getVersion() {
      return response.getVersion();
    }
    
    @Override
    public org.eclipse.jetty.http.HttpFields getHeaders() {
      return response.getHeaders();
    }
    
    @Override
    public org.eclipse.jetty.http.HttpFields getTrailers() {
      return response.getTrailers();
    }
    
    @Override
    public Request getRequest() {
      return response.getRequest();
    }
    
    @Override
    public byte[] getContent() {
      return content;
    }
    
    @Override
    public String getMediaType() {
      return mediaType;
    }
    
    @Override
    public String getEncoding() {
      return encoding;
    }
    
    @Override
    public String getContentAsString() {
      if (encoding != null) {
        try {
          return new String(content, Charset.forName(encoding));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
          LOG.warn("Unsupported charset '{}', falling back to UTF-8", encoding, e);
        }
      }
      return new String(content, StandardCharsets.UTF_8);
    }
    
    // ContentResponse extends Response, so we need to implement Response methods
    // In Jetty 12, abort() returns CompletableFuture<Boolean> instead of Callback
    @Override
    public java.util.concurrent.CompletableFuture<Boolean> abort(Throwable failure) {
      return response.abort(failure);
    }
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    lowLevelDebug("=== cancel() called ===");
    cancelled = true;
    // In Jetty 12, abort() returns CompletableFuture<Boolean>
    if (request != null) {
      request.abort(new CancellationException());
    }
    releaseTransportBuffers();
    return true;
  }

  /**
   * Called once the sample has been materialised into a
   * {@link org.apache.jmeter.samplers.SampleResult} so this listener can drop the Jetty request
   * graph (attributes, body, conversation). The SampleResult already owns its own body copy; the
   * {@link ContentResponse} wrapper (and its link back to Jetty's {@link Response}/{@link Request})
   * must not stay pinned on this listener.
   */
  public void releaseAfterSampleMaterialised() {
    releaseTransportBuffers();
    this.request = null;
    this.response = null;
  }

  /**
   * Drops Jetty {@link AbstractResponseListener}'s cached {@code content} byte[] after the body has
   * been copied into {@link ContentResponseWrapper} / SampleResult. That field is a full second
   * copy of the response under unlimited buffering.
   *
   * <p>Do <strong>not</strong> {@code release()} the {@code accumulator}
   * {@link org.eclipse.jetty.io.RetainableByteBuffer}: it is owned by Jetty's listener /
   * {@code ByteBufferPool}. Returning it to the pool while connections may still reference it
   * corrupts pooled buffers and collapses throughput (protocol errors, repeated HTTP/3
   * exploration, multi-second samples). Jetty releases the accumulator with the exchange; we only
   * drop the Java {@code content} duplicate and null request/response links.
   */
  private void releaseTransportBuffers() {
    if (!transportBuffersReleased.compareAndSet(false, true)) {
      return;
    }
    try {
      Field contentField = AbstractResponseListener.class.getDeclaredField("content");
      contentField.setAccessible(true);
      contentField.set(this, BufferUtil.EMPTY_BYTES);
    } catch (ReflectiveOperationException e) {
      lowLevelDebug("Could not clear BufferingResponseListener.content", e);
    }
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public boolean isDone() {
    return latch.getCount() == 0 || isCancelled();
  }

  @Override
  public ContentResponse get() throws InterruptedException, ExecutionException {
    lowLevelDebug("=== get() called (no timeout) ===");
    lowLevelDebug("Thread: {}", Thread.currentThread().getName());
    lowLevelDebug("onCompleteCalled before await: {}", onCompleteCalled);
    setStart();
    latch.await();
    lowLevelDebug("latch.await() completed, onCompleteCalled: {}", onCompleteCalled);
    try {
      return getResult();
    } catch (ProtocolErrorException e) {
      LOG.debug("ProtocolErrorException caught in get(), wrapping in ExecutionException");
      // Wrap ProtocolErrorException in ExecutionException so HTTP2JettyClient (the single,
      // centralized place for HTTP/1.1 fallback decisions) can unwrap it and handle the fallback.
      throw new ExecutionException(e);
    }
  }

  @Override
  public ContentResponse get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException,
      TimeoutException {
    lowLevelDebug("=== get(timeout) called ===");
    lowLevelDebug("Timeout: {} {}", timeout, unit);
    lowLevelDebug("Thread: {}", Thread.currentThread().getName());
    lowLevelDebug("onCompleteCalled before await: {}", onCompleteCalled);
    setStart();
    boolean expired = !latch.await(timeout, unit);
    lowLevelDebug("latch.await() completed, expired: {}, onCompleteCalled: {}",
        expired, onCompleteCalled);
    if (expired) {
      lowLevelDebug("Timeout expired, throwing TimeoutException");
      throw new TimeoutException();
    }
    try {
      return getResult();
    } catch (ProtocolErrorException e) {
      LOG.debug("ProtocolErrorException caught in get(timeout), wrapping in ExecutionException");
      // Wrap ProtocolErrorException in ExecutionException so HTTP2JettyClient (the single,
      // centralized place for HTTP/1.1 fallback decisions) can unwrap it and handle the fallback.
      throw new ExecutionException(e);
    }
  }

  private ContentResponse getResult() throws ExecutionException, ProtocolErrorException {
    lowLevelDebug("=== getResult() called ===");
    lowLevelDebug("Thread: {}", Thread.currentThread().getName());
    lowLevelDebug("onCompleteCalled: {}", onCompleteCalled);
    lowLevelDebug("isCancelled(): {}", isCancelled());
    String failureInfo = failure != null
        ? failure.getClass().getName() + ": " + failure.getMessage()
        : "null";
    lowLevelDebug("failure: {}", failureInfo);
    lowLevelDebug("response: {}", response != null ? "present" : "null");
    
    // If onComplete was never called, log a warning
    if (!onCompleteCalled) {
      lowLevelDebug("getResult() called but onComplete() was NEVER called!");
      lowLevelDebug("This suggests the error was handled before onComplete() could execute");
      lowLevelDebug("The error may have been thrown synchronously or handled by "
          + "BufferingResponseListener");
    }
    
    if (isCancelled()) {
      lowLevelDebug("Request was cancelled");
      throw (CancellationException) new CancellationException().initCause(failure);
    }
    if (failure != null) { // Failure and Response can coexist.
      if (HpackFailureDetector.indicatesHpackFailure(failure)) {
        lowLevelDebug("HPACK-related failure detected in getResult()");
      }
      if (response == null) { // Only generate exception response when an response not exist
        // Generated by nginx GOAWAY
        LOG.debug("Request failed without response: exception type={}, message={}",
            failure.getClass().getName(), failure.getMessage());

        // Check if this is a protocol_error and throw ProtocolErrorException instead
        boolean isProtocolError = ProtocolErrorException.isProtocolError(failure);
        LOG.debug("ProtocolErrorException.isProtocolError() returned: {}", isProtocolError);

        if (isProtocolError && !HpackFailureDetector.indicatesHpackFailure(failure)) {
          String message = failure.getMessage();
          LOG.debug("HTTP/2 protocol_error detected in getResult() - "
              + "throwing ProtocolErrorException (HTTP/1.1 fallback may follow)");
          throw new ProtocolErrorException(message != null ? message : "protocol_error", failure);
        } else if (!HpackFailureDetector.indicatesHpackFailure(failure)) {
          LOG.debug("Failure is NOT detected as protocol_error, will throw ExecutionException");
        }

        if (failure instanceof IOException) {
          IOException ioException = (IOException) failure;
          String message = ioException.getMessage();
          LOG.debug("IOException details: {}", message);
        }
        lowLevelDebug("Full failure stack trace (no response):", failure);
        throw new ExecutionException(failure);
      } else {
        // It is a failure caused after obtaining the response,
        // analyzing what type of failure it is, and incorporating mechanisms to manage it.
        if (failure instanceof CancellationException) {
          // The losing side of a protocol race ends cancelled once the winner has answered. That is
          // the algorithm working, not a failure, and warning about it made a healthy run look
          // broken once per raced request.
          lowLevelDebug("{} attempt cancelled after response: status={}, version={}",
              raceProtocol != null ? raceProtocol : "Request", response.getStatus(),
              response.getVersion());
        } else if (raceProtocol != null) {
          LOG.debug("{} not negotiated after response: status={}, version={}, exception={}",
              raceProtocol, response.getStatus(), response.getVersion(),
              failure.getClass().getName());
        } else {
          LOG.debug("Request failed after response received: status={}, version={}, exception={}",
              response.getStatus(), response.getVersion(), failure.getClass().getName());
        }

        // Check if this is a protocol_error even though we have a response
        if (ProtocolErrorException.isProtocolError(failure)
            && !HpackFailureDetector.indicatesHpackFailure(failure)) {
          String message = failure.getMessage();
          LOG.debug("HTTP/2 protocol_error detected after response - "
              + "throwing ProtocolErrorException");
          throw new ProtocolErrorException(message != null ? message : "protocol_error", failure);
        }

        lowLevelDebug("Failure after response received:", failure);
        throw new ExecutionException(failure);
      }
    }

    if (response == null) {
      lowLevelDebug("Response is null in getResult() but no failure was set");
    } else {
      lowLevelDebug("Response retrieved successfully: status={}, version={}", 
          response.getStatus(), response.getVersion());
    }
    return response;
  }

  /**
   * One instant of the exchange, read on both clocks at once so a consumer can pick the one it
   * needs: the wall clock to report an absolute time, the monotonic reading to measure a duration
   * or to translate the instant onto a {@link SampleResult}'s clock.
   *
   * <p>Immutable, so publishing it through a single volatile field is enough for a JMeter thread to
   * see a whole instant rather than half of one written by a Jetty thread.
   */
  private static final class Stamp {

    private final long wallClockMillis;
    private final long nanoTime;
    /** Whether {@link #nanoTime} belongs to this instant, rather than never having been taken. */
    private final boolean monotonic;

    private Stamp(long wallClockMillis, long nanoTime, boolean monotonic) {
      this.wallClockMillis = wallClockMillis;
      this.nanoTime = nanoTime;
      this.monotonic = monotonic;
    }

    private static Stamp now() {
      // The monotonic reading first: it is the one this instant gets translated with, the wall
      // clock one is only ever reported as it is.
      long nanoTime = System.nanoTime();
      return new Stamp(System.currentTimeMillis(), nanoTime, true);
    }

    /**
     * An instant known only as a wall-clock time, adopted from another exchange. Its monotonic
     * counterpart is derived from how long ago it was, so measuring a wait from it still works;
     * {@code monotonic} stays false because the derivation already went through the wall clock and
     * translating it again would compound the two conversions.
     */
    private static Stamp ofWallClock(long wallClockMillis) {
      long nanoTime = System.nanoTime()
          - TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis() - wallClockMillis);
      return new Stamp(wallClockMillis, nanoTime, false);
    }

    private static long wallClockOf(Stamp stamp) {
      return stamp == null ? 0 : stamp.wallClockMillis;
    }

    /** This instant on {@code result}'s own clock, or 0 when there is no instant. */
    private static long on(SampleResult result, Stamp stamp) {
      if (stamp == null) {
        return 0;
      }
      return stamp.monotonic
          ? SampleClock.fromNanoTime(result, stamp.nanoTime)
          : SampleClock.fromWallClock(result, stamp.wallClockMillis);
    }
  }
}

