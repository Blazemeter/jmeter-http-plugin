package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.LowLevelDebugLog.lowLevelDebug;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
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
  private long responseStart;
  private long responseEnd;

  public HTTP2FutureResponseListener() {
    this(2 * 1024 * 1024);
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

  protected void setStart() {
    if (this.responseStart == 0) {
      this.responseStart = System.currentTimeMillis();
    }
  }

  protected void setEnd() {
    this.responseEnd = System.currentTimeMillis();
  }

  public long getResponseStart() {
    return this.responseStart;
  }

  public long getResponseEnd() {
    return this.responseEnd;
  }

  public void completeWith(ContentResponse response, long responseStart, long responseEnd) {
    this.response = response;
    this.failure = null;
    if (responseStart > 0) {
      this.responseStart = responseStart;
    }
    this.responseEnd = responseEnd > 0 ? responseEnd : System.currentTimeMillis();
    this.onCompleteCalled = true;
    this.latch.countDown();
  }

  /**
   * Called when the request fails before completion.
   * This method is called BEFORE onComplete() when there's a failure.
   * This is our opportunity to intercept protocol_error early.
   */
  @Override
  public void onFailure(Response response, Throwable failure) {
    lowLevelDebug("=== onFailure() CALLED ===");
    lowLevelDebug("Thread: {}", Thread.currentThread().getName());
    lowLevelDebug("Response: {}", response != null ? "present" : "null");
    String failureInfo = failure != null
        ? failure.getClass().getName() + ": " + failure.getMessage()
        : "null";
    lowLevelDebug("Failure: {}", failureInfo);
    
    // Store the failure immediately
    this.failure = failure;
    
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
      
      if (isProtocolError) {
        lowLevelDebug("=== PROTOCOL_ERROR DETECTED IN onComplete() ===");
        lowLevelDebug("Original failure: {}: {}",
            failure.getClass().getName(), failure.getMessage());
        lowLevelDebug("HTTP/2 protocol_error detected in onComplete() - "
            + "replacing with ProtocolErrorException");
        String message = failure.getMessage();
        // Replace failure with ProtocolErrorException so it can be caught specifically
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
    } else {
      lowLevelDebug("Response is null in onComplete()");
    }
    
    if (failure != null) {
      LOG.error("Request failed with exception: type={}, message={}", 
          failure.getClass().getName(), failure.getMessage());
      if (failure instanceof IOException) {
        IOException ioException = (IOException) failure;
        String message = ioException.getMessage();
        LOG.error("IOException message: {}", message);
        if (message != null && message.contains("protocol_error")) {
          LOG.error("HTTP/2 protocol_error in onComplete() - ALPN negotiation likely failed");
        }
      }
      lowLevelDebug("Full failure stack trace:", failure);
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
    LOG.error("=== cancel() called ===");
    cancelled = true;
    // In Jetty 12, abort() returns CompletableFuture<Boolean>
    if (request != null) {
      request.abort(new CancellationException());
    }
    return true;
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
      LOG.error("ProtocolErrorException caught in get(), wrapping in ExecutionException");
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
      LOG.error("ProtocolErrorException caught in get(timeout), wrapping in ExecutionException");
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
      if (response == null) { // Only generate exception response when an response not exist
        // Generated by nginx GOAWAY
        LOG.error("Request failed without response: exception type={}, message={}",
            failure.getClass().getName(), failure.getMessage());
        
        // Check if this is a protocol_error and throw ProtocolErrorException instead
        // Add detailed logging to diagnose detection
        LOG.error("Checking if failure is protocol_error: type={}, message={}", 
            failure.getClass().getName(), failure.getMessage());
        boolean isProtocolError = ProtocolErrorException.isProtocolError(failure);
        LOG.error("ProtocolErrorException.isProtocolError() returned: {}", isProtocolError);
        
        if (isProtocolError) {
          String message = failure.getMessage();
          LOG.error("HTTP/2 protocol_error detected in getResult() - "
              + "throwing ProtocolErrorException");
          LOG.error("  - ALPN negotiation may have failed during TLS handshake");
          LOG.error("  - Server rejected HTTP/2 connection");
          LOG.error("  - This will trigger HTTP/1.1 fallback");
          throw new ProtocolErrorException(message != null ? message : "protocol_error", failure);
        } else {
          LOG.error("Failure is NOT detected as protocol_error, will throw ExecutionException");
        }
        
        if (failure instanceof IOException) {
          IOException ioException = (IOException) failure;
          String message = ioException.getMessage();
          LOG.error("IOException details: {}", message);
        }
        lowLevelDebug("Full failure stack trace (no response):", failure);
        throw new ExecutionException(failure);
      } else {
        // It is a failure caused after obtaining the response,
        // analyzing what type of failure it is, and incorporating mechanisms to manage it.
        LOG.warn("Request failed after response received: status={}, version={}, exception={}",
            response.getStatus(), response.getVersion(), failure.getClass().getName());
        
        // Check if this is a protocol_error even though we have a response
        if (ProtocolErrorException.isProtocolError(failure)) {
          String message = failure.getMessage();
          LOG.error("HTTP/2 protocol_error detected after response - "
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

}

