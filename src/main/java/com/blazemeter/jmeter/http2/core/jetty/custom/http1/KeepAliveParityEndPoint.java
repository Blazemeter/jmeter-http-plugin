package com.blazemeter.jmeter.http2.core.jetty.custom.http1;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

/**
 * Patches the HTTP request line on the wire from {@code HTTP/1.0} to {@code HTTP/1.1}.
 *
 * <p>Jetty's {@code HttpGenerator} strips explicit {@code Connection: keep-alive} for HTTP/1.1
 * but keeps it for HTTP/1.0. The custom HTTP/1 sender temporarily uses HTTP/1.0 metadata and
 * this endpoint rewrites only the request line so JMeter mirror assertions still see HTTP/1.1.
 */
public class KeepAliveParityEndPoint implements EndPoint, EndPoint.Wrapper {

  private final EndPoint delegate;
  private volatile boolean patchRequestLineToHttp11;

  public KeepAliveParityEndPoint(EndPoint delegate) {
    this.delegate = delegate;
  }

  public void enableRequestLinePatch() {
    patchRequestLineToHttp11 = true;
  }

  public void disableRequestLinePatch() {
    patchRequestLineToHttp11 = false;
  }

  @Override
  public EndPoint unwrap() {
    return delegate;
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return delegate.getLocalSocketAddress();
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return delegate.getRemoteSocketAddress();
  }

  @Override
  public int fill(ByteBuffer buffer) throws IOException {
    return delegate.fill(buffer);
  }

  @Override
  public SocketAddress receive(ByteBuffer buffer) throws IOException {
    return delegate.receive(buffer);
  }

  @Override
  public boolean flush(ByteBuffer... buffers) throws IOException {
    return delegate.flush(buffers);
  }

  @Override
  public boolean send(SocketAddress address, ByteBuffer... buffers) throws IOException {
    return delegate.send(address, buffers);
  }

  @Override
  public boolean isSecure() {
    return delegate.isSecure();
  }

  @Override
  public SslSessionData getSslSessionData() {
    return delegate.getSslSessionData();
  }

  @Override
  public boolean isOpen() {
    return delegate.isOpen();
  }

  @Override
  public long getCreatedTimeStamp() {
    return delegate.getCreatedTimeStamp();
  }

  @Override
  public void shutdownOutput() {
    delegate.shutdownOutput();
  }

  @Override
  public boolean isOutputShutdown() {
    return delegate.isOutputShutdown();
  }

  @Override
  public boolean isInputShutdown() {
    return delegate.isInputShutdown();
  }

  @Override
  public void close(Throwable cause) {
    delegate.close(cause);
  }

  @Override
  public Object getTransport() {
    return delegate.getTransport();
  }

  @Override
  public long getIdleTimeout() {
    return delegate.getIdleTimeout();
  }

  @Override
  public void setIdleTimeout(long idleTimeout) {
    delegate.setIdleTimeout(idleTimeout);
  }

  @Override
  public void fillInterested(Callback callback) throws ReadPendingException {
    delegate.fillInterested(callback);
  }

  @Override
  public boolean tryFillInterested(Callback callback) {
    return delegate.tryFillInterested(callback);
  }

  @Override
  public boolean isFillInterested() {
    return delegate.isFillInterested();
  }

  @Override
  public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException {
    delegate.write(callback, maybePatch(buffers));
  }

  @Override
  public void write(Callback callback, SocketAddress address, ByteBuffer... buffers)
      throws WritePendingException {
    delegate.write(callback, address, maybePatch(buffers));
  }

  @Override
  public void write(boolean last, ByteBuffer buffer, Callback callback) {
    delegate.write(last, maybePatch(buffer), callback);
  }

  @Override
  public Callback cancelWrite(Throwable cause) {
    return delegate.cancelWrite(cause);
  }

  @Override
  public Connection getConnection() {
    return delegate.getConnection();
  }

  @Override
  public void setConnection(Connection connection) {
    delegate.setConnection(connection);
  }

  @Override
  public void onOpen() {
    delegate.onOpen();
  }

  @Override
  public void onClose(Throwable cause) {
    delegate.onClose(cause);
  }

  @Override
  public void upgrade(Connection newConnection) {
    delegate.upgrade(newConnection);
  }

  private ByteBuffer[] maybePatch(ByteBuffer... buffers) {
    if (!patchRequestLineToHttp11 || buffers == null) {
      return buffers;
    }
    ByteBuffer[] patched = new ByteBuffer[buffers.length];
    for (int i = 0; i < buffers.length; i++) {
      patched[i] = maybePatch(buffers[i]);
    }
    return patched;
  }

  private ByteBuffer maybePatch(ByteBuffer buffer) {
    if (!patchRequestLineToHttp11 || buffer == null) {
      return buffer;
    }
    patchRequestLineToHttp11(buffer);
    return buffer;
  }

  static void patchRequestLineToHttp11(ByteBuffer buffer) {
    int start = buffer.position();
    int end = buffer.limit();
    for (int i = start; i <= end - 8; i++) {
      if (buffer.get(i) == 'H'
          && buffer.get(i + 1) == 'T'
          && buffer.get(i + 2) == 'T'
          && buffer.get(i + 3) == 'P'
          && buffer.get(i + 4) == '/'
          && buffer.get(i + 5) == '1'
          && buffer.get(i + 6) == '.'
          && buffer.get(i + 7) == '0') {
        buffer.put(i + 7, (byte) '1');
        return;
      }
      if (buffer.get(i) == '\n') {
        return;
      }
    }
  }
}
