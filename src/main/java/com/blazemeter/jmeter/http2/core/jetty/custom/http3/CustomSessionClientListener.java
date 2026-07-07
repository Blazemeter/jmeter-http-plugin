package com.blazemeter.jmeter.http2.core.jetty.custom.http3;

import java.util.Map;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.client.transport.internal.SessionClientListener;

/**
 * Custom session listener that creates custom HTTP/3 connections.
 */
public class CustomSessionClientListener extends SessionClientListener {
  public CustomSessionClientListener(Map<String, Object> context) {
    super(context);
  }

  @Override
  protected Connection newConnection(Destination destination, HTTP3SessionClient session) {
    return new CustomHttpConnectionOverHTTP3(destination, session);
  }
}
