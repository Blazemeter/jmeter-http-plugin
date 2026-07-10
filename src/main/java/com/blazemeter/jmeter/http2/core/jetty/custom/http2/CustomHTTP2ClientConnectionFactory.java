package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.internal.HTTP2ClientSession;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.hpack.HpackContext;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

/**
 * Jetty 12.1.11 {@code HTTP2ClientConnectionFactory} variant that installs
 * {@link CustomParser} and {@link org.eclipse.jetty.http2.hpack.CustomHpackDecoder}
 * for Firefox-inspired soft header handling.
 */
public class CustomHTTP2ClientConnectionFactory implements ClientConnectionFactory {

  @Override
  public Connection newConnection(EndPoint endPoint, Map<String, Object> context) {
    HTTP2Client client = (HTTP2Client) context.get(HTTP2Client.CONTEXT_KEY);
    ByteBufferPool bufferPool = client.getByteBufferPool();
    Session.Listener listener =
        (Session.Listener) context.get(HTTP2Client.SESSION_LISTENER_CONTEXT_KEY);
    @SuppressWarnings("unchecked")
    Promise<Session> sessionPromise =
        (Promise<Session>) context.get(HTTP2Client.SESSION_PROMISE_CONTEXT_KEY);

    Generator generator = new Generator(bufferPool, client.isUseOutputDirectByteBuffers(),
        client.getMaxHeaderBlockFragment());
    generator.getHpackEncoder().setMaxHeaderListSize(client.getMaxRequestHeadersSize());

    FlowControlStrategy flowControl = client.getFlowControlStrategyFactory()
        .newFlowControlStrategy();

    CustomParser parser = new CustomParser(bufferPool, client.getMaxResponseHeadersSize());
    parser.setMaxSettingsKeys(client.getMaxSettingsKeys());

    HTTP2ClientSession session = new HTTP2ClientSession(client.getScheduler(), endPoint, parser,
        generator, listener, flowControl);
    session.setMaxLocalStreams(client.getMaxLocalStreams());
    session.setMaxRemoteStreams(client.getMaxConcurrentPushedStreams());
    session.setMaxEncoderTableCapacity(client.getMaxEncoderTableCapacity());
    long streamIdleTimeout = client.getStreamIdleTimeout();
    if (streamIdleTimeout > 0) {
      session.setStreamIdleTimeout(streamIdleTimeout);
    }

    HTTP2ClientConnection connection =
        new HTTP2ClientConnection(client, endPoint, session, sessionPromise, listener);
    context.put(HTTP2Connection.class.getName(), connection);
    try {
      java.lang.reflect.Method getSessionContainer =
          HTTP2Client.class.getDeclaredMethod("getSessionContainer");
      getSessionContainer.setAccessible(true);
      connection.addEventListener(
          (java.util.EventListener) getSessionContainer.invoke(client));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unable to access HTTP2Client session container", e);
    }
    client.getEventListeners().forEach(session::addEventListener);
    parser.init(connection);

    return customize(connection, context);
  }

  private static class HTTP2ClientConnection extends HTTP2Connection implements Callback {
    private final HTTP2Client client;
    private final Promise<Session> promise;
    private final Session.Listener listener;

    private HTTP2ClientConnection(HTTP2Client client, EndPoint endpoint,
        HTTP2ClientSession session, Promise<Session> sessionPromise,
        Session.Listener listener) {
      super(client.getByteBufferPool(), client.getExecutor(), endpoint, session,
          client.getInputBufferSize(), -1);
      this.client = client;
      this.promise = sessionPromise;
      this.listener = listener;
      setUseInputDirectByteBuffers(client.isUseInputDirectByteBuffers());
      setUseOutputDirectByteBuffers(client.isUseOutputDirectByteBuffers());
    }

    @Override
    public void onOpen() {
      HTTP2Session session = getSession();
      session.notifyLifeCycleOpen();

      Map<Integer, Integer> settings = listener.onPreface(session);
      settings = settings == null ? new HashMap<>() : new HashMap<>(settings);

      settings.compute(SettingsFrame.HEADER_TABLE_SIZE, (k, v) -> {
        if (v == null) {
          v = client.getMaxDecoderTableCapacity();
          if (v == HpackContext.DEFAULT_MAX_TABLE_CAPACITY) {
            v = null;
          }
        }
        return v;
      });
      settings.computeIfAbsent(SettingsFrame.MAX_CONCURRENT_STREAMS,
          k -> client.getMaxConcurrentPushedStreams());
      settings.compute(SettingsFrame.INITIAL_WINDOW_SIZE, (k, v) -> {
        if (v == null) {
          v = client.getInitialStreamRecvWindow();
          if (v == FlowControlStrategy.DEFAULT_WINDOW_SIZE) {
            v = null;
          }
        }
        return v;
      });
      settings.compute(SettingsFrame.MAX_FRAME_SIZE, (k, v) -> {
        if (v == null) {
          v = client.getMaxFrameSize();
          if (v == Frame.DEFAULT_MAX_SIZE) {
            v = null;
          }
        }
        return v;
      });
      settings.compute(SettingsFrame.MAX_HEADER_LIST_SIZE, (k, v) -> {
        if (v == null) {
          v = client.getMaxResponseHeadersSize();
          if (v <= 0) {
            v = null;
          }
        }
        return v;
      });

      PrefaceFrame prefaceFrame = new PrefaceFrame();
      SettingsFrame settingsFrame = new SettingsFrame(settings, false);

      int windowDelta =
          client.getInitialSessionRecvWindow() - FlowControlStrategy.DEFAULT_WINDOW_SIZE;
      session.updateRecvWindow(windowDelta);
      if (windowDelta > 0) {
        session.frames(null,
            List.of(prefaceFrame, settingsFrame, new WindowUpdateFrame(0, windowDelta)), this);
      } else {
        session.frames(null, List.of(prefaceFrame, settingsFrame), this);
      }
    }

    @Override
    public void succeeded() {
      super.onOpen();
      promise.succeeded(getSession());
      produce();
    }

    @Override
    public void failed(Throwable ex) {
      close();
      promise.failed(ex);
    }
  }
}
