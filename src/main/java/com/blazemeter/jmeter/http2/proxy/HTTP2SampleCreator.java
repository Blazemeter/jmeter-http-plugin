package com.blazemeter.jmeter.http2.proxy;

import static com.blazemeter.jmeter.http2.core.LowLevelDebugLog.lowLevelDebug;

import com.blazemeter.jmeter.http2.sampler.BlazeMeterHttpSamplerFactory;
import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.jmeter.protocol.http.proxy.AbstractSamplerCreator;
import org.apache.jmeter.protocol.http.proxy.DefaultSamplerCreator;
import org.apache.jmeter.protocol.http.proxy.HttpRequestHdr;
import org.apache.jmeter.protocol.http.proxy.SamplerCreator;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proxy recorder; {@link #PROXY_ENABLED} enables BlazeMeter HTTP/HTTP2 samplers in the proxy.
 *
 * <p>{@code true} → BlazeMeter HTTP recording; {@code false} → stock {@code HTTPSampler} path
 * (no {@code HTTP2Sampler} from the recorder). Default {@link #PROXY_ENABLED_DEFAULT}.
 */
public class HTTP2SampleCreator extends AbstractSamplerCreator {

  /**
   * Default when {@link #PROXY_ENABLED} is unset or blank: BlazeMeter HTTP in the proxy
   * ({@code true}); set the property to {@code false} for stock HTTP.
   */
  public static final boolean PROXY_ENABLED_DEFAULT = true;

  /** When {@code true}, proxy recording uses BlazeMeter HTTP; when {@code false}, stock HTTP. */
  public static final String PROXY_ENABLED = "HTTP2Sampler.proxy_enabled"; // $NON-NLS-1$

  /** Logger. */
  private static final Logger LOG = LoggerFactory.getLogger(HTTP2SampleCreator.class);

  /** {@link DefaultSamplerCreator} for shared populate logic. */
  private static final SamplerCreator DEFAULT_SAMPLER_CREATOR =
      new DefaultSamplerCreator();

  /** Snapshot of {@link #PROXY_ENABLED} at creator construction. */
  private final boolean blazeMeterProxyRecorder =
      BzmHttpPluginProperties.getPropDefault(PROXY_ENABLED, PROXY_ENABLED_DEFAULT);

  /**
   * Whether BlazeMeter HTTP is active for proxy recording.
   *
   * @return true when {@link #PROXY_ENABLED} resolves to true
   */
  public static boolean isBlazeMeterProxyRecorderActive() {
    return BzmHttpPluginProperties.getPropDefault(
        PROXY_ENABLED, PROXY_ENABLED_DEFAULT);
  }

  @Override
  public String[] getManagedContentTypes() {
    if (!blazeMeterProxyRecorder) {
      LOG.info(
          "{} raw={} blazeMeterHttpInProxy={} (default if unset: {}); "
              + "HTTP(S) Test Script Recorder uses the standard HTTP sampler.",
          PROXY_ENABLED,
          BzmHttpPluginProperties.resolveRaw(PROXY_ENABLED),
          blazeMeterProxyRecorder,
          PROXY_ENABLED_DEFAULT);
      LOG.info(
          "To record with BlazeMeter HTTP: set {}=true (default is {}), or Tools → "
              + "BlazeMeter HTTP → Enable Recording with HTTP(S) Test Script Recorder "
              + "(restart when prompted).",
          PROXY_ENABLED,
          PROXY_ENABLED_DEFAULT);
      return ArrayUtils.EMPTY_STRING_ARRAY;
    }
    lowLevelDebug("getManagedContentTypes()");
    String[] contentTypes = new String[] {
        "text/plain", "text/html", "text/xml", "application/xhtml+xml", "application/octet-stream",
        "application/x-www-form-urlencoded",
        "text/css", "text/javascript",
        "text/csv", "application/json", "application/ld+json",
        "application/xml", "application/atom+xml",
        "application/gzip", "application/zip", "application/x-7z-compressed", "application/x-tar",
        "image/gif", "image/bmp", "image/jpeg", "image/pn", "image/avif", "audio/aac",
        "image/svg+xml",
        "font/ttf", "font/woff", "font/woff2", "font/otf",
        null};
    String[] charsets = new String[] {"UTF-8"};
    // Create a list with all default content types and the combinations with different charsets
    ArrayList<String> contentTypesList = new ArrayList<>();
    contentTypesList.addAll(Arrays.asList(contentTypes));
    for (String contentType : contentTypes) {
      for (String charset : charsets) {
        // With space
        contentTypesList.add(contentType + "; charset=" + charset);
        // Without space
        contentTypesList.add(contentType + ";charset=" + charset);
      }
    }
    lowLevelDebug("managed content types: {}", contentTypesList);
    return contentTypesList.toArray(new String[0]);
  }

  @Override
  public HTTPSamplerBase createSampler(HttpRequestHdr httpRequestHdr, Map<String, String> map,
                                       Map<String, String> map1) {
    lowLevelDebug("createSampler()");

    lowLevelDebug("url: {}", httpRequestHdr.getUrl());

    return BlazeMeterHttpSamplerFactory.newSamplerForProxyRecording();
  }

  @Override
  public void populateSampler(HTTPSamplerBase httpSamplerBase, HttpRequestHdr httpRequestHdr,
                              Map<String, String> map, Map<String, String> map1) throws Exception {
    lowLevelDebug("populateSampler()");
    // GUI/test-element classes match BlazeMeterHttpSamplerFactory (shared with Tools migration).
    lowLevelDebug("url: {}", httpRequestHdr.getUrl());
    lowLevelDebug("raw post data: {}", httpRequestHdr.getRawPostData());

    // In Jetty 12, Brotli compression is now supported
    // We no longer need to remove "br" from Accept-Encoding header
    // The HTTP2JettyClient will handle Brotli decoding automatically
    // when the server responds with Content-Encoding: br

    DEFAULT_SAMPLER_CREATOR.populateSampler(httpSamplerBase, httpRequestHdr, map, map1);

  }

}
