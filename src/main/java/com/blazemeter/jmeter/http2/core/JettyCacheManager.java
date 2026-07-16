package com.blazemeter.jmeter.http2.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.sampler.HTTPHC4Impl;
import org.apache.jmeter.protocol.http.sampler.HTTPHC4Impl.HttpDelete;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HttpWebdav;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;

public class JettyCacheManager {

  private final CacheManager cacheManager;

  private JettyCacheManager(CacheManager cacheManager) {
    this.cacheManager = cacheManager;
  }

  public static JettyCacheManager fromCacheManager(CacheManager cacheManager) {
    return cacheManager == null ? null : new JettyCacheManager(cacheManager);
  }

  public void setHeaders(URL url, Request request) throws URISyntaxException {
    HttpRequestBase apacheRequest = buildApacheRequest(url, request.getMethod());
    cacheManager.setHeaders(url, apacheRequest);
    setRequestHeaderFromApache(HTTPConstants.VARY, apacheRequest, request);
    setRequestHeaderFromApache(HTTPConstants.IF_MODIFIED_SINCE, apacheRequest, request);
    setRequestHeaderFromApache(HTTPConstants.IF_NONE_MATCH, apacheRequest, request);
  }

  private HttpRequestBase buildApacheRequest(URL url, String method) throws URISyntaxException {
    URI uri = url.toURI();
    switch (method) {
      case HTTPConstants.POST:
        return new HttpPost(uri);
      case HTTPConstants.GET:
        return new HttpGet(uri);
      case HTTPConstants.PUT:
        return new HttpPut(uri);
      case HTTPConstants.HEAD:
        return new HttpHead(uri);
      case HTTPConstants.TRACE:
        return new HttpTrace(uri);
      case HTTPConstants.OPTIONS:
        return new HttpOptions(uri);
      case HTTPConstants.DELETE:
        return new HttpDelete(uri);
      case HTTPConstants.PATCH:
        return new HttpPatch(uri);
      default:
        if (HttpWebdav.isWebdavMethod(method)) {
          return new HttpWebdav(method, uri);
        } else {
          throw new IllegalArgumentException(String.format("Unexpected method: '%s'", method));
        }
    }
  }

  private void setRequestHeaderFromApache(String headerName, HttpRequestBase apacheRequest,
      Request request) {
    Header header = apacheRequest.getFirstHeader(headerName);
    if (header != null) {
      HttpFields headers = request.getHeaders();
      if (headers instanceof HttpFields.Mutable) {
        ((HttpFields.Mutable) headers).put(headerName, header.getValue());
      }
    }
  }

  public boolean inCache(URL url, HttpFields headers) {
    Header[] apacheHeaders = headers.stream()
        .map(h -> new BasicHeader(h.getName(), h.getValue()))
        .toArray(Header[]::new);
    return cacheManager.inCache(url, apacheHeaders);
  }

  public HTTPSampleResult buildCachedSampleResult(HTTPSampleResult res) {
    // Match HttpClient4: HTTPAbstractImpl snapshots cache_manager.cached_resource_mode once.
    return CachedResourceResultBridge.forCachedResource(res);
  }

  private static final class CachedResourceResultBridge extends HTTPHC4Impl {
    private static final CachedResourceResultBridge INSTANCE =
        new CachedResourceResultBridge(new BridgeSampler());

    private CachedResourceResultBridge(HTTPSamplerBase sampler) {
      super(sampler);
    }

    private static HTTPSampleResult forCachedResource(HTTPSampleResult res) {
      return INSTANCE.updateSampleResultForResourceInCache(res);
    }
  }

  private static final class BridgeSampler extends HTTPSamplerBase {
    @Override
    protected HTTPSampleResult sample(URL url, String method, boolean areFollowingRedirect,
        int depth) {
      throw new UnsupportedOperationException("cache bridge");
    }
  }

  public void saveDetails(ContentResponse contentResponse, HTTPSampleResult result) {
    cacheManager.saveDetails(buildApacheResponse(contentResponse), result);
  }

  public HttpResponse buildApacheResponse(ContentResponse contentResponse) {
    HttpResponse httpResponse = new BasicHttpResponse(
        buildApacheVersion(contentResponse.getVersion()), contentResponse.getStatus(),
        contentResponse.getReason());
    setApacheResponseHeader(HTTPConstants.VARY, contentResponse, httpResponse);
    setApacheResponseHeader(HTTPConstants.LAST_MODIFIED, contentResponse, httpResponse);
    setApacheResponseHeader(HTTPConstants.EXPIRES, contentResponse, httpResponse);
    setApacheResponseHeader(HTTPConstants.ETAG, contentResponse, httpResponse);
    setApacheResponseHeader(HTTPConstants.CACHE_CONTROL, contentResponse, httpResponse);
    setApacheResponseHeader(HTTPConstants.DATE, contentResponse, httpResponse);
    return httpResponse;
  }

  private ProtocolVersion buildApacheVersion(HttpVersion version) {
    return new ProtocolVersion(version.name(), version.getVersion() / 10,
        version.getVersion() % 10);
  }

  private void setApacheResponseHeader(String headerName, ContentResponse response,
      HttpResponse apacheResponse) {
    apacheResponse.addHeader(headerName, response.getHeaders().get(headerName));
  }
}
