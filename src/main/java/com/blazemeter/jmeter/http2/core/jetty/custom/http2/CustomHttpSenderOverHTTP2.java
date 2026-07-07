package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.transport.internal.HttpChannelOverHTTP2;
import org.eclipse.jetty.http2.client.transport.internal.HttpSenderOverHTTP2;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.URIUtil;

/**
 * Custom HTTP/2 sender that filters HTTP/1.1-only headers before sending HEADERS.
 *
 * <p>NOTE: This class depends on Jetty internal APIs and mirrors core logic.
 * It is a PoC and may break if Jetty internals change.
 */
public class CustomHttpSenderOverHTTP2 extends HttpSenderOverHTTP2 {
  public CustomHttpSenderOverHTTP2(HttpChannelOverHTTP2 channel) {
    super(channel);
  }

  @Override
  protected void sendHeaders(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent,
      Callback callback) {
    HttpRequest request = exchange.getRequest();
    boolean isTunnel = HttpMethod.CONNECT.is(request.getMethod());
    HttpFields headers = filterHeaders(request);
    MetaData.Request metaData;
    if (isTunnel) {
      String upgradeProtocol =
          (String) request.getAttributes().get(HttpUpgrader.PROTOCOL_ATTRIBUTE);
      if (upgradeProtocol == null) {
        metaData =
            new MetaData.ConnectRequest((String) null, new HostPortHttpField(request.getPath()),
                null, headers, null);
      } else {
        HostPortHttpField authority = new HostPortHttpField(request.getHost(), request.getPort());
        String pathQuery = URIUtil.addPathQuery(request.getPath(), request.getQuery());
        metaData = new MetaData.ConnectRequest(request.getScheme(), authority, pathQuery, headers,
            upgradeProtocol);
      }
    } else {
      String path = relativize(request.getPath());
      HttpURI uri = HttpURI.from(request.getScheme(), request.getHost(), request.getPort(), path,
          request.getQuery(), null);
      metaData = new MetaData.Request(request.getMethod(), uri, HttpVersion.HTTP_2, headers, -1,
          request.getTrailersSupplier());
    }

    HeadersFrame headersFrame;
    DataFrame dataFrame = null;
    HeadersFrame trailersFrame = null;

    if (isTunnel) {
      headersFrame = new HeadersFrame(metaData, null, false);
    } else {
      boolean hasContent = BufferUtil.hasContent(contentBuffer);
      if (hasContent) {
        headersFrame = new HeadersFrame(metaData, null, false);
        if (lastContent) {
          HttpFields trailers = retrieveTrailers(request);
          boolean hasTrailers = trailers != null;
          dataFrame = new DataFrame(contentBuffer, !hasTrailers);
          if (hasTrailers) {
            trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_2, trailers), null,
                true);
          }
        } else {
          dataFrame = new DataFrame(contentBuffer, false);
        }
      } else {
        if (lastContent) {
          HttpFields trailers = retrieveTrailers(request);
          boolean hasTrailers = trailers != null;
          headersFrame = new HeadersFrame(metaData, null, !hasTrailers);
          if (hasTrailers) {
            trailersFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_2, trailers), null,
                true);
          }
        } else {
          headersFrame = new HeadersFrame(metaData, null, false);
        }
      }
    }

    HttpChannelOverHTTP2 channel = getHttpChannel();
    HTTP2Stream.FrameList frameList = new HTTP2Stream.FrameList(headersFrame, dataFrame,
        trailersFrame);
    ((HTTP2Session) channel.getSession()).newStream(frameList,
        new CustomHeadersPromise(request, callback), channel.getStreamListener());
  }

  private HttpFields filterHeaders(HttpRequest request) {
    HttpFields.Mutable filtered = HttpFields.build(request.getHeaders());
    filtered.remove(HttpHeader.HOST);
    filtered.remove(HttpHeader.CONNECTION);
    filtered.remove(HttpHeader.KEEP_ALIVE);
    filtered.remove(HttpHeader.PROXY_CONNECTION);
    filtered.remove(HttpHeader.TRANSFER_ENCODING);
    filtered.remove(HttpHeader.UPGRADE);

    String te = filtered.get(HttpHeader.TE);
    if (te != null && !"trailers".equalsIgnoreCase(te)) {
      filtered.remove(HttpHeader.TE);
    }

    return filtered;
  }

  private HttpFields retrieveTrailers(HttpRequest request) {
    Supplier<HttpFields> trailerSupplier = request.getTrailersSupplier();
    HttpFields trailers = trailerSupplier == null ? null : trailerSupplier.get();
    return trailers == null || trailers.size() == 0 ? null : trailers;
  }

  private static class CustomHeadersPromise implements Promise<Stream> {
    private final HttpRequest request;
    private final Callback callback;

    private CustomHeadersPromise(HttpRequest request, Callback callback) {
      this.request = request;
      this.callback = callback;
    }

    @Override
    public void succeeded(Stream stream) {
      long idleTimeout = request.getIdleTimeout();
      if (idleTimeout >= 0) {
        stream.setIdleTimeout(idleTimeout);
      }
      callback.succeeded();
    }

    @Override
    public void failed(Throwable x) {
      callback.failed(x);
    }
  }
}
