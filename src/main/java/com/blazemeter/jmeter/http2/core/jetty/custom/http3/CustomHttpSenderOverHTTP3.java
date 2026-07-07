package com.blazemeter.jmeter.http2.core.jetty.custom.http3;

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
import org.eclipse.jetty.http3.HTTP3Stream;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.client.transport.internal.HttpChannelOverHTTP3;
import org.eclipse.jetty.http3.client.transport.internal.HttpSenderOverHTTP3;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom HTTP/3 sender that filters HTTP/1.1-only headers before sending HEADERS.
 *
 * <p>NOTE: This class depends on Jetty internal APIs and mirrors core logic.
 * It is a PoC and may break if Jetty internals change.
 */
public class CustomHttpSenderOverHTTP3 extends HttpSenderOverHTTP3 {
  private static final Logger LOG = LoggerFactory.getLogger(CustomHttpSenderOverHTTP3.class);

  public CustomHttpSenderOverHTTP3(HttpChannelOverHTTP3 channel) {
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
      metaData = new MetaData.Request(request.getMethod(), uri, HttpVersion.HTTP_3, headers, -1,
          request.getTrailersSupplier());
    }

    HeadersFrame headersFrame;
    DataFrame dataFrame = null;
    HeadersFrame trailerFrame = null;

    if (isTunnel) {
      headersFrame = new HeadersFrame(metaData, false);
    } else {
      boolean hasContent = BufferUtil.hasContent(contentBuffer);
      if (hasContent) {
        headersFrame = new HeadersFrame(metaData, false);
        if (lastContent) {
          HttpFields trailers = retrieveTrailers(request);
          boolean hasTrailers = trailers != null;
          dataFrame = new DataFrame(contentBuffer, !hasTrailers);
          if (hasTrailers) {
            trailerFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
          }
        } else {
          dataFrame = new DataFrame(contentBuffer, false);
        }
      } else {
        if (lastContent) {
          HttpFields trailers = retrieveTrailers(request);
          boolean hasTrailers = trailers != null;
          headersFrame = new HeadersFrame(metaData, !hasTrailers);
          if (hasTrailers) {
            trailerFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
          }
        } else {
          headersFrame = new HeadersFrame(metaData, false);
        }
      }
    }

    HeadersFrame hf = headersFrame;
    DataFrame df = dataFrame;
    HeadersFrame tf = trailerFrame;

    HTTP3SessionClient session = getHttpChannel().getSession();
    session.newRequest(hf, getHttpChannel().getStreamListener(),
        Promise.Invocable.from(callback.getInvocationType(), s -> {
          onNewStream(s, request);

          if (LOG.isDebugEnabled()) {
            LOG.debug("HTTP3 request #{}/{}:{}{} {}{}{}",
                s.getId(), Integer.toHexString(s.getSession().hashCode()),
                System.lineSeparator(), metaData.getMethod(), metaData.getHttpURI(),
                System.lineSeparator(), metaData.getHttpFields());
          }

          if (df != null) {
            if (tf != null) {
              sendDataAndTrailer(s, df, lastContent, tf, callback);
            } else {
              sendData(s, df, lastContent, callback);
            }
          } else {
            if (tf != null) {
              sendTrailer(s, tf, callback);
            } else {
              callback.succeeded();
            }
          }
        }, callback::failed));
  }

  private void onNewStream(Stream stream, HttpRequest request) {
    long idleTimeout = request.getIdleTimeout();
    if (idleTimeout > 0) {
      ((HTTP3Stream) stream).setIdleTimeout(idleTimeout);
    }
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

  private void sendDataAndTrailer(Stream stream, DataFrame dataFrame, boolean lastContent,
      HeadersFrame trailersFrame, Callback callback) {
    sendData(stream, dataFrame, lastContent, Callback.from(callback.getInvocationType(),
        () -> sendTrailer(stream, trailersFrame, callback), callback::failed));
  }

  private void sendData(Stream stream, DataFrame dataFrame, boolean lastContent,
      Callback callback) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("HTTP3 request #{}/{}: {} content bytes{}",
          stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
          dataFrame.getByteBuffer().remaining(), lastContent ? " (last chunk)" : "");
    }
    stream.data(dataFrame, Promise.Invocable.from(callback.getInvocationType(),
        s -> callback.succeeded(), callback::failed));
  }

  private void sendTrailer(Stream stream, HeadersFrame trailerFrame, Callback callback) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("HTTP3 request #{}/{}: trailer{}{}",
          stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
          System.lineSeparator(), trailerFrame.getMetaData().getHttpFields());
    }
    stream.trailer(trailerFrame, Promise.Invocable.from(callback.getInvocationType(),
        s -> callback.succeeded(), callback::failed));
  }
}
