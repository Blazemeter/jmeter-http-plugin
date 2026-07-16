package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.LowLevelDebugLog.lowLevelDebug;

import com.blazemeter.jmeter.http2.core.jetty.CustomWwwAuthenticationProtocolHandler;
import com.blazemeter.jmeter.http2.core.jetty.custom.http2.CustomClientConnectionFactoryOverHTTP2;
import com.blazemeter.jmeter.http2.core.jetty.custom.http2.CustomHttpClientTransportOverHTTP2;
import com.blazemeter.jmeter.http2.core.jetty.custom.http3.CustomClientConnectionFactoryOverHTTP3;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.Cookie;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.brotli.dec.BrotliInputStream;
import org.eclipse.jetty.client.AbstractAuthentication;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentDecoder;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.DigestAuthentication;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.PathRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.RetryableRequestException;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.compression.brotli.BrotliCompression;
import org.eclipse.jetty.compression.client.CompressionContentDecoderFactory;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientQuicConfiguration;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.quic.quiche.client.QuicheClientQuicConfiguration;
import org.eclipse.jetty.quic.quiche.client.QuicheTransport;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2JettyClient {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2JettyClient.class);
  private static final String PLUGIN_BUILD_TAG =
      "HTTP2JettyClient build: host-header-filter+http3-always-v2026-01-26";
  private static final boolean FORCE_HTTP2_ONLY = false;
  private static final Set<String> SUPPORTED_METHODS = new HashSet<>(Arrays
      .asList(HTTPConstants.GET, HTTPConstants.HEAD, HTTPConstants.POST, HTTPConstants.PUT,
          HTTPConstants.PATCH, HTTPConstants.OPTIONS, HTTPConstants.DELETE));
  private static final Set<String> METHODS_WITH_BODY = new HashSet<>(Arrays
      .asList(HTTPConstants.POST, HTTPConstants.PUT, HTTPConstants.PATCH));
  private static final Path ALPN_DEBUG_LOG_PATH = resolveAlpnLogPath();
  private static final boolean ADD_CONTENT_TYPE_TO_POST_IF_MISSING = JMeterUtils.getPropDefault(
      "http.post_add_content_type_if_missing", false);
  private static final Pattern PORT_PATTERN = Pattern.compile("\\d+");
  private static final String MULTI_PART_SEPARATOR = "--";
  private static final String LINE_SEPARATOR = "\r\n";
  private static final String DEFAULT_FILE_MIME_TYPE = "application/octet-stream";
  private static final String ALT_SVC_HEADER = "alt-svc";
  private static final String ATTR_HTTP3_ATTEMPTED = "bzm.http3.attempted";
  private static final String ATTR_H2C_FALLBACK_ATTEMPTED = "bzm.h2cFallbackAttempted";
  private static final String ATTR_ORIGIN_KEY = "bzm.http3.origin";
  private static final String ATTR_REQUEST_HEADERS_SERIALIZED = "bzm.request.headers.serialized";
  private static final String PROP_SKIP_REDUNDANT_MANUAL_DECODE =
      "blazemeter.http.skipManualDecodeWhenAdvertised";
  private static final Path DEBUG_LOG_PATH = resolveDebugLogPath();
  private static final String PROFILE_PROPERTY = "httpJettyClient.profile";
  private static final String PROFILE_BROWSER_LIKE = "browser-like";
  private static final String PROFILE_BROWSER_LIKE_CUSTOM = "browser-like-custom";
  private static final String PROFILE_BROWSER_COMPATIBLE = "browser-compatible";
  private static final String PROFILE_LEGACY = "legacy";
  private static final long ALT_SVC_DEFAULT_MAX_AGE_SECONDS = 86400;
  private static final long DEFAULT_HTTP3_BROKEN_COOLDOWN_MS = 300000;
  private static final long DEFAULT_HTTP1_ONLY_COOLDOWN_MS = 300000;
  private static final long DEFAULT_H2C_CACHE_TTL_MS = 300000;
  private static final long DEFAULT_HAPPY_EYEBALLS_DELAY_MS = 250;
  private static final long H3_RECENT_SUCCESS_WINDOW_MS =
      TimeUnit.MINUTES.toMillis(5);
  private static final Object SHARED_POOL_LOCK = new Object();
  private static final String SHARED_POOL_NAME = "http2-shared";
  private static volatile QueuedThreadPool sharedThreadPool;
  private static volatile Executor sharedExecutor;
  private static volatile int sharedMaxThreads = -1;
  private static volatile int sharedMinThreads = -1;
  private static final Object HAPPY_EYEBALLS_LOCK = new Object();
  private static final AtomicInteger HAPPY_EYEBALLS_CLIENTS = new AtomicInteger(0);
  private static volatile ScheduledExecutorService happyEyeballsScheduler;
  private static volatile ScheduledExecutorService happyEyeballsExecutor;
  private static final Map<String, AltSvcEntry> ALT_SVC_CACHE = new ConcurrentHashMap<>();
  private static final Map<String, Http1OnlyEntry> HTTP1_ONLY_CACHE = new ConcurrentHashMap<>();
  private static final Map<String, H2cEntry> H2C_CACHE = new ConcurrentHashMap<>();
  private int requestTimeout = 0;
  private int maxBufferSize = 21 * 1024 * 1024;
  private int maxThreads = 5;
  private boolean maxThreadsConfigured = false;
  private int minThreads = 1;
  private int maxRequestsQueuedPerDestination = Short.MAX_VALUE;
  private int maxConnectionsPerDestination = 100;

  private int byteBufferPoolFactor = 4;
  private int maxConcurrentPushedStreams = 100;
  private int maxRequestsPerConnection = 100;
  private boolean sharedThreadPoolEnabled = true;

  // Experimental HTTP/2 SETTINGS frame configuration
  // These can be adjusted via properties to fix protocol_error with specific servers
  private int settingsInitialWindowSize = 65535;
  private int settingsMaxFrameSize = 16384;
  private int settingsMaxConcurrentStreams = 100;
  // Reduced from 8192 to be more conservative and avoid protocol_error (Issue #12071)
  private int settingsMaxHeaderListSize = 4096;
  private int settingsHeaderTableSize = 4096;
  private boolean disableServerPush = false;

  private boolean strictEventOrdering = false;
  private boolean removeIdleDestinations = true;
  private int idleTimeout = 60000;
  private final HttpClient httpClient;
  private final HttpClient httpClientNoH3;
  private final HttpClient httpClientHttp1Only;
  private final HttpClient httpClientH2cPrior;
  private final HttpClient httpClientH2cUpgrade;
  private String mainProtocolsSnapshot;
  private boolean http1UpgradeRequired;

  private ByteBufferPool bufferPool;
  private CompressionContentDecoderFactory brotliDecoderFactory;
  private CompressionContentDecoderFactory zstdDecoderFactory;
  private ContentDecoder.Factory gzipDecoderFactory;
  // Jetty's compression module ships gzip/brotli/zstd decoders but no deflate one, so
  // DeflateContentDecoderFactory is our own Inflater-based implementation (not a Jetty class).
  // Kept initialized for the disableDeflateDecoder diagnostic toggle, but no longer registered
  // per-request; see the comment in configureContentDecoders() below.
  private DeflateContentDecoderFactory deflateDecoderFactory;
  private boolean decoderFactoriesInitialized = false;
  private int quicMaxIdleTimeout = 30000;
  private int quicMaxBidirectionalStreams = 100;
  private int quicMaxUnidirectionalStreams = 100;
  private long http3BrokenCooldownMs = DEFAULT_HTTP3_BROKEN_COOLDOWN_MS;
  private long http1OnlyCooldownMs = DEFAULT_HTTP1_ONLY_COOLDOWN_MS;
  private long h2cCacheTtlMs = DEFAULT_H2C_CACHE_TTL_MS;
  private long happyEyeballsDelayMs = DEFAULT_HAPPY_EYEBALLS_DELAY_MS;
  private boolean http2PriorKnowledgeEnabled = false;
  private boolean http3PriorKnowledgeEnabled = false;
  private boolean enableHttp3 = true;
  private boolean enableHttp2 = true;
  private boolean enableHttp1 = true;
  private boolean alpnEnabled = true;
  private boolean fallbackEnabled = true;
  private boolean protocolErrorFallbackEnabled = true;
  private boolean goawayRetryEnabled = true;
  private int maxGoawayRetries = 1;
  private boolean altSvcCacheEnabled = true;
  private boolean http1OnlyCacheEnabled = true;
  private boolean h2cCacheEnabled = true;
  private boolean heExecutorsRegistered = false;

  public HTTP2JettyClient(boolean http1UpgradeRequired, String name) {
    this(http1UpgradeRequired, name, null);
  }

  public HTTP2JettyClient(boolean http1UpgradeRequired, String name,
                          HTTP2ClientProfileConfig profileConfig) {
    loadProperties(profileConfig);
    lowLevelDebug(PLUGIN_BUILD_TAG);

    // Create buffer pool first (needed for both TCP and QUIC connectors)
    this.bufferPool = new ArrayByteBufferPool();
    ensureDecoderFactoriesInitialized();

    ClientConnector clientConnector = createClientConnector(name);

    // Configure SSL/TLS protocol
    // In Jetty 12, ALPN protocols are automatically configured by HttpClientTransportDynamic
    // based on the ClientConnectionFactory.Info instances provided (http2, http11, etc.)
    // We only need to set the TLS protocol here
    try {
      SslContextFactory.Client sslContextFactoryFromConnector =
          (SslContextFactory.Client) clientConnector.getSslContextFactory();
      if (sslContextFactoryFromConnector != null) {
        sslContextFactoryFromConnector.setProtocol("TLS");
        lowLevelDebug("SSL Context Factory: protocol set to TLS");
        lowLevelDebug("ALPN protocols will be automatically configured by "
            + "HttpClientTransportDynamic based on provided connection factories");
      }
    } catch (Exception e) {
      lowLevelDebug("Could not set SSL protocol explicitly", e);
    }

    ClientConnectionFactory.Info http11 = HttpClientConnectionFactory.HTTP11;

    HTTP2Client http2Client = new HTTP2Client(clientConnector);
    // HTTP2Client defaults to 8 KiB; HttpClient defaults to -1 (no local HPACK cap). Match
    // HttpClient here so parsers are not created with 8192. On start(), Jetty configure()
    // re-syncs from HttpClient.getMaxResponseHeadersSize(), so this stays dynamic if callers
    // change HttpClient before start().
    http2Client.setMaxResponseHeadersSize(-1);
    enableFrameLoggingIfConfigured(http2Client);

    // Add session listener to log SETTINGS frames received from server (for debugging Issue #12071)
    // This helps identify if the server sends a lower SETTINGS_MAX_HEADER_LIST_SIZE
    try {
      // Use reflection to add Session.Listener if available
      Class<?> sessionListenerClass = Class.forName("org.eclipse.jetty.http2.api.Session$Listener");

      Object sessionListener = java.lang.reflect.Proxy.newProxyInstance(
          sessionListenerClass.getClassLoader(),
          new Class<?>[] {sessionListenerClass},
          (proxy, method, args) -> {
            if ("onSettings".equals(method.getName()) && args.length >= 2) {
              // Log SETTINGS frame received from server
              Object settingsFrame = args[1];
              try {
                // Try to get settings map from SettingsFrame
                java.lang.reflect.Method getSettingsMethod =
                    settingsFrame.getClass().getMethod("getSettings");
                @SuppressWarnings("unchecked")
                java.util.Map<Integer, Integer> settings =
                    (java.util.Map<Integer, Integer>) getSettingsMethod.invoke(settingsFrame);

                if (settings != null) {
                  // SETTINGS_MAX_HEADER_LIST_SIZE = 0x6
                  Integer maxHeaderListSize = settings.get(0x6);
                  if (maxHeaderListSize != null) {
                    lowLevelDebug("HTTP/2 SETTINGS frame received from server: "
                        + "SETTINGS_MAX_HEADER_LIST_SIZE={}", maxHeaderListSize);
                    if (maxHeaderListSize < settingsMaxHeaderListSize) {
                      LOG.warn("Server SETTINGS_MAX_HEADER_LIST_SIZE ({}) is lower than "
                              + "client setting ({}). This may cause protocol_error if headers "
                              + "exceed server limit (Issue #12071).",
                          maxHeaderListSize, settingsMaxHeaderListSize);
                    }
                  }
                  // Log other relevant SETTINGS
                  Integer maxFrameSize = settings.get(0x5); // SETTINGS_MAX_FRAME_SIZE
                  Integer initialWindowSize = settings.get(0x4); // INITIAL_WINDOW_SIZE
                  Integer maxConcurrentStreams = settings.get(0x3); // MAX_CONCURRENT_STREAMS

                  if (maxFrameSize != null) {
                    lowLevelDebug("HTTP/2 SETTINGS: SETTINGS_MAX_FRAME_SIZE={}", maxFrameSize);
                  }
                  if (initialWindowSize != null) {
                    lowLevelDebug("HTTP/2 SETTINGS: SETTINGS_INITIAL_WINDOW_SIZE={}",
                        initialWindowSize);
                  }
                  if (maxConcurrentStreams != null) {
                    lowLevelDebug("HTTP/2 SETTINGS: SETTINGS_MAX_CONCURRENT_STREAMS={}",
                        maxConcurrentStreams);
                  }
                }
              } catch (Exception e) {
                lowLevelDebug("Could not extract SETTINGS from frame", e);
              }
            }
            return null; // Session.Listener methods return void
          });

      // Add the listener to HTTP2Client
      java.lang.reflect.Method addSessionListenerMethod =
          http2Client.getClass().getMethod("addSessionListener", sessionListenerClass);
      addSessionListenerMethod.invoke(http2Client, sessionListener);
      lowLevelDebug("HTTP2Client: Session listener added to log SETTINGS frames from server");
    } catch (Exception e) {
      lowLevelDebug("Could not add Session.Listener to HTTP2Client "
          + "(may not be available in this Jetty version)", e);
    }

    CustomClientConnectionFactoryOverHTTP2.HTTP2 http2 =
        new CustomClientConnectionFactoryOverHTTP2.HTTP2(http2Client);

    // Configure server push (can be disabled for compatibility)
    if (disableServerPush) {
      http2Client.setMaxConcurrentPushedStreams(0);
      lowLevelDebug("HTTP2Client: Server push disabled for compatibility");
    } else {
      http2Client.setMaxConcurrentPushedStreams(maxConcurrentPushedStreams);
    }
    if (alpnEnabled) {
      http2Client.setApplicationProtocols(Arrays.asList("h2", "http/1.1"));
    }
    http2Client.setUseALPN(alpnEnabled);

    // Diagnostic toggle: skip custom HTTP/2 SETTINGS configuration.
    boolean skipHttp2Settings = Boolean.getBoolean("blazemeter.http.skipHttp2Settings");
    if (skipHttp2Settings) {
      lowLevelDebug("HTTP2Client: skipping custom SETTINGS configuration");
    } else {
      // Configure HTTP/2 SETTINGS frame parameters
      // These parameters are sent in the SETTINGS frame during HTTP/2 connection establishment
      // Some servers may reject HTTP/2 if these values are not compatible
      // Using reflection to access methods that may not be available in all Jetty versions
      // Values can be configured via properties for specific server compatibility

      // SETTINGS_INITIAL_WINDOW_SIZE: Initial window size for flow control
      try {
        if (hasMethod(http2Client.getClass(), "setInitialStreamWindowSize", int.class)) {
          http2Client.getClass().getMethod("setInitialStreamWindowSize", int.class)
              .invoke(http2Client, settingsInitialWindowSize);
          lowLevelDebug("HTTP2Client: setInitialStreamWindowSize={} (SETTINGS_INITIAL_WINDOW_SIZE)",
              settingsInitialWindowSize);
        }
      } catch (Exception e) {
        lowLevelDebug("HTTP2Client: setInitialStreamWindowSize not available", e);
      }

      // SETTINGS_MAX_FRAME_SIZE: Maximum size of a frame
      try {
        if (hasMethod(http2Client.getClass(), "setMaxFrameSize", int.class)) {
          http2Client.getClass().getMethod("setMaxFrameSize", int.class)
              .invoke(http2Client, settingsMaxFrameSize);
          lowLevelDebug("HTTP2Client: setMaxFrameSize={} (SETTINGS_MAX_FRAME_SIZE)",
              settingsMaxFrameSize);
        }
      } catch (Exception e) {
        lowLevelDebug("HTTP2Client: setMaxFrameSize not available", e);
      }

      // SETTINGS_MAX_CONCURRENT_STREAMS: Maximum number of concurrent streams
      // Note: 0 means no limit (RFC 7540)
      try {
        if (hasMethod(http2Client.getClass(), "setMaxConcurrentStreams", int.class)) {
          http2Client.getClass().getMethod("setMaxConcurrentStreams", int.class)
              .invoke(http2Client, settingsMaxConcurrentStreams);
          lowLevelDebug("HTTP2Client: setMaxConcurrentStreams={} (SETTINGS_MAX_CONCURRENT_STREAMS)",
              settingsMaxConcurrentStreams);
        }
      } catch (Exception e) {
        lowLevelDebug("HTTP2Client: setMaxConcurrentStreams not available", e);
      }

      // SETTINGS_MAX_HEADER_LIST_SIZE: Maximum size of header list
      // Note: 0 means no limit (RFC 7540)
      try {
        if (hasMethod(http2Client.getClass(), "setMaxHeaderListSize", int.class)) {
          http2Client.getClass().getMethod("setMaxHeaderListSize", int.class)
              .invoke(http2Client, settingsMaxHeaderListSize);
          lowLevelDebug("HTTP2Client: setMaxHeaderListSize={} (SETTINGS_MAX_HEADER_LIST_SIZE)",
              settingsMaxHeaderListSize);
        }
      } catch (Exception e) {
        lowLevelDebug("HTTP2Client: setMaxHeaderListSize not available", e);
      }

      // SETTINGS_HEADER_TABLE_SIZE: Maximum size of header compression table (HPACK)
      try {
        if (hasMethod(http2Client.getClass(), "setHeaderTableSize", int.class)) {
          http2Client.getClass().getMethod("setHeaderTableSize", int.class)
              .invoke(http2Client, settingsHeaderTableSize);
          lowLevelDebug("HTTP2Client: setHeaderTableSize={} (SETTINGS_HEADER_TABLE_SIZE)",
              settingsHeaderTableSize);
        }
      } catch (Exception e) {
        lowLevelDebug("HTTP2Client: setHeaderTableSize not available", e);
      }
    }

    lowLevelDebug("HTTP2Client configured: ALPN={}, maxConcurrentPushedStreams={}, "
        + "http1UpgradeRequired={}", alpnEnabled, maxConcurrentPushedStreams, http1UpgradeRequired);
    lowLevelDebug("HTTP2Client SETTINGS frame parameters configured "
        + "(via reflection where available)");
    // Note: setProtocols() was removed in Jetty 12.1.5, protocols are configured via ALPN

    // Configure HTTP/3 and QUIC (temporarily disabled for diagnostic runs)
    ClientConnectionFactory.Info http3 = null;
    if (!FORCE_HTTP2_ONLY && enableHttp3) {
      try {
        ClientConnector quicConnector = createClientConnector(name + "-quic");
        quicConnector.setIdleTimeout(Duration.ofMillis(quicMaxIdleTimeout));

        QuicheClientQuicConfiguration quicConfig =
            HTTP3ClientQuicConfiguration.configure(new QuicheClientQuicConfiguration());
        HTTP3Client http3Client = new HTTP3Client(quicConfig, quicConnector);
        http3Client.setUseALPN(true);

        if (hasMethod(http3Client.getClass(), "setMaxConcurrentPushedStreams", int.class)) {
          http3Client.getClass().getMethod("setMaxConcurrentPushedStreams", int.class)
              .invoke(http3Client, maxConcurrentPushedStreams);
        }

        Transport quicTransport = new QuicheTransport(quicConfig);
        http3 = new CustomClientConnectionFactoryOverHTTP3.HTTP3(http3Client, quicTransport);

        lowLevelDebug("HTTP/3 and QUIC support enabled");
      } catch (Exception e) {
        throw new IllegalStateException(
            "Failed to initialize HTTP/3/QUIC support; dependencies must be available at runtime.",
            e);
      }
    } else if (FORCE_HTTP2_ONLY) {
      lowLevelDebug("HTTP/3 disabled (forced HTTP/2 only)");
    } else {
      lowLevelDebug("HTTP/3 disabled (profile configuration)");
    }

    // If ALPN could not negotiate HTTP2, it tries in the order of protocols indicated
    // Include HTTP/3 if available
    // NOTE: In Jetty 12.1.5, the order in HttpClientTransportDynamic affects ALPN negotiation.
    // Some servers (Google, demoblaze.com, blazedemo.com) reject HTTP/2 frames from Jetty 12.1.5
    // even though ALPN negotiates HTTP/2 successfully. This is a regression from Jetty 11.
    // We try HTTP/2 first, then fallback to HTTP/1.1 if needed.
    ClientConnectionFactory.Info[] mainProtocols = buildMainProtocols(http3, http2, http11);
    HttpClientTransport transport = new HttpClientTransportDynamic(clientConnector, mainProtocols);
    mainProtocolsSnapshot = protocolList(mainProtocols);
    lowLevelDebug("HttpClientTransportDynamic configured with protocols: {}",
        mainProtocolsSnapshot);

    configureTransport(transport);

    this.httpClient = new HttpClient(transport);
    configureHttpClient(this.httpClient, clientConnector);

    if (FORCE_HTTP2_ONLY || !enableHttp3) {
      this.httpClientNoH3 = this.httpClient;
    } else {
      ClientConnector noH3Connector = createClientConnector(name + "-noh3");
      ClientConnectionFactory.Info[] noH3Protocols = buildNoH3Protocols(http2, http11);
      HttpClientTransport noH3Transport =
          new HttpClientTransportDynamic(noH3Connector, noH3Protocols);
      configureTransport(noH3Transport);
      this.httpClientNoH3 = new HttpClient(noH3Transport);
      configureHttpClient(this.httpClientNoH3, noH3Connector);
    }
    ClientConnector http1Connector = createClientConnector(name + "-http1");
    HttpClientTransport http1Transport = new HttpClientTransportDynamic(http1Connector, http11);
    // HTTP/1.1 has no multiplexing (Jetty rejects a 2nd in-flight exchange per connection).
    configureTransport(http1Transport, 1);
    this.httpClientHttp1Only = new HttpClient(http1Transport);
    configureHttpClient(this.httpClientHttp1Only, http1Connector);

    ClientConnector h2cUpgradeConnector = createClientConnector(name + "-h2c-upgrade");
    HTTP2Client http2cUpgradeClient = new HTTP2Client(h2cUpgradeConnector);
    http2cUpgradeClient.setMaxResponseHeadersSize(-1);
    http2cUpgradeClient.setUseALPN(false);
    if (disableServerPush) {
      http2cUpgradeClient.setMaxConcurrentPushedStreams(0);
    } else {
      http2cUpgradeClient.setMaxConcurrentPushedStreams(maxConcurrentPushedStreams);
    }
    CustomClientConnectionFactoryOverHTTP2.HTTP2C http2cUpgrade =
        new CustomClientConnectionFactoryOverHTTP2.HTTP2C(http2cUpgradeClient);
    ClientConnectionFactory.Info[] h2cUpgradeProtocols =
        buildH2cUpgradeProtocols(http11, http2cUpgrade);
    HttpClientTransport h2cUpgradeTransport =
        new HttpClientTransportDynamic(h2cUpgradeConnector, h2cUpgradeProtocols);
    configureTransport(h2cUpgradeTransport);
    this.httpClientH2cUpgrade = new HttpClient(h2cUpgradeTransport);
    configureHttpClient(this.httpClientH2cUpgrade, h2cUpgradeConnector);

    ClientConnector h2cConnector = createClientConnector(name + "-h2c");
    HTTP2Client http2cClient = new HTTP2Client(h2cConnector);
    http2cClient.setMaxResponseHeadersSize(-1);
    http2cClient.setUseALPN(false);
    if (disableServerPush) {
      http2cClient.setMaxConcurrentPushedStreams(0);
    } else {
      http2cClient.setMaxConcurrentPushedStreams(maxConcurrentPushedStreams);
    }
    HttpClientTransport h2cTransport = new CustomHttpClientTransportOverHTTP2(http2cClient);
    configureTransport(h2cTransport);
    this.httpClientH2cPrior = new HttpClient(h2cTransport);
    configureHttpClient(this.httpClientH2cPrior, h2cConnector);
    this.http1UpgradeRequired = http1UpgradeRequired;
    this.httpClient.setName(name);
    if (httpClientNoH3 != httpClient) {
      this.httpClientNoH3.setName(name + "-noh3");
    }
    this.httpClientHttp1Only.setName(name + "-http1");
    this.httpClientH2cPrior.setName(name + "-h2c");
    this.httpClientH2cUpgrade.setName(name + "-h2c-upgrade");
  }

  public HTTP2JettyClient() {
    this(false, "HttpClient");
  }

  private void enableFrameLoggingIfConfigured(HTTP2Client http2Client) {
    if (!LowLevelDebugLog.isEnabled()) {
      return;
    }
    http2Client.addBean(new HTTP2Session.FrameListener() {
      @Override
      public void onIncomingFrame(Session session, Frame frame) {
        logFrame("IN", frame);
      }

      @Override
      public void onOutgoingFrame(Session session, Frame frame) {
        logFrame("OUT", frame);
      }
    });
    lowLevelDebug("HTTP2Client: Frame logging enabled (http2-debug.log)");
  }

  private void logFrame(String direction, Frame frame) {
    if (frame == null) {
      return;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("frame ").append(direction).append(" type=")
        .append(frame.getClass().getSimpleName());
    Integer streamId = tryGetInt(frame, "getStreamId");
    if (streamId != null) {
      sb.append(" streamId=").append(streamId);
    }
    Boolean endStream = tryGetBoolean(frame, "isEndStream");
    if (endStream != null) {
      sb.append(" endStream=").append(endStream);
    }
    Integer dataLength = tryGetDataLength(frame);
    if (dataLength != null) {
      sb.append(" dataLen=").append(dataLength);
    }
    if (frame instanceof ResetFrame) {
      Integer error = tryGetInt(frame, "getError");
      if (error != null) {
        sb.append(" error=").append(error);
      }
    } else if (frame instanceof GoAwayFrame) {
      Integer error = tryGetInt(frame, "getError");
      Integer lastStreamId = tryGetInt(frame, "getLastStreamId");
      if (lastStreamId != null) {
        sb.append(" lastStreamId=").append(lastStreamId);
      }
      if (error != null) {
        sb.append(" error=").append(error);
      }
    } else if (frame instanceof HeadersFrame) {
      MetaData metaData = ((HeadersFrame) frame).getMetaData();
      if (metaData != null) {
        HttpFields fields = metaData.getHttpFields();
        if (fields != null && fields.size() > 0) {
          sb.append(" headers=").append(fields.toString().trim());
        } else {
          sb.append(" meta=").append(metaData.toString());
        }
      } else {
        sb.append(" meta=null");
      }
    } else if (frame instanceof SettingsFrame) {
      SettingsFrame settingsFrame = (SettingsFrame) frame;
      Map<Integer, Integer> settings = settingsFrame.getSettings();
      if (settings != null && !settings.isEmpty()) {
        sb.append(" settings=").append(settings);
      }
    }
    debugToFile(sb.toString());
    lowLevelDebug(sb.toString());
  }

  private Integer tryGetInt(Object target, String methodName) {
    if (target == null) {
      return null;
    }
    try {
      Method method = target.getClass().getMethod(methodName);
      Object value = method.invoke(target);
      if (value instanceof Integer) {
        return (Integer) value;
      }
    } catch (Exception ignored) {
      // Best-effort for diagnostic logging.
    }
    return null;
  }

  private Integer tryGetDataLength(Object target) {
    Integer dataLength = tryGetInt(target, "getDataLength");
    if (dataLength != null) {
      return dataLength;
    }
    dataLength = tryGetInt(target, "remaining");
    if (dataLength != null) {
      return dataLength;
    }
    return tryGetInt(target, "getLength");
  }

  private Boolean tryGetBoolean(Object target, String methodName) {
    if (target == null) {
      return null;
    }
    try {
      Method method = target.getClass().getMethod(methodName);
      Object value = method.invoke(target);
      if (value instanceof Boolean) {
        return (Boolean) value;
      }
    } catch (Exception ignored) {
      // Best-effort for diagnostic logging.
    }
    return null;
  }

  public void clearBufferPool() {
    // ByteBufferPool doesn't have a clear() method in Jetty 12
    // The pool is managed automatically by Jetty
  }

  /**
   * Helper method to check if a class has a specific method.
   * Used for reflection-based optional feature detection.
   */
  private boolean hasMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
    try {
      clazz.getMethod(methodName, paramTypes);
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  public ByteBufferPool getBufferPool() {
    return bufferPool;
  }

  public HttpClient getHttpClient() {
    return httpClient;
  }

  public int getMaxBufferSize() {
    return maxBufferSize;
  }

  public int getRequestTimeout() {
    return requestTimeout;
  }

  public void loadProperties() {
    loadProperties(null);
  }

  public void loadProperties(HTTP2ClientProfileConfig profileConfig) {
    ProfileDefaults defaults = resolveProfileDefaults(profileConfig);
    requestTimeout = JMeterUtils.getPropDefault("HTTPSampler.response_timeout", 0);
    byteBufferPoolFactor =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.byteBufferPoolFactor", String.valueOf(byteBufferPoolFactor)));
    maxBufferSize =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault("httpJettyClient.maxBufferSize",
            String.valueOf(2 * 1024 * 1024)));
    minThreads = Integer
        .parseInt(BzmHttpPluginProperties.getPropDefault("httpJettyClient.minThreads",
            String.valueOf(minThreads)));
    maxThreadsConfigured = BzmHttpPluginProperties.isDefined("httpJettyClient.maxThreads");
    maxThreads = Integer
        .parseInt(BzmHttpPluginProperties.getPropDefault("httpJettyClient.maxThreads",
            String.valueOf(maxThreads)));
    maxRequestsQueuedPerDestination =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.maxRequestsQueuedPerDestination",
            String.valueOf(maxRequestsQueuedPerDestination)));
    maxRequestsPerConnection =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.maxRequestsPerConnection",
            String.valueOf(maxRequestsPerConnection)));
    maxConcurrentPushedStreams =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.maxConcurrentPushedStreams",
            String.valueOf(maxConcurrentPushedStreams)));
    maxConnectionsPerDestination =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.maxConnectionsPerDestination",
            String.valueOf(maxConnectionsPerDestination)));
    strictEventOrdering =
        Boolean.parseBoolean(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.strictEventOrdering",
            String.valueOf(strictEventOrdering)));
    removeIdleDestinations =
        Boolean.parseBoolean(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.removeIdleDestinations",
            String.valueOf(removeIdleDestinations)));
    idleTimeout =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.idleTimeout",
            String.valueOf(idleTimeout)));
    sharedThreadPoolEnabled =
        BzmHttpPluginProperties.getPropDefault("httpJettyClient.sharedThreadPool", false);
    if (sharedThreadPoolEnabled && !maxThreadsConfigured) {
      maxThreads = 500;
    }
    quicMaxIdleTimeout = Integer
        .parseInt(BzmHttpPluginProperties.getPropDefault("httpJettyClient.quicMaxIdleTimeout",
            String.valueOf(quicMaxIdleTimeout)));
    quicMaxBidirectionalStreams =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.quicMaxBidirectionalStreams",
            String.valueOf(quicMaxBidirectionalStreams)));
    quicMaxUnidirectionalStreams =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.quicMaxUnidirectionalStreams",
            String.valueOf(quicMaxUnidirectionalStreams)));
    enableHttp3 = getBooleanProp("httpJettyClient.enableHttp3",
        profileConfig != null ? profileConfig.getEnableHttp3() : null,
        defaults.enableHttp3);
    enableHttp2 = getBooleanProp("httpJettyClient.enableHttp2",
        profileConfig != null ? profileConfig.getEnableHttp2() : null,
        defaults.enableHttp2);
    enableHttp1 = getBooleanProp("httpJettyClient.enableHttp1",
        profileConfig != null ? profileConfig.getEnableHttp1() : null,
        defaults.enableHttp1);
    alpnEnabled = getBooleanProp("httpJettyClient.alpnEnabled",
        profileConfig != null ? profileConfig.getAlpnEnabled() : null,
        defaults.alpnEnabled);
    fallbackEnabled = getBooleanProp("httpJettyClient.fallbackEnabled",
        profileConfig != null ? profileConfig.getFallbackEnabled() : null,
        defaults.fallbackEnabled);
    altSvcCacheEnabled =
        getBooleanProp("httpJettyClient.altSvcCacheEnabled",
            profileConfig != null ? profileConfig.getAltSvcCacheEnabled() : null,
            defaults.altSvcCacheEnabled);
    http1OnlyCacheEnabled =
        getBooleanProp("httpJettyClient.http1OnlyCacheEnabled",
            profileConfig != null ? profileConfig.getHttp1OnlyCacheEnabled() : null,
            defaults.http1OnlyCacheEnabled);
    h2cCacheEnabled = getBooleanProp("httpJettyClient.h2cCacheEnabled",
        profileConfig != null ? profileConfig.getH2cCacheEnabled() : null,
        defaults.h2cCacheEnabled);
    protocolErrorFallbackEnabled = resolveProtocolErrorFallback(defaults, profileConfig);
    goawayRetryEnabled =
        Boolean.parseBoolean(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.goawayRetryEnabled", "true"));
    maxGoawayRetries = Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
        "httpJettyClient.maxGoawayRetries", String.valueOf(maxGoawayRetries)));
    if (maxGoawayRetries < 0) {
      maxGoawayRetries = 0;
    }

    http3BrokenCooldownMs = getLongProp("httpJettyClient.http3BrokenCooldownMs",
        profileConfig != null ? profileConfig.getHttp3BrokenCooldownMs() : null,
        defaults.http3BrokenCooldownMs);
    http1OnlyCooldownMs = getLongProp("httpJettyClient.http1OnlyCooldownMs",
        profileConfig != null ? profileConfig.getHttp1OnlyCooldownMs() : null,
        defaults.http1OnlyCooldownMs);
    h2cCacheTtlMs = getLongProp("httpJettyClient.h2cCacheTtlMs",
        profileConfig != null ? profileConfig.getH2cCacheTtlMs() : null,
        defaults.h2cCacheTtlMs);
    http2PriorKnowledgeEnabled = getBooleanProp("httpJettyClient.http2PriorKnowledge",
        profileConfig != null ? profileConfig.getHttp2PriorKnowledgeEnabled() : null,
        defaults.http2PriorKnowledgeEnabled);
    http3PriorKnowledgeEnabled = Boolean.parseBoolean(BzmHttpPluginProperties.getPropDefault(
        "httpJettyClient.http3PriorKnowledge", "false"));
    happyEyeballsDelayMs = getLongProp("httpJettyClient.happyEyeballsDelayMs",
        profileConfig != null ? profileConfig.getHappyEyeballsDelayMs() : null,
        defaults.happyEyeballsDelayMs);
    // Default reduced to 4096 (Issue #12071)
    settingsMaxHeaderListSize =
        Integer.parseInt(BzmHttpPluginProperties.getPropDefault(
            "httpJettyClient.settingsMaxHeaderListSize", "4096"));

    if (!enableHttp1 && !enableHttp2 && !enableHttp3) {
      LOG.warn("All protocols disabled via configuration; enabling HTTP/1.1 "
          + "to keep client usable");
      enableHttp1 = true;
    }
    if (enableHttp3 && !enableHttp1 && !enableHttp2) {
      http3PriorKnowledgeEnabled = true;
      lowLevelDebug("HTTP/3 prior knowledge enabled (HTTP/3-only configuration)");
    }
    if (!enableHttp1 && !enableHttp2 && enableHttp3 && !altSvcCacheEnabled) {
      LOG.warn("HTTP/3 enabled but Alt-Svc cache disabled; enabling HTTP/1.1 "
          + "fallback");
      enableHttp1 = true;
    }
    if (!enableHttp2 && !alpnEnabled) {
      lowLevelDebug("ALPN disabled; HTTP/2 over TLS will not be attempted");
    }
    if (!enableHttp1) {
      protocolErrorFallbackEnabled = false;
    }
  }

  private boolean getBooleanProp(String key, Boolean overrideValue, boolean defaultValue) {
    if (overrideValue != null) {
      return overrideValue;
    }
    String raw = BzmHttpPluginProperties.resolveRaw(key);
    if (raw != null) {
      return Boolean.parseBoolean(raw);
    }
    return defaultValue;
  }

  private long getLongProp(String key, Long overrideValue, long defaultValue) {
    if (overrideValue != null) {
      return overrideValue;
    }
    String raw = BzmHttpPluginProperties.resolveRaw(key);
    if (raw != null) {
      String trimmed = raw.trim();
      if (!trimmed.isEmpty()) {
        return Long.parseLong(trimmed);
      }
    }
    return defaultValue;
  }

  private boolean resolveProtocolErrorFallback(ProfileDefaults defaults,
                                               HTTP2ClientProfileConfig profileConfig) {
    if (profileConfig != null && profileConfig.getProtocolErrorFallbackEnabled() != null) {
      return profileConfig.getProtocolErrorFallbackEnabled();
    }
    String fb = BzmHttpPluginProperties.resolveRaw("httpJettyClient.protocolErrorFallbackEnabled");
    if (fb != null) {
      return Boolean.parseBoolean(fb);
    }
    String df = BzmHttpPluginProperties.resolveRaw("httpJettyClient.disableFallback");
    if (df != null) {
      return !Boolean.parseBoolean(df);
    }
    return defaults.protocolErrorFallbackEnabled;
  }

  private ProfileDefaults resolveProfileDefaults(HTTP2ClientProfileConfig profileConfig) {
    String profile = profileConfig != null ? profileConfig.getProfile() : null;
    if (profile == null || profile.trim().isEmpty()) {
      String rawProfile =
          BzmHttpPluginProperties.getPropDefault(PROFILE_PROPERTY, PROFILE_BROWSER_LIKE).trim();
      profile = rawProfile.isEmpty() ? PROFILE_BROWSER_LIKE : rawProfile;
    }
    profile = profile.trim().toLowerCase(Locale.ROOT);
    switch (profile) {
      case PROFILE_BROWSER_COMPATIBLE:
        return ProfileDefaults.browserCompatible();
      case PROFILE_LEGACY:
        return ProfileDefaults.legacy();
      case PROFILE_BROWSER_LIKE_CUSTOM:
      case PROFILE_BROWSER_LIKE:
      default:
        return ProfileDefaults.browserLike();
    }
  }

  private ClientConnectionFactory.Info[] buildMainProtocols(
      ClientConnectionFactory.Info http3,
      ClientConnectionFactory.Info http2,
      ClientConnectionFactory.Info http11) {
    java.util.ArrayList<ClientConnectionFactory.Info> protocols = new java.util.ArrayList<>();
    if (!FORCE_HTTP2_ONLY && enableHttp3 && http3 != null) {
      protocols.add(http3);
    }
    if (enableHttp2 && alpnEnabled && http2 != null) {
      protocols.add(http2);
    }
    if (enableHttp1 && http11 != null) {
      protocols.add(http11);
    }
    if (protocols.isEmpty()) {
      LOG.warn("No protocols enabled for main transport; forcing HTTP/1.1");
      protocols.add(http11);
    }
    return protocols.toArray(new ClientConnectionFactory.Info[0]);
  }

  private ClientConnectionFactory.Info[] buildNoH3Protocols(
      ClientConnectionFactory.Info http2,
      ClientConnectionFactory.Info http11) {
    java.util.ArrayList<ClientConnectionFactory.Info> protocols = new java.util.ArrayList<>();
    if (enableHttp2 && alpnEnabled && http2 != null) {
      protocols.add(http2);
    }
    if (enableHttp1 && http11 != null) {
      protocols.add(http11);
    }
    if (protocols.isEmpty()) {
      LOG.warn("No protocols enabled for noH3 transport; forcing HTTP/1.1");
      protocols.add(http11);
    }
    return protocols.toArray(new ClientConnectionFactory.Info[0]);
  }

  private ClientConnectionFactory.Info[] buildH2cUpgradeProtocols(
      ClientConnectionFactory.Info http11,
      ClientConnectionFactory.Info http2c) {
    java.util.ArrayList<ClientConnectionFactory.Info> protocols = new java.util.ArrayList<>();
    if (enableHttp1 && http11 != null) {
      protocols.add(http11);
    }
    if (enableHttp2 && http2c != null) {
      protocols.add(http2c);
    }
    if (protocols.isEmpty()) {
      LOG.warn("No protocols enabled for h2c upgrade transport; forcing HTTP/1.1");
      protocols.add(http11);
    }
    return protocols.toArray(new ClientConnectionFactory.Info[0]);
  }

  private String protocolList(ClientConnectionFactory.Info[] protocols) {
    if (protocols == null || protocols.length == 0) {
      return "none";
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < protocols.length; i++) {
      if (i > 0) {
        out.append(", ");
      }
      out.append(protocols[i].getProtocols(true));
    }
    return out.toString();
  }

  private static class ProfileDefaults {
    private final boolean enableHttp3;
    private final boolean enableHttp2;
    private final boolean enableHttp1;
    private final boolean alpnEnabled;
    private final boolean fallbackEnabled;
    private final boolean protocolErrorFallbackEnabled;
    private final boolean altSvcCacheEnabled;
    private final boolean http1OnlyCacheEnabled;
    private final boolean h2cCacheEnabled;
    private final boolean http2PriorKnowledgeEnabled;
    private final long http3BrokenCooldownMs;
    private final long http1OnlyCooldownMs;
    private final long h2cCacheTtlMs;
    private final long happyEyeballsDelayMs;

    private ProfileDefaults(boolean enableHttp3, boolean enableHttp2, boolean enableHttp1,
                            boolean alpnEnabled, boolean fallbackEnabled,
                            boolean protocolErrorFallbackEnabled,
                            boolean altSvcCacheEnabled, boolean http1OnlyCacheEnabled,
                            boolean h2cCacheEnabled,
                            boolean http2PriorKnowledgeEnabled, long http3BrokenCooldownMs,
                            long http1OnlyCooldownMs,
                            long h2cCacheTtlMs, long happyEyeballsDelayMs) {
      this.enableHttp3 = enableHttp3;
      this.enableHttp2 = enableHttp2;
      this.enableHttp1 = enableHttp1;
      this.alpnEnabled = alpnEnabled;
      this.fallbackEnabled = fallbackEnabled;
      this.protocolErrorFallbackEnabled = protocolErrorFallbackEnabled;
      this.altSvcCacheEnabled = altSvcCacheEnabled;
      this.http1OnlyCacheEnabled = http1OnlyCacheEnabled;
      this.h2cCacheEnabled = h2cCacheEnabled;
      this.http2PriorKnowledgeEnabled = http2PriorKnowledgeEnabled;
      this.http3BrokenCooldownMs = http3BrokenCooldownMs;
      this.http1OnlyCooldownMs = http1OnlyCooldownMs;
      this.h2cCacheTtlMs = h2cCacheTtlMs;
      this.happyEyeballsDelayMs = happyEyeballsDelayMs;
    }

    private static ProfileDefaults browserLike() {
      return new ProfileDefaults(true, true, true, true, true, true, true, true, true, false,
          DEFAULT_HTTP3_BROKEN_COOLDOWN_MS, DEFAULT_HTTP1_ONLY_COOLDOWN_MS,
          DEFAULT_H2C_CACHE_TTL_MS, DEFAULT_HAPPY_EYEBALLS_DELAY_MS);
    }

    private static ProfileDefaults browserCompatible() {
      return new ProfileDefaults(true, true, true, true, true, true, true, true, true, false,
          DEFAULT_HTTP3_BROKEN_COOLDOWN_MS, DEFAULT_HTTP1_ONLY_COOLDOWN_MS,
          DEFAULT_H2C_CACHE_TTL_MS, 0L);
    }

    private static ProfileDefaults legacy() {
      return new ProfileDefaults(false, false, true, false, false, false, false, false, true,
          false, 0L, 0L, DEFAULT_H2C_CACHE_TTL_MS, 0L);
    }
  }

  public void start() throws Exception {
    if (!heExecutorsRegistered) {
      ensureHappyEyeballsExecutors();
      HAPPY_EYEBALLS_CLIENTS.incrementAndGet();
      heExecutorsRegistered = true;
    }
    if (!httpClient.isStarted()) {
      lowLevelDebug("Starting HttpClient: name={}, http1UpgradeRequired={}",
          httpClient.getName(), http1UpgradeRequired);
      httpClient.start();
      CustomWwwAuthenticationProtocolHandler.install(httpClient);
      lowLevelDebug("HttpClient started successfully");
    } else {
      lowLevelDebug("HttpClient already started");
    }
    if (httpClientNoH3 != httpClient && !httpClientNoH3.isStarted()) {
      lowLevelDebug("Starting HttpClient (no HTTP/3): name={}", httpClientNoH3.getName());
      httpClientNoH3.start();
      CustomWwwAuthenticationProtocolHandler.install(httpClientNoH3);
      lowLevelDebug("HttpClient (no HTTP/3) started successfully");
    }
    if (!httpClientHttp1Only.isStarted()) {
      lowLevelDebug("Starting HttpClient (HTTP/1.1 only): name={}", httpClientHttp1Only.getName());
      httpClientHttp1Only.start();
      CustomWwwAuthenticationProtocolHandler.install(httpClientHttp1Only);
      lowLevelDebug("HttpClient (HTTP/1.1 only) started successfully");
    }
    if (!httpClientH2cPrior.isStarted()) {
      lowLevelDebug("Starting HttpClient (H2C prior knowledge): name={}",
          httpClientH2cPrior.getName());
      httpClientH2cPrior.start();
      CustomWwwAuthenticationProtocolHandler.install(httpClientH2cPrior);
      lowLevelDebug("HttpClient (H2C prior knowledge) started successfully");
    }
    if (!httpClientH2cUpgrade.isStarted()) {
      lowLevelDebug("Starting HttpClient (H2C upgrade): name={}", httpClientH2cUpgrade.getName());
      httpClientH2cUpgrade.start();
      CustomWwwAuthenticationProtocolHandler.install(httpClientH2cUpgrade);
      lowLevelDebug("HttpClient (H2C upgrade) started successfully");
    }
  }

  /**
   * Creates a new HttpClient configured with only HTTP/1.1 (no HTTP/2) for fallback scenarios.
   * This is used when protocol_error is detected and we need to retry with HTTP/1.1 only.
   *
   * @param name Name for the HttpClient
   * @return A new HttpClient configured for HTTP/1.1 only
   */
  private HttpClient createHTTP11OnlyClient(String name) throws Exception {
    lowLevelDebug("Creating HTTP/1.1-only client for fallback");

    ClientConnector clientConnector = createClientConnector(name + "-http11-fallback");

    // Create transport with ONLY HTTP/1.1 (no HTTP/2)
    ClientConnectionFactory.Info http11 = HttpClientConnectionFactory.HTTP11;
    HttpClientTransport transport = new HttpClientTransportDynamic(clientConnector, http11);
    lowLevelDebug("HttpClientTransportDynamic configured with HTTP/1.1 only (fallback mode)");

    HttpClient http11Client = new HttpClient(transport);
    http11Client.setUserAgentField(null);
    http11Client.setMaxRequestsQueuedPerDestination(maxRequestsQueuedPerDestination);
    http11Client.setMaxConnectionsPerDestination(maxConnectionsPerDestination);
    http11Client.setStrictEventOrdering(strictEventOrdering);
    if (removeIdleDestinations) {
      http11Client.setDestinationIdleTimeout(idleTimeout);
    }
    http11Client.setIdleTimeout(idleTimeout);
    http11Client.setName(name + "-http11-fallback");

    // Start the client
    if (!http11Client.isStarted()) {
      http11Client.start();
      CustomWwwAuthenticationProtocolHandler.install(http11Client);
      lowLevelDebug("HTTP/1.1-only fallback client started");
    }

    return http11Client;
  }

  /**
   * Retries a request using HTTP/1.1 only (fallback mode).
   * This is called when protocol_error is detected with HTTP/2.
   * This is a public method that can be called from HTTP2Sampler.
   *
   * @param sampler The HTTP2Sampler with request details
   * @param result  The HTTPSampleResult with URL and method
   * @return HTTPSampleResult from the HTTP/1.1 request
   */
  public HTTPSampleResult retryWithHTTP11Only(HTTP2Sampler sampler, HTTPSampleResult result)
      throws Exception {
    lowLevelDebug("Retrying request with HTTP/1.1 only due to protocol_error");
    URL url = result.getURL();

    clearContentDecoders(httpClientHttp1Only);

    // Build a new request with the shared HTTP/1.1-only client (keeps auth config)
    Request http11Request = httpClientHttp1Only.newRequest(url.toURI())
        .method(result.getHTTPMethod())
        .timeout(requestTimeout, TimeUnit.MILLISECONDS)
        .followRedirects(sampler.getAutoRedirects());

    // Copy headers from sampler
    if (sampler.getHeaderManager() != null) {
      setHeaders(http11Request, url, sampler.getHeaderManager());
    }
    ensureHostHeader(http11Request, url);

    configureContentDecodersAndCapture(httpClientHttp1Only, http11Request);

    // Copy body if present
    setBody(http11Request, sampler, result);
    JmeterRequestHeadersSupport.prepareFromSampler(http11Request, sampler.getUseKeepAlive());

    // Send request
    lowLevelDebug("Sending HTTP/1.1 fallback request");
    ContentResponse response = http11Request.send();

    lowLevelDebug("HTTP/1.1 fallback request succeeded: status={}, version={}",
        response.getStatus(), response.getVersion());
    updateHttp1OnlyCache(http11Request, response);
    updateH2cCache(http11Request, response);
    updateAltSvcCache(http11Request, response.getHeaders());

    // Update result with response
    postContentResponse(sampler, http11Request, result, response,
        JettyCacheManager.fromCacheManager(sampler.getCacheManager()));

    return result;
  }

  /**
   * Sends a request using HTTP/1.1 only (fallback mode).
   * This is called when protocol_error is detected with HTTP/2.
   *
   * @param originalRequest  The original request that failed with protocol_error
   * @param originalListener The original listener (for compatibility)
   * @return ContentResponse from the HTTP/1.1 request
   */
  private ContentResponse sendWithHTTP11Only(Request originalRequest,
                                             HTTP2FutureResponseListener originalListener)
      throws InterruptedException, TimeoutException, ExecutionException {
    URI uri = originalRequest.getURI();
    lowLevelDebug("Retrying request with HTTP/1.1 only: method={}, URI={}",
        originalRequest.getMethod(), uri);

    try {
      Request http11Request = buildHttp11FallbackRequest(originalRequest);
      lowLevelDebug("Sending HTTP/1.1 fallback request");
      ContentResponse response = http11Request.send();
      lowLevelDebug("HTTP/1.1 fallback request succeeded: status={}, version={}",
          response.getStatus(), response.getVersion());
      return response;
    } catch (Exception e) {
      LOG.error("HTTP/1.1 fallback also failed for URI: {}", uri, e);
      throw new ExecutionException("HTTP/1.1 fallback failed", e);
    }
  }

  private Request buildHttp11FallbackRequest(Request originalRequest) {
    URI uri = originalRequest.getURI();
    clearContentDecoders(httpClientHttp1Only);
    Request http11Request = httpClientHttp1Only.newRequest(uri)
        .method(originalRequest.getMethod())
        .timeout(requestTimeout, TimeUnit.MILLISECONDS)
        .followRedirects(originalRequest.isFollowRedirects());
    JmeterRequestHeadersSupport.copySamplerHeaderState(originalRequest, http11Request);
    http11Request.attribute(ATTR_H2C_FALLBACK_ATTEMPTED, Boolean.TRUE);
    if (originalRequest.getHeaders() != null) {
      HttpFields originalHeaders = originalRequest.getHeaders();
      HttpFields requestHeaders = http11Request.getHeaders();
      if (requestHeaders instanceof HttpFields.Mutable) {
        HttpFields.Mutable newHeaders = (HttpFields.Mutable) requestHeaders;
        originalHeaders.forEach(field -> {
          // Skip HTTP/2 pseudo-headers and h2c upgrade headers - neither exists in HTTP/1.1,
          // and re-sending them would trigger another (futile) upgrade attempt on this retry.
          String name = field.getName();
          if (name.startsWith(":") || isH2cUpgradeHeader(name, field.getValue())) {
            return;
          }
          newHeaders.put(name, field.getValue());
        });
      }
    }
    ensureHostHeader(http11Request, uri);
    Object useKeepAlive =
        http11Request.getAttributes().get(JmeterRequestHeadersSupport.ATTR_USE_KEEPALIVE);
    if (useKeepAlive instanceof Boolean) {
      JmeterRequestHeadersSupport.prepareFromSampler(http11Request, (Boolean) useKeepAlive);
    }
    configureContentDecodersAndCapture(httpClientHttp1Only, http11Request);
    if (originalRequest.getBody() != null) {
      http11Request.body(originalRequest.getBody());
    }
    SslClientCertAliasSupport.copyFromRequest(originalRequest, http11Request);
    return http11Request;
  }

  private static boolean isH2cUpgradeHeader(String name, String value) {
    if (HttpHeader.UPGRADE.is(name) || HttpHeader.HTTP2_SETTINGS.is(name)) {
      return true;
    }
    if (HttpHeader.CONNECTION.is(name) && value != null) {
      String lower = value.toLowerCase(Locale.ROOT);
      return lower.contains("upgrade") || lower.contains("http2-settings");
    }
    return false;
  }

  /** True if {@code request} carried an {@code Upgrade: h2c} header or attribute. */
  private boolean wasH2cUpgradeAttempt(Request request) {
    if (request == null) {
      return false;
    }
    Object protocol = request.getAttributes().get(HttpUpgrader.PROTOCOL_ATTRIBUTE);
    if ("h2c".equals(protocol)) {
      return true;
    }
    HttpFields headers = request.getHeaders();
    if (headers == null) {
      return false;
    }
    String upgrade = headers.get(HttpHeader.UPGRADE);
    return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("h2c");
  }

  /**
   * The server answered (no timeout/error) but never actually negotiated HTTP/2 despite our
   * {@code Upgrade: h2c} attempt - e.g. it silently ignored the header, as a compliant HTTP/1.1
   * server that doesn't support h2c is allowed to do. Retry once with a plain HTTP/1.1 request.
   */
  private boolean shouldRetryAfterFailedH2cUpgrade(Request request, ContentResponse response) {
    if (!enableHttp1 || !http1UpgradeRequired || request == null || response == null) {
      return false;
    }
    URI uri = request.getURI();
    if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())) {
      return false;
    }
    if (Boolean.TRUE.equals(
        request.getAttributes().get(ATTR_H2C_FALLBACK_ATTEMPTED))) {
      return false;
    }
    if (!wasH2cUpgradeAttempt(request)) {
      return false;
    }
    return response.getVersion() != HttpVersion.HTTP_2;
  }

  private void markCleartextHttp1Only(URI uri) {
    if (!enableHttp1 || !http1OnlyCacheEnabled || http1OnlyCooldownMs <= 0 || uri == null) {
      return;
    }
    if (!"http".equalsIgnoreCase(uri.getScheme())) {
      return;
    }
    markHttp1OnlyOrigin(originKey(uri));
  }

  /**
   * Retries a request that timed out or failed while attempting an h2c upgrade, as plain
   * HTTP/1.1. Returns {@code null} (instead of throwing) when the failure isn't h2c-related, or
   * when a fallback was already attempted for this request, so callers can fall through to their
   * existing (non-h2c) handling.
   */
  private ContentResponse tryCleartextHttp11FallbackAfterH2cFailure(
      Request request, HTTP2FutureResponseListener listener)
      throws InterruptedException, TimeoutException, ExecutionException {
    if (!enableHttp1 || request == null) {
      return null;
    }
    URI uri = request.getURI();
    if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())
        || !wasH2cUpgradeAttempt(request)) {
      return null;
    }
    if (Boolean.TRUE.equals(
        request.getAttributes().get(ATTR_H2C_FALLBACK_ATTEMPTED))) {
      return null;
    }
    lowLevelDebug("Falling back to HTTP/1.1 after failed H2C upgrade for {}", uri);
    markCleartextHttp1Only(uri);
    return sendWithHTTP11Only(request, listener);
  }

  private ContentResponse sendWithH2cPriorKnowledge(Request originalRequest)
      throws InterruptedException, TimeoutException, ExecutionException {
    URI uri = originalRequest.getURI();
    lowLevelDebug("Retrying request with H2C prior knowledge: method={}, URI={}",
        originalRequest.getMethod(), uri);

    clearContentDecoders(httpClientH2cPrior);

    Request h2cRequest = httpClientH2cPrior.newRequest(uri)
        .method(originalRequest.getMethod())
        .followRedirects(originalRequest.isFollowRedirects());
    if (requestTimeout > 0) {
      h2cRequest.timeout(requestTimeout, TimeUnit.MILLISECONDS);
    }
    h2cRequest.version(HttpVersion.HTTP_2);

    if (originalRequest.getHeaders() != null) {
      HttpFields originalHeaders = originalRequest.getHeaders();
      HttpFields requestHeaders = h2cRequest.getHeaders();
      if (requestHeaders instanceof HttpFields.Mutable) {
        HttpFields.Mutable newHeaders = (HttpFields.Mutable) requestHeaders;
        originalHeaders.forEach(field -> {
          String name = field.getName();
          if (!name.startsWith(":")
              && !HttpHeader.UPGRADE.is(name)
              && !HttpHeader.CONNECTION.is(name)
              && !HttpHeader.HTTP2_SETTINGS.is(name)) {
            newHeaders.put(name, field.getValue());
          }
        });
      }
    }
    ensureHostHeader(h2cRequest, uri);
    configureContentDecodersAndCapture(httpClientH2cPrior, h2cRequest);
    if (originalRequest.getBody() != null) {
      h2cRequest.body(originalRequest.getBody());
    }

    ContentResponse response = h2cRequest.send();
    updateH2cCache(h2cRequest, response);
    return response;
  }

  public void stop() throws Exception {
    httpClient.stop();
    if (httpClientNoH3 != httpClient) {
      httpClientNoH3.stop();
    }
    httpClientHttp1Only.stop();
    httpClientH2cPrior.stop();
    httpClientH2cUpgrade.stop();
    clearBufferPool();
    if (heExecutorsRegistered) {
      int remaining = HAPPY_EYEBALLS_CLIENTS.decrementAndGet();
      if (remaining <= 0) {
        HAPPY_EYEBALLS_CLIENTS.set(0);
        shutdownHappyEyeballsExecutors();
      }
      heExecutorsRegistered = false;
    }
  }

  private void samplePrepareRequest(Request request,
                                    HTTP2Sampler sampler,
                                    HTTPSampleResult result,
                                    HttpClient client) throws IOException {

    URL url = result.getURL();
    lowLevelDebug("Preparing request: URL={}, method={}", url, result.getHTTPMethod());
    setTimeouts(sampler, request);
    request.followRedirects(sampler.getAutoRedirects());
    String method = result.getHTTPMethod();
    request.method(method);
    setHeaders(request, url, sampler.getHeaderManager());
    ensureHostHeader(request, url);
    addPreemptiveAuthorizationHeader(request, url, sampler.getAuthManager());
    lowLevelDebug("Headers set, request URI: {}", request.getURI());

    configureContentDecodersAndCapture(client, request);

    String ae = request.getHeaders() != null
        ? request.getHeaders().get(HttpHeader.ACCEPT_ENCODING)
        : null;
    debugToFile(String.format("prepareRequest: uri=%s accept-encoding=%s client=%s",
        request.getURI(), ae, client != null ? client.getName() : "null"));

    CookieManager cookieManager = sampler.getCookieManager();
    if (cookieManager != null) {
      result.setCookies(buildCookies(request, url, cookieManager));
    }

    if (!sampler.getProxyHost().isEmpty()) {
      setProxy(sampler.getProxyHost(), sampler.getProxyPortInt(), sampler.getProxyScheme());
      if (Boolean.TRUE.equals(request.getAttributes().get(ATTR_HTTP3_ATTEMPTED))) {
        LOG.warn("Proxy configured but HTTP/3 is attempted for {}. "
            + "Most proxies cannot capture HTTP/3/QUIC traffic; consider disabling HTTP/3 "
            + "or removing the proxy.", request.getURI());
      }
    }
    result.sampleStart();

    setBody(request, sampler, result);
    JmeterRequestHeadersSupport.prepareFromSampler(request, sampler.getUseKeepAlive());
    initializeSentBytes(result, request);

  }

  private boolean requestInCache(JettyCacheManager cacheManager,
                                 Request request)
      throws URISyntaxException, MalformedURLException {
    URL url = request.getURI().toURL();
    String method = request.getMethod();
    if (cacheManager != null) {
      cacheManager.setHeaders(url, request);
      if (HTTPConstants.GET.equalsIgnoreCase(method) && cacheManager.inCache(url,
          request.getHeaders())) {
        return true;
      }
    }
    return false;
  }

  private void postContentResponse(HTTP2Sampler sampler, Request request,
                                   HTTPSampleResult result,
                                   ContentResponse contentResponse,
                                   JettyCacheManager cacheManager)
      throws IOException {
    http1UpgradeRequired = contentResponse.getVersion() != HttpVersion.HTTP_2;
    result.setRequestHeaders(getSerializedRequestHeaders(request, true));
    setResultContentResponse(result, contentResponse);
    saveCookiesInCookieManager(contentResponse, request.getURI().toURL(),
        sampler.getCookieManager());

    if (cacheManager != null) {
      cacheManager.saveDetails(contentResponse, result);
    }
  }

  public Request sampleAsync(HTTP2Sampler sampler,
                             HTTPSampleResult result,
                             HTTP2FutureResponseListener listener)
      throws Exception {
    URL url = result.getURL();
    lowLevelDebug("Creating async HTTP request: method={}, URL={}", result.getHTTPMethod(), url);
    errorWhenNotSupportedMethod(result.getHTTPMethod());
    setAuthManager(sampler);
    RequestContext context = buildRequestContext(result, resolveClientForRequest(sampler, result));
    Request request = context.request;
    lowLevelDebug("Request built: URI={}, method={}", request.getURI(), request.getMethod());
    samplePrepareRequest(request, sampler, result, context.client);
    listener.setRequest(request);
    listener.setFallbackHttp1Client(httpClientHttp1Only);
    lowLevelDebug("Request prepared, ready to send");
    return request;

  }

  private void errorWhenNotSupportedMethod(String method) throws UnsupportedOperationException {
    if (!isSupportedMethod(method)) {
      LOG.error(String.format("Method %s is not supported",
          method));
      throw new UnsupportedOperationException(String.format("Method %s is not supported",
          method));
    }
  }

  public HTTPSampleResult sample(HTTP2Sampler sampler, HTTPSampleResult result,
                                 boolean areFollowingRedirect, int depth) throws Exception {
    lowLevelDebug("=== HTTP2JettyClient.sample() called ===");
    lowLevelDebug("Method: {}, URL: {}", result.getHTTPMethod(), result.getURL());

    errorWhenNotSupportedMethod(result.getHTTPMethod());
    setAuthManager(sampler);
    RequestContext context = buildRequestContext(result, resolveClientForRequest(sampler, result));
    Request request = context.request;

    samplePrepareRequest(request, sampler, result, context.client);

    JettyCacheManager cacheManager =
        JettyCacheManager.fromCacheManager(sampler.getCacheManager());
    if (requestInCache(cacheManager, request)) {
      return cacheManager.buildCachedSampleResult(result);
    }
    lowLevelDebug("=== Creating HTTP2FutureResponseListener ===");
    lowLevelDebug("maxBufferSize: {}", maxBufferSize);
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(maxBufferSize);
    lowLevelDebug("=== HTTP2FutureResponseListener created successfully ===");
    listener.setRequest(request);
    lowLevelDebug("=== About to call send() ===");
    ContentResponse contentResponse;
    try {
      contentResponse = send(request, listener);
    } catch (TimeoutException e) {
      if (fallbackEnabled && enableHttp1) {
        LOG.warn("Timeout during send(), retrying with HTTP/1.1 only");
        return retryWithHTTP11Only(sampler, result);
      }
      throw e;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (shouldFallbackToHttp11AfterTransportFailure(cause, e)) {
        LOG.warn("Transport failure during send(), retrying with HTTP/1.1 only");
        return retryWithHTTP11Only(sampler, result);
      }
      throw e;
    }
    lowLevelDebug("=== send() returned successfully ===");

    postContentResponse(sampler, request, result, contentResponse, cacheManager);
    result.setEndTime(listener.getResponseEnd());

    resetSamplerDataBeforeResultProcessing(result);
    return sampler.resultProcessing(areFollowingRedirect, depth, result);
  }

  public HTTPSampleResult sampleFromListener(HTTP2Sampler sampler, HTTPSampleResult result,
                                             boolean areFollowingRedirect, int depth,
                                             HTTP2FutureResponseListener listener
  ) throws Exception {
    lowLevelDebug("=== sampleFromListener() called ===");
    lowLevelDebug("URL: {}", result.getURL());

    Request request = listener.getRequest();
    try {
      ContentResponse contentResponse = getContent(listener, request);
      JettyCacheManager cacheManager =
          JettyCacheManager.fromCacheManager(sampler.getCacheManager());
      postContentResponse(sampler, request, result, contentResponse, cacheManager);
      result.setEndTime(listener.getResponseEnd());

      resetSamplerDataBeforeResultProcessing(result);
      return sampler.resultProcessing(areFollowingRedirect, depth, result);
    } catch (ExecutionException e) {
      LOG.error("=== ExecutionException caught in sampleFromListener() ===");
      LOG.error("Exception type: {}", e.getClass().getName());
      LOG.error("Exception message: {}", e.getMessage());
      Throwable cause = e.getCause();
      String causeInfo = cause != null
          ? cause.getClass().getName() + ": " + cause.getMessage()
          : "null";
      LOG.error("Cause: {}", causeInfo);

      // Check if this is a protocol_error and attempt HTTP/1.1 fallback
      RetryableRequestException retryable =
          findRetryableRequestException(cause != null ? cause : e);
      if (retryable != null && goawayRetryEnabled && maxGoawayRetries > 0) {
        LOG.warn("RetryableRequestException detected (likely GOAWAY) in sampleFromListener()");
        LOG.warn("Error: {}", retryable.getMessage());
        try {
          ContentResponse retryResponse = retryAfterGoAway(request);
          lowLevelDebug("Retry after GOAWAY succeeded: status={}, version={}",
              retryResponse.getStatus(), retryResponse.getVersion());
          JettyCacheManager cacheManager =
              JettyCacheManager.fromCacheManager(sampler.getCacheManager());
          postContentResponse(sampler, request, result, retryResponse, cacheManager);
          result.setEndTime(listener.getResponseEnd());
          resetSamplerDataBeforeResultProcessing(result);
          return sampler.resultProcessing(areFollowingRedirect, depth, result);
        } catch (Exception retryException) {
          LOG.error("Retry after GOAWAY failed", retryException);
          if (enableHttp1) {
            try {
              lowLevelDebug("Retrying request with HTTP/1.1 only after GOAWAY: {}",
                  result.getURL());
              HTTPSampleResult fallbackResult = retryWithHTTP11Only(sampler, result);
              if (fallbackResult != null && fallbackResult.isSuccessful()) {
                lowLevelDebug("HTTP/1.1 fallback succeeded: status={}",
                    fallbackResult.getResponseCode());
                return fallbackResult;
              }
              LOG.warn("HTTP/1.1 fallback returned unsuccessful result");
            } catch (Exception fallbackException) {
              LOG.error("Failed to attempt HTTP/1.1 fallback after GOAWAY", fallbackException);
            }
          }
        }
      }
      boolean isProtocolErrorCause = cause != null && ProtocolErrorException.isProtocolError(cause);
      boolean isProtocolErrorException = ProtocolErrorException.isProtocolError(e);
      LOG.error("isProtocolError(cause): {}", isProtocolErrorCause);
      LOG.error("isProtocolError(exception): {}", isProtocolErrorException);

      if ((isProtocolErrorCause || isProtocolErrorException) && protocolErrorFallbackEnabled
          && !HpackFailureDetector.indicatesHpackFailure(e)
          && !HpackFailureDetector.indicatesHpackFailure(cause)) {
        LOG.warn("HTTP/2 protocol_error detected in sampleFromListener()! "
            + "Attempting fallback to HTTP/1.1");
        LOG.warn("Error: {}", cause != null ? cause.getMessage() : e.getMessage());
        if (enableHttp1) {
          try {
            // Retry with HTTP/1.1 only
            lowLevelDebug("Retrying request with HTTP/1.1 only: {}", result.getURL());
            HTTPSampleResult fallbackResult = retryWithHTTP11Only(sampler, result);

            if (fallbackResult != null && fallbackResult.isSuccessful()) {
              lowLevelDebug("HTTP/1.1 fallback succeeded: status={}",
                  fallbackResult.getResponseCode());
              return fallbackResult;
            } else {
              LOG.warn("HTTP/1.1 fallback returned unsuccessful result");
            }
          } catch (Exception fallbackException) {
            LOG.error("Failed to attempt HTTP/1.1 fallback", fallbackException);
          }
        } else {
          LOG.warn("HTTP/1.1 fallback disabled by configuration");
        }
      }

      // Re-throw if fallback didn't work
      throw e;
    }
  }

  public ContentResponse send(Request request, HTTP2FutureResponseListener listener)
      throws InterruptedException,
      TimeoutException, ExecutionException {
    lowLevelDebug("=== send() called ===");
    lowLevelDebug("Request URI: {}", request.getURI());
    lowLevelDebug("Listener: {}", listener != null ? listener.getClass().getName() : "null");

    URI uri = request.getURI();
    lowLevelDebug("Sending request: method={}, URI={}", request.getMethod(), uri);
    if (request.getHeaders() != null) {
      HttpFields hm = request.getHeaders();
      String ae = hm.get("Accept-Encoding");
      lowLevelDebug("Request headers: Accept-Encoding={}, total headers={}", ae, hm.size());
    }
    lowLevelDebug("Sending request via HttpClient (ALPN negotiation will occur "
        + "during TLS handshake)");
    // Diagnostic toggle: bypass listener flow, call request.send() directly.
    if (Boolean.getBoolean("blazemeter.http.directSend")) {
      lowLevelDebug("HTTP2Client: using direct request.send() for diagnostics");
      return request.send();
    }
    if (shouldUseHappyEyeballs(request)) {
      return sendWithHappyEyeballs(request, listener);
    }
    request.send(listener);
    lowLevelDebug("Request sent, waiting for response...");
    try {
      return getContent(listener, request);
    } catch (TimeoutException e) {
      if (http1UpgradeRequired && "http".equalsIgnoreCase(uri.getScheme())) {
        try {
          ContentResponse fallback = tryCleartextHttp11FallbackAfterH2cFailure(request, listener);
          if (fallback != null) {
            return fallback;
          }
        } catch (Exception fallbackException) {
          LOG.error("HTTP/1.1 fallback after H2C timeout failed", fallbackException);
        }
        try {
          LOG.warn("H2C upgrade timed out; retrying with prior knowledge");
          return sendWithH2cPriorKnowledge(request);
        } catch (Exception retryException) {
          LOG.error("H2C prior knowledge retry failed", retryException);
        }
      }
      throw e;
    } catch (ExecutionException e) {
      // Check if the cause is a ProtocolErrorException
      Throwable cause = e.getCause();
      RetryableRequestException retryable =
          findRetryableRequestException(cause != null ? cause : e);
      if (retryable != null && goawayRetryEnabled && maxGoawayRetries > 0) {
        LOG.warn("RetryableRequestException detected (likely GOAWAY): {}",
            retryable.getMessage());
        try {
          ContentResponse retryResponse = retryAfterGoAway(request);
          lowLevelDebug("Retry after GOAWAY succeeded: status={}, version={}",
              retryResponse.getStatus(), retryResponse.getVersion());
          return retryResponse;
        } catch (Exception retryException) {
          LOG.error("Retry after GOAWAY failed", retryException);
          if (enableHttp1) {
            try {
              lowLevelDebug("Falling back to HTTP/1.1 after GOAWAY retry failure");
              return sendWithHTTP11Only(request, listener);
            } catch (Exception fallbackException) {
              LOG.error("HTTP/1.1 fallback after GOAWAY retry failed", fallbackException);
            }
          }
          throw e;
        }
      }
      if (shouldFallbackToHttp11AfterTransportFailure(cause, e)) {
        LOG.warn("Transport failure detected in send()! Attempting fallback to HTTP/1.1");
        LOG.warn("Error details: message='{}', exception={}",
            cause != null ? cause.getMessage() : e.getMessage(),
            cause != null ? cause.getClass().getName() : "unknown");
        if (enableHttp1) {
          try {
            lowLevelDebug("Falling back to HTTP/1.1 for URI: {}", request.getURI());
            ContentResponse fallbackResponse = sendWithHTTP11Only(request, listener);
            lowLevelDebug("HTTP/1.1 fallback succeeded: status={}, version={}",
                fallbackResponse.getStatus(), fallbackResponse.getVersion());
            return fallbackResponse;
          } catch (Exception fallbackException) {
            LOG.error("HTTP/1.1 fallback also failed", fallbackException);
            // Re-throw the original protocol_error
            throw e;
          }
        } else {
          LOG.warn("HTTP/1.1 fallback disabled by configuration");
        }
      }
      // If not a protocol_error, re-throw as-is
      throw e;
    }
  }

  private boolean shouldUseHappyEyeballs(Request request) {
    if (request == null || happyEyeballsDelayMs <= 0 || FORCE_HTTP2_ONLY) {
      return false;
    }
    if (!fallbackEnabled || !enableHttp3 || !enableHttp2) {
      return false;
    }
    Object attempted = request.getAttributes().get(ATTR_HTTP3_ATTEMPTED);
    return Boolean.TRUE.equals(attempted);
  }

  private ContentResponse sendWithHappyEyeballs(Request h3Request,
                                                HTTP2FutureResponseListener h3Listener)
      throws InterruptedException, TimeoutException, ExecutionException {
    URI uri = h3Request.getURI();
    ensureHappyEyeballsExecutors();
    long effectiveDelayMs = computeHappyEyeballsDelayMs(uri);
    lowLevelDebug("Happy Eyeballs enabled for HTTP/3: origin={}, delayMs={}",
        originKey(uri), effectiveDelayMs);
    int timeoutMs = requestTimeout > 0 ? requestTimeout + 2000 : 0;
    AtomicReference<ContentResponse> winner = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicInteger failures = new AtomicInteger(0);
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean resolved =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicBoolean h2Started =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    HTTP2FutureResponseListener h2Listener = new HTTP2FutureResponseListener(maxBufferSize);
    Request h2Request = cloneRequest(h3Request, httpClientNoH3);
    h2Listener.setRequest(h2Request);

    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>
        h2StartFuture = new java.util.concurrent.atomic.AtomicReference<>();

    Runnable cancelScheduledStart = () -> {
      java.util.concurrent.ScheduledFuture<?> future = h2StartFuture.get();
      if (future != null) {
        future.cancel(false);
      }
    };

    Runnable completeTimeoutCleanup = () -> {
      cancelScheduledStart.run();
      h3Request.abort(new java.util.concurrent.CancellationException(
          "Happy Eyeballs timeout"));
      h2Request.abort(new java.util.concurrent.CancellationException(
          "Happy Eyeballs timeout"));
    };

    java.util.function.BiConsumer<ContentResponse, Boolean> completeSuccess =
        (response, h3Won) -> {
          if (!resolved.compareAndSet(false, true)) {
            return;
          }
          winner.set(response);
          cancelScheduledStart.run();
          if (h3Won) {
            h2Request.abort(new java.util.concurrent.CancellationException(
                "Happy Eyeballs H3 won"));
          } else {
            h3Listener.completeWith(response,
                h2Listener.getResponseStart(), h2Listener.getResponseEnd());
            h3Request.abort(new java.util.concurrent.CancellationException(
                "Happy Eyeballs H2 won"));
          }
          done.countDown();
        };

    java.util.function.Consumer<Throwable> completeFailure = (t) -> {
      if (failures.incrementAndGet() >= 2 && resolved.compareAndSet(false, true)) {
        failure.set(t);
        cancelScheduledStart.run();
        h3Request.abort(new java.util.concurrent.CancellationException(
            "Happy Eyeballs failed"));
        h2Request.abort(new java.util.concurrent.CancellationException(
            "Happy Eyeballs failed"));
        done.countDown();
      }
    };

    java.util.function.Consumer<String> startH2 = (reason) -> {
      if (resolved.get() || !h2Started.compareAndSet(false, true)) {
        return;
      }
      lowLevelDebug("Happy Eyeballs starting HTTP/2 ({}): origin={}",
          reason, originKey(uri));
      try {
        h2Request.send(h2Listener);
      } catch (Throwable sendFailure) {
        completeFailure.accept(sendFailure);
        return;
      }
      happyEyeballsExecutor.execute(() -> {
        try {
          ContentResponse response = getContent(h2Listener, h2Request);
          completeSuccess.accept(response, false);
        } catch (Throwable t) {
          completeFailure.accept(t);
        }
      });
    };

    boolean h3Sent = false;
    try {
      h3Request.send(h3Listener);
      h3Sent = true;
    } catch (Throwable sendFailure) {
      startH2.accept("h3-send-failed");
      completeFailure.accept(sendFailure);
    }
    if (h3Sent) {
      happyEyeballsExecutor.execute(() -> {
        try {
          ContentResponse response = getContent(h3Listener, h3Request);
          completeSuccess.accept(response, true);
        } catch (Throwable t) {
          startH2.accept("h3-failed-early");
          completeFailure.accept(t);
        }
      });
    }

    if (effectiveDelayMs <= 0) {
      startH2.accept("delay 0ms");
    } else {
      h2StartFuture.set(happyEyeballsScheduler.schedule(
          () -> startH2.accept("delay " + effectiveDelayMs + "ms"),
          effectiveDelayMs, TimeUnit.MILLISECONDS));
    }

    boolean completed;
    if (timeoutMs > 0) {
      completed = done.await(timeoutMs, TimeUnit.MILLISECONDS);
      if (!completed) {
        if (resolved.compareAndSet(false, true)) {
          completeTimeoutCleanup.run();
        }
        throw new TimeoutException();
      }
    } else {
      done.await();
    }
    ContentResponse response = winner.get();
    if (response != null) {
      return response;
    }
    Throwable t = failure.get();
    if (t instanceof ExecutionException) {
      throw (ExecutionException) t;
    }
    if (t instanceof TimeoutException) {
      throw (TimeoutException) t;
    }
    throw new ExecutionException(t);
  }

  public ContentResponse getContent(HTTP2FutureResponseListener listener)
      throws InterruptedException, TimeoutException, ExecutionException {
    return getContent(listener, null);
  }

  private ContentResponse getContent(HTTP2FutureResponseListener listener, Request originalRequest)
      throws InterruptedException, TimeoutException, ExecutionException {
    long getStart = System.currentTimeMillis();
    int timeoutMs = requestTimeout > 0 ? requestTimeout + 2000 : 0;
    lowLevelDebug("Waiting for response with timeout={}ms", timeoutMs);

    lowLevelDebug("=== getContent() called ===");
    String originalRequestUri = originalRequest != null
        ? originalRequest.getURI().toString()
        : "null";
    lowLevelDebug("originalRequest: {}", originalRequestUri);

    try {
      ContentResponse response;
      lowLevelDebug("=== Calling listener.get() ===");
      if (requestTimeout > 0) {
        int extraTime = 2000;
        response = listener.get(requestTimeout + extraTime, TimeUnit.MILLISECONDS);
      } else {
        response = listener.get();
      }
      lowLevelDebug("=== listener.get() returned successfully ===");
      long elapsed = System.currentTimeMillis() - getStart;
      if (response != null) {
        int contentLength = response.getContent() != null ? response.getContent().length : 0;
        lowLevelDebug("Response received: status={}, version={}, elapsed={}ms, contentLength={}",
            response.getStatus(), response.getVersion(), elapsed, contentLength);
        int headerCount = response.getHeaders() != null ? response.getHeaders().size() : 0;
        lowLevelDebug("Response headers: {}", headerCount);
        if (originalRequest != null
            && shouldRetryAfterFailedH2cUpgrade(originalRequest, response)) {
          lowLevelDebug("H2C upgrade did not negotiate HTTP/2; retrying with HTTP/1.1 for {}",
              originalRequest.getURI());
          markCleartextHttp1Only(originalRequest.getURI());
          ContentResponse fallbackResponse = sendWithHTTP11Only(originalRequest, listener);
          updateHttp1OnlyCache(originalRequest, fallbackResponse);
          updateH2cCache(originalRequest, fallbackResponse);
          updateAltSvcCache(originalRequest, fallbackResponse.getHeaders());
          return fallbackResponse;
        }
        if (originalRequest != null && response.getVersion() == HttpVersion.HTTP_3) {
          recordHttp3Success(originalRequest.getURI());
        }
        updateHttp1OnlyCache(originalRequest, response);
        updateH2cCache(originalRequest, response);
        updateAltSvcCache(originalRequest, response.getHeaders());
      } else {
        LOG.warn("Response is null after {}ms", elapsed);
      }
      return response;
    } catch (TimeoutException e) {
      long endGet = System.currentTimeMillis();
      long elapsed = endGet - getStart;
      LOG.error("Request timeout after {}ms: {}", elapsed, e.getMessage());
      if (originalRequest != null) {
        try {
          ContentResponse fallback =
              tryCleartextHttp11FallbackAfterH2cFailure(originalRequest, listener);
          if (fallback != null) {
            updateHttp1OnlyCache(originalRequest, fallback);
            updateH2cCache(originalRequest, fallback);
            updateAltSvcCache(originalRequest, fallback.getHeaders());
            return fallback;
          }
        } catch (Exception fallbackException) {
          LOG.error("HTTP/1.1 fallback after H2C timeout in getContent() failed",
              fallbackException);
        }
      }
      throw new TimeoutException("The request took more than " + elapsed
          + " milliseconds to complete");
    } catch (ExecutionException e) {
      long elapsed = System.currentTimeMillis() - getStart;
      Throwable cause = e.getCause();
      LOG.error("Request failed after {}ms with ExecutionException", elapsed, e);

      // Check if the cause is a ProtocolErrorException
      RetryableRequestException retryable =
          findRetryableRequestException(cause != null ? cause : e);
      if (retryable != null && goawayRetryEnabled && maxGoawayRetries > 0
          && originalRequest != null) {
        LOG.warn("RetryableRequestException detected (likely GOAWAY): {}",
            retryable.getMessage());
        try {
          ContentResponse retryResponse = retryAfterGoAway(originalRequest);
          lowLevelDebug("Retry after GOAWAY succeeded: status={}, version={}",
              retryResponse.getStatus(), retryResponse.getVersion());
          return retryResponse;
        } catch (Exception retryException) {
          LOG.error("Retry after GOAWAY failed", retryException);
          if (enableHttp1) {
            try {
              lowLevelDebug("Falling back to HTTP/1.1 after GOAWAY retry failure");
              return sendWithHTTP11Only(originalRequest, listener);
            } catch (Exception fallbackException) {
              LOG.error("HTTP/1.1 fallback after GOAWAY retry failed", fallbackException);
            }
          }
        }
      }
      if (shouldFallbackToHttp11AfterTransportFailure(cause, e)) {
        LOG.warn("Transport failure detected in getContent()! "
            + "Attempting fallback to HTTP/1.1");
        LOG.warn("Error details: message='{}', exception={}",
            cause != null ? cause.getMessage() : e.getMessage(),
            cause != null ? cause.getClass().getName() : "unknown");
        // If we have the original request, try fallback to HTTP/1.1
        if (originalRequest != null && enableHttp1) {
          lowLevelDebug("Original request available, attempting HTTP/1.1 fallback for URI: {}",
              originalRequest.getURI());
          try {
            lowLevelDebug("Falling back to HTTP/1.1 for URI: {}", originalRequest.getURI());
            ContentResponse fallbackResponse = sendWithHTTP11Only(originalRequest, listener);
            lowLevelDebug("HTTP/1.1 fallback succeeded: status={}, version={}",
                fallbackResponse.getStatus(), fallbackResponse.getVersion());
            return fallbackResponse;
          } catch (Exception fallbackException) {
            LOG.error("HTTP/1.1 fallback also failed", fallbackException);
            // Re-throw the original protocol_error, not the fallback exception
            throw e;
          }
        } else if (!enableHttp1) {
          LOG.warn("HTTP/1.1 fallback disabled by configuration");
        } else {
          LOG.error("Cannot fallback to HTTP/1.1: original request not available "
              + "(originalRequest is null)");
        }
      } else {
        lowLevelDebug("ExecutionException is not a protocol_error, no fallback. Cause: {}",
            cause != null ? cause.getClass().getName() : "null");
      }
      if (fallbackEnabled && enableHttp2 && isHttp3ConnectTimeout(cause)
          && originalRequest != null
          && Boolean.TRUE.equals(originalRequest.getAttributes().get(ATTR_HTTP3_ATTEMPTED))) {
        LOG.warn("HTTP/3 connect timeout detected; marking origin as broken and retrying "
            + "without HTTP/3");
        markHttp3Broken(originalRequest.getURI());
        try {
          ContentResponse fallbackResponse = sendWithHttp2Only(originalRequest);
          lowLevelDebug("HTTP/2 fallback succeeded: status={}, version={}",
              fallbackResponse.getStatus(), fallbackResponse.getVersion());
          return fallbackResponse;
        } catch (Exception fallbackException) {
          LOG.error("HTTP/2 fallback after HTTP/3 timeout failed", fallbackException);
        }
      }
      if (e.getCause() != null && e.getCause() instanceof TimeoutException) {
        throw (TimeoutException) e.getCause();
      } else if (e.getCause() != null && e.getCause() instanceof IllegalArgumentException) {
        throw (IllegalArgumentException) e.getCause();
      }
      throw e;
    }
  }

  private void clearContentDecoders(HttpClient client) {
    ContentDecoder.Factories factories = client.getContentDecoderFactories();
    if (factories != null) {
      factories.clear();
    }
  }

  private void ensureDecoderFactoriesInitialized() {
    if (decoderFactoriesInitialized) {
      return;
    }
    decoderFactoriesInitialized = true;

    boolean disableBrotliDecoder = Boolean.parseBoolean(
        System.getProperty("blazemeter.http.disableBrotliDecoder", "false"));
    if (disableBrotliDecoder) {
      lowLevelDebug("Brotli decoder disabled by blazemeter.http.disableBrotliDecoder");
    } else {
      try {
        BrotliCompression brotli = new BrotliCompression();
        brotli.setByteBufferPool(bufferPool);
        brotliDecoderFactory = new CompressionContentDecoderFactory(brotli);
        lowLevelDebug("Initialized Brotli content decoder factory");
      } catch (Throwable t) {
        LOG.warn("Brotli decoder not available; skipping factory initialization", t);
      }
    }

    boolean disableZstdDecoder = Boolean.parseBoolean(
        System.getProperty("blazemeter.http.disableZstdDecoder", "false"));
    if (disableZstdDecoder) {
      lowLevelDebug("Zstd decoder disabled by blazemeter.http.disableZstdDecoder");
    } else {
      try {
        ZstandardCompression zstd = new ZstandardCompression();
        zstd.setByteBufferPool(bufferPool);
        zstdDecoderFactory = new CompressionContentDecoderFactory(zstd);
        lowLevelDebug("Initialized Zstandard content decoder factory");
      } catch (Throwable t) {
        LOG.warn("Zstandard decoder not available; skipping factory initialization", t);
      }
    }

    boolean disableGzipDecoder = Boolean.parseBoolean(
        System.getProperty("blazemeter.http.disableGzipDecoder", "false"));
    if (disableGzipDecoder) {
      lowLevelDebug("Gzip decoder disabled by blazemeter.http.disableGzipDecoder");
    } else {
      try {
        GzipCompression gzip = new GzipCompression();
        gzip.setByteBufferPool(bufferPool);
        // Ensure inflater pool is initialized; missing pool can trigger NPE and stream cancel.
        InflaterPool inflaterPool = new InflaterPool(1024, true);
        try {
          if (inflaterPool instanceof LifeCycle) {
            ((LifeCycle) inflaterPool).start();
          }
        } catch (Exception e) {
          lowLevelDebug("Failed to start InflaterPool", e);
        }
        gzip.setInflaterPool(inflaterPool);
        gzipDecoderFactory = new CompressionContentDecoderFactory(gzip);
        lowLevelDebug("Initialized Gzip content decoder factory");
      } catch (Throwable t) {
        LOG.warn("Gzip decoder not available; skipping factory initialization", t);
      }
    }

    boolean disableDeflateDecoder = Boolean.parseBoolean(
        System.getProperty("blazemeter.http.disableDeflateDecoder", "false"));
    if (disableDeflateDecoder) {
      lowLevelDebug("Deflate decoder disabled by blazemeter.http.disableDeflateDecoder");
    } else {
      try {
        deflateDecoderFactory = new DeflateContentDecoderFactory(bufferPool);
        lowLevelDebug("Initialized Deflate content decoder factory");
      } catch (Throwable t) {
        LOG.warn("Deflate decoder not available; skipping factory initialization", t);
      }
    }
  }

  private void configureContentDecoders(HttpClient client, Request request) {
    if (request == null || client == null) {
      return;
    }
    ContentDecoder.Factories factories = client != null ? client.getContentDecoderFactories()
        : null;
    if (factories == null) {
      return;
    }

    String acceptEncoding = null;
    HttpFields headers = request.getHeaders();
    if (headers != null) {
      acceptEncoding = headers.get(HttpHeader.ACCEPT_ENCODING);
      if (acceptEncoding == null) {
        acceptEncoding = headers.get("Accept-Encoding");
      }
      if (acceptEncoding == null) {
        acceptEncoding = headers.get("accept-encoding");
      }
    }
    if (acceptEncoding == null || acceptEncoding.trim().isEmpty()) {
      return;
    }

    if (acceptEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
      lowLevelDebug("Configuring decoders for Accept-Encoding: {}", acceptEncoding);
    }

    boolean addBrotli = false;
    boolean addZstd = false;
    boolean addGzip = false;
    boolean addDeflate = false;
    for (String token : acceptEncoding.split(",")) {
      String encoding = token.trim().toLowerCase(Locale.ROOT);
      int paramsIndex = encoding.indexOf(';');
      if (paramsIndex >= 0) {
        encoding = encoding.substring(0, paramsIndex).trim();
      }
      switch (encoding) {
        case "br":
          addBrotli = true;
          break;
        case "zstd":
          addZstd = true;
          break;
        case "gzip":
        case "x-gzip":
          addGzip = true;
          break;
        case "deflate":
          addDeflate = true;
          break;
        default:
          break;
      }
    }

    ensureDecoderFactoriesInitialized();
    // Diagnostic toggles: disable specific decoder registrations.
    boolean disableBrotliDecoder = Boolean.parseBoolean(
        System.getProperty("blazemeter.http.disableBrotliDecoder", "false"));
    boolean disableZstdDecoder = Boolean.parseBoolean(
        System.getProperty("blazemeter.http.disableZstdDecoder", "false"));
    boolean disableGzipDecoder = Boolean.parseBoolean(
        System.getProperty("blazemeter.http.disableGzipDecoder", "false"));
    boolean disableDeflateDecoder = Boolean.parseBoolean(
        System.getProperty("blazemeter.http.disableDeflateDecoder", "false"));

    if (addBrotli && brotliDecoderFactory != null && !disableBrotliDecoder) {
      factories.put(brotliDecoderFactory);
    } else if (addBrotli && disableBrotliDecoder) {
      lowLevelDebug("Brotli decoder disabled by blazemeter.http.disableBrotliDecoder");
    }
    if (addZstd && zstdDecoderFactory != null && !disableZstdDecoder) {
      factories.put(zstdDecoderFactory);
    } else if (addZstd && disableZstdDecoder) {
      lowLevelDebug("Zstd decoder disabled by blazemeter.http.disableZstdDecoder");
    }
    if (addGzip && gzipDecoderFactory != null && !disableGzipDecoder) {
      factories.put(gzipDecoderFactory);
    } else if (addGzip && disableGzipDecoder) {
      lowLevelDebug("Gzip decoder disabled by blazemeter.http.disableGzipDecoder");
    }
    // Not registering deflateDecoderFactory here on purpose. It decodes per-chunk as bytes
    // arrive off the wire, so if the first bytes don't inflate as zlib-wrapped (RFC 1950) there's
    // no clean way to rewind and retry as raw/headerless deflate (RFC 1951) - some servers send
    // "Content-Encoding: deflate" as raw deflate despite the RFC implying zlib. getDecodedContent()
    // below instead decodes the fully buffered response body, where retrying with a fresh
    // Inflater is trivial, so all deflate decoding is funneled through decodeDeflate() there to
    // match HttpClient4 (which also tries zlib first, then raw).
    if (addDeflate && disableDeflateDecoder) {
      lowLevelDebug("Deflate decoder disabled by blazemeter.http.disableDeflateDecoder");
    }

    if (acceptEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
      StringBuilder encodings = new StringBuilder();
      for (ContentDecoder.Factory factory : factories) {
        if (factory == null) {
          continue;
        }
        if (encodings.length() > 0) {
          encodings.append(", ");
        }
        encodings.append(factory.getEncoding());
      }
      lowLevelDebug("Decoder factories registered: {}", encodings);
    }
  }

  private void configureContentDecodersAndCapture(HttpClient client, Request request) {
    configureContentDecoders(client, request);
    JmeterCompressionHeadersSupport.installCapture(request);
  }

  private HttpClient selectHttpClient(URI uri) {
    if (uri != null && "http".equalsIgnoreCase(uri.getScheme())) {
      if (enableHttp1 && isHttp1Only(uri)) {
        lowLevelDebug("HTTP/1.1-only cache hit for cleartext origin {}", originKey(uri));
        return httpClientHttp1Only;
      }
      if (!enableHttp2 && enableHttp1) {
        return httpClientHttp1Only;
      }
      if (!enableHttp1 && enableHttp2) {
        lowLevelDebug("HTTP/1.1 disabled; using H2C prior knowledge for origin {}",
            originKey(uri));
        return httpClientH2cPrior;
      }
      if (http1UpgradeRequired) {
        if (!enableHttp2) {
          LOG.warn("H2C upgrade requested but HTTP/2 is disabled; using HTTP/1.1");
          return httpClientHttp1Only;
        }
        if (shouldUseH2cPriorKnowledge(uri)) {
          lowLevelDebug("H2C prior knowledge enabled for origin {}", originKey(uri));
          return httpClientH2cPrior;
        }
        lowLevelDebug("H2C upgrade enabled for origin {}", originKey(uri));
        return httpClientH2cUpgrade;
      }
      if (shouldUseH2cPriorKnowledge(uri)) {
        lowLevelDebug("H2C prior knowledge enabled for origin {}", originKey(uri));
        return httpClientH2cPrior;
      }
      if (!enableHttp2 && enableHttp1) {
        return httpClientHttp1Only;
      }
      return httpClientNoH3;
    }
    if (FORCE_HTTP2_ONLY) {
      if (enableHttp1 && isHttp1Only(uri)) {
        lowLevelDebug("HTTP/1.1-only cache hit for origin {}", originKey(uri));
        return httpClientHttp1Only;
      }
      return httpClientNoH3;
    }
    boolean attemptHttp3 = shouldAttemptHttp3(uri);
    if (attemptHttp3) {
      lowLevelDebug("HTTP/3 enabled for origin {}", originKey(uri));
    } else {
      lowLevelDebug("HTTP/3 not enabled for origin {}", originKey(uri));
    }
    if (attemptHttp3) {
      return httpClient;
    }
    if (!enableHttp2 && enableHttp1) {
      return httpClientHttp1Only;
    }
    if (enableHttp1 && isHttp1Only(uri)) {
      lowLevelDebug("HTTP/1.1-only cache hit for origin {}", originKey(uri));
      return httpClientHttp1Only;
    }
    return httpClientNoH3;
  }

  private boolean shouldAttemptHttp3(URI uri) {
    if (!enableHttp3) {
      return false;
    }
    if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
      return false;
    }
    if (http3PriorKnowledgeEnabled) {
      return true;
    }
    if (!altSvcCacheEnabled) {
      return false;
    }
    AltSvcEntry entry = ALT_SVC_CACHE.get(originKey(uri));
    if (entry == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    if (entry.expiresAt <= now) {
      ALT_SVC_CACHE.remove(originKey(uri));
      return false;
    }
    return entry.h3 && now >= entry.brokenUntil;
  }

  private boolean isHttp1Only(URI uri) {
    if (!http1OnlyCacheEnabled) {
      return false;
    }
    Http1OnlyEntry entry = HTTP1_ONLY_CACHE.get(originKey(uri));
    if (entry == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    if (entry.expiresAt <= now) {
      HTTP1_ONLY_CACHE.remove(originKey(uri));
      return false;
    }
    return true;
  }

  private boolean shouldUseH2cPriorKnowledge(URI uri) {
    if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())) {
      return false;
    }
    if (!enableHttp2) {
      return false;
    }
    return http2PriorKnowledgeEnabled || (h2cCacheEnabled && isH2cCached(uri));
  }

  private boolean isH2cCached(URI uri) {
    if (!h2cCacheEnabled) {
      return false;
    }
    H2cEntry entry = H2C_CACHE.get(originKey(uri));
    if (entry == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    if (entry.expiresAt <= now) {
      H2C_CACHE.remove(originKey(uri));
      return false;
    }
    return true;
  }

  private void updateAltSvcCache(Request request, HttpFields headers) {
    if (!altSvcCacheEnabled || !enableHttp3) {
      return;
    }
    if (request == null || headers == null) {
      return;
    }
    String origin = originKey(request.getURI());
    StringBuilder combined = new StringBuilder();
    for (HttpField field : headers) {
      if (field.getName() != null
          && ALT_SVC_HEADER.equalsIgnoreCase(field.getName())) {
        if (combined.length() > 0) {
          combined.append(",");
        }
        combined.append(field.getValue());
      }
    }
    if (combined.length() == 0) {
      return;
    }
    String value = combined.toString().trim();
    if ("clear".equalsIgnoreCase(value)) {
      ALT_SVC_CACHE.remove(origin);
      lowLevelDebug("Alt-Svc cleared for origin {}", origin);
      return;
    }
    AltSvcEntry entry = parseAltSvc(value);
    if (entry == null) {
      return;
    }
    ALT_SVC_CACHE.put(origin, entry);
    lowLevelDebug("Alt-Svc cached for origin {} (h3={}, expiresAt={})",
        origin, entry.h3, entry.expiresAt);
  }

  private long computeHappyEyeballsDelayMs(URI uri) {
    long baseDelay = happyEyeballsDelayMs;
    if (baseDelay <= 0) {
      return 0L;
    }
    if (http3PriorKnowledgeEnabled) {
      return baseDelay;
    }
    if (!altSvcCacheEnabled || uri == null) {
      return baseDelay;
    }
    AltSvcEntry entry = ALT_SVC_CACHE.get(originKey(uri));
    if (entry == null) {
      return baseDelay;
    }
    long now = System.currentTimeMillis();
    if (entry.brokenUntil > now) {
      return 0L;
    }
    if (entry.lastH3SuccessAt > 0
        && now - entry.lastH3SuccessAt <= H3_RECENT_SUCCESS_WINDOW_MS) {
      return baseDelay;
    }
    return Math.max(0L, baseDelay / 2);
  }

  private AltSvcEntry parseAltSvc(String value) {
    String[] parts = value.split(",");
    boolean h3 = false;
    long maxAgeSeconds = ALT_SVC_DEFAULT_MAX_AGE_SECONDS;
    for (String part : parts) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if ("clear".equalsIgnoreCase(trimmed)) {
        return null;
      }
      String[] attrs = trimmed.split(";");
      String protoPart = attrs[0].trim();
      int eq = protoPart.indexOf('=');
      String proto = eq >= 0 ? protoPart.substring(0, eq).trim() : protoPart;
      if (proto.toLowerCase(Locale.ROOT).startsWith("h3")) {
        h3 = true;
      }
      for (int i = 1; i < attrs.length; i++) {
        String attr = attrs[i].trim();
        if (attr.startsWith("ma=")) {
          try {
            maxAgeSeconds = Long.parseLong(attr.substring(3).replace("\"", ""));
          } catch (NumberFormatException ignored) {
            // keep default
          }
        }
      }
    }
    if (!h3 || maxAgeSeconds <= 0) {
      return null;
    }
    AltSvcEntry entry = new AltSvcEntry();
    entry.h3 = true;
    entry.expiresAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(maxAgeSeconds);
    entry.brokenUntil = 0L;
    entry.lastH3SuccessAt = 0L;
    return entry;
  }

  private void recordHttp3Success(URI uri) {
    if (!enableHttp3 || !altSvcCacheEnabled || uri == null) {
      return;
    }
    String origin = originKey(uri);
    AltSvcEntry entry = ALT_SVC_CACHE.get(origin);
    if (entry == null) {
      return;
    }
    entry.lastH3SuccessAt = System.currentTimeMillis();
    entry.brokenUntil = 0L;
    ALT_SVC_CACHE.put(origin, entry);
  }

  private void updateHttp1OnlyCache(Request request, Response response) {
    if (!enableHttp1 || !http1OnlyCacheEnabled || http1OnlyCooldownMs <= 0) {
      return;
    }
    if (request == null || response == null) {
      return;
    }
    URI uri = request.getURI();
    if (uri == null) {
      return;
    }
    String origin = originKey(uri);
    HttpVersion version = response.getVersion();
    if ("https".equalsIgnoreCase(uri.getScheme())) {
      if (version == HttpVersion.HTTP_1_1) {
        markHttp1OnlyOrigin(origin);
      } else if (version != null && HTTP1_ONLY_CACHE.remove(origin) != null) {
        lowLevelDebug("HTTP/1.1-only cache cleared for origin {}", origin);
      }
      return;
    }
    // Cleartext origin that attempted an h2c upgrade but didn't get HTTP/2 back: cache it as
    // HTTP/1.1-only too, so later requests to the same origin skip the futile upgrade attempt.
    if ("http".equalsIgnoreCase(uri.getScheme()) && wasH2cUpgradeAttempt(request)
        && version != HttpVersion.HTTP_2) {
      markHttp1OnlyOrigin(origin);
    }
  }

  private void markHttp1OnlyOrigin(String origin) {
    Http1OnlyEntry entry = new Http1OnlyEntry();
    entry.expiresAt = System.currentTimeMillis() + http1OnlyCooldownMs;
    HTTP1_ONLY_CACHE.put(origin, entry);
    lowLevelDebug("HTTP/1.1-only cache set for origin {} until {}", origin, entry.expiresAt);
  }

  private void updateH2cCache(Request request, Response response) {
    if (!enableHttp2 || !h2cCacheEnabled || h2cCacheTtlMs <= 0) {
      return;
    }
    if (request == null || response == null) {
      return;
    }
    URI uri = request.getURI();
    if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())) {
      return;
    }
    String origin = originKey(uri);
    HttpVersion version = response.getVersion();
    if (version == HttpVersion.HTTP_2) {
      H2cEntry entry = new H2cEntry();
      entry.expiresAt = System.currentTimeMillis() + h2cCacheTtlMs;
      H2C_CACHE.put(origin, entry);
      lowLevelDebug("H2C cache set for origin {} until {}", origin, entry.expiresAt);
    } else if (version != null) {
      if (H2C_CACHE.remove(origin) != null) {
        lowLevelDebug("H2C cache cleared for origin {}", origin);
      }
    }
  }

  private void markHttp3Broken(URI uri) {
    if (!enableHttp3 || !altSvcCacheEnabled) {
      return;
    }
    String origin = originKey(uri);
    AltSvcEntry entry = ALT_SVC_CACHE.get(origin);
    if (entry == null) {
      return;
    }
    entry.brokenUntil = System.currentTimeMillis() + http3BrokenCooldownMs;
    ALT_SVC_CACHE.put(origin, entry);
    lowLevelDebug("HTTP/3 marked broken for origin {} until {}", origin, entry.brokenUntil);
  }

  private boolean isHttp3ConnectTimeout(Throwable cause) {
    if (cause == null) {
      return false;
    }
    if (cause instanceof java.net.SocketTimeoutException) {
      String msg = cause.getMessage() != null ? cause.getMessage().toLowerCase(Locale.ROOT) : "";
      return msg.contains("connect timeout");
    }
    return isHttp3ConnectTimeout(cause.getCause());
  }

  RetryableRequestException findRetryableRequestException(Throwable cause) {
    Throwable current = cause;
    while (current != null) {
      if (current instanceof RetryableRequestException) {
        return (RetryableRequestException) current;
      }
      current = current.getCause();
    }
    return null;
  }

  /**
   * Unifies the two failure modes that warrant an HTTP/1.1 fallback: an explicit HTTP/2
   * {@code protocol_error}, and a {@link ClosedChannelException} anywhere in the cause chain -
   * some servers drop the connection outright instead of returning a clean protocol error when
   * they don't like the request (e.g. a failed h2c upgrade attempt).
   */
  private boolean shouldFallbackToHttp11AfterTransportFailure(Throwable cause,
      Throwable wrapped) {
    if (!protocolErrorFallbackEnabled || !enableHttp1) {
      return false;
    }
    return ProtocolErrorException.isProtocolError(wrapped)
        || ProtocolErrorException.isProtocolError(cause)
        || isClosedChannelFailure(cause != null ? cause : wrapped);
  }

  private static boolean isClosedChannelFailure(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof ClosedChannelException) {
        return true;
      }
    }
    return false;
  }

  private ContentResponse retryAfterGoAway(Request originalRequest)
      throws InterruptedException, TimeoutException, ExecutionException {
    if (originalRequest == null) {
      throw new ExecutionException("Cannot retry after GOAWAY: original request is null", null);
    }
    URI uri = originalRequest.getURI();
    ExecutionException lastException = null;
    for (int attempt = 1; attempt <= maxGoawayRetries; attempt++) {
      try {
        HttpClient retryClient = selectHttpClient(uri);
        lowLevelDebug("Retrying request after GOAWAY: attempt {}/{} method={}, URI={}, client={}",
            attempt, maxGoawayRetries, originalRequest.getMethod(), uri, retryClient.getName());
        Request retryRequest = cloneRequest(originalRequest, retryClient);
        ContentResponse response = retryRequest.send();
        updateHttp1OnlyCache(retryRequest, response);
        updateH2cCache(retryRequest, response);
        updateAltSvcCache(retryRequest, response.getHeaders());
        return response;
      } catch (ExecutionException e) {
        lastException = e;
        RetryableRequestException retryable = findRetryableRequestException(e);
        if (retryable != null && attempt < maxGoawayRetries) {
          LOG.warn("RetryableRequestException during GOAWAY retry, retrying: {}",
              retryable.getMessage());
          continue;
        }
        throw e;
      }
    }
    if (lastException != null) {
      throw lastException;
    }
    throw new ExecutionException("Retry after GOAWAY failed without exception", null);
  }

  private ContentResponse sendWithHttp2Only(Request originalRequest)
      throws InterruptedException, TimeoutException, ExecutionException {
    URI uri = originalRequest.getURI();
    lowLevelDebug("Retrying request without HTTP/3: method={}, URI={}",
        originalRequest.getMethod(), uri);
    Request request = cloneRequest(originalRequest, httpClientNoH3);
    ContentResponse response = request.send();
    updateHttp1OnlyCache(request, response);
    updateH2cCache(request, response);
    updateAltSvcCache(request, response.getHeaders());
    return response;
  }

  private Request cloneRequest(Request originalRequest, HttpClient client)
      throws ExecutionException {
    URI uri = originalRequest.getURI();
    if (!client.isStarted()) {
      try {
        client.start();
      } catch (Exception e) {
        throw new ExecutionException("Failed to start HTTP client", e);
      }
    }
    clearContentDecoders(client);
    Request request = client.newRequest(uri)
        .method(originalRequest.getMethod())
        .timeout(originalRequest.getTimeout(), TimeUnit.MILLISECONDS)
        .followRedirects(originalRequest.isFollowRedirects());
    if (originalRequest.getHeaders() != null) {
      HttpFields originalHeaders = originalRequest.getHeaders();
      HttpFields requestHeaders = request.getHeaders();
      if (requestHeaders instanceof HttpFields.Mutable) {
        HttpFields.Mutable newHeaders = (HttpFields.Mutable) requestHeaders;
        originalHeaders.forEach(field -> {
          String name = field.getName();
          if (!name.startsWith(":")) {
            newHeaders.put(name, field.getValue());
          }
        });
      }
    }
    if (originalRequest.getBody() != null) {
      request.body(originalRequest.getBody());
    }
    SslClientCertAliasSupport.copyFromRequest(originalRequest, request);
    configureContentDecodersAndCapture(client, request);
    return request;
  }

  private static class HappyEyeballsThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger(1);

    HappyEyeballsThreadFactory(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
      t.setDaemon(true);
      return t;
    }
  }

  private void configureHttpClient(HttpClient client, ClientConnector connector) {
    client.setUserAgentField(null);
    connector.setByteBufferPool(this.bufferPool);
    client.setMaxRequestsQueuedPerDestination(maxRequestsQueuedPerDestination);
    client.setMaxConnectionsPerDestination(maxConnectionsPerDestination);
    client.setStrictEventOrdering(strictEventOrdering);
    if (removeIdleDestinations) {
      client.setDestinationIdleTimeout(idleTimeout);
    }
    client.setIdleTimeout(idleTimeout);
    if (LowLevelDebugLog.isEnabled()) {
      addConnectionLogging(client);
    }
  }

  private static void addConnectionLogging(HttpClient client) {
    client.addBean(new Connection.Listener() {
      @Override
      public void onOpened(Connection connection) {
        logConnection("client", connection);
      }

      @Override
      public void onClosed(Connection connection) {
        logAlpnLine("client connection closed: " + connection.getClass().getName());
      }
    });
  }

  private static void logConnection(String side, Connection connection) {
    StringBuilder message = new StringBuilder();
    message.append(side).append(" connection opened: ")
        .append(connection.getClass().getName());
    if (connection instanceof SslConnection) {
      SslConnection sslConnection = (SslConnection) connection;
      javax.net.ssl.SSLEngine engine = sslConnection.getSSLEngine();
      String appProtocol = engine.getApplicationProtocol();
      String sslProtocol = engine.getSession().getProtocol();
      message.append(" alpn=").append(appProtocol)
          .append(" tls=").append(sslProtocol);
      sslConnection.addHandshakeListener(new SslHandshakeListener() {
        @Override
        public void handshakeSucceeded(Event event) {
          javax.net.ssl.SSLEngine handshakeEngine = event.getSSLEngine();
          String negotiated = handshakeEngine.getApplicationProtocol();
          String protocol = handshakeEngine.getSession().getProtocol();
          logAlpnLine(side + " handshake succeeded: alpn=" + negotiated + " tls=" + protocol);
        }

        @Override
        public void handshakeFailed(Event event, Throwable failure) {
          logAlpnLine(side + " handshake failed: "
              + (failure != null ? failure.getMessage() : "unknown"));
        }
      });
    }
    logAlpnLine(message.toString());
  }

  private static void logAlpnLine(String message) {
    if (!LowLevelDebugLog.isEnabled()) {
      return;
    }
    try {
      Path parent = ALPN_DEBUG_LOG_PATH.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      String line = System.currentTimeMillis() + " " + message + System.lineSeparator();
      Files.write(ALPN_DEBUG_LOG_PATH, line.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // Best-effort diagnostic logging.
    }
  }

  private static Path resolveAlpnLogPath() {
    Path baseDir = Paths.get(System.getProperty("user.dir", "."));
    if (baseDir.endsWith("jmeter-http2-plugin")) {
      return baseDir.resolve("target").resolve("http2-client-alpn.log");
    }
    return baseDir.resolve("jmeter-http2-plugin")
        .resolve("target")
        .resolve("http2-client-alpn.log");
  }

  private ClientConnector createClientConnector(String name) {
    ClientConnector connector = new ClientConnector();
    if (sharedThreadPoolEnabled) {
      connector.setSelectors(-1);
    } else {
      connector.setSelectors(1); // Only one selector per thread in thread pool
    }
    connector.setConnectBlocking(false);
    SslContextFactory.Client sslContextFactory = new JMeterJettySslContextFactory();
    connector.setSslContextFactory(sslContextFactory);
    try {
      SslContextFactory.Client sslFromConnector =
          (SslContextFactory.Client) connector.getSslContextFactory();
      if (sslFromConnector != null) {
        sslFromConnector.setProtocol("TLS");
      }
    } catch (Exception e) {
      lowLevelDebug("Could not set SSL protocol explicitly for connector {}", name, e);
    }
    connector.setExecutor(resolveExecutor(name));
    connector.setByteBufferPool(this.bufferPool);
    return connector;
  }

  private Executor resolveExecutor(String name) {
    if (!sharedThreadPoolEnabled) {
      return createLocalThreadPool(name);
    }
    return getSharedExecutor();
  }

  private Executor createLocalThreadPool(String name) {
    QueuedThreadPool queuedThreadPool = new QueuedThreadPool(maxThreads);
    queuedThreadPool.setMinThreads(minThreads);
    queuedThreadPool.setName(name);
    return queuedThreadPool;
  }

  private Executor getSharedExecutor() {
    if (sharedExecutor != null) {
      return sharedExecutor;
    }
    synchronized (SHARED_POOL_LOCK) {
      if (sharedExecutor == null) {
        if (sharedMaxThreads <= 0) {
          sharedMaxThreads = maxThreads;
        }

        sharedThreadPool = new QueuedThreadPool(sharedMaxThreads);
        sharedThreadPool.setMinThreads(minThreads);

        sharedThreadPool.setReservedThreads(-1); // Automatic
        sharedThreadPool.setIdleTimeout(10000); // 60 seconds to free a idle thread
        int aggressiveEvictCount = Math.max(8, sharedMaxThreads / 10); // ~10% of  pool
        sharedThreadPool.setMaxEvictCount(aggressiveEvictCount); // Free on aggressive way

        int cores = Runtime.getRuntime().availableProcessors();
        sharedThreadPool.setLowThreadsThreshold(cores * 2);

        sharedThreadPool.setName(SHARED_POOL_NAME);
        try {
          sharedThreadPool.start();
        } catch (Exception e) {
          LOG.warn("Failed to start shared Jetty thread pool, falling back to " +
              "local pools", e);
          sharedThreadPool = null;
          return createLocalThreadPool("http2-local-fallback");
        }

        sharedMinThreads = minThreads;
        sharedExecutor = sharedThreadPool;
      } else if (sharedThreadPool != null
          && (sharedMaxThreads != maxThreads || sharedMinThreads != minThreads)) {
        int requestedMaxThreads = maxThreads;
        int requestedMinThreads = minThreads;
        if (requestedMaxThreads > sharedMaxThreads || requestedMinThreads > sharedMinThreads) {
          int newMaxThreads = Math.max(sharedMaxThreads, requestedMaxThreads);
          int newMinThreads = Math.max(sharedMinThreads, requestedMinThreads);
          if (newMinThreads > newMaxThreads) {
            newMaxThreads = newMinThreads;
          }
          sharedThreadPool.setMaxThreads(newMaxThreads);
          sharedThreadPool.setMinThreads(newMinThreads);
          sharedMaxThreads = newMaxThreads;
          sharedMinThreads = newMinThreads;
          lowLevelDebug("Shared thread pool resized to min={}, max={} (requested min={}, max={})",
              sharedMinThreads, sharedMaxThreads, requestedMinThreads, requestedMaxThreads);
        } else {
          lowLevelDebug("Shared thread pool already initialized with min={}, max={}; "
                  + "requested min={}, max={}",
              sharedMinThreads, sharedMaxThreads, requestedMinThreads, requestedMaxThreads);
        }
      }
      return sharedExecutor;
    }
  }

  private void configureTransport(HttpClientTransport transport) {
    configureTransport(transport, maxRequestsPerConnection);
  }

  /**
   * @param maxMultiplex max concurrent exchanges per connection (HTTP/2/3); use {@code 1} for
   *     HTTP/1-only transports so parallel requests open extra TCP connections instead of
   *     failing with "Pipelined requests not supported".
   */
  private void configureTransport(HttpClientTransport transport, int maxMultiplex) {
    transport.setConnectionPoolFactory((destination) -> {
      MultiplexConnectionPool mcp = new MultiplexConnectionPool(
          destination,
          destination.getHttpClient().getMaxConnectionsPerDestination(),
          maxMultiplex);
      mcp.setInitialMaxMultiplex(maxMultiplex);
      return mcp;
    });
  }

  private String originKey(URI uri) {
    if (uri == null) {
      return "unknown";
    }
    String host = uri.getHost() != null ? uri.getHost() : uri.getAuthority();
    int port = uri.getPort();
    if (port < 0) {
      port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
    return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
        + ":" + port;
  }

  private static void ensureHappyEyeballsExecutors() {
    if (happyEyeballsScheduler != null && !happyEyeballsScheduler.isShutdown()
        && happyEyeballsExecutor != null && !happyEyeballsExecutor.isShutdown()) {
      return;
    }
    synchronized (HAPPY_EYEBALLS_LOCK) {
      if (happyEyeballsScheduler == null || happyEyeballsScheduler.isShutdown()) {
        happyEyeballsScheduler = Executors.newSingleThreadScheduledExecutor(
            new HappyEyeballsThreadFactory("http3-he"));
      }
      if (happyEyeballsExecutor == null || happyEyeballsExecutor.isShutdown()) {
        happyEyeballsExecutor = Executors.newScheduledThreadPool(2,
            new HappyEyeballsThreadFactory("http3-he-worker"));
      }
    }
  }

  private static void shutdownHappyEyeballsExecutors() {
    ScheduledExecutorService scheduler;
    ScheduledExecutorService executor;
    synchronized (HAPPY_EYEBALLS_LOCK) {
      scheduler = happyEyeballsScheduler;
      executor = happyEyeballsExecutor;
      happyEyeballsScheduler = null;
      happyEyeballsExecutor = null;
    }
    shutdownExecutor(scheduler);
    shutdownExecutor(executor);
  }

  private static void shutdownExecutor(ScheduledExecutorService executor) {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }

  private static class AltSvcEntry {
    private boolean h3;
    private long expiresAt;
    private long brokenUntil;
    private long lastH3SuccessAt;
  }

  private static class Http1OnlyEntry {
    private long expiresAt;
  }

  private static class H2cEntry {
    private long expiresAt;
  }

  private void setAuthManager(HTTP2Sampler sampler) {
    AuthManager authManager = sampler.getAuthManager();
    if (authManager != null) {
      StreamSupport.stream(authManager.getAuthObjects().spliterator(), false)
          .map(j -> (Authorization) j.getObjectValue())
          .filter(auth -> isSupportedMechanism(auth) && !StringUtils.isEmpty(auth.getURL()))
          .forEach(this::addAuthenticationToJettyClient);
    }
  }

  private boolean isSupportedMechanism(Authorization auth) {
    String authName = auth.getMechanism().name();
    return authName.equals(AuthManager.Mechanism.BASIC.name())
        || authName.equals(AuthManager.Mechanism.DIGEST.name());
  }

  private void addAuthenticationToJettyClient(Authorization auth) {
    AuthenticationStore authenticationStore = httpClient.getAuthenticationStore();
    AuthenticationStore authenticationStoreNoH3 = httpClientNoH3.getAuthenticationStore();
    AuthenticationStore authenticationStoreHttp1 = httpClientHttp1Only.getAuthenticationStore();
    AuthenticationStore authenticationStoreH2c = httpClientH2cPrior.getAuthenticationStore();
    AuthenticationStore authenticationStoreH2cUpgrade =
        httpClientH2cUpgrade.getAuthenticationStore();
    String authName = auth.getMechanism().name();
    if (authName.equals(AuthManager.Mechanism.BASIC.name())
        && BzmHttpPluginProperties.getPropDefault("httpJettyClient.auth.preemptive", false)) {
      BasicAuthentication.BasicResult result =
          new BasicAuthentication.BasicResult(URI.create(auth.getURL()), auth.getUser(),
              auth.getPass());
      authenticationStore.addAuthenticationResult(result);
      authenticationStoreNoH3.addAuthenticationResult(result);
      authenticationStoreHttp1.addAuthenticationResult(result);
      authenticationStoreH2c.addAuthenticationResult(result);
      authenticationStoreH2cUpgrade.addAuthenticationResult(result);
    } else {
      AbstractAuthentication authentication =
          authName.equals(AuthManager.Mechanism.BASIC.name()) ? new BasicAuthentication(
              URI.create(auth.getURL()), auth.getRealm(), auth.getUser(), auth.getPass())
              :
              new DigestAuthentication(URI.create(auth.getURL()), auth.getRealm(),
                  auth.getUser(),
                  auth.getPass());
      authenticationStore.addAuthentication(authentication);
      authenticationStoreNoH3.addAuthentication(authentication);
      authenticationStoreHttp1.addAuthentication(authentication);
      authenticationStoreH2c.addAuthentication(authentication);
      authenticationStoreH2cUpgrade.addAuthentication(authentication);
    }
  }

  private static class RequestContext {
    private final Request request;
    private final HttpClient client;

    private RequestContext(Request request, HttpClient client) {
      this.request = request;
      this.client = client;
    }
  }

  private RequestContext buildRequestContext(HTTPSampleResult result) throws URISyntaxException,
      IllegalArgumentException {
    HttpClient client = selectHttpClient(result.getURL().toURI());
    return buildRequestContext(result, client);
  }

  private RequestContext buildRequestContext(HTTPSampleResult result, HttpClient client)
      throws URISyntaxException, IllegalArgumentException {
    URL url = result.getURL();
    URI uri = url.toURI();
    clearContentDecoders(client);
    Request request = client.newRequest(uri);
    if (client == httpClientH2cPrior) {
      request.version(HttpVersion.HTTP_2);
    } else if (client == httpClientH2cUpgrade) {
      request.version(HttpVersion.HTTP_1_1);
    } else if ("https".equalsIgnoreCase(uri.getScheme())
        && enableHttp2
        && !enableHttp1
        && !enableHttp3) {
      // Force HTTP/2 when HTTP/1.1 is disabled to avoid mixed-protocol frames.
      request.version(HttpVersion.HTTP_2);
    }
    boolean http3Attempted = enableHttp3 && client == httpClient && shouldAttemptHttp3(uri);
    request.attribute(ATTR_HTTP3_ATTEMPTED, http3Attempted);
    request.attribute(ATTR_ORIGIN_KEY, originKey(uri));
    if ("https".equalsIgnoreCase(uri.getScheme())) {
      String clientCertAlias = JMeterSslAliasResolver.resolveForRequest();
      SslClientCertAliasSupport.bindToRequest(request, clientCertAlias);
    }
    request.onRequestBegin(r -> result.connectEnd());
    request.onRequestContent(
        (r, c) -> result.setSentBytes(result.getSentBytes() + c.limit()));
    request.onResponseBegin(r -> result.latencyEnd());
    return new RequestContext(request, client);
  }

  private HttpClient resolveClientForRequest(HTTP2Sampler sampler, HTTPSampleResult result)
      throws URISyntaxException {
    return selectHttpClient(result.getURL().toURI());
  }

  private boolean requestAdvertisesEncoding(HTTP2Sampler sampler, String encoding) {
    HeaderManager headerManager = sampler.getHeaderManager();
    if (headerManager == null) {
      return false;
    }
    return StreamSupport.stream(headerManager.getHeaders().spliterator(), false)
        .map(prop -> (Header) prop.getObjectValue())
        .filter(header -> HttpHeader.ACCEPT_ENCODING.is(header.getName()))
        .map(Header::getValue)
        .anyMatch(value -> containsEncodingToken(value, encoding));
  }

  private boolean containsEncodingToken(String headerValue, String encoding) {
    if (headerValue == null) {
      return false;
    }
    for (String token : headerValue.split(",")) {
      String normalized = token.trim().toLowerCase(Locale.ROOT);
      int paramsIndex = normalized.indexOf(';');
      if (paramsIndex >= 0) {
        normalized = normalized.substring(0, paramsIndex).trim();
      }
      if (encoding.equals(normalized)) {
        return true;
      }
    }
    return false;
  }

  private void setTimeouts(HTTP2Sampler sampler, Request request) {
    if (sampler.getConnectTimeout() > 0) {
      httpClient.setConnectTimeout(sampler.getConnectTimeout());
      if (httpClientNoH3 != httpClient) {
        httpClientNoH3.setConnectTimeout(sampler.getConnectTimeout());
      }
      httpClientHttp1Only.setConnectTimeout(sampler.getConnectTimeout());
      httpClientH2cPrior.setConnectTimeout(sampler.getConnectTimeout());
      httpClientH2cUpgrade.setConnectTimeout(sampler.getConnectTimeout());
    }
    if (sampler.getResponseTimeout() > 0) {
      requestTimeout = sampler.getResponseTimeout();
      request.timeout(sampler.getResponseTimeout(), TimeUnit.MILLISECONDS);
    } else if (requestTimeout > 0) {
      request.timeout(requestTimeout, TimeUnit.MILLISECONDS);
    }
  }

  private void setHeaders(Request request, URL url, HeaderManager headerManager) {
    boolean[] acceptEncodingSeen = new boolean[] {false};
    if (headerManager != null) {
      StreamSupport.stream(headerManager.getHeaders().spliterator(), false)
          .map(prop -> (Header) prop.getObjectValue())
          .filter(header -> (!header.getName().isEmpty()) && (!HTTPConstants.HEADER_CONTENT_LENGTH
              .equalsIgnoreCase(header.getName())))
          .forEach(header -> {
            if (HttpHeader.ACCEPT_ENCODING.is(header.getName())) {
              acceptEncodingSeen[0] = true;
            }
            HttpField jettyHeader = createJettyHeader(header, url);
            HttpFields headers = request.getHeaders();
            if (headers instanceof HttpFields.Mutable) {
              ((HttpFields.Mutable) headers).put(jettyHeader.getName(), jettyHeader.getValue());
            }
          });
    }

    if (!acceptEncodingSeen[0]) {
      HttpFields headers = request.getHeaders();
      if (headers instanceof HttpFields.Mutable) {
        ((HttpFields.Mutable) headers).remove(HttpHeader.ACCEPT_ENCODING);
      }
    }

    // Filter invalid headers for HTTP/2 (Issue #2788)
    // HTTP/2 does not support Connection: close or other connection-specific headers
    // This must be done after all headers are set to ensure we catch headers from HeaderManager
    filterInvalidHTTP2Headers(request);
    // HTTP/2 cleartext (h2c) upgrade headers are only for HTTP, not HTTPS.
    // For HTTPS, HTTP/2 is negotiated via ALPN during the TLS handshake.
    // Adding upgrade headers on HTTPS can trigger protocol_error because:
    // 1. The connection is already HTTP/2 (negotiated via ALPN)
    // 2. Upgrade headers are for cleartext HTTP, not HTTPS
    // 3. It violates the HTTP/2 protocol (RFC 7540)
    if (http1UpgradeRequired && enableHttp2 && !"https".equalsIgnoreCase(url.getProtocol())
        && !shouldUseH2cPriorKnowledge(request.getURI())) {
      Mutable headers = ((Mutable) request.getHeaders());
      addHeaderIfMissing(HttpHeader.UPGRADE, "h2c", headers);
      addHeaderIfMissing(HttpHeader.HTTP2_SETTINGS, buildH2cSettingsHeaderValue(), headers);
      addHeaderIfMissing(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings", headers);
      if (request.getAttributes().get(HttpUpgrader.PROTOCOL_ATTRIBUTE) == null) {
        request.attribute(HttpUpgrader.PROTOCOL_ATTRIBUTE, "h2c");
      }
      lowLevelDebug("Added HTTP/2 cleartext upgrade headers for HTTP connection");
    } else if (!"https".equalsIgnoreCase(url.getProtocol())
        && shouldUseH2cPriorKnowledge(request.getURI())) {
      lowLevelDebug("Skipping h2c upgrade headers (prior knowledge enabled)");
    } else if (http1UpgradeRequired && "https".equalsIgnoreCase(url.getProtocol())) {
      lowLevelDebug("Skipping upgrade headers for HTTPS connection "
          + "(ALPN handles HTTP/2 negotiation)");
    }

    // Filter invalid headers for HTTP/2 again after upgrade headers (if any)
    // This ensures we don't have invalid headers even after adding upgrade headers
    filterInvalidHTTP2Headers(request);

    // Log all headers for debugging HTTP/2 protocol_error
    if (request.getHeaders() != null) {
      HttpFields headers = request.getHeaders();
      lowLevelDebug("Request headers configured: total={}, http1UpgradeRequired={}",
          headers.size(), http1UpgradeRequired);

      // Log pseudo-headers (HTTP/2 specific) - these are set automatically by Jetty
      URI uri = request.getURI();
      String authority = uri.getAuthority() != null ? uri.getAuthority()
          : uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort()
          : ("https".equals(uri.getScheme()) ? 443 : 80));
      String path = uri.getPath() + (uri.getQuery() != null ? "?" + uri.getQuery() : "");
      lowLevelDebug("HTTP/2 pseudo-headers (set by Jetty): :method={}, :scheme={}, "
          + ":authority={}, :path={}", request.getMethod(), uri.getScheme(), authority, path);

      // Log all headers (for debugging)
      if (LOG.isDebugEnabled()) {
        headers.forEach(field -> {
          lowLevelDebug("  Header: {} = {}", field.getName(), field.getValue());
        });
      }
    }
  }

  private void ensureHostHeader(Request request, URL url) {
    if (request == null || url == null) {
      return;
    }
    HttpFields headers = request.getHeaders();
    if (!(headers instanceof HttpFields.Mutable)) {
      return;
    }
    HttpFields.Mutable mutableHeaders = (HttpFields.Mutable) headers;
    if (mutableHeaders.contains(HttpHeader.HOST)) {
      return;
    }
    int port = url.getPort();
    boolean includePort = port > 0 && port != url.getDefaultPort();
    String hostValue = includePort ? url.getHost() + ":" + port : url.getHost();
    mutableHeaders.put(HttpHeader.HOST, hostValue);
  }

  private void ensureHostHeader(Request request, URI uri) {
    if (request == null || uri == null) {
      return;
    }
    HttpFields headers = request.getHeaders();
    if (!(headers instanceof HttpFields.Mutable)) {
      return;
    }
    HttpFields.Mutable mutableHeaders = (HttpFields.Mutable) headers;
    if (mutableHeaders.contains(HttpHeader.HOST)) {
      return;
    }
    String host = uri.getHost() != null ? uri.getHost() : uri.getAuthority();
    if (host == null || host.isEmpty()) {
      return;
    }
    int port = uri.getPort();
    int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    boolean includePort = port > 0 && port != defaultPort;
    String hostValue = includePort ? host + ":" + port : host;
    mutableHeaders.put(HttpHeader.HOST, hostValue);
  }

  private void addPreemptiveAuthorizationHeader(Request request, URL url,
                                                AuthManager authManager) {
    if (request == null || url == null || authManager == null) {
      return;
    }
    if (!BzmHttpPluginProperties.getPropDefault("httpJettyClient.auth.preemptive", false)) {
      return;
    }
    HttpFields headers = request.getHeaders();
    if (!(headers instanceof HttpFields.Mutable)) {
      return;
    }
    HttpFields.Mutable mutableHeaders = (HttpFields.Mutable) headers;
    if (mutableHeaders.contains(HttpHeader.AUTHORIZATION)) {
      return;
    }
    StreamSupport.stream(authManager.getAuthObjects().spliterator(), false)
        .map(j -> (Authorization) j.getObjectValue())
        .filter(auth -> auth != null
            && AuthManager.Mechanism.BASIC.equals(auth.getMechanism())
            && !StringUtils.isEmpty(auth.getURL()))
        .filter(auth -> url.toString().startsWith(auth.getURL()))
        .findFirst()
        .ifPresent(auth -> {
          String credentials = auth.getUser() + ":" + auth.getPass();
          String token = Base64.getEncoder()
              .encodeToString(credentials.getBytes(StandardCharsets.ISO_8859_1));
          mutableHeaders.put(HttpHeader.AUTHORIZATION, "Basic " + token);
        });
  }

  private void addHeaderIfMissing(HttpHeader header, String value, Mutable headers) {
    if (!headers.contains(header)) {
      headers.put(header, value);
    }
  }

  private String buildH2cSettingsHeaderValue() {
    Map<Integer, Integer> settings = new LinkedHashMap<>();
    if (settingsHeaderTableSize > 0) {
      settings.put(0x1, settingsHeaderTableSize);
    }
    if (settingsMaxConcurrentStreams > 0) {
      settings.put(0x3, settingsMaxConcurrentStreams);
    }
    if (settingsInitialWindowSize > 0) {
      settings.put(0x4, settingsInitialWindowSize);
    }
    if (settingsMaxFrameSize > 0) {
      settings.put(0x5, settingsMaxFrameSize);
    }
    if (settingsMaxHeaderListSize > 0) {
      settings.put(0x6, settingsMaxHeaderListSize);
    }
    if (settings.isEmpty()) {
      return "";
    }
    ByteBuffer buffer = ByteBuffer.allocate(settings.size() * 6);
    for (Map.Entry<Integer, Integer> entry : settings.entrySet()) {
      buffer.putShort(entry.getKey().shortValue());
      buffer.putInt(entry.getValue());
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
  }

  /**
   * Filters invalid headers for HTTP/2 (Issue #2788).
   * HTTP/2 does not support certain headers like "Connection: close" or other
   * connection-specific headers that are valid in HTTP/1.1 but invalid in HTTP/2.
   * This method removes these headers to prevent protocol_error.
   *
   * @param request The HTTP request to filter headers from
   */
  private void filterInvalidHTTP2Headers(Request request) {
    HttpFields headers = request.getHeaders();
    if (headers == null || !(headers instanceof HttpFields.Mutable)) {
      return;
    }

    HttpFields.Mutable mutableHeaders = (HttpFields.Mutable) headers;

    // Check if this is an HTTP/2 request (has pseudo-headers or version is HTTP/2)
    // For HTTPS connections, we assume HTTP/2 if ALPN negotiated it
    // For HTTP connections, we check if upgrade headers are present
    boolean isHTTP2 = "https".equalsIgnoreCase(request.getURI().getScheme())
        || (enableHttp2 && http1UpgradeRequired && headers.contains(HttpHeader.UPGRADE));

    if (isHTTP2) {
      // HTTP/2 does not support Connection header except for upgrade (which we handle separately)
      // Remove Connection: close or any Connection header that's not for upgrade
      if (headers.contains(HttpHeader.CONNECTION)) {
        String connectionValue = headers.get(HttpHeader.CONNECTION);
        if (connectionValue != null &&
            !connectionValue.contains("Upgrade") &&
            !connectionValue.contains("HTTP2-Settings")) {
          lowLevelDebug("Removing invalid Connection header for HTTP/2: {}", connectionValue);
          mutableHeaders.remove(HttpHeader.CONNECTION);
        }
      }

      // HTTP/2 headers must be lowercase (RFC 7540)
      // Jetty should handle this automatically, but we log if we see uppercase headers
      if (LOG.isDebugEnabled()) {
        headers.forEach(field -> {
          String name = field.getName();
          if (!name.startsWith(":") && !name.equals(name.toLowerCase())) {
            lowLevelDebug("Warning: HTTP/2 header name should be lowercase: {}", name);
          }
        });
      }
    }
  }

  private HttpField createJettyHeader(Header header, URL url) {
    String headerName = header.getName();
    String headerValue = header.getValue();
    if (HTTPConstants.HEADER_HOST.equalsIgnoreCase(headerName)) {
      int port = getPortFromHostHeader(headerValue, url.getPort());
      // remove any port specification
      headerValue = headerValue.replaceFirst(":\\d+$", "");
      if (port != -1 && port == url.getDefaultPort()) {
        // no need to specify the port if it is the default
        port = -1;
      }
      return port == -1 ? new HttpField(HTTPConstants.HEADER_HOST, headerValue)
          : new HttpField(HTTPConstants.HEADER_HOST, headerValue + ":" + port);
    } else {
      return new HttpField(headerName, headerValue);
    }
  }

  private int getPortFromHostHeader(String hostHeaderValue, int defaultValue) {
    String[] hostParts = hostHeaderValue.split(":");
    if (hostParts.length > 1) {
      String portString = hostParts[hostParts.length - 1];
      if (PORT_PATTERN.matcher(portString).matches()) {
        return Integer.parseInt(portString);
      }
    }
    return defaultValue;
  }

  private String buildCookies(Request request, URL url, CookieManager cookieManager) {
    if (cookieManager == null) {
      return null;
    }
    HttpCookieStore cookieStore = httpClient.getHttpCookieStore();
    if (cookieStore != null) {
      URI uri = request.getURI();
      if (cookieManager.getCookieCount() == 0) {
        if (!cookieStore.match(uri).isEmpty()) {
          cookieStore.clear();
          lowLevelDebug("Cleared Jetty cookie store because JMeter CookieManager is empty");
        }
      } else {
        Set<String> jmeterCookieNames = new HashSet<>();
        for (JMeterProperty property : cookieManager.getCookies()) {
          Cookie cookie = (Cookie) property.getObjectValue();
          if (cookie != null) {
            jmeterCookieNames.add(cookie.getName());
          }
        }
        if (!jmeterCookieNames.isEmpty()) {
          for (HttpCookie cookie : cookieStore.match(uri)) {
            if (jmeterCookieNames.contains(cookie.getName())) {
              cookieStore.remove(uri, cookie);
              lowLevelDebug("Removed cookie '{}' from Jetty store because JMeter overrides it",
                  cookie.getName());
            }
          }
        }
      }
    }
    String cookieString = cookieManager.getCookieHeaderForURL(url);
    if (cookieString != null) {
      HttpFields headers = request.getHeaders();
      if (headers instanceof HttpFields.Mutable) {
        ((HttpFields.Mutable) headers).put(HTTPConstants.HEADER_COOKIE, cookieString);
      }
    }
    return cookieString;
  }

  private void setProxy(String host, int port, String protocol) {
    boolean secureProxy = HTTPConstants.PROTOCOL_HTTPS.equals(protocol);
    // It is not allowed to change the running proxy.
    // Only the first assigned is used.
    addProxyIfEmpty(httpClient, host, port, secureProxy);
    addProxyIfEmpty(httpClientNoH3, host, port, secureProxy);
    addProxyIfEmpty(httpClientHttp1Only, host, port, secureProxy);
    addProxyIfEmpty(httpClientH2cPrior, host, port, secureProxy);
    addProxyIfEmpty(httpClientH2cUpgrade, host, port, secureProxy);
  }

  private void addProxyIfEmpty(HttpClient target, String host, int port, boolean secureProxy) {
    if (target.getProxyConfiguration().getProxies().isEmpty()) {
      HttpProxy proxy = new HttpProxy(new Address(host, port), secureProxy);
      target.getProxyConfiguration().addProxy(proxy);
    }
  }

  private void setBody(Request request, HTTP2Sampler sampler, HTTPSampleResult result)
      throws IOException {
    String contentEncoding = sampler.getContentEncoding();
    String contentTypeHeader =
        request.getHeaders() != null ? request.getHeaders().get(HTTPConstants.HEADER_CONTENT_TYPE)
            : null;
    boolean hasContentTypeHeader = contentTypeHeader != null && contentTypeHeader.isEmpty();
    StringBuilder postBody = new StringBuilder();
    if (sampler.getUseMultipart()) {
      // In Jetty 12, MultiPartRequestContent API has changed significantly
      // The methods addFieldPart() and addFilePart() no longer exist
      // Solution: Build multipart body manually as bytes and use BytesRequestContent
      MultiPartRequestContent multipartEntityBuilder = new MultiPartRequestContent();
      String boundary = extractMultipartBoundary(multipartEntityBuilder);
      Charset contentCharset =
          buildCharsetOrDefault(contentEncoding, StandardCharsets.UTF_8);

      // Build multipart body as bytes
      byte[] multipartBody = buildMultipartBodyBytes(sampler, boundary, contentCharset,
          hasContentTypeHeader);

      // Set Content-Type header with boundary
      // Note: boundary from extractMultipartBoundary() does NOT include the "--" prefix
      // The test expects: boundary="JettyHttpClient-..." (with quotes)
      // HttpFields.toString() formats values, and when the test uses .add() with the value,
      // it will format it correctly. We need to match the test's format exactly.
      // The test (line 690) uses: "multipart/form-data; boundary=" + boundary.substring(2)
      // Since our boundary doesn't have "--", we use it directly
      HttpFields.Mutable headers = (Mutable) request.getHeaders();
      headers.put(HTTPConstants.HEADER_CONTENT_TYPE,
          "multipart/form-data; boundary=\"" + boundary + "\"");

      // Use BytesRequestContent instead of MultiPartRequestContent
      Request.Content requestContent = new BytesRequestContent(multipartBody);
      request.body(requestContent);

      // Build postBody string for query string display (for logging/debugging)
      for (JMeterProperty jMeterProperty : sampler.getArguments()) {
        HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
        String parameterName = arg.getName();
        if (!arg.isSkippable(parameterName)) {
          postBody.append(
              buildArgumentPartRequestBody(arg, contentCharset, contentEncoding, boundary));
        }
      }
      for (int i = 0; i < sampler.getHTTPFiles().length; i++) {
        final HTTPFileArg file = sampler.getHTTPFiles()[i];
        if (StringUtils.isBlank(file.getParamName())) {
          throw new IllegalStateException("Param name is blank");
        }
        String fileName = Paths.get((file.getPath())).getFileName().toString();
        postBody.append(buildFilePartRequestBody(file, fileName, boundary));
      }
      postBody.append(MULTI_PART_SEPARATOR).append(boundary).append(MULTI_PART_SEPARATOR)
          .append(LINE_SEPARATOR);

      multipartEntityBuilder.close();
    } else {
      if (!sampler.hasArguments() && sampler.getSendFileAsPostBody()) {
        // Only one File support in not multipart scenario
        final HTTPFileArg file = sampler.getHTTPFiles()[0];
        if (sampler.getHTTPFiles().length > 1) {
          LOG.warn("Send multiples files is not currently supported, only first file will be "
              + "sending");
        }

        String mimeTypeFile = extractFileMimeType(hasContentTypeHeader, file);
        if (!DEFAULT_FILE_MIME_TYPE.equals(mimeTypeFile)) {
          HttpFields headers = request.getHeaders();
          if (headers instanceof HttpFields.Mutable) {
            ((HttpFields.Mutable) headers).put(HTTPConstants.HEADER_CONTENT_TYPE, mimeTypeFile);
          }
        }
        // In Jetty 12, PathRequestContent implements Request.Content directly
        Request.Content requestContent =
            new PathRequestContent(mimeTypeFile, Path.of(file.getPath()));
        request.body(requestContent);
        postBody.append("<actual file content, not shown here>");
      } else {
        if (!hasContentTypeHeader && ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
          HttpFields headers = request.getHeaders();
          if (headers instanceof HttpFields.Mutable) {
            ((HttpFields.Mutable) headers).put(HTTPConstants.HEADER_CONTENT_TYPE,
                HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED);
          }
        }
        Charset contentCharset = buildCharsetOrDefault(contentEncoding, StandardCharsets.UTF_8);
        if (sampler.getSendParameterValuesAsPostBody()) {
          for (JMeterProperty jMeterProperty : sampler.getArguments()) {
            HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
            postBody.append(arg.getEncodedValue(contentCharset.name()));
          }
          // In Jetty 12, StringRequestContent implements Request.Content directly
          Request.Content requestContent =
              new StringRequestContent(contentTypeHeader, postBody.toString(),
                  contentCharset);
          request.body(requestContent);
        } else if (isMethodWithBody(sampler.getMethod())) {
          Fields fields = new Fields();
          for (JMeterProperty p : sampler.getArguments()) {
            HTTPArgument arg = (HTTPArgument) p.getObjectValue();
            String parameterName = arg.getName();
            if (!arg.isSkippable(parameterName)) {
              String parameterValue = arg.getValue();
              if (!arg.isAlwaysEncoded()) {
                // The FormRequestContent always urlencodes both name and value, in this case the
                // value is already encoded by the user so is needed to decode the value now, so
                // that when the httpclient encodes it, we end up with the same value as the user
                // had entered.
                parameterName = URLDecoder.decode(parameterName, contentCharset.name());
                parameterValue = URLDecoder.decode(parameterValue, contentCharset.name());
              }
              fields.add(parameterName, parameterValue);
            }
          }
          postBody.append(FormRequestContent.convert(fields));
          request.body(new FormRequestContent(fields, contentCharset));
        }
      }
    }
    result.setQueryString(postBody.toString());
  }

  private void initializeSentBytes(HTTPSampleResult result, Request request) {
    if (result.getSentBytes() > 0) {
      return;
    }
    long headerBytes = estimateRequestHeaderBytes(request);
    if (headerBytes > 0) {
      result.setSentBytes(headerBytes);
    }
  }

  private long estimateRequestHeaderBytes(Request request) {
    String headers = getSerializedRequestHeaders(request, false);
    long headersBytes = headers.isEmpty()
        ? 0
        : headers.getBytes(StandardCharsets.UTF_8).length + 1;

    String path = request.getURI().getRawPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    }
    String query = request.getURI().getRawQuery();
    if (query != null && !query.isEmpty()) {
      path = path + "?" + query;
    }

    String version = request.getVersion() != null
        ? request.getVersion().asString()
        : "HTTP/1.1";
    String requestLine = request.getMethod() + " " + path + " " + version + "\n";
    long requestLineBytes = requestLine.getBytes(StandardCharsets.UTF_8).length;

    return requestLineBytes + headersBytes;
  }

  private String getSerializedRequestHeaders(Request request, boolean refresh) {
    if (request == null) {
      return "";
    }
    Object cached = request.getAttributes().get(ATTR_REQUEST_HEADERS_SERIALIZED);
    if (!refresh && cached instanceof String) {
      return (String) cached;
    }
    String serialized = buildHeadersString(
        JmeterRequestHeadersSupport.headersForSampleResult(request));
    request.attribute(ATTR_REQUEST_HEADERS_SERIALIZED, serialized);
    return serialized;
  }

  private String extractMultipartBoundary(MultiPartRequestContent multipartEntityBuilder) {
    String contentType = multipartEntityBuilder.getContentType();
    String boundaryParam = contentType.substring(contentType.indexOf(" ") + 1);
    return boundaryParam.substring(boundaryParam.indexOf("=") + 1);
  }

  private Charset buildCharsetOrDefault(String contentEncoding, Charset defaultCharset) {
    return !contentEncoding.isEmpty() ? Charset.forName(contentEncoding) : defaultCharset;
  }

  private String buildArgumentPartRequestBody(HTTPArgument arg, Charset contentCharset,
                                              String contentEncoding, String boundary)
      throws UnsupportedEncodingException {
    String disposition = "name=\"" + arg.getEncodedName() + "\"";
    String contentType = arg.getContentType() + "; charset=" + contentCharset.name();
    String encoding = StringUtils.isNotBlank(contentEncoding) ? contentEncoding : "8bit";
    return buildPartBody(boundary, disposition, contentType, encoding,
        arg.getEncodedValue(contentCharset.name()));
  }

  private String buildPartBody(String boundary, String disposition, String contentType,
                               String encoding, String value) {
    return MULTI_PART_SEPARATOR + boundary + LINE_SEPARATOR +
        HttpFields.build()
            .add("Content-Disposition", "form-data; " + disposition)
            .add(HttpHeader.CONTENT_TYPE.toString(), contentType)
            .add("Content-Transfer-Encoding", encoding)
            .toString()
        + value + LINE_SEPARATOR;
  }

  private String buildFilePartRequestBody(HTTPFileArg file, String fileName, String boundary) {
    String disposition = "name=\"" + file.getParamName() + "\"; filename=\"" + fileName + "\"";
    return buildPartBody(boundary, disposition, file.getMimeType(), "binary",
        "<actual file content, not shown here>");
  }

  private String extractFileMimeType(boolean hasContentTypeHeader, HTTPFileArg file) {
    String ret = null;
    if (!hasContentTypeHeader) {
      if (file.getMimeType() != null && !file.getMimeType().isEmpty()) {
        ret = file.getMimeType();
      } else if (ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
        ret = HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED;
      }
    }
    return ret == null ? DEFAULT_FILE_MIME_TYPE : ret;
  }

  /**
   * Builds multipart/form-data body as bytes for Jetty 12.
   * In Jetty 12, MultiPartRequestContent API changed, so we build the body manually.
   *
   * @param sampler              The HTTP2 sampler with arguments and files
   * @param boundary             The multipart boundary (with -- prefix)
   * @param contentCharset       The charset for encoding
   * @param hasContentTypeHeader Whether content type header is already set
   * @return The complete multipart body as bytes
   * @throws IOException If there's an error reading files
   */
  private byte[] buildMultipartBodyBytes(HTTP2Sampler sampler, String boundary,
                                         Charset contentCharset, boolean hasContentTypeHeader)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    String newLine = LINE_SEPARATOR;
    String boundaryLine = MULTI_PART_SEPARATOR + boundary + newLine;

    // Add argument parts (form fields)
    for (JMeterProperty jMeterProperty : sampler.getArguments()) {
      HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
      String parameterName = arg.getName();
      if (!arg.isSkippable(parameterName)) {
        String argContentType = arg.getContentType();
        if (StringUtils.isBlank(argContentType)) {
          argContentType = "text/plain";
        }
        argContentType = argContentType + "; charset="
            + contentCharset.name().toLowerCase(Locale.ROOT);

        Mutable partHeaders = HttpFields.build()
            .add("Content-Disposition", "form-data; name=\"" + arg.getEncodedName() + "\"")
            .add(HttpHeader.CONTENT_TYPE, argContentType);

        output.write(boundaryLine.getBytes(StandardCharsets.US_ASCII));
        output.write(partHeaders.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(newLine.getBytes(StandardCharsets.US_ASCII));
        String argValue = arg.getEncodedValue(contentCharset.name());
        output.write(argValue.getBytes(contentCharset));
        output.write(newLine.getBytes(StandardCharsets.US_ASCII));
      }
    }

    // Add file parts
    for (HTTPFileArg file : sampler.getHTTPFiles()) {
      if (StringUtils.isBlank(file.getParamName())) {
        throw new IllegalStateException("Param name is blank");
      }
      String fileName = Paths.get(file.getPath()).getFileName().toString();
      String mimeTypeFile = extractFileMimeType(hasContentTypeHeader, file);

      // Build headers using HttpFields to match the format expected by tests
      // The test uses HttpFields.build().toString() which has a specific format
      Mutable partHeaders = HttpFields.build()
          .add("Content-Disposition",
              "form-data; name=\"" + file.getParamName() + "\"; filename=\"" + fileName + "\"")
          .add(HttpHeader.CONTENT_TYPE, mimeTypeFile);

      output.write(boundaryLine.getBytes(StandardCharsets.US_ASCII));
      output.write(partHeaders.toString().getBytes(StandardCharsets.US_ASCII));
      output.write(newLine.getBytes(StandardCharsets.US_ASCII));

      // Read and write file content
      try (InputStream fileStream = Files.newInputStream(Paths.get(file.getPath()))) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = fileStream.read(buffer)) != -1) {
          output.write(buffer, 0, bytesRead);
        }
      }
      output.write(newLine.getBytes(StandardCharsets.US_ASCII));
    }

    // Add final boundary
    String finalBoundary = MULTI_PART_SEPARATOR + boundary + MULTI_PART_SEPARATOR + newLine;
    output.write(finalBoundary.getBytes(StandardCharsets.US_ASCII));

    return output.toByteArray();
  }

  private boolean isMethodWithBody(String method) {
    return METHODS_WITH_BODY.contains(method);
  }

  private boolean isSupportedMethod(String method) {
    return SUPPORTED_METHODS.contains(method);
  }

  private String buildHeadersString(HttpFields headers) {
    if (headers == null) {
      return "";
    } else {
      String ret = HttpFields.build(headers).remove(HTTPConstants.HEADER_COOKIE).toString()
          .replace("\r\n", "\n");
      return ret.substring(0,
          ret.length() - 1); // removing final separator not included in jmeter headers
    }
  }

  private void setResultContentResponse(HTTPSampleResult result,
                                        ContentResponse contentResponse) throws IOException {
    if (LowLevelDebugLog.isEnabled()) {
      int headerCount = contentResponse.getHeaders() != null
          ? contentResponse.getHeaders().size()
          : 0;
      int contentLength = contentResponse.getContent() != null
          ? contentResponse.getContent().length
          : 0;
      debugToFile(String.format("resultContent: uri=%s status=%s headers=%d contentLength=%d",
          contentResponse.getRequest() != null ? contentResponse.getRequest().getURI() : "null",
          contentResponse.getStatus(), headerCount, contentLength));
    }
    String contentType = contentResponse.getHeaders() != null
        ? contentResponse.getHeaders().get(HTTPConstants.HEADER_CONTENT_TYPE)
        : null;
    if (contentType != null) {
      result.setContentType(contentType);
      result.setEncodingAndType(contentType);
    }

    // Decode compressed payloads when possible even if the request did not advertise
    // Accept-Encoding (some servers still compress, and JMeter should show decoded body).
    byte[] responseContent = maybeDecodeCompressedContent(contentResponse);
    // Avoid an extra stream->byte[] copy; content is already fully buffered.
    result.setResponseData(responseContent);

    if (result.getEndTime() == 0) {
      result.sampleEnd();
    } else {
      result.setEndTime(result.currentTimeInMillis());
    }

    result.setResponseCode(String.valueOf(contentResponse.getStatus()));
    String responseMessage = contentResponse.getReason() != null ? contentResponse.getReason()
        : HttpStatus.getMessage(contentResponse.getStatus());
    result.setResponseMessage(responseMessage);
    result.setSuccessful(
        contentResponse.getStatus() >= 200 && contentResponse.getStatus() <= 399);
    result.setResponseHeaders(extractResponseHeaders(contentResponse, responseMessage));
    if (result.isRedirect()) {
      result.setRedirectLocation(extractRedirectLocation(contentResponse));
    }

    if (contentResponse.getRequest() != null && contentResponse.getRequest().isFollowRedirects()) {
      result.setURL(contentResponse.getRequest().getURI().toURL());
    }

    HttpFields sampleResultHeaders =
        JmeterCompressionHeadersSupport.headersForSampleResult(contentResponse);
    long headerBytes =
        (long) result.getResponseHeaders().length()   // condensed length (without \r)
            + (long) sampleResultHeaders.asString().length() // Add \r for each header
            + 1L // Add \r for initial header
            + 2L; // final \r\n before data
    result.setHeadersSize((int) headerBytes);
  }

  private String extractResponseHeaders(ContentResponse contentResponse,
                                        String message) {
    HttpFields headers = JmeterCompressionHeadersSupport.headersForSampleResult(contentResponse);
    return contentResponse.getVersion() + " " + contentResponse.getStatus() + " " + message + "\n"
        + buildHeadersString(headers);
  }

  private String extractRedirectLocation(ContentResponse contentResponse) {
    String redirectLocation = contentResponse.getHeaders() != null
        ? contentResponse.getHeaders().get(HTTPConstants.HEADER_LOCATION)
        : null;
    if (redirectLocation == null) {
      throw new IllegalArgumentException("Missing location header in redirect");
    }
    return redirectLocation;
  }

  private void saveCookiesInCookieManager(ContentResponse response, URL url,
                                          CookieManager cookieManager) {
    if (cookieManager == null) {
      return;
    }
    for (HttpField field : response.getHeaders()) {
      if (field.is(HTTPConstants.HEADER_SET_COOKIE)) {
        String cookieHeader = field.getValue();
        if (cookieHeader != null) {
          cookieManager.addCookieFromHeader(cookieHeader, url);
        }
      }
    }
  }

  public void clearCookies() {
    // In Jetty 12, getCookieStore() was replaced by getHttpCookieStore()
    // removeAll() was replaced by clear()
    HttpCookieStore cookieStore = httpClient.getHttpCookieStore();
    if (cookieStore != null) {
      cookieStore.clear();
    }
  }

  public void clearAuthenticationResults() {
    httpClient.getAuthenticationStore().clearAuthenticationResults();
  }

  public String dump() {
    return httpClient.dump();
  }

  private static Path resolveDebugLogPath() {
    Path baseDir = Paths.get(System.getProperty("user.dir", "."));
    if (baseDir.endsWith("jmeter-http2-plugin")) {
      return baseDir.resolve("target").resolve("http2-debug.log");
    }
    return baseDir.resolve("jmeter-http2-plugin")
        .resolve("target")
        .resolve("http2-debug.log");
  }

  private static void debugToFile(String message) {
    if (!LowLevelDebugLog.isEnabled()) {
      return;
    }
    try {
      Path parent = DEBUG_LOG_PATH.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      String line = System.currentTimeMillis() + " " + message + System.lineSeparator();
      Files.write(DEBUG_LOG_PATH, line.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // Intentional: best-effort diagnostic logging.
    }
  }

  private void resetSamplerDataBeforeResultProcessing(HTTPSampleResult result) {
    if (result == null) {
      return;
    }
    // Avoid sampler-data duplication when JMeter/redirect/retry flows process the same result.
    result.setSamplerData("");
  }

  private byte[] maybeDecodeCompressedContent(ContentResponse contentResponse) {
    byte[] content = contentResponse != null ? contentResponse.getContent() : null;
    if (content == null || content.length == 0 || contentResponse == null
        || contentResponse.getHeaders() == null) {
      return content == null ? new byte[0] : content;
    }
    String contentEncoding = contentResponse.getHeaders().get(HttpHeader.CONTENT_ENCODING);
    if (contentEncoding == null || contentEncoding.trim().isEmpty()) {
      return content;
    }
    JmeterCompressionHeadersSupport.captureIfCompressed(
        contentResponse.getRequest(), contentResponse.getHeaders());
    String encodingToken = normalizeEncodingToken(contentEncoding);
    if (encodingToken.isEmpty()) {
      return content;
    }

    // Fast path (enabled by default): if request already advertised this encoding, Jetty decoders
    // should have handled it and re-decoding only adds CPU/alloc pressure.
    boolean skipRedundantManualDecode = Boolean.parseBoolean(
        System.getProperty(PROP_SKIP_REDUNDANT_MANUAL_DECODE, "true"));
    // deflate is excluded from this fast path: it is never registered as a per-request Jetty
    // decoder (see configureContentDecoders() above), so it must always go through decodeDeflate()
    // below, which is the only place that tries both deflate variants.
    if (skipRedundantManualDecode
        && !"deflate".equals(encodingToken)
        && requestAdvertisedEncoding(contentResponse.getRequest(), encodingToken)) {
      return content;
    }

    switch (encodingToken) {
      case "gzip":
      case "x-gzip":
        return decodeGzip(content, contentEncoding);
      case "deflate":
        return decodeDeflate(content, contentEncoding);
      case "br":
        return decodeBrotli(content, contentEncoding);
      case "zstd":
        return decodeZstd(content, contentEncoding);
      default:
        return content;
    }
  }

  private String normalizeEncodingToken(String headerValue) {
    if (headerValue == null) {
      return "";
    }
    String token = headerValue.split(",")[0].trim().toLowerCase(Locale.ROOT);
    int paramsIndex = token.indexOf(';');
    if (paramsIndex >= 0) {
      token = token.substring(0, paramsIndex).trim();
    }
    return token;
  }

  private boolean requestAdvertisedEncoding(Request request, String responseEncoding) {
    if (request == null || request.getHeaders() == null || responseEncoding == null
        || responseEncoding.isEmpty()) {
      return false;
    }
    String acceptEncoding = request.getHeaders().get(HttpHeader.ACCEPT_ENCODING);
    if (acceptEncoding == null || acceptEncoding.trim().isEmpty()) {
      return false;
    }
    for (String token : acceptEncoding.split(",")) {
      String normalized = normalizeEncodingToken(token);
      if (normalized.isEmpty()) {
        continue;
      }
      if (responseEncoding.equals(normalized)) {
        return true;
      }
      if (("gzip".equals(responseEncoding) || "x-gzip".equals(responseEncoding))
          && ("gzip".equals(normalized) || "x-gzip".equals(normalized))) {
        return true;
      }
    }
    return false;
  }

  private byte[] decodeGzip(byte[] content, String contentEncoding) {
    if (content.length < 2 || (content[0] & 0xFF) != 0x1F || (content[1] & 0xFF) != 0x8B) {
      return content;
    }
    try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(content));
         ByteArrayOutputStream output = new ByteArrayOutputStream(content.length)) {
      copy(input, output);
      return output.toByteArray();
    } catch (IOException e) {
      lowLevelDebug("Failed to decode gzip content ({}), keeping original bytes",
          contentEncoding, e);
      return content;
    }
  }

  /**
   * "Content-Encoding: deflate" is ambiguous in practice: the RFC implies zlib-wrapped deflate
   * (RFC 1950), but plenty of real servers send raw/headerless deflate (RFC 1951) under the same
   * header. Try zlib first, then raw, before giving up - matching HttpClient4's behavior.
   */
  private byte[] decodeDeflate(byte[] content, String contentEncoding) {
    byte[] zlibDecoded = tryInflateDeflate(content, false);
    if (zlibDecoded != null) {
      return zlibDecoded;
    }
    byte[] rawDecoded = tryInflateDeflate(content, true);
    if (rawDecoded != null) {
      return rawDecoded;
    }
    lowLevelDebug("Failed to decode deflate content ({}), keeping original bytes",
        contentEncoding);
    return content;
  }

  private byte[] tryInflateDeflate(byte[] content, boolean nowrap) {
    try (InflaterInputStream input = new InflaterInputStream(
        new ByteArrayInputStream(content), new Inflater(nowrap));
         ByteArrayOutputStream output = new ByteArrayOutputStream(content.length)) {
      copy(input, output);
      return output.toByteArray();
    } catch (IOException e) {
      return null;
    }
  }

  private byte[] decodeBrotli(byte[] content, String contentEncoding) {
    try (InputStream input = new BrotliInputStream(new ByteArrayInputStream(content));
         ByteArrayOutputStream output = new ByteArrayOutputStream(content.length)) {
      copy(input, output);
      return output.toByteArray();
    } catch (IOException e) {
      lowLevelDebug("Failed to decode brotli content ({}), keeping original bytes",
          contentEncoding, e);
      return content;
    }
  }

  private byte[] decodeZstd(byte[] content, String contentEncoding) {
    if (content.length < 4
        || (content[0] & 0xFF) != 0x28
        || (content[1] & 0xFF) != 0xB5
        || (content[2] & 0xFF) != 0x2F
        || (content[3] & 0xFF) != 0xFD) {
      return content;
    }
    try (InputStream input = new ZstdInputStream(new ByteArrayInputStream(content));
         ByteArrayOutputStream output = new ByteArrayOutputStream(content.length)) {
      copy(input, output);
      return output.toByteArray();
    } catch (IOException e) {
      lowLevelDebug("Failed to decode zstd content ({}), keeping original bytes",
          contentEncoding, e);
      return content;
    }
  }

  private void copy(InputStream input, ByteArrayOutputStream output) throws IOException {
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
  }
}

