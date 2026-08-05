package com.blazemeter.jmeter.http2.sampler;

import static org.apache.jmeter.util.JMeterUtils.getPropDefault;

import com.blazemeter.jmeter.http2.core.HTTP2ClientProfileConfig;
import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.core.HpackFailureDetector;
import com.blazemeter.jmeter.http2.core.JmeterHttpClientExceptionMapper;
import com.blazemeter.jmeter.http2.core.ProtocolErrorException;
import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import com.blazemeter.jmeter.http2.util.Rfc9110Redirects;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.helger.commons.annotation.VisibleForTesting;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.protocol.http.parser.BaseParser;
import org.apache.jmeter.protocol.http.parser.LinkExtractorParseException;
import org.apache.jmeter.protocol.http.parser.LinkExtractorParser;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.ConversionUtils;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.oro.text.MalformedCachePatternException;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.Perl5Matcher;
import org.eclipse.jetty.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase implements LoopIterationListener, ThreadListener {

  private static class OwnInheritableThreadLocal
      extends InheritableThreadLocal<Map<HTTP2ClientKey, HTTP2JettyClient>> {
    @Override
    protected Map<HTTP2ClientKey, HTTP2JettyClient> initialValue() {
      return new HashMap<>();
    }

    @Override
    protected Map<HTTP2ClientKey, HTTP2JettyClient> childValue(
        Map<HTTP2ClientKey, HTTP2JettyClient> parentValue) {
      return parentValue;
    }
  }

  public static final String SYNC_REQUEST = "HTTP2Sampler.sync_request";
  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  /*
  private static final ThreadLocal<Map<HTTP2ClientKey, HTTP2JettyClient>> CONNECTIONS =
      ThreadLocal
          .withInitial(HashMap::new);
  */
  private static final OwnInheritableThreadLocal CONNECTIONS = new OwnInheritableThreadLocal();

  private static final boolean IGNORE_FAILED_EMBEDDED_RESOURCES =
      getPropDefault(
          "httpsampler.ignore_failed_embedded_resources", false); // $NON-NLS-1$

  /** How often the concurrent embedded-resources drain checks a pending request for completion. */
  private static final long EMBEDDED_POLL_INTERVAL_MILLIS = 10;

  private static final String HTTP1_UPGRADE_PROPERTY = "HTTP2Sampler.http1_upgrade";
  private static final String PROFILE_PROPERTY = "HTTP2Sampler.profile";
  private static final String ENABLE_HTTP3_PROPERTY = "HTTP2Sampler.enableHttp3";
  private static final String ENABLE_HTTP2_PROPERTY = "HTTP2Sampler.enableHttp2";
  private static final String ENABLE_HTTP1_PROPERTY = "HTTP2Sampler.enableHttp1";
  private static final String ALPN_ENABLED_PROPERTY = "HTTP2Sampler.alpnEnabled";
  private static final String FALLBACK_ENABLED_PROPERTY = "HTTP2Sampler.fallbackEnabled";
  private static final String PROTOCOL_ERROR_FALLBACK_PROPERTY =
      "HTTP2Sampler.protocolErrorFallbackEnabled";
  private static final String ALT_SVC_CACHE_PROPERTY = "HTTP2Sampler.altSvcCacheEnabled";
  private static final String HTTP1_ONLY_CACHE_PROPERTY = "HTTP2Sampler.http1OnlyCacheEnabled";
  private static final String H2C_CACHE_PROPERTY = "HTTP2Sampler.h2cCacheEnabled";
  private static final String HTTP2_PRIOR_KNOWLEDGE_PROPERTY =
      "HTTP2Sampler.http2PriorKnowledge";
  private static final String HAPPY_EYEBALLS_DELAY_PROPERTY =
      "HTTP2Sampler.happyEyeballsDelayMs";
  private static final String HTTP3_BROKEN_COOLDOWN_PROPERTY =
      "HTTP2Sampler.http3BrokenCooldownMs";
  private static final String HTTP1_ONLY_COOLDOWN_PROPERTY =
      "HTTP2Sampler.http1OnlyCooldownMs";
  private static final String H2C_CACHE_TTL_PROPERTY = "HTTP2Sampler.h2cCacheTtlMs";
  private static final String UI_TAB_INDEX_PROPERTY = "HTTP2Sampler.uiTabIndex";
  private static final String H2C_UPGRADE_DEFAULT_PROPERTY = "httpJettyClient.h2cUpgradeEnabled";
  // Derive the mapping of content types to parsers
  private static final Map<String, String> PARSERS_FOR_CONTENT_TYPE = new ConcurrentHashMap<>();
  private static final String USER_AGENT = "User-Agent"; // $NON-NLS-1$
  private static final boolean USE_JAVA_REGEX = !getPropDefault(
      "jmeter.regex.engine", "oro").equalsIgnoreCase("oro");

  private final transient Callable<HTTP2JettyClient> clientFactory;
  private final boolean dumpAtThreadEnd =
      BzmHttpPluginProperties.getPropDefault("httpJettyClient.DumpAtThreadEnd", false);
  private boolean syncRequest = true;
  private HTTP2FutureResponseListener asyncListener;
  private int maxBufferSize;
  private int requestTimeout;
  private HTTPSampleResult result;
  /**
   * Frame depth captured when an async request is dispatched, so the completion turn can call
   * {@link #sample(URL, String, boolean, int)} with the same depth JMeter's {@code ASyncSample}
   * passes instead of resetting to 0 via public {@link #sample()}.
   */
  private int pendingCompletionDepth;
  private transient List<PreProcessor> suppressedPreProcessors;
  private transient List<Timer> suppressedTimers;
  private transient SamplePackage suppressedSamplePackage;
  private transient boolean profileInferenceWarningLogged;

  public HTTP2Sampler() {
    clientFactory = this::getClient;
    initializeDefaults();
  }

  @VisibleForTesting
  public HTTP2Sampler(Callable<HTTP2JettyClient> clientFactory) {
    this.clientFactory = clientFactory;
    initializeDefaults();
  }

  private void initializeDefaults() {
    setName("bzm - HTTP Sampler");
    setMethod(HTTPConstants.GET);
    setArguments(new Arguments());
    this.syncRequest = getPropertyAsBoolean(SYNC_REQUEST, true);
  }

  public void setSyncRequest(boolean sync) {
    this.syncRequest = sync;
  }

  @VisibleForTesting
  public boolean isSyncRequest() {
    return this.syncRequest;
  }

  public HTTP2FutureResponseListener getFutureResponseListener() {
    return asyncListener;
  }

  @VisibleForTesting
  public void setFutureResponseListener(HTTP2FutureResponseListener listener) {
    this.asyncListener = listener;
  }

  public void setHttp1UpgradeEnabled(boolean http1UpgradeSelected) {
    setProperty(HTTP1_UPGRADE_PROPERTY, http1UpgradeSelected);
  }

  public boolean isHttp1UpgradeEnabled() {
    return getPropertyAsBoolean(HTTP1_UPGRADE_PROPERTY,
        BzmHttpPluginProperties.getPropDefault(H2C_UPGRADE_DEFAULT_PROPERTY, false));
  }

  @Override
  public boolean isConcurrentDwn() {
    // Match JMeter: concurrent download is off unless explicitly enabled.
    return getPropertyAsBoolean(CONCURRENT_DWN, false);
  }

  public void setProfile(String profile) {
    setProperty(PROFILE_PROPERTY, profile);
  }

  public String getProfile() {
    JMeterProperty property = getProperty(PROFILE_PROPERTY);
    if (property == null || property instanceof NullProperty) {
      boolean http1UpgradeEnabled = isHttp1UpgradeEnabled();
      if (http1UpgradeEnabled && !profileInferenceWarningLogged) {
        LOG.warn(
            "Profile not set; inferring 'legacy' because HTTP/1 upgrade is enabled. "
                + "Consider setting Profile explicitly (Client Behavior panel) to avoid "
                + "ambiguity.");
        profileInferenceWarningLogged = true;
      }
      return http1UpgradeEnabled ? "legacy" : "browser-like";
    }
    String value = property.getStringValue();
    return value == null || value.trim().isEmpty() ? "browser-like" : value.trim();
  }

  public void setUiTabIndex(int index) {
    setProperty(UI_TAB_INDEX_PROPERTY, index);
  }

  public int getUiTabIndex() {
    return getPropertyAsInt(UI_TAB_INDEX_PROPERTY, 0);
  }

  public void setEnableHttp3(Boolean enabled) {
    setOptionalBoolean(ENABLE_HTTP3_PROPERTY, enabled);
  }

  public Boolean getEnableHttp3() {
    return getOptionalBoolean(ENABLE_HTTP3_PROPERTY);
  }

  public void setEnableHttp2(Boolean enabled) {
    setOptionalBoolean(ENABLE_HTTP2_PROPERTY, enabled);
  }

  public Boolean getEnableHttp2() {
    return getOptionalBoolean(ENABLE_HTTP2_PROPERTY);
  }

  public void setEnableHttp1(Boolean enabled) {
    setOptionalBoolean(ENABLE_HTTP1_PROPERTY, enabled);
  }

  public Boolean getEnableHttp1() {
    return getOptionalBoolean(ENABLE_HTTP1_PROPERTY);
  }

  public void setAlpnEnabled(Boolean enabled) {
    setOptionalBoolean(ALPN_ENABLED_PROPERTY, enabled);
  }

  public Boolean getAlpnEnabled() {
    return getOptionalBoolean(ALPN_ENABLED_PROPERTY);
  }

  public void setFallbackEnabled(Boolean enabled) {
    setOptionalBoolean(FALLBACK_ENABLED_PROPERTY, enabled);
  }

  public Boolean getFallbackEnabled() {
    return getOptionalBoolean(FALLBACK_ENABLED_PROPERTY);
  }

  public void setProtocolErrorFallbackEnabled(Boolean enabled) {
    setOptionalBoolean(PROTOCOL_ERROR_FALLBACK_PROPERTY, enabled);
  }

  public Boolean getProtocolErrorFallbackEnabled() {
    return getOptionalBoolean(PROTOCOL_ERROR_FALLBACK_PROPERTY);
  }

  public void setAltSvcCacheEnabled(Boolean enabled) {
    setOptionalBoolean(ALT_SVC_CACHE_PROPERTY, enabled);
  }

  public Boolean getAltSvcCacheEnabled() {
    return getOptionalBoolean(ALT_SVC_CACHE_PROPERTY);
  }

  public void setHttp1OnlyCacheEnabled(Boolean enabled) {
    setOptionalBoolean(HTTP1_ONLY_CACHE_PROPERTY, enabled);
  }

  public Boolean getHttp1OnlyCacheEnabled() {
    return getOptionalBoolean(HTTP1_ONLY_CACHE_PROPERTY);
  }

  public void setH2cCacheEnabled(Boolean enabled) {
    setOptionalBoolean(H2C_CACHE_PROPERTY, enabled);
  }

  public Boolean getH2cCacheEnabled() {
    return getOptionalBoolean(H2C_CACHE_PROPERTY);
  }

  public void setHttp2PriorKnowledgeEnabled(Boolean enabled) {
    setOptionalBoolean(HTTP2_PRIOR_KNOWLEDGE_PROPERTY, enabled);
  }

  public Boolean getHttp2PriorKnowledgeEnabled() {
    return getOptionalBoolean(HTTP2_PRIOR_KNOWLEDGE_PROPERTY);
  }

  public void setHappyEyeballsDelayMs(Long value) {
    setOptionalLong(HAPPY_EYEBALLS_DELAY_PROPERTY, value);
  }

  public Long getHappyEyeballsDelayMs() {
    return getOptionalLong(HAPPY_EYEBALLS_DELAY_PROPERTY);
  }

  public void setHttp3BrokenCooldownMs(Long value) {
    setOptionalLong(HTTP3_BROKEN_COOLDOWN_PROPERTY, value);
  }

  public Long getHttp3BrokenCooldownMs() {
    return getOptionalLong(HTTP3_BROKEN_COOLDOWN_PROPERTY);
  }

  public void setHttp1OnlyCooldownMs(Long value) {
    setOptionalLong(HTTP1_ONLY_COOLDOWN_PROPERTY, value);
  }

  public Long getHttp1OnlyCooldownMs() {
    return getOptionalLong(HTTP1_ONLY_COOLDOWN_PROPERTY);
  }

  public void setH2cCacheTtlMs(Long value) {
    setOptionalLong(H2C_CACHE_TTL_PROPERTY, value);
  }

  public Long getH2cCacheTtlMs() {
    return getOptionalLong(H2C_CACHE_TTL_PROPERTY);
  }

  public void clearProfileOverrides() {
    removeProperty(ENABLE_HTTP3_PROPERTY);
    removeProperty(ENABLE_HTTP2_PROPERTY);
    removeProperty(ENABLE_HTTP1_PROPERTY);
    removeProperty(ALPN_ENABLED_PROPERTY);
    removeProperty(FALLBACK_ENABLED_PROPERTY);
    removeProperty(PROTOCOL_ERROR_FALLBACK_PROPERTY);
    removeProperty(ALT_SVC_CACHE_PROPERTY);
    removeProperty(HTTP1_ONLY_CACHE_PROPERTY);
    removeProperty(H2C_CACHE_PROPERTY);
    removeProperty(HTTP2_PRIOR_KNOWLEDGE_PROPERTY);
    removeProperty(HAPPY_EYEBALLS_DELAY_PROPERTY);
    removeProperty(HTTP3_BROKEN_COOLDOWN_PROPERTY);
    removeProperty(HTTP1_ONLY_COOLDOWN_PROPERTY);
    removeProperty(H2C_CACHE_TTL_PROPERTY);
  }

  private Boolean getOptionalBoolean(String key) {
    JMeterProperty property = getProperty(key);
    if (property == null || property instanceof NullProperty) {
      return null;
    }
    return property.getBooleanValue();
  }

  private void setOptionalBoolean(String key, Boolean value) {
    if (value == null) {
      removeProperty(key);
    } else {
      setProperty(key, value);
    }
  }

  private Long getOptionalLong(String key) {
    JMeterProperty property = getProperty(key);
    if (property == null || property instanceof NullProperty) {
      return null;
    }
    String text = property.getStringValue();
    if (text == null || text.trim().isEmpty()) {
      return null;
    }
    return Long.parseLong(text.trim());
  }

  private void setOptionalLong(String key, Long value) {
    if (value == null) {
      removeProperty(key);
    } else {
      setProperty(key, String.valueOf(value));
    }
  }

  @Override
  protected HTTPSampleResult sample(URL url, String method, boolean areFollowingRedirect,
                                    int depth) {
    LOG.trace("=== sample() ENTRY ===");
    LOG.trace("URL: {}, method: {}, depth: {}", url, method, depth);
    try {
      HTTP2JettyClient client = clientFactory.call();
      LOG.trace("=== Client obtained, proceeding with request ===");
      this.maxBufferSize = client.getMaxBufferSize();
      this.requestTimeout = client.getRequestTimeout();
      if (!isSyncRequest()) {
        if (Objects.isNull(this.asyncListener)) {
          this.result = buildResult(url, method); // Save the main result for next step
          this.pendingCompletionDepth = depth;
          HTTP2FutureResponseListener listener =
              new HTTP2FutureResponseListener(HTTP2JettyClient.getJettyBufferingMaxLength());
          // The client fires it: dispatching there is what lets an async request take part in the
          // HTTP/3 vs HTTP/2 race. Sending it from here reached the fallbacks all the same, through
          // the second stage, but went out as a lone HTTP/3 attempt with nothing racing it.
          client.dispatchAsync(this, this.result, listener);
          // Published only once the request is really on the wire. If the dispatch throws, the
          // controller must not be left waiting on a listener that nobody will ever complete.
          this.asyncListener = listener;
          // Nothing to report yet: the async controller will hand this sampler back once the
          // response has arrived, and JMeterThread applies post-processors, assertions, listeners
          // and the transaction nesting to the result returned on that turn.
          return null;
        } else {
          // If there is a listener, it is processed using the result it had
          try {
            int completionDepth = depth > 0 ? depth : pendingCompletionDepth;
            this.result = sampleFromListener(
                this.result, areFollowingRedirect, completionDepth, this.asyncListener);
            return this.result;
          } finally {
            restoreSuppressedPreProcessors();
          }
        }
      } else {
        this.result = buildResult(url, method);
        return client.sample(this, this.result, areFollowingRedirect, depth);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (Objects.isNull(this.result)) {
        this.result = buildResult(url, method);
      }
      return buildErrorResult(e, this.result);
    } catch (Exception e) {
      LOG.error("BlazeMeter HTTP sample failed", e);

      Throwable cause = e.getCause();
      String causeInfo = cause != null
          ? cause.getClass().getName() + ": " + cause.getMessage()
          : "null";
      LOG.debug("Cause: {}", causeInfo);

      // Check if this is a protocol_error and attempt HTTP/1.1 fallback
      boolean isProtocolErrorCause = cause != null && ProtocolErrorException.isProtocolError(cause);
      boolean isProtocolErrorException = ProtocolErrorException.isProtocolError(e);
      LOG.debug("isProtocolError(cause): {}", isProtocolErrorCause);
      LOG.debug("isProtocolError(exception): {}", isProtocolErrorException);

      if ((isProtocolErrorCause || isProtocolErrorException)
          && !HpackFailureDetector.indicatesHpackFailure(e)
          && !HpackFailureDetector.indicatesHpackFailure(cause)) {
        boolean fallbackEnabled = isProtocolErrorFallbackEnabled();
        if (!fallbackEnabled) {
          LOG.warn("HTTP/2 protocol_error detected and fallback is DISABLED. "
              + "Request will fail.");
          LOG.warn("Error: {}", cause != null ? cause.getMessage() : e.getMessage());
          LOG.warn("To enable fallback, set blazemeter.http.protocolErrorFallbackEnabled=true "
              + "or blazemeter.http.disableFallback=false in user.properties or jmeter.properties");
        } else {
          LOG.warn("HTTP/2 protocol_error detected. Attempting fallback to HTTP/1.1");
          LOG.warn("Error: {}", cause != null ? cause.getMessage() : e.getMessage());

          try {
            // Get the client and request details for fallback
            HTTP2JettyClient client = clientFactory.call();
            if (Objects.isNull(this.result)) {
              this.result = buildResult(url, method);
            }

            // Retry with HTTP/1.1 only
            LOG.info("Retrying request with HTTP/1.1 only: {}", url);
            HTTPSampleResult fallbackResult = client.retryWithHTTP11Only(this, this.result);

            if (fallbackResult != null && fallbackResult.isSuccessful()) {
              LOG.info("HTTP/1.1 fallback succeeded: status={}", fallbackResult.getResponseCode());
              return fallbackResult;
            } else {
              LOG.warn("HTTP/1.1 fallback returned unsuccessful result");
            }
          } catch (Exception fallbackException) {
            LOG.error("Failed to attempt HTTP/1.1 fallback", fallbackException);
          }
        }
      }

      if (Objects.isNull(this.result)) {
        this.result = buildResult(url, method);
      }
      return buildErrorResult(e, this.result);
    }
  }

  private boolean isProtocolErrorFallbackEnabled() {
    String fb =
        BzmHttpPluginProperties.resolveRaw("httpJettyClient.protocolErrorFallbackEnabled");
    if (fb != null) {
      return Boolean.parseBoolean(fb);
    }
    String df = BzmHttpPluginProperties.resolveRaw("httpJettyClient.disableFallback");
    if (df != null) {
      return !Boolean.parseBoolean(df);
    }
    return true;
  }

  protected Request sampleAsync(HTTPSampleResult result, HTTP2FutureResponseListener listener)
      throws Exception {

    HTTP2JettyClient client = clientFactory.call();
    return client.sampleAsync(this, result, listener);

  }

  protected HTTPSampleResult sampleFromListener(HTTPSampleResult result,
                                                boolean areFollowingRedirect,
                                                int depth, HTTP2FutureResponseListener listener)
      throws Exception {
    HTTP2JettyClient client = clientFactory.call();
    return client.sampleFromListener(this, result, areFollowingRedirect,
        depth, listener);
  }

  private HTTPSampleResult buildResult(URL url, String method) {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel(SampleResult.isRenameSampleLabel() ? getName() : url.toString());
    result.setHTTPMethod(method);
    result.setURL(url);
    return result;
  }

  private HTTPSampleResult buildErrorResult(Exception e, HTTPSampleResult result) {
    if (result.getStartTime() == 0) {
      result.sampleStart();
    }
    if (result.getEndTime() == 0) {
      if (!Objects.isNull(this.asyncListener) && this.asyncListener.getResponseEnd() != 0) {
        result.setEndTime(this.asyncListener.getResponseEnd());
      } else {
        result.sampleEnd();
      }
    }
    return errorResult(
        JmeterHttpClientExceptionMapper.forSampleResult(e, getAutoRedirects(), result.getURL()),
        result);
  }

  /**
   * Builds an error sub-result from {@code parent} without inheriting its sub-result list.
   * {@link HTTPSampleResult#HTTPSampleResult(HTTPSampleResult)} copies {@code subResults} by
   * reference; adding that copy back onto the parent would make the error result a child of itself
   * and crash View Results Tree with {@link StackOverflowError} when it walks the tree.
   */
  HTTPSampleResult detachedErrorResult(Throwable cause, HTTPSampleResult parent) {
    HTTPSampleResult err = new HTTPSampleResult(parent);
    err.removeSubResults();
    return errorResult(cause, err);
  }

  /**
   * Error sample for an embedded resource that did not finish in time, shaped like JMeter HC4's
   * per-request socket timeout: the child's URL/label identify the resource, not the page
   * container, and its elapsed time covers the wait until we gave up. That elapsed end time is what
   * {@link SampleResult#addSubResult} uses to push the container's clock forward; stamping
   * {@code sampleStart}/{@code sampleEnd} back-to-back left elapsed at ~0 and made the page look
   * like it finished when the last fast image landed.
   *
   * @param startedAtMs when this resource's attempt began (dispatch / listener start), used as the
   *                    sample start so the reported duration matches the timeout wait
   */
  private HTTPSampleResult embeddedTimeoutErrorResult(HTTP2Sampler embeddedSampler,
                                                      long startedAtMs) {
    HTTPSampleResult err = new HTTPSampleResult();
    URL url = resolveEmbeddedResourceUrl(embeddedSampler);
    if (url != null) {
      err.setURL(url);
      err.setSampleLabel(SampleResult.isRenameSampleLabel()
          ? embeddedSampler.getName()
          : url.toString());
    } else if (embeddedSampler.getName() != null) {
      err.setSampleLabel(embeddedSampler.getName());
    }
    err.setHTTPMethod(HTTPConstants.GET);
    long endedAtMs = System.currentTimeMillis();
    long elapsedMs = Math.max(0L, endedAtMs - startedAtMs);
    // Stamp end = now, start = now - elapsed (same contract as a real timed-out HC4 sample).
    err.setStampAndTime(endedAtMs, elapsedMs);
    err.setConnectTime(0L);
    err.setLatency(0L);
    return errorResult(new SocketTimeoutException("Read timed out"), err);
  }

  private long embeddedAttemptStartMillis(HTTP2Sampler embeddedSampler) {
    HTTP2FutureResponseListener listener = embeddedSampler.getFutureResponseListener();
    if (listener != null && listener.getResponseStart() > 0) {
      return listener.getResponseStart();
    }
    return System.currentTimeMillis();
  }

  private URL resolveEmbeddedResourceUrl(HTTP2Sampler embeddedSampler) {
    HTTP2FutureResponseListener listener = embeddedSampler.getFutureResponseListener();
    if (listener != null && listener.getRequest() != null
        && listener.getRequest().getURI() != null) {
      try {
        return listener.getRequest().getURI().toURL();
      } catch (IllegalArgumentException | MalformedURLException e) {
        // Fall through to the sampler URL.
      }
    }
    try {
      return embeddedSampler.getUrl();
    } catch (MalformedURLException e) {
      return null;
    }
  }

  /**
   * Cancels every still-pending embedded request and reports each as a failed child with that
   * resource's URL (JMeter-style), instead of a single container-cloned timeout stub.
   */
  private void failPendingEmbeddedRequestsWithTimeout(List<TestElement> samplers,
                                                      HTTPSampleResult subres) {
    List<TestElement> pending = new ArrayList<>(samplers);
    samplers.clear();
    for (TestElement element : pending) {
      HTTP2Sampler embedded = (HTTP2Sampler) element;
      long startedAtMs = embeddedAttemptStartMillis(embedded);
      HTTP2FutureResponseListener listener = embedded.getFutureResponseListener();
      if (listener != null && !listener.isDone() && !listener.isCancelled()) {
        listener.cancel(true);
      }
      subres.addSubResult(embeddedTimeoutErrorResult(embedded, startedAtMs));
    }
    setParentSampleSuccess(subres, false);
  }

  /**
   * Copies Jetty/ALPN/protocol flags from this sampler onto an embedded-resource child sampler so
   * child requests obey the same profile as the parent. Without this, a fresh {@link HTTP2Sampler}
   * falls back to default profile semantics (typically HTTP/1.1 enabled), which incorrectly applies
   * the global HTTP/1.1-only origin cache ({@link HTTP2JettyClient}) even when the parent has
   * HTTP/1.1 explicitly disabled (e.g. HTTP/2-only mode).
   */
  private void copyJettyProtocolSettingsToEmbeddedSampler(HTTP2Sampler embedded) {
    embedded.setProfile(getProfile());
    embedded.setEnableHttp3(getEnableHttp3());
    embedded.setEnableHttp2(getEnableHttp2());
    embedded.setEnableHttp1(getEnableHttp1());
    embedded.setAlpnEnabled(getAlpnEnabled());
    embedded.setFallbackEnabled(getFallbackEnabled());
    embedded.setProtocolErrorFallbackEnabled(getProtocolErrorFallbackEnabled());
    embedded.setAltSvcCacheEnabled(getAltSvcCacheEnabled());
    embedded.setHttp1OnlyCacheEnabled(getHttp1OnlyCacheEnabled());
    embedded.setH2cCacheEnabled(getH2cCacheEnabled());
    embedded.setHttp2PriorKnowledgeEnabled(getHttp2PriorKnowledgeEnabled());
    embedded.setHappyEyeballsDelayMs(getHappyEyeballsDelayMs());
    embedded.setHttp3BrokenCooldownMs(getHttp3BrokenCooldownMs());
    embedded.setHttp1OnlyCooldownMs(getHttp1OnlyCooldownMs());
    embedded.setH2cCacheTtlMs(getH2cCacheTtlMs());
    embedded.setHttp1UpgradeEnabled(isHttp1UpgradeEnabled());
    copyTimeoutsToEmbeddedSampler(embedded);
  }

  /**
   * Propagates Connect Timeout and Response Timeout to an embedded-resource child sampler.
   *
   * <p>JMeter builds its embedded-resource samplers with {@code base.clone()}
   * ({@code HTTPSamplerBase.ASyncSample}), so they inherit every configured property, timeouts
   * included. This plugin builds them from a fresh {@link HTTP2Sampler} instead, which reports 0
   * for both - and 0 means "no timeout" in JMeter, exactly as it does here. The result was that an
   * embedded request never received a deadline even when the user had configured one on the parent:
   * {@code HTTP2JettyClient} only calls {@code Request.timeout(..)} for a value above 0, so a
   * stalled resource left the polling loop in {@link #downloadPageResources} waiting forever.
   *
   * <p>The raw property strings are copied rather than the {@code int} values so that "unset" stays
   * unset instead of becoming a literal "0".
   */
  private void copyTimeoutsToEmbeddedSampler(HTTP2Sampler embedded) {
    embedded.setConnectTimeout(getPropertyAsString(CONNECT_TIMEOUT));
    embedded.setResponseTimeout(getPropertyAsString(RESPONSE_TIMEOUT));
  }

  /**
   * Factory for HTTP(S) embedded-resource children. Tests can override to inject a failing
   * client without changing the download loop.
   */
  protected HTTP2Sampler newHttpEmbeddedSampler() {
    return new HTTP2Sampler();
  }

  /**
   * Configures an HTTP(S) embedded-resource child the way JMeter's {@code ASyncSample} does via
   * {@code base.clone()}: same timeouts, managers, redirects, image-parser and MD5 flags as the
   * parent. Concurrent children get a CacheManager proxy and a cloned CookieManager so siblings do
   * not share mutable cookie state mid-page.
   *
   * <p>Also copies keep-alive, content encoding, concurrent-download flags and embedded URL
   * allow/exclude regexes so a nested HTML resource (iframe) that itself runs
   * {@link #downloadPageResources} behaves like a JMeter clone of the parent, not a fresh sampler
   * with defaults.
   */
  private void configureHttpEmbeddedSampler(HTTP2Sampler embedded, URL url,
                                            boolean concurrent) {
    copyJettyProtocolSettingsToEmbeddedSampler(embedded);
    embedded.setMethod(HTTPConstants.GET);
    embedded.setSyncRequest(!concurrent);
    embedded.setProtocol(url.getProtocol());
    embedded.setDomain(url.getHost() != null ? url.getHost() : "");
    embedded.setPort(url.getPort());
    embedded.setFollowRedirects(getFollowRedirects());
    embedded.setAutoRedirects(getAutoRedirects());
    embedded.setPath(pathWithQuery(url));
    embedded.setImageParser(isImageParser());
    embedded.setMD5(useMD5() || JMeterUtils.getPropDefault(
        "httpsampler.embedded_resources_use_md5", false));
    embedded.setUseKeepAlive(getUseKeepAlive());
    embedded.setContentEncoding(getContentEncoding());
    // Nested embeds (child parses HTML) must keep the parent's parallel-download and URL filter
    // settings; the {@code concurrent} parameter only controls this child's sync vs async send.
    embedded.setConcurrentDwn(isConcurrentDwn());
    embedded.setConcurrentPool(getConcurrentPool());
    embedded.setEmbeddedUrlRE(getEmbeddedUrlRE());
    embedded.setEmbeddedUrlExcludeRE(getEmbededUrlExcludeRE());

    embedded.setProxyHost(getProxyHost());
    embedded.setProxyPortInt(String.valueOf(getProxyPortInt()));
    embedded.setProxyScheme(getProxyScheme());
    embedded.setProxyUser(getProxyUser());
    embedded.setProxyPass(getProxyPass());

    embedded.setHeaderManager(getHeaderManager());
    embedded.setAuthManager(getAuthManager());
    if (getCacheManager() != null) {
      embedded.setCacheManager(getCacheManager().createCacheManagerProxy());
    }
    if (getCookieManager() != null) {
      if (concurrent) {
        embedded.setCookieManager((org.apache.jmeter.protocol.http.control.CookieManager)
            getCookieManager().clone());
      } else {
        embedded.setCookieManager(getCookieManager());
      }
    }
  }

  private static String pathWithQuery(URL url) {
    if (url.getQuery() == null) {
      return url.getPath();
    }
    return url.getPath() + "?" + url.getQuery();
  }

  private void mergeEmbeddedCookiesIntoParent(HTTP2Sampler embedded) {
    org.apache.jmeter.protocol.http.control.CookieManager parent = getCookieManager();
    org.apache.jmeter.protocol.http.control.CookieManager child = embedded.getCookieManager();
    if (parent == null || child == null || parent == child) {
      return;
    }
    for (JMeterProperty property : child.getCookies()) {
      parent.add((org.apache.jmeter.protocol.http.control.Cookie) property.getObjectValue());
    }
  }

  /**
   * Builds an embedded-resource child sampler for a {@code file://} URL discovered by the HTML
   * parser (e.g. a relative {@code href}/{@code src} resolved against a {@code file://} parent).
   * {@code setImageParser} is enabled for {@code .html}/{@code .htm} targets so nested embedded
   * resources (e.g. an iframe pointing at another local HTML file) are themselves parsed and
   * downloaded, matching how a real HTTP-embedded HTML resource would recurse.
   *
   * <p>{@code file://} embeds are always completed inline (synchronously), even when concurrent
   * download is enabled: there is no HTTP transport to race, and Jetty async dispatch does not
   * apply. JMeter's {@code ASyncSample} still runs them on the pool thread via
   * {@code HTTPFileImpl}; content and nesting match, only the pool scheduling differs.
   */
  private HTTP2Sampler newFileEmbeddedSampler(URL url) {
    HTTP2Sampler fileSampler = new HTTP2Sampler();
    copyJettyProtocolSettingsToEmbeddedSampler(fileSampler);
    String path = url.getPath();
    boolean htmlResource = path != null
        && (path.endsWith(".html") || path.endsWith(".htm"));
    fileSampler.setImageParser(htmlResource);
    fileSampler.setMethod(HTTPConstants.GET);
    fileSampler.setProtocol(url.getProtocol());
    fileSampler.setDomain(url.getHost() != null ? url.getHost() : "");
    fileSampler.setPort(url.getPort());
    fileSampler.setPath(pathWithQuery(url));
    fileSampler.setHeaderManager(getHeaderManager());
    fileSampler.setCookieManager(getCookieManager());
    fileSampler.setMD5(useMD5() || JMeterUtils.getPropDefault(
        "httpsampler.embedded_resources_use_md5", false));
    return fileSampler;
  }

  /** {@code file://} URLs have no HTTP-style path to label sub-results with; build one instead. */
  private static String formatFileEmbeddedLabel(URL url, int index) {
    String path = url.getPath();
    if (path != null && path.startsWith("/")) {
      path = path.substring(1);
    }
    return url.getProtocol() + ":" + path + "-" + index;
  }

  private static void relabelFileEmbeddedChildren(HTTPSampleResult parent) {
    int childIndex = 0;
    for (SampleResult child : parent.getSubResults()) {
      if (child instanceof HTTPSampleResult) {
        HTTPSampleResult httpChild = (HTTPSampleResult) child;
        if (httpChild.getURL() != null
            && "file".equalsIgnoreCase(httpChild.getURL().getProtocol())) {
          httpChild.setSampleLabel(
              formatFileEmbeddedLabel(httpChild.getURL(), childIndex++));
          relabelFileEmbeddedChildren(httpChild);
        }
      }
    }
  }

  private HTTP2JettyClient buildClient() throws Exception {
    HTTP2ClientKey connectionKey = buildConnectionKey();
    HTTP2JettyClient client = new HTTP2JettyClient(isHttp1UpgradeEnabled(),
        "http2[" + connectionKey.target + ":" + Thread.currentThread().getId() + "]",
        buildProfileConfig());
    client.start();
    CONNECTIONS.get().put(connectionKey, client);
    return client;
  }

  private HTTP2ClientKey buildConnectionKey() throws MalformedURLException {
    return new HTTP2ClientKey(getUrl(), !getProxyHost().isEmpty(), getProxyScheme(), getProxyHost(),
        getProxyPortInt(), buildProfileKey());
  }

  private HTTP2ClientProfileConfig buildProfileConfig() {
    return HTTP2ClientProfileConfig.builder()
        .profile(getProfile())
        .enableHttp3(getEnableHttp3())
        .enableHttp2(getEnableHttp2())
        .enableHttp1(getEnableHttp1())
        .alpnEnabled(getAlpnEnabled())
        .fallbackEnabled(getFallbackEnabled())
        .protocolErrorFallbackEnabled(getProtocolErrorFallbackEnabled())
        .altSvcCacheEnabled(getAltSvcCacheEnabled())
        .http1OnlyCacheEnabled(getHttp1OnlyCacheEnabled())
        .h2cCacheEnabled(getH2cCacheEnabled())
        .http2PriorKnowledgeEnabled(getHttp2PriorKnowledgeEnabled())
        .happyEyeballsDelayMs(getHappyEyeballsDelayMs())
        .http3BrokenCooldownMs(getHttp3BrokenCooldownMs())
        .http1OnlyCooldownMs(getHttp1OnlyCooldownMs())
        .h2cCacheTtlMs(getH2cCacheTtlMs())
        .build();
  }

  private String buildProfileKey() {
    StringBuilder key = new StringBuilder();
    key.append(getProfile());
    appendBooleanKey(key, "h3", getEnableHttp3());
    appendBooleanKey(key, "h2", getEnableHttp2());
    appendBooleanKey(key, "h1", getEnableHttp1());
    appendBooleanKey(key, "alpn", getAlpnEnabled());
    appendBooleanKey(key, "fb", getFallbackEnabled());
    appendBooleanKey(key, "pef", getProtocolErrorFallbackEnabled());
    appendBooleanKey(key, "altsvc", getAltSvcCacheEnabled());
    appendBooleanKey(key, "h1c", getHttp1OnlyCacheEnabled());
    appendBooleanKey(key, "h2cc", getH2cCacheEnabled());
    appendBooleanKey(key, "h2pk", getHttp2PriorKnowledgeEnabled());
    appendLongKey(key, "he", getHappyEyeballsDelayMs());
    appendLongKey(key, "h3cd", getHttp3BrokenCooldownMs());
    appendLongKey(key, "h1cd", getHttp1OnlyCooldownMs());
    appendLongKey(key, "h2cttl", getH2cCacheTtlMs());
    appendBooleanKey(key, "h2cup", isHttp1UpgradeEnabled());
    return key.toString();
  }

  private void appendBooleanKey(StringBuilder key, String name, Boolean value) {
    key.append(';').append(name).append('=').append(value == null ? "-" : value);
  }

  private void appendLongKey(StringBuilder key, String name, Long value) {
    key.append(';').append(name).append('=').append(value == null ? "-" : value);
  }

  private HTTP2JettyClient getClient() throws Exception {
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    HTTP2ClientKey key = buildConnectionKey();
    return clients.containsKey(key) ? clients.get(key)
        : buildClient();
  }

  /**
   * JMeter 5.6.3's {@code HTTPSampleResult.isRedirect()} only recognizes a {@code 307} as a
   * redirect for GET/HEAD, so a POST/PUT/PATCH/DELETE + 307 is returned as-is by the inherited
   * {@code resultProcessing()} - {@link #followRedirects} below is never even reached. See
   * {@link Rfc9110Redirects} for the fix this ports (apache/jmeter PR #6658) and why it's applied
   * this way instead of overriding {@code HTTPSampleResult.isRedirect()} directly. This drives
   * the follow-up itself only for that one gap, then hands off to the inherited
   * {@code resultProcessing()} (marking {@code areFollowingRedirect=true}) for everything else
   * (embedded resources, etc.) exactly as it would normally run. Falls back to the unfixed
   * inherited behavior when {@link Rfc9110Redirects#useLegacyMethodHandling()}.
   */
  @Override
  public HTTPSampleResult resultProcessing(final boolean pAreFollowingRedirect,
                                           final int frameDepth, final HTTPSampleResult pRes) {
    if (!Rfc9110Redirects.useLegacyMethodHandling()
        && !pAreFollowingRedirect
        && !pRes.isRedirect()
        && Rfc9110Redirects.isRedirect(pRes.getResponseCode())
        && getFollowRedirects()) {
      HTTPSampleResult followed = followRedirects(pRes, frameDepth);
      return super.resultProcessing(true, frameDepth, followed);
    }
    return super.resultProcessing(pAreFollowingRedirect, frameDepth, pRes);
  }

  /**
   * Ported from {@code HTTPSamplerBase.followRedirects} (JMeter 5.6.3) with the fix from
   * apache/jmeter PR #6658 (see {@link Rfc9110Redirects} and {@link #resultProcessing}):
   * {@code computeMethodForRedirect} now takes the response code into account so 307/308
   * preserve the original method (RFC 9110 sections 15.4.8/15.4.9), instead of always rewriting
   * to GET like 301/302/303 do. Uses {@link Rfc9110Redirects#isRedirect} instead of
   * {@code HTTPSampleResult.isRedirect()} to decide whether to keep following a redirect chain,
   * for the same reason {@link #resultProcessing} avoids relying on that method for 307. Falls
   * back to the inherited, unfixed JMeter behavior when
   * {@link Rfc9110Redirects#useLegacyMethodHandling()}.
   */
  @Override
  protected HTTPSampleResult followRedirects(HTTPSampleResult res, int frameDepth) {
    if (Rfc9110Redirects.useLegacyMethodHandling()) {
      return super.followRedirects(res, frameDepth);
    }
    HTTPSampleResult totalRes = new HTTPSampleResult(res);
    totalRes.addRawSubResult(res);
    HTTPSampleResult lastRes = res;

    int redirect;
    for (redirect = 0; redirect < MAX_REDIRECTS; redirect++) {
      boolean invalidRedirectUrl = false;
      String location = lastRes.getRedirectLocation();
      if (JMeterUtils.getPropDefault("httpsampler.redirect.removeslashdotdot", true)) {
        location = ConversionUtils.removeSlashDotDot(location);
      }
      location = encodeSpaces(location);
      String method = computeMethodForRedirect(lastRes.getHTTPMethod(), lastRes.getResponseCode());

      try {
        URL url = ConversionUtils.makeRelativeURL(lastRes.getURL(), location);
        url = ConversionUtils.sanitizeUrl(url).toURL();
        HTTPSampleResult tempRes = sample(url, method, true, frameDepth);
        if (tempRes != null) {
          lastRes = tempRes;
        } else {
          break;
        }
      } catch (MalformedURLException | URISyntaxException e) {
        errorResult(e, lastRes);
        invalidRedirectUrl = true;
      }
      if (lastRes.getSubResults() != null && lastRes.getSubResults().length > 0) {
        for (SampleResult sub : lastRes.getSubResults()) {
          totalRes.addSubResult(sub);
        }
      } else if (!invalidRedirectUrl) {
        totalRes.addSubResult(lastRes);
      }

      if (!Rfc9110Redirects.isRedirect(lastRes.getResponseCode())) {
        break;
      }
    }
    if (redirect >= MAX_REDIRECTS) {
      lastRes = errorResult(
          new IOException("Exceeded maximum number of redirects: " + MAX_REDIRECTS),
          new HTTPSampleResult(lastRes));
      totalRes.addSubResult(lastRes);
    }

    totalRes.setSampleLabel(totalRes.getSampleLabel() + "->" + lastRes.getSampleLabel());
    totalRes.setURL(lastRes.getURL());
    totalRes.setHTTPMethod(lastRes.getHTTPMethod());
    totalRes.setQueryString(lastRes.getQueryString());
    totalRes.setRequestHeaders(lastRes.getRequestHeaders());
    totalRes.setResponseData(lastRes.getResponseData());
    totalRes.setResponseCode(lastRes.getResponseCode());
    totalRes.setSuccessful(lastRes.isSuccessful());
    totalRes.setResponseMessage(lastRes.getResponseMessage());
    totalRes.setDataType(lastRes.getDataType());
    totalRes.setResponseHeaders(lastRes.getResponseHeaders());
    totalRes.setContentType(lastRes.getContentType());
    totalRes.setDataEncoding(lastRes.getDataEncodingNoDefault());
    return totalRes;
  }

  private String computeMethodForRedirect(String initialMethod, String responseCode) {
    if (HTTPConstants.SC_TEMPORARY_REDIRECT.equals(responseCode)
        || HTTPConstants.SC_PERMANENT_REDIRECT.equals(responseCode)) {
      return initialMethod;
    }
    if (!HTTPConstants.HEAD.equalsIgnoreCase(initialMethod)) {
      return HTTPConstants.GET;
    }
    return initialMethod;
  }

  static void registerParser(String contentType, String className) {
    LOG.info("Parser for {} is {}", contentType, className);
    PARSERS_FOR_CONTENT_TYPE.put(contentType, className);
  }

  /**
   * JMeter batch runs configure HTML parsers via {@code -q jmeter-batch.properties}, loaded after
   * this class may already have been initialized - so register parsers lazily on first use
   * instead of in a static block, to read whatever properties are actually in effect by then.
   */
  private static void ensureResponseParsersLoaded() {
    if (!PARSERS_FOR_CONTENT_TYPE.isEmpty()) {
      return;
    }
    synchronized (PARSERS_FOR_CONTENT_TYPE) {
      if (PARSERS_FOR_CONTENT_TYPE.isEmpty()) {
        loadResponseParsersFromProperties();
      }
    }
  }

  private static void loadResponseParsersFromProperties() {
    String responseParsers = JMeterUtils.getProperty("HTTPResponse.parsers"); //$NON-NLS-1$
    String[] parsers = JOrphanUtils.split(responseParsers, " ", true); // empty array for null
    for (final String parser : parsers) {
      String classname = JMeterUtils.getProperty(parser + ".className"); //$NON-NLS-1$
      if (classname == null) {
        LOG.error("Cannot find .className property for {}, ensure you set property: '{}.className'",
            parser, parser);
        continue;
      }
      String typeList = JMeterUtils.getProperty(parser + ".types"); //$NON-NLS-1$
      if (typeList != null) {
        String[] types = JOrphanUtils.split(typeList, " ", true);
        for (final String type : types) {
          registerParser(type, classname);
        }
      } else {
        LOG.warn(
            "Cannot find .types property for {}, as a consequence parser "
                + "will not be used, to make it usable, define property:'{}.types'",
            parser, parser);
      }
    }
  }

  private LinkExtractorParser getParser(HTTPSampleResult res)
      throws LinkExtractorParseException {
    ensureResponseParsersLoaded();
    String parserClassName =
        PARSERS_FOR_CONTENT_TYPE.get(res.getMediaType());
    if (!StringUtils.isEmpty(parserClassName)) {
      return BaseParser.getParser(parserClassName);
    }
    return null;
  }

  private String getUserAgent(HTTPSampleResult sampleResult) {
    String res = sampleResult.getRequestHeaders();
    int index = res.indexOf(USER_AGENT);
    if (index >= 0) {
      // see HTTPHC3Impl#getConnectionHeaders
      // see HTTPHC4Impl#getConnectionHeaders
      // see HTTPJavaImpl#getConnectionHeaders
      //': ' is used by JMeter to fill-in requestHeaders, see getConnectionHeaders
      final String userAgentPrefix = USER_AGENT + ": ";
      int valueStart = index + userAgentPrefix.length();
      // '\n' is used by JMeter to fill-in requestHeaders, see getConnectionHeaders. When
      // User-Agent is the last header, there's no trailing '\n' and indexOf returns -1; fall
      // back to the end of the string instead of feeding -1 into substring().
      int lineEnd = res.indexOf('\n', valueStart);
      if (lineEnd < 0) {
        lineEnd = res.length();
      }
      return res.substring(valueStart, lineEnd).trim();
    } else {
      if (LOG.isDebugEnabled()) {
        LOG.debug("No user agent extracted from requestHeaders:{}", res);
      }
      return null;
    }
  }

  private void setParentSampleSuccess(HTTPSampleResult res, boolean initialValue) {
    if (!IGNORE_FAILED_EMBEDDED_RESOURCES) {
      res.setSuccessful(initialValue);
      if (!initialValue) {
        StringBuilder detailedMessage = new StringBuilder(80);
        detailedMessage.append("Embedded resource download error:"); //$NON-NLS-1$
        for (SampleResult subResult : res.getSubResults()) {
          HTTPSampleResult httpSampleResult = (HTTPSampleResult) subResult;
          if (!httpSampleResult.isSuccessful()) {
            detailedMessage.append(httpSampleResult.getURL())
                .append(" code:") //$NON-NLS-1$
                .append(httpSampleResult.getResponseCode())
                .append(" message:") //$NON-NLS-1$
                .append(httpSampleResult.getResponseMessage())
                .append(", "); //$NON-NLS-1$
          }
        }
        res.setResponseMessage(detailedMessage.toString()); //$NON-NLS-1$
      }
    }
  }

  private static final class LazyJavaPatternCacheHolder {

    public static final LoadingCache<Pair<String, Integer>, java.util.regex.Pattern> INSTANCE =
        Caffeine
            .newBuilder()
            .maximumSize(getPropDefault("jmeter.regex.patterncache.size", 1000))
            .build(key -> {
              //noinspection MagicConstant
              return java.util.regex.Pattern.compile(key.getLeft(), key.getRight().intValue());
            });

    private LazyJavaPatternCacheHolder() {
      super();
    }

  }

  public static java.util.regex.Pattern compilePattern(String expression) {
    return compilePattern(expression, 0);
  }

  public static java.util.regex.Pattern compilePattern(String expression, int flags) {
    return LazyJavaPatternCacheHolder.INSTANCE.get(Pair.of(expression, Integer.valueOf(flags)));
  }

  private Predicate<URL> generateMatcherPredicate(String regex, String explanation,
                                                  boolean defaultAnswer) {
    if (StringUtils.isEmpty(regex)) {
      return s -> defaultAnswer;
    }
    if (USE_JAVA_REGEX) {
      try {
        java.util.regex.Pattern pattern = compilePattern(regex);
        return s -> pattern.matcher(s.toString()).matches();
      } catch (PatternSyntaxException e) {
        LOG.warn("Ignoring embedded URL {} string: {}", explanation, e.getMessage());
        return s -> defaultAnswer;
      }
    }
    try {
      Pattern pattern = JMeterUtils.getPattern(regex);
      Perl5Matcher matcher = JMeterUtils.getMatcher();
      return s -> matcher.matches(s.toString(), pattern);
    } catch (MalformedCachePatternException e) { // NOSONAR
      LOG.warn("Ignoring embedded URL {} string: {}", explanation, e.getMessage());
      return s -> defaultAnswer;
    }
  }

  private URL escapeIllegalURLCharacters(java.net.URL url) {
    if (url == null || "file".equals(url.getProtocol())) {
      return url;
    }
    try {
      return ConversionUtils.sanitizeUrl(url).toURL();
    } catch (Exception ex) { // NOSONAR
      LOG.error("Error escaping URL:'{}', message:{}", url, ex.getMessage());
      return url;
    }
  }

  @Override
  protected HTTPSampleResult downloadPageResources(final HTTPSampleResult pRes,
                                                   final HTTPSampleResult container,
                                                   final int frameDepth) {

    boolean orgSyncRequest = isSyncRequest();
    boolean interrupted = false;
    HTTPSampleResult res = pRes;
    Iterator<URL> urls = null;
    List<TestElement> samplers = new ArrayList();

    try {
      final byte[] responseData = res.getResponseData();
      if (responseData.length > 0) {  // Bug 39205
        final LinkExtractorParser parser = getParser(res);
        if (parser != null) {
          String userAgent = getUserAgent(res);
          urls = parser.getEmbeddedResourceURLs(userAgent, responseData, res.getURL(),
              res.getDataEncodingWithDefault());
        }
      }
    } catch (LinkExtractorParseException e) {
      e.printStackTrace(System.err);
      // Don't break the world just because this failed:
      res.addSubResult(detachedErrorResult(e, res));
      setParentSampleSuccess(res, false);
    }

    HTTPSampleResult lContainer = container;
    // Iterate through the URLs and download each image:
    if (urls != null && urls.hasNext()) {
      if (lContainer == null) {
        lContainer = new HTTPSampleResult(res);
        lContainer.addRawSubResult(res);
      }
      final HTTPSampleResult subres = lContainer;
      res = subres;

      // Get the URL matcher
      String allowRegex = "";
      String excludeRegex = "";
      Predicate<URL> allowPredicate = null;
      Predicate<URL> excludePredicate = null;
      try {
        allowRegex = getEmbeddedUrlRE();
        allowPredicate = generateMatcherPredicate(allowRegex, "allow", true);
        excludeRegex = getEmbededUrlExcludeRE();
        excludePredicate =
            generateMatcherPredicate(excludeRegex, "exclude", false);
      } catch (Exception ex) {
        ex.printStackTrace(System.err);
        throw ex;
      }
      // For concurrent get resources
      int maxConcurrentDownloads = CONCURRENT_POOL_SIZE; // init with default value
      boolean isConcurrentDwn = isConcurrentDwn();

      if (isConcurrentDwn) {
        try {
          maxConcurrentDownloads = Integer.parseInt(getConcurrentPool());
        } catch (NumberFormatException nfe) {
          LOG.warn("Concurrent download resources selected, "// $NON-NLS-1$
              + "but pool size value is bad. Use default value"); // $NON-NLS-1$
        }

        // if the user choose a number of parallel downloads of 1
        // no need to use another thread, do the sample on the current thread
        if (maxConcurrentDownloads == 1) {
          LOG.warn("Number of parallel downloads set to 1, (sampler name={})", getName());
          isConcurrentDwn = false;
        }
      }

      setSyncRequest(!isConcurrentDwn); // Change default from main request based on sub request

      // Deadline for waiting on a single embedded resource. 0 means "no timeout", matching JMeter
      // (ResourcesDownloader has no wait timeout and relies on per-request socket timeouts only).
      // Do not fall back to a Jetty client field that may still hold a previous sampler's timeout.
      int embeddedTimeout = getResponseTimeout();

      int fileEmbeddedIndex = 0;
      while (urls.hasNext()) {
        Object binURL = urls.next(); // See catch clause below
        try {
          URL url = (URL) binURL;
          if (url == null) {
            LOG.warn("Null URL detected (should not happen)");
          } else {
            try {
              url = escapeIllegalURLCharacters(url);
            } catch (Exception e) { // NOSONAR
              subres.addSubResult(detachedErrorResult(
                  new Exception(url.toString() + " is not a correct URI", e), subres));
              setParentSampleSuccess(subres, false);
              continue;
            }
            if (!allowPredicate.test(url)) {
              continue; // we have a pattern and the URL does not match, so skip it
            }
            if (excludePredicate.test(url)) {
              continue; // we have a pattern and the URL does not match, so skip it
            }
            try {
              url = url.toURI().normalize().toURL();
            } catch (MalformedURLException | URISyntaxException e) {
              subres.addSubResult(detachedErrorResult(
                  new Exception(url.toString() + " URI can not be normalized", e), subres));
              setParentSampleSuccess(subres, false);
              continue;
            }

            if ("file".equalsIgnoreCase(url.getProtocol())) {
              HTTP2Sampler fileSampler = newFileEmbeddedSampler(url);
              HTTPSampleResult binRes =
                  fileSampler.sample(url, HTTPConstants.GET, false, frameDepth + 1);
              if (binRes != null) {
                binRes.setSampleLabel(formatFileEmbeddedLabel(url, fileEmbeddedIndex++));
                relabelFileEmbeddedChildren(binRes);
              }
              subres.addSubResult(binRes);
              setParentSampleSuccess(subres,
                  subres.isSuccessful() && (binRes == null || binRes.isSuccessful()));
              continue;
            }

            // Honour the configured parallel-download pool size. Previously every URL on the page
            // was dispatched at once, so the setting only ever mattered for the value 1.
            if (isConcurrentDwn) {
              interrupted = awaitEmbeddedDownloadSlot(samplers, subres, maxConcurrentDownloads,
                  embeddedTimeout);
              if (interrupted) {
                break;
              }
            }

            HTTP2Sampler h2s = newHttpEmbeddedSampler();
            configureHttpEmbeddedSampler(h2s, url, isConcurrentDwn);

            HTTPSampleResult binRes = h2s.sample(
                url, HTTPConstants.GET, false, frameDepth + 1);

            if (isConcurrentDwn) {
              if (h2s.getFutureResponseListener() == null) {
                // Dispatch never started (or failed before publishing a listener): attach the error
                // result now. Queuing a sampler with a null listener used to drop it silently in
                // the drain loop.
                if (binRes != null) {
                  subres.addSubResult(binRes);
                  setParentSampleSuccess(subres,
                      subres.isSuccessful() && binRes.isSuccessful());
                }
              } else {
                samplers.add(h2s);
              }
            } else {
              // default: serial download embedded resources
              subres.addSubResult(binRes);
              setParentSampleSuccess(subres,
                  subres.isSuccessful() && (binRes == null || binRes.isSuccessful()));
            }
          }
        } catch (ClassCastException e) { // NOSONAR
          subres.addSubResult(detachedErrorResult(
              new Exception(binURL + " is not a correct URI", e), subres));
          setParentSampleSuccess(subres, false);
        }
        if (interrupted) {
          break;
        }
      }
      // Drain whatever is still in flight, in submission order, like JMeter iterates the futures
      // returned by ResourcesDownloader.invokeAllAndAwaitTermination.
      while (!interrupted && isConcurrentDwn && !samplers.isEmpty()) {
        interrupted = drainFirstEmbeddedSampler(samplers, subres, embeddedTimeout);
      }
    }
    setSyncRequest(orgSyncRequest); // Restore the default setting to main request

    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    return res;
  }

  /**
   * Blocks until fewer than {@code maxConcurrentDownloads} embedded requests are still in flight,
   * so a new one can be dispatched.
   *
   * <p>Mirrors JMeter's {@code ResourcesDownloader.invokeAllAndAwaitTermination}, which keeps at
   * most that many tasks running and starts the next one as soon as *any* of them completes.
   * Waiting on the oldest request instead would leave the other slots idle for as long as it takes
   * that one to finish, turning a slow resource into a stall for the whole page.
   *
   * <p>Requests that already completed stay queued: results are consumed separately, in submission
   * order, so the reported sub-results keep matching the order the resources appear in the page.
   *
   * @return whether the wait was interrupted, in which case pending requests were aborted and the
   *         queue cleared.
   */
  private boolean awaitEmbeddedDownloadSlot(List<TestElement> samplers, HTTPSampleResult subres,
                                            int maxConcurrentDownloads, int embeddedTimeout) {
    long waitStartedAt = System.currentTimeMillis();
    while (true) {
      // Independent of slot accounting: harvest what is already finished so responses are not left
      // buffered in the queue for the rest of the page. Never blocks on the head.
      collectFinishedEmbeddedResults(samplers, subres);
      if (countInFlightEmbeddedRequests(samplers) < maxConcurrentDownloads) {
        return false;
      }
      // Without a deadline here, a full pool of stalled requests freezes dispatch forever even when
      // the user configured a response timeout - the drain timeout only runs after every URL has
      // been queued, which never happens if we cannot free a slot.
      if (embeddedTimeout > 0
          && (System.currentTimeMillis() - waitStartedAt) >= embeddedTimeout) {
        LOG.warn("Timeout after {}ms waiting for an embedded download slot; failing in-flight "
            + "resources and freeing slots so remaining URLs can still be dispatched",
            embeddedTimeout);
        // Fail only what is already in flight. Returning false (not interrupted) lets the URL loop
        // continue — JMeter keeps scheduling until the list is exhausted rather than abandoning
        // undispatched embeds.
        failPendingEmbeddedRequestsWithTimeout(samplers, subres);
        return false;
      }
      try {
        Thread.sleep(EMBEDDED_POLL_INTERVAL_MILLIS);
      } catch (InterruptedException e) {
        abortPendingEmbeddedRequests(samplers);
        return true;
      }
    }
  }

  /**
   * Turns the head of {@code samplers} into a sub-result and dequeues it. The caller must have
   * established that its request finished.
   */
  private void consumeFirstEmbeddedSampler(List<TestElement> samplers, HTTPSampleResult subres) {
    HTTP2Sampler embeddedSampler = (HTTP2Sampler) samplers.get(0);
    URL processedUrl = resolveEmbeddedResourceUrl(embeddedSampler);
    LOG.debug("Embedded resource finished, re-sampling with that data {}",
        processedUrl != null ? processedUrl : "unknown");
    // Call sample(url, …, depth) directly — not public sample() — so the label stays the URL (or
    // rename policy) and frame depth matches what was used at dispatch, as JMeter's ASyncSample
    // does.
    HTTPSampleResult binRes;
    if (processedUrl != null) {
      binRes = embeddedSampler.sample(processedUrl, HTTPConstants.GET, false,
          embeddedSampler.pendingCompletionDepth > 0
              ? embeddedSampler.pendingCompletionDepth
              : 1);
    } else {
      binRes = (HTTPSampleResult) embeddedSampler.sample();
    }
    samplers.remove(0);
    mergeEmbeddedCookiesIntoParent(embeddedSampler);
    subres.addSubResult(binRes);
    setParentSampleSuccess(subres,
        subres.isSuccessful() && (binRes == null || binRes.isSuccessful()));
  }

  /**
   * Consumes every already-finished resource at the head of the queue, without ever waiting.
   *
   * <p>Freeing a concurrency slot and collecting a result are deliberately separate: a slot is free
   * as soon as its download finishes (see {@link #countInFlightEmbeddedRequests}), so a slow first
   * resource never holds back the dispatch of the rest. But nothing used to collect until the whole
   * page had been dispatched, which left every finished response buffered in the queue in the
   * meantime. Draining here keeps that buffer as short as the head allows, while sub-results still
   * come out in the order the resources appear in the page.
   */
  private void collectFinishedEmbeddedResults(List<TestElement> samplers,
                                              HTTPSampleResult subres) {
    while (!samplers.isEmpty()) {
      HTTP2FutureResponseListener listener =
          ((HTTP2Sampler) samplers.get(0)).getFutureResponseListener();
      if (listener == null) {
        // Never dispatched, so no completion can arrive: drop it rather than block the queue on it.
        LOG.debug("Embedded resource has no pending request, dropping it from the queue");
        samplers.remove(0);
        continue;
      }
      if (!listener.isDone() && !listener.isCancelled()) {
        return;
      }
      consumeFirstEmbeddedSampler(samplers, subres);
    }
  }

  private int countInFlightEmbeddedRequests(List<TestElement> samplers) {
    int inFlight = 0;
    for (TestElement element : samplers) {
      HTTP2FutureResponseListener listener =
          ((HTTP2Sampler) element).getFutureResponseListener();
      if (listener != null && !listener.isDone() && !listener.isCancelled()) {
        inFlight++;
      }
    }
    return inFlight;
  }

  /**
   * Aborts whatever is still in flight and empties the queue, the way JMeter cancels the futures it
   * submitted when the drain is interrupted (bug 51925), so a stopped test does not leak requests.
   */
  private void abortPendingEmbeddedRequests(List<TestElement> samplers) {
    for (TestElement element : samplers) {
      HTTP2FutureResponseListener listener =
          ((HTTP2Sampler) element).getFutureResponseListener();
      if (listener != null && !listener.isDone() && !listener.isCancelled()) {
        listener.cancel(true);
      }
    }
    samplers.clear();
  }

  /**
   * Waits for the first still-pending embedded resource in {@code samplers}, appends its result to
   * {@code subres} and dequeues it. Always dequeues, so the caller's loop makes progress on every
   * call.
   *
   * @param embeddedTimeout how long to wait for this one resource, or 0 to wait indefinitely (see
   *                        {@link #downloadPageResources} on why 0 matches JMeter).
   * @return whether the wait was interrupted, in which case the queue has been cleared.
   */
  private boolean drainFirstEmbeddedSampler(List<TestElement> samplers, HTTPSampleResult subres,
                                            int embeddedTimeout) {
    HTTP2Sampler embeddedSampler = (HTTP2Sampler) samplers.get(0);
    HTTP2FutureResponseListener listener = embeddedSampler.getFutureResponseListener();
    if (listener == null) {
      // The request was never dispatched (the async send threw), so no completion can ever arrive.
      // Dequeue it: leaving it at the head made the caller re-select it forever without sleeping.
      LOG.debug("Embedded resource has no pending request, dropping it from the queue");
      samplers.remove(0);
      return false;
    }

    // Measured per resource, from when its request was dispatched, so it lines up with the deadline
    // the request itself carries. Timing from the moment this resource reaches the head of the
    // queue instead would grant it a fresh full timeout on top of however long it already waited;
    // timing the whole page from a single start (as this used to) went the other way and expired
    // resources that had in fact completed normally.
    long start = listener.getResponseStart() > 0
        ? listener.getResponseStart()
        : System.currentTimeMillis();
    while (true) {
      if (listener.isDone() || listener.isCancelled()) {
        consumeFirstEmbeddedSampler(samplers, subres);
        return false;
      }
      try {
        Thread.sleep(EMBEDDED_POLL_INTERVAL_MILLIS);
      } catch (InterruptedException e) {
        abortPendingEmbeddedRequests(samplers);
        return true;
      }
      if (embeddedTimeout > 0 && (System.currentTimeMillis() - start) >= embeddedTimeout) {
        String pendingUrl = listener.getRequest() != null
            ? listener.getRequest().getURI().toString()
            : "unknown";
        LOG.warn("Timeout after {}ms waiting for embedded resource {}", embeddedTimeout,
            pendingUrl);
        // Dequeue and abort before returning: without this the caller re-selects the same head, and
        // aborting is what releases the underlying Jetty request instead of leaking it. Report the
        // failure against this resource's URL (as JMeter does for a socket timeout), not as a clone
        // of the page container.
        samplers.remove(0);
        listener.cancel(true);
        subres.addSubResult(embeddedTimeoutErrorResult(embeddedSampler, start));
        setParentSampleSuccess(subres, false);
        return false;
      }
    }
  }

  @Override
  public void iterationStart(LoopIterationEvent iterEvent) {
    this.asyncListener = null;
    restoreSuppressedPreProcessors();
    JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();
    if (!jMeterVariables.isSameUserOnNextIteration()) {
      clearUserStores();
    }
  }

  /**
   * Clears pre-processors and timers from the {@link SamplePackage} before the async completion
   * pass so JMeter does not run them twice.
   */
  public void suppressPreProcessorsOnce() {
    if (suppressedSamplePackage != null) {
      return;
    }
    try {
      JMeterThread thread = JMeterContextService.getContext().getThread();
      if (thread == null) {
        return;
      }
      Field compilerField = JMeterThread.class.getDeclaredField("compiler");
      compilerField.setAccessible(true);
      TestCompiler compiler = (TestCompiler) compilerField.get(thread);
      if (compiler == null) {
        return;
      }
      SamplePackage pack = getSamplePackageFromCompiler(compiler);
      if (pack == null) {
        LOG.debug(
            "No SamplePackage found for sampler={}, skipping async completion suppression",
            getName());
        return;
      }
      boolean suppressed = false;
      List<PreProcessor> currentPre = pack.getPreProcessors();
      if (currentPre != null && !currentPre.isEmpty()) {
        suppressedPreProcessors = new ArrayList<>(currentPre);
        currentPre.clear();
        suppressed = true;
      }
      List<Timer> currentTimers = pack.getTimers();
      if (currentTimers != null && !currentTimers.isEmpty()) {
        suppressedTimers = new ArrayList<>(currentTimers);
        currentTimers.clear();
        suppressed = true;
      }
      if (suppressed) {
        suppressedSamplePackage = pack;
        LOG.debug("Pre-processors/timers suppressed for async completion run (sampler={})",
            getName());
      }
    } catch (Exception e) {
      LOG.debug("Failed to suppress pre-processors/timers for async completion", e);
    }
  }

  private SamplePackage getSamplePackageFromCompiler(TestCompiler compiler) {
    try {
      Field mapField = TestCompiler.class.getDeclaredField("samplerConfigMap");
      mapField.setAccessible(true);
      Map<?, SamplePackage> map = (Map<?, SamplePackage>) mapField.get(compiler);
      return map != null ? map.get(this) : null;
    } catch (Exception e) {
      LOG.debug("Failed to access samplerConfigMap for pre-processor suppression", e);
      return null;
    }
  }

  private void restoreSuppressedPreProcessors() {
    if (suppressedSamplePackage == null) {
      return;
    }
    try {
      if (suppressedPreProcessors != null) {
        List<PreProcessor> currentPre = suppressedSamplePackage.getPreProcessors();
        if (currentPre != null) {
          currentPre.clear();
          currentPre.addAll(suppressedPreProcessors);
        }
      }
      if (suppressedTimers != null) {
        List<Timer> currentTimers = suppressedSamplePackage.getTimers();
        if (currentTimers != null) {
          currentTimers.clear();
          currentTimers.addAll(suppressedTimers);
        }
      }
      LOG.debug("Pre-processors/timers restored after async completion (sampler={})", getName());
    } finally {
      suppressedPreProcessors = null;
      suppressedTimers = null;
      suppressedSamplePackage = null;
    }
  }

  private void closeConnections() {
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    for (HTTP2JettyClient client : clients.values()) {
      try {
        client.stop();
      } catch (InterruptedException e) {
        // JMeter Stop interrupts the thread before threadFinished; Jetty shutdown is interruptible.
        Thread.currentThread().interrupt();
        LOG.debug("Interrupted while closing BlazeMeter HTTP connection (test stopped)");
      } catch (Exception e) {
        if (HTTP2JettyClient.isExpectedShutdownException(e)) {
          LOG.debug("BlazeMeter HTTP connection closed during test stop: {}", e.toString());
        } else {
          LOG.error("Error while closing connection", e);
        }
      }
    }
    clients.clear();
  }

  private void dump() {
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    for (HTTP2JettyClient client : clients.values()) {
      try {
        LOG.debug(client.dump());
      } catch (Exception e) {
        LOG.error("Error while dumping BlazeMeter HTTP client state", e);
      }
    }
  }

  @Override
  public void testEnded() {
    super.testEnded();
    System.gc(); // Force free memory
  }

  @Override
  public void threadFinished() {
    if (dumpAtThreadEnd) {
      dump();
    }
    closeConnections();
  }

  private void clearUserStores() {
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    for (HTTP2JettyClient client : clients.values()) {
      try {
        client.clearCookies();
        client.clearAuthenticationResults();
      } catch (Exception e) {
        LOG.error("Error while cleaning user store", e);
      }
    }
  }

  private static final class HTTP2ClientKey {

    private final String target;
    private final boolean hasProxy;
    private final String proxyScheme;
    private final String proxyHost;
    private final int proxyPort;
    private final String profileKey;

    private HTTP2ClientKey(URL url, boolean hasProxy, String proxyScheme, String proxyHost,
                           int proxyPort, String profileKey) {
      this.target = url.getProtocol() + "://" + url.getAuthority();
      this.hasProxy = hasProxy;
      this.proxyScheme = proxyScheme;
      this.proxyHost = proxyHost;
      this.proxyPort = proxyPort;
      this.profileKey = profileKey;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      HTTP2ClientKey that = (HTTP2ClientKey) o;
      return hasProxy == that.hasProxy &&
          proxyPort == that.proxyPort &&
          target.equals(that.target) &&
          proxyScheme.equals(that.proxyScheme) &&
          proxyHost.equals(that.proxyHost) &&
          profileKey.equals(that.profileKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(target, hasProxy, proxyScheme, proxyHost, proxyPort, profileKey);
    }
  }
}
