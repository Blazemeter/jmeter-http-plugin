package com.blazemeter.jmeter.http2.core;

import java.nio.file.Paths;
import java.util.Arrays;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * HTTPS/HTTP2 server that returns a single response header whose uncompressed HPACK size is
 * {@value #PRODUCTION_HEADER_SIZE} bytes, reproducing production's {@code Header size 8692 > 8192}.
 */
final class LargeResponseHeaderHttpsServer {

  static final int HEADER_LIST_LIMIT = 8192;
  static final int PRODUCTION_HEADER_SIZE = 8692;
  static final String LARGE_HEADER_NAME = "X-Large-Response-Header";
  static final String PRODUCTION_ERROR_MESSAGE = "Header size 8692 > 8192";

  private static final int SERVER_HEADER_LIMIT = 128 * 1024;

  private final Server server;

  private LargeResponseHeaderHttpsServer(Server server) {
    this.server = server;
  }

  static LargeResponseHeaderHttpsServer start() throws Exception {
    Server server = new Server();
    HttpConfiguration httpsConfig = new HttpConfiguration();
    httpsConfig.setSendServerVersion(false);
    httpsConfig.setSendDateHeader(false);
    httpsConfig.addCustomizer(new SecureRequestCustomizer());
    httpsConfig.setResponseHeaderSize(SERVER_HEADER_LIMIT);
    httpsConfig.setMaxResponseHeaderSize(SERVER_HEADER_LIMIT);

    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStorePath(resolveTestKeyStorePath());
    sslContextFactory.setKeyStorePassword(ServerBuilder.KEYSTORE_PASSWORD);

    HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(httpsConfig);
    HttpConfiguration httpConfig = new HttpConfiguration(httpsConfig);
    HttpConnectionFactory http11 = new HttpConnectionFactory(httpConfig);

    ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
    alpn.setDefaultProtocol(http11.getProtocol());

    ServerConnector connector = new ServerConnector(server,
        new SslConnectionFactory(sslContextFactory, alpn.getProtocol()),
        alpn,
        http11,
        http2);
    connector.setPort(0);
    server.addConnector(connector);
    server.setHandler(new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) {
        response.setStatus(200);
        response.getHeaders().add(largeResponseHeader());
        Content.Sink.write(response, true, "ok", callback);
        return true;
      }
    });
    server.start();
    return new LargeResponseHeaderHttpsServer(server);
  }

  int getPort() {
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  void stop() throws Exception {
    server.stop();
  }

  static HttpField largeResponseHeader() {
    int valueLength = PRODUCTION_HEADER_SIZE - 32 - LARGE_HEADER_NAME.length();
    char[] padding = new char[valueLength];
    Arrays.fill(padding, 'x');
    return new HttpField(LARGE_HEADER_NAME, new String(padding));
  }

  private static String resolveTestKeyStorePath() {
    try {
      return Paths.get(LargeResponseHeaderHttpsServer.class.getResource("keystore.p12").toURI())
          .toAbsolutePath()
          .normalize()
          .toString();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to resolve test keystore", e);
    }
  }
}
