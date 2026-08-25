package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.transport.internal.HTTPSessionListenerPromise;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.util.Callback;

/**
 * Custom session listener that creates custom HTTP/2 connections, and reports the origins that
 * turn out not to speak HTTP/2 at all.
 *
 * <p>A peer that never agreed to {@code h2} - because the TLS handshake selected no ALPN protocol,
 * which a TLS-inspecting proxy produces and RFC 7301 permits - still gets the HTTP/2 preface, since
 * Jetty falls back to the first configured protocol when ALPN negotiated nothing. It answers with
 * HTTP/1.1 bytes, the parser rejects them, and the session dies before the server preface ever
 * arrives.
 *
 * <p>That last part is the discriminator, and Jetty already relies on it: its own
 * {@code failConnectionPromise} only fires while the connection reference is still unset, which is
 * exactly "the server preface never arrived". {@link #newConnection} is called when it does
 * arrive, so tracking that separates a peer that cannot speak HTTP/2 from a healthy session being
 * closed - a distinction that matters, because marking an origin on a normal GOAWAY would break
 * reconnection.
 */
public class CustomHttpSessionListenerPromise extends HTTPSessionListenerPromise {

  private final Map<String, Object> context;
  private final Consumer<URI> onHttp2Rejected;

  private volatile boolean serverPrefaceSeen;

  public CustomHttpSessionListenerPromise(Map<String, Object> context) {
    this(context, null);
  }

  public CustomHttpSessionListenerPromise(Map<String, Object> context,
                                          Consumer<URI> onHttp2Rejected) {
    super(context);
    this.context = context;
    this.onHttp2Rejected = onHttp2Rejected;
  }

  @Override
  protected Connection newConnection(Destination destination, Session session,
      HTTP2Connection connection) {
    serverPrefaceSeen = true;
    return new CustomHttpConnectionOverHTTP2(destination, session, connection);
  }

  /**
   * Where a peer that does not speak HTTP/2 surfaces: the parser rejects the answer to the preface
   * and fails the session. Verified against a TLS server that selects no ALPN protocol, which
   * reports {@code frame_size_error/invalid_frame_length} here with no server preface seen.
   */
  @Override
  public void onFailure(Session session, Throwable failure, Callback callback) {
    reportHttp2RejectedIfPrefaceNeverArrived();
    super.onFailure(session, failure, callback);
  }

  @Override
  public void onClose(Session session, GoAwayFrame frame, Callback callback) {
    reportHttp2RejectedIfPrefaceNeverArrived();
    super.onClose(session, frame, callback);
  }

  private void reportHttp2RejectedIfPrefaceNeverArrived() {
    if (serverPrefaceSeen || onHttp2Rejected == null) {
      return;
    }
    Destination destination = (Destination) context.get(Destination.CONTEXT_KEY);
    if (destination == null) {
      return;
    }
    onHttp2Rejected.accept(URI.create(destination.getOrigin().asString()));
  }
}
