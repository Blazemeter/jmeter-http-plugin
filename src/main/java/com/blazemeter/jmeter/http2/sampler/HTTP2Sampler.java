package com.blazemeter.jmeter.http2.sampler;

import static org.apache.jmeter.util.JMeterUtils.getPropDefault;

import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import com.blazemeter.jmeter.http2.core.HTTP2ClientProfileConfig;
import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.core.HpackFailureDetector;
import com.blazemeter.jmeter.http2.core.JmeterHttpClientExceptionMapper;
import com.blazemeter.jmeter.http2.core.ProtocolErrorException;
import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.helger.commons.annotation.VisibleForTesting;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
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
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.threads.TestCompiler;
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
  private transient List<PreProcessor> suppressedPreProcessors;
  private transient SamplePackage suppressedSamplePackage;
  private transient boolean profileInferenceWarningLogged;
  private transient boolean asyncParentSampleEnabled;

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

  public void setAsyncParentSampleEnabled(boolean enabled) {
    this.asyncParentSampleEnabled = enabled;
  }

  public boolean isAsyncParentSampleEnabled() {
    return asyncParentSampleEnabled;
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
    return getPropertyAsBoolean(CONCURRENT_DWN, true);
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
          if (isAsyncParentSampleEnabled()) {
            this.result.setIgnore();
          }
          HTTP2FutureResponseListener listener =
              new HTTP2FutureResponseListener(client.getMaxBufferSize());
          this.asyncListener = listener;
          Request req = client.sampleAsync(this, this.result, listener);
          req.send(listener); // Fire the Async
          return null;
        } else {
          // If there is a listener, it is processed using the result it had
          try {
            this.result = sampleFromListener(
                this.result, areFollowingRedirect, depth, this.asyncListener);
            if (isAsyncParentSampleEnabled()) {
              applyAsyncParentCompletionPipeline(this.result);
              this.result.setIgnore();
              HTTP2Controller.registerAsyncSampleResult(this, this.result, this.asyncListener);
              return null;
            }
            HTTP2Controller.registerAsyncSampleResult(this, this.result, this.asyncListener);
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
      HTTPSampleResult errorResult = buildErrorResult(e, this.result);
      if (isAsyncParentSampleEnabled()) {
        applyAsyncParentCompletionPipeline(errorResult);
        errorResult.setIgnore();
      }
      HTTP2Controller.registerAsyncSampleResult(this, errorResult, this.asyncListener);
      return isAsyncParentSampleEnabled() ? null : errorResult;
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
      HTTPSampleResult errorResult = buildErrorResult(e, this.result);
      if (isAsyncParentSampleEnabled()) {
        applyAsyncParentCompletionPipeline(errorResult);
        errorResult.setIgnore();
      }
      HTTP2Controller.registerAsyncSampleResult(this, errorResult, this.asyncListener);
      return isAsyncParentSampleEnabled() ? null : errorResult;
    }
  }

  /**
   * When {@link #isAsyncParentSampleEnabled()} and this sampler returns {@code null} to JMeter,
   * run post-processors and assertions here (same order as {@code JMeterThread}) so child elements
   * still apply; pre-processors already ran on the initial async dispatch and stay suppressed until
   * {@link #restoreSuppressedPreProcessors()} in {@code finally}.
   */
  private void applyAsyncParentCompletionPipeline(HTTPSampleResult res) {
    if (res == null || !isAsyncParentSampleEnabled()) {
      return;
    }
    JMeterContext ctx = JMeterContextService.getContext();
    SamplePackage pack = resolveSamplePackageForAsyncCompletion(ctx);
    if (pack == null) {
      LOG.warn("Async parent completion pipeline skipped for sampler={}: no SamplePackage",
          getName());
      return;
    }
    ctx.setCurrentSampler(this);
    AsyncCompletionSamplePipeline.runAfterAsyncSample(res, pack, ctx);
  }

  /**
   * Resolves the {@link SamplePackage} for the in-flight {@code JMeterThread.executeSamplePackage}
   * call. Prefer the pack captured when pre-processors were suppressed for async completion, then
   * {@code JMeterThread.PACKAGE_OBJECT}, then the compiler map.
   */
  private SamplePackage resolveSamplePackageForAsyncCompletion(JMeterContext ctx) {
    if (suppressedSamplePackage != null) {
      return suppressedSamplePackage;
    }
    if (ctx != null && ctx.getVariables() != null) {
      Object packObj = ctx.getVariables().getObject(JMeterThread.PACKAGE_OBJECT);
      if (packObj instanceof SamplePackage) {
        return (SamplePackage) packObj;
      }
    }
    try {
      JMeterThread thread = ctx != null ? ctx.getThread() : null;
      if (thread == null) {
        return null;
      }
      Field compilerField = JMeterThread.class.getDeclaredField("compiler");
      compilerField.setAccessible(true);
      TestCompiler compiler = (TestCompiler) compilerField.get(thread);
      return getSamplePackageFromCompiler(compiler);
    } catch (Exception e) {
      LOG.debug("Failed to resolve SamplePackage for async completion", e);
      return null;
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
  }

  /**
   * Builds an embedded-resource child sampler for a {@code file://} URL discovered by the HTML
   * parser (e.g. a relative {@code href}/{@code src} resolved against a {@code file://} parent).
   * {@code setImageParser} is enabled for {@code .html}/{@code .htm} targets so nested embedded
   * resources (e.g. an iframe pointing at another local HTML file) are themselves parsed and
   * downloaded, matching how a real HTTP-embedded HTML resource would recurse.
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
    fileSampler.setDomain(url.getHost());
    fileSampler.setPort(url.getPort());
    if (url.getQuery() == null) {
      fileSampler.setPath(url.getPath());
    } else {
      fileSampler.setPath(url.getPath() + url.getQuery());
    }
    fileSampler.setHeaderManager(getHeaderManager());
    fileSampler.setCookieManager(getCookieManager());
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

  public HTTPSampleResult resultProcessing(final boolean pAreFollowingRedirect,
                                           final int frameDepth, final HTTPSampleResult pRes) {
    return super.resultProcessing(pAreFollowingRedirect, frameDepth, pRes);
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
      res.addSubResult(errorResult(e, new HTTPSampleResult(res)));
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
              subres.addSubResult(
                  errorResult(new Exception(url.toString() + " is not a correct URI", e),
                      new HTTPSampleResult(res)));
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
              subres.addSubResult(
                  errorResult(new Exception(url.toString() + " URI can not be normalized", e),
                      new HTTPSampleResult(subres)));
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

            HTTP2Sampler h2s = new HTTP2Sampler();
            copyJettyProtocolSettingsToEmbeddedSampler(h2s);
            h2s.setMethod("GET");
            h2s.setSyncRequest(!isConcurrentDwn);
            h2s.setProtocol(url.getProtocol());
            h2s.setDomain(url.getHost());
            h2s.setPort(url.getPort());
            h2s.setFollowRedirects(true);
            h2s.setAutoRedirects(true);
            if (url.getQuery() == null) {
              h2s.setPath(url.getPath());
            } else {
              h2s.setPath(url.getPath() + url.getQuery());
            }

            // Set proxy
            h2s.setProxyHost(this.getProxyHost());
            h2s.setProxyPortInt(String.valueOf(this.getProxyPortInt()));
            h2s.setProxyScheme(this.getProxyScheme());
            h2s.setProxyUser(this.getProxyUser());
            h2s.setProxyPass(this.getProxyPass());
            // Set Managers
            h2s.setHeaderManager(getHeaderManager());
            h2s.setAuthManager(this.getAuthManager());
            h2s.setCookieManager(this.getCookieManager());

            HTTPSampleResult binRes = h2s.sample(
                url, HTTPConstants.GET, false, frameDepth + 1);

            if (isConcurrentDwn) {
              // if concurrent download emb. resources, add to a list for async gets later
              samplers.add(h2s);
            } else {
              // default: serial download embedded resources
              subres.addSubResult(binRes);
              setParentSampleSuccess(subres,
                  subres.isSuccessful() && (binRes == null || binRes.isSuccessful()));
              try {
                Thread.sleep(10);
              } catch (InterruptedException e) {
                interrupted = true;
              }
            }
          }
        } catch (ClassCastException e) { // NOSONAR
          subres.addSubResult(errorResult(new Exception(binURL + " is not a correct URI", e),
              new HTTPSampleResult(subres)));
          setParentSampleSuccess(subres, false);
        }
        if (interrupted) {
          break;
        }
      }
      int embeddedTimeout = 0; // Use the same timeout for response
      if (this.getResponseTimeout() > 0) {
        embeddedTimeout = this.getResponseTimeout();
      } else {
        embeddedTimeout = this.requestTimeout;
      }
      long start = System.currentTimeMillis();

      // IF for download concurrent embedded resources
      if (isConcurrentDwn && !samplers.isEmpty()) {

        while (!samplers.isEmpty()) {

          HTTP2Sampler http2Sam = (HTTP2Sampler) samplers.get(0);

          HTTP2FutureResponseListener http2FListener =
              http2Sam.getFutureResponseListener();
          while (!interrupted && (http2FListener != null)) {
            if (http2FListener.isDone() || http2FListener.isCancelled()) {
              String urlProcesed = http2FListener.getRequest().getURI().toString();
              LOG.debug("HTTP2 Future Finished, retrying the sample with that data {}",
                  urlProcesed);

              HTTPSampleResult binRes = (HTTPSampleResult) http2Sam.sample();
              samplers.remove(0); // Remove the sample
              LOG.debug("OnComplete " + urlProcesed);

              subres.addSubResult(binRes);
              setParentSampleSuccess(subres,
                  subres.isSuccessful() && (binRes == null
                      || binRes.isSuccessful()));
              break;
            }
            try {
              Thread.sleep(10);
            } catch (InterruptedException e) {
              samplers.clear();
              interrupted = true;
            }
            if (embeddedTimeout > 0 && (System.currentTimeMillis() - start) >= embeddedTimeout) {
              // TODO: This doesn't stop the async execution, only allow to don't lock execution
              LOG.debug("Timeout on Wait!");
              subres.addSubResult(errorResult(new Exception(
                      "Error downloading embedded resources, execution timeout"),
                  new HTTPSampleResult(subres)));
              setParentSampleSuccess(subres, false);
              break;
            }
          }
        }
      }
    }
    setSyncRequest(orgSyncRequest); // Restore the default setting to main request

    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    return res;
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

  public void suppressPreProcessorsOnce() {
    if (suppressedPreProcessors != null) {
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
      if (pack == null || pack.getPreProcessors() == null) {
        LOG.debug("No SamplePackage found for sampler={}, skipping pre-processor suppression",
            getName());
        return;
      }
      List<PreProcessor> currentPre = pack.getPreProcessors();
      if (currentPre.isEmpty()) {
        return;
      }
      suppressedPreProcessors = new ArrayList<>(currentPre);
      suppressedSamplePackage = pack;
      currentPre.clear();
      LOG.debug("Pre-processors suppressed for async completion run (sampler={})", getName());
    } catch (Exception e) {
      LOG.debug("Failed to suppress pre-processors for async completion", e);
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
    if (suppressedPreProcessors == null || suppressedSamplePackage == null) {
      return;
    }
    try {
      List<PreProcessor> currentPre = suppressedSamplePackage.getPreProcessors();
      if (currentPre != null) {
        currentPre.clear();
        currentPre.addAll(suppressedPreProcessors);
      }
      LOG.debug("Pre-processors restored after async completion (sampler={})", getName());
    } finally {
      suppressedPreProcessors = null;
      suppressedSamplePackage = null;
    }
  }

  private void closeConnections() {
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    for (HTTP2JettyClient client : clients.values()) {
      try {
        client.stop();
      } catch (Exception e) {
        LOG.error("Error while closing connection", e);
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
