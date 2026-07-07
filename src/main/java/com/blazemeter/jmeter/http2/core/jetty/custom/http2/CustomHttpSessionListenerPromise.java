package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import java.util.Map;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.transport.internal.HTTPSessionListenerPromise;

/**
 * Custom session listener that creates custom HTTP/2 connections.
 */
public class CustomHttpSessionListenerPromise extends HTTPSessionListenerPromise {
  public CustomHttpSessionListenerPromise(Map<String, Object> context) {
    super(context);
  }

  @Override
  protected Connection newConnection(Destination destination, Session session,
      HTTP2Connection connection) {
    return new CustomHttpConnectionOverHTTP2(destination, session, connection);
  }
}
