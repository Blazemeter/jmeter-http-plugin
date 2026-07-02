package com.blazemeter.jmeter.http2.core;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.eclipse.jetty.compression.brotli.BrotliCompression;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import jodd.net.MimeTypes;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class ServerBuilder {

  public static final String HOST_NAME = "localhost";
  public static final int SERVER_PORT = 6666;
  public static final String SERVER_RESPONSE = "Hello World!";
  public static final String SERVER_IMAGE = "/test/image.png";
  public static final String SERVER_IMAGE_0 = "/test/image-0.png";
  public static final String SERVER_IMAGE_1 = "/test/image-1.png";
  public static final String SERVER_IMAGE_2 = "/test/image-2.png";
  public static final String SERVER_IMAGE_3 = "/test/image-3.png";
  public static final String SERVER_IMAGE_4 = "/test/image-4.png";
  public static final String SERVER_PATH = "/test";
  public static final String SERVER_PATH_SET_COOKIES = "/test/set-cookies";
  public static final String SERVER_PATH_USE_COOKIES = "/test/use-cookies";
  public static final String RESPONSE_DATA_COOKIES = "testCookie=test";
  public static final String RESPONSE_DATA_COOKIES2 = "testCookie2=test";
  public static final String SERVER_PATH_200 = "/test/200";
  public static final String SERVER_PATH_SLOW = "/test/slow";
  public static final String SERVER_PATH_200_GZIP = "/test/gzip";
  public static final String SERVER_PATH_200_DEFLATE = "/test/deflate";
  public static final String SERVER_PATH_200_BROTLI = "/test/brotli";
  public static final String SERVER_PATH_200_ZSTD = "/test/zstd";
  public static final String SERVER_PATH_200_EMBEDDED = "/test/embedded";
  /** HTML with several same-origin images to stress concurrent embedded downloads. */
  public static final String SERVER_PATH_200_EMBEDDED_MANY = "/test/embedded-many";
  /**
   * HTML whose embedded asset points at another HTTPS origin ({@code https://localhost:{port}/test/image.png}).
   * Requires {@link #withCrossOriginEmbeddedAssetPort(int)} when building the server.
   */
  public static final String SERVER_PATH_200_EMBEDDED_CROSS_ORIGIN = "/test/embedded-cross-origin";
  public static final String SERVER_PATH_200_FILE_SENT = "/test/file";
  public static final String SERVER_PATH_BIG_RESPONSE = "/test/big-response";
  public static final String SERVER_PATH_400 = "/test/400";
  public static final String SERVER_PATH_302 = "/test/302";
  public static final String SERVER_PATH_200_WITH_BODY = "/test/body";
  public static final String SERVER_PATH_JSON_ONLY = "/test/json-only";
  public static final String SERVER_PATH_DELETE_DATA = "/test/delete";
  public static final String BASIC_HTML_TEMPLATE = "<!DOCTYPE html><html><head><title>Page "
      + "Title</title></head><body><div><img src='image.png'></div></body></html>";
  public static final String MULTI_IMAGE_HTML_TEMPLATE = "<!DOCTYPE html><html><body>"
      + "<img src='image-0.png'/><img src='image-1.png'/><img src='image-2.png'/>"
      + "<img src='image-3.png'/><img src='image-4.png'/>"
      + "</body></html>";
  public static final byte[] BINARY_RESPONSE_BODY = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
  private static final byte[] GZIP_RESPONSE_BODY = buildGzipResponseBody();
  private static final byte[] DEFLATE_RESPONSE_BODY = buildDeflateResponseBody();
  private static final AtomicInteger GZIP_REQUEST_COUNT = new AtomicInteger();
  public static final String AUTH_USERNAME = "username";
  public static final String AUTH_PASSWORD = "password";
  public static final String AUTH_REALM = "realm";
  public static final String KEYSTORE_PASSWORD = "storepwd";
  public static final int BIG_BUFFER_SIZE = 4 * 1024 * 1024;
  private static final ByteBufferPool COMPRESSION_BUFFER_POOL = new ArrayByteBufferPool();

  private boolean ALPN;
  private final TeardownableServer server = new TeardownableServer();
  private final HttpConfiguration httpsConfig = new HttpConfiguration();
  private boolean withSSL;
  private String serverKeyStorePathOverride;
  private String serverKeyStoreTypeOverride;
  private HTTP2ServerConnectionFactory http2ConnectionFactory;
  private HttpConnectionFactory http1ConnectionFactory;
  private HTTP2CServerConnectionFactory http2cConnectionFactory;
  private boolean clientAuth;
  private boolean isBasicAuth;
  private boolean isDigestAuth;
  /** Optional port for {@link #SERVER_PATH_200_EMBEDDED_CROSS_ORIGIN}; {@code -1} if unset. */
  private int crossOriginEmbeddedAssetPort = -1;

  public ServerBuilder() {
    httpsConfig.addCustomizer(new SecureRequestCustomizer());
  }

  public ServerBuilder withHTTP1() {
    http1ConnectionFactory = new HttpConnectionFactory(httpsConfig);
    return this;
  }

  public ServerBuilder withHTTP2C() {
    http2cConnectionFactory = new HTTP2CServerConnectionFactory(httpsConfig);
    return this;
  }

  public ServerBuilder withHTTP2() {
    http2ConnectionFactory = new HTTP2ServerConnectionFactory(httpsConfig);
    return withHTTP1();//For handshake proposes
  }

  public ServerBuilder withSSL() {
    this.withSSL = true;
    return this;
  }

  /**
   * Uses a filesystem keystore for the server TLS certificate (e.g. an untrusted JKS in tests).
   */
  public ServerBuilder withServerKeyStorePath(String keyStorePath, String keyStoreType) {
    this.serverKeyStorePathOverride = keyStorePath;
    this.serverKeyStoreTypeOverride = keyStoreType;
    return this;
  }

  public ServerBuilder withALPN() {
    this.ALPN = true;
    return this;
  }

  public ServerBuilder withNeedClientAuth() {
    this.clientAuth = true;
    return this;
  }

  public ServerBuilder withBasicAuth() {
    this.isBasicAuth = true;
    return this;
  }

  public ServerBuilder withDigestAuth() {
    this.isDigestAuth = true;
    return this;
  }

  /**
   * Enables {@link #SERVER_PATH_200_EMBEDDED_CROSS_ORIGIN} to reference an asset on {@code localhost}
   * at the given HTTPS port.
   */
  public ServerBuilder withCrossOriginEmbeddedAssetPort(int assetOriginPortHttps) {
    this.crossOriginEmbeddedAssetPort = assetOriginPortHttps;
    return this;
  }

  public TeardownableServer buildServer() {

    ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
    alpn.setDefaultProtocol(http1ConnectionFactory.getProtocol());
    ServerConnector http1UpgradedConnector = null;
    if (http2cConnectionFactory != null && http1ConnectionFactory != null) {
      http1UpgradedConnector = new ServerConnector(server, http1ConnectionFactory,
          http2cConnectionFactory);
      http1UpgradedConnector.setReusePort(false);
    }
    ConnectionFactory ssl = setupSslConnectionFactory(alpn, http1UpgradedConnector);
    List<ConnectionFactory> connectionFactories = new ArrayList<>();
    if (withSSL) {
      connectionFactories.add(ssl);
    }
    if (ALPN) {
      connectionFactories.add(alpn);
    }
    connectionFactories.addAll(buildConnectionFactories());

    ServerConnector connector = buildServerConnector(connectionFactories);

    server.addConnector(connector);

    // In Jetty 12, ServletContextHandler constructor changed
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    server.setHandler(context);
    context.addServlet(new ServletHolder(buildServlet()), SERVER_PATH + "/*");

    if (isBasicAuth) {
      // In Jetty 12, Constraint.__BASIC_AUTH is replaced by using BasicAuthenticator directly
      configureAuthHandler(server, new BasicAuthenticator(), "BASIC");
      return server;
    }
    if (isDigestAuth) {
      // In Jetty 12, Constraint.__DIGEST_AUTH is replaced by using DigestAuthenticator directly
      configureAuthHandler(server, new DigestAuthenticator(), "DIGEST");
    }
    return server;
  }

  private ConnectionFactory setupSslConnectionFactory(ALPNServerConnectionFactory alpn,
                                                      ServerConnector http1UpgradedConnector) {
    SslContextFactory.Server sslFactory = buildServerSslContextFactory();
    sslFactory.setNeedClientAuth(clientAuth);
    return new SslConnectionFactory(sslFactory,
        ALPN ? alpn.getDefaultProtocol()
            : http1UpgradedConnector != null ? http1UpgradedConnector.getDefaultProtocol()
            : http1ConnectionFactory.getProtocol());
  }

  private ServerConnector buildServerConnector(List<ConnectionFactory> connectionFactories) {
    ServerConnector connector =
        new ServerConnector(server, 1, 1,
            connectionFactories.toArray(new ConnectionFactory[0]));
    connector.setPort(0);
    connector.setReusePort(false);
    return connector;
  }

  private List<ConnectionFactory> buildConnectionFactories() {
    List<ConnectionFactory> connectionFactories = new ArrayList<>();
    Consumer<ConnectionFactory> factoryAppender = (c) -> {
      if (c != null) {
        connectionFactories.add(c);
      }
    };
    factoryAppender.accept(http1ConnectionFactory);
    factoryAppender.accept(http2cConnectionFactory);
    factoryAppender.accept(http2ConnectionFactory);
    return connectionFactories;
  }

  private SslContextFactory.Server buildServerSslContextFactory() {
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    if (serverKeyStorePathOverride != null) {
      sslContextFactory.setKeyStorePath(
          SslStorePathResolver.toJettyFileUri(serverKeyStorePathOverride));
      sslContextFactory.setKeyStoreType(serverKeyStoreTypeOverride);
    } else {
      sslContextFactory.setKeyStorePath(getKeyStorePathAsUriPathInSSLContextFactoryFormat());
    }
    sslContextFactory.setKeyStorePassword(KEYSTORE_PASSWORD);
    return sslContextFactory;
  }

  private String getKeyStorePathAsUriPathInSSLContextFactoryFormat() {
    try {
      URL resource = getClass().getResource("keystore.p12");
      if (resource == null) {
        throw new IllegalStateException("classpath resource keystore.p12 not found");
      }
      if (!"file".equalsIgnoreCase(resource.getProtocol())) {
        throw new IllegalStateException(
            "keystore.p12 must be a file URL (tests run from target/test-classes), got: "
                + resource);
      }
      return Paths.get(resource.toURI()).toAbsolutePath().normalize().toString();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private HttpServlet buildServlet() {
    return new HttpServlet() {

      @Override
      protected void service(HttpServletRequest req, HttpServletResponse resp)
          throws IOException {
        switch (req.getServletPath() + req.getPathInfo()) {
          case SERVER_PATH_200:
            resp.setStatus(HttpStatus.OK_200);
            resp.setContentType(MimeTypes.MIME_TEXT_HTML + ";" + StandardCharsets.UTF_8.name());
            resp.getWriter().write(SERVER_RESPONSE);
            break;
          case SERVER_PATH_SLOW:
            try {
              Thread.sleep(10000);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
            resp.setStatus(HttpStatus.OK_200);
            break;
          case SERVER_PATH_400:
            resp.setStatus(HttpStatus.BAD_REQUEST_400);
            break;
          case SERVER_PATH_302:
            resp.addHeader(HTTPConstants.HEADER_LOCATION,
                "https://localhost:" + req.getLocalPort() + SERVER_PATH_200);
            resp.setStatus(HttpStatus.FOUND_302);
            break;
          case SERVER_PATH_200_WITH_BODY:
            String bodyRequest = req.getReader().lines().collect(Collectors.joining());
            resp.getWriter().write(bodyRequest);
            break;
          case SERVER_PATH_JSON_ONLY:
            String contentType = req.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
              resp.setStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE_415);
              resp.setContentType("text/html; charset=utf-8");
              resp.getWriter().write("Unsupported Media Type");
              break;
            }
            resp.setStatus(HttpStatus.OK_200);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"ok\":true}");
            break;
          case SERVER_PATH_SET_COOKIES:
            resp.addHeader(HTTPConstants.HEADER_SET_COOKIE,
                RESPONSE_DATA_COOKIES);
            resp.addHeader(HTTPConstants.HEADER_SET_COOKIE,
                RESPONSE_DATA_COOKIES2);
            break;
          case SERVER_PATH_USE_COOKIES:
            String cookie = req.getHeader(HTTPConstants.HEADER_COOKIE);
            resp.getWriter().write(cookie);
            break;
          case SERVER_PATH_200_EMBEDDED:
            resp.setContentType(MimeTypes.MIME_TEXT_HTML + ";" + StandardCharsets.UTF_8.name());
            resp.getWriter().write(BASIC_HTML_TEMPLATE);
            resp.addHeader(HTTPConstants.EXPIRES,
                "Sat, 25 Sep 2041 00:00:00 GMT");
            break;
          case SERVER_PATH_200_EMBEDDED_MANY:
            resp.setContentType(MimeTypes.MIME_TEXT_HTML + ";" + StandardCharsets.UTF_8.name());
            resp.getWriter().write(MULTI_IMAGE_HTML_TEMPLATE);
            resp.addHeader(HTTPConstants.EXPIRES,
                "Sat, 25 Sep 2041 00:00:00 GMT");
            break;
          case SERVER_PATH_200_EMBEDDED_CROSS_ORIGIN:
            if (crossOriginEmbeddedAssetPort < 0) {
              resp.sendError(HttpStatus.NOT_FOUND_404,
                  "Cross-origin embedded port not configured");
              break;
            }
            resp.setStatus(HttpStatus.OK_200);
            resp.setContentType(MimeTypes.MIME_TEXT_HTML + ";" + StandardCharsets.UTF_8.name());
            resp.getWriter().write("<!DOCTYPE html><html><body><img src='" + HTTPConstants.PROTOCOL_HTTPS
                + "://" + HOST_NAME + ":" + crossOriginEmbeddedAssetPort + SERVER_IMAGE
                + "'/></body></html>");
            break;
          case SERVER_IMAGE_0:
          case SERVER_IMAGE_1:
          case SERVER_IMAGE_2:
          case SERVER_IMAGE_3:
          case SERVER_IMAGE_4:
            resp.setContentType("image/png");
            resp.getOutputStream().write(new byte[] {1, 2, 3, 4, 5});
            break;
          case SERVER_IMAGE:
            resp.getOutputStream().write(new byte[] {1, 2, 3, 4, 5});
          case SERVER_PATH_200_FILE_SENT:
            resp.setContentType("image/png");
            byte[] requestBody = req.getInputStream().readAllBytes();
            resp.getOutputStream().write(requestBody);
            break;
          case SERVER_PATH_200_GZIP:
            GZIP_REQUEST_COUNT.incrementAndGet();
            resp.addHeader("Content-Encoding", "gzip");
            resp.addHeader("X-Gzip-Req-Protocol", req.getProtocol());
            resp.addHeader("Connection", "close");
            resp.setContentLength(GZIP_RESPONSE_BODY.length);
            resp.getOutputStream().write(GZIP_RESPONSE_BODY);
            resp.flushBuffer();
            resp.getOutputStream().close();
            break;
          case SERVER_PATH_200_DEFLATE:
            resp.addHeader("Content-Encoding", "deflate");
            resp.setContentLength(DEFLATE_RESPONSE_BODY.length);
            resp.getOutputStream().write(DEFLATE_RESPONSE_BODY);
            resp.flushBuffer();
            resp.getOutputStream().close();
            break;
          case SERVER_PATH_200_BROTLI:
            resp.addHeader("Content-Encoding", "br");
            BrotliCompression brotliCompression = new BrotliCompression();
            brotliCompression.setByteBufferPool(COMPRESSION_BUFFER_POOL);
            try (java.io.OutputStream brotliOutputStream =
                brotliCompression.newEncoderOutputStream(resp.getOutputStream())) {
              brotliOutputStream.write(BINARY_RESPONSE_BODY);
            }
            break;
          case SERVER_PATH_200_ZSTD:
            resp.addHeader("Content-Encoding", "zstd");
            ZstandardCompression zstdCompression = new ZstandardCompression();
            zstdCompression.setByteBufferPool(COMPRESSION_BUFFER_POOL);
            try (java.io.OutputStream zstdOutputStream =
                zstdCompression.newEncoderOutputStream(resp.getOutputStream())) {
              zstdOutputStream.write(BINARY_RESPONSE_BODY);
            }
            break;
          case SERVER_PATH_DELETE_DATA:
            resp.setStatus(HttpStatus.OK_200);
            break;
          case SERVER_PATH_BIG_RESPONSE:
            resp.getOutputStream().write(new byte[(int) BIG_BUFFER_SIZE]);
            resp.setContentType("image/jpg");
            break;
        }
      }
    };
  }

  private static byte[] buildGzipResponseBody() {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
         GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
      gzipOutputStream.write(BINARY_RESPONSE_BODY);
      gzipOutputStream.finish();
      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static byte[] buildDeflateResponseBody() {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
         DeflaterOutputStream deflateOutputStream = new DeflaterOutputStream(outputStream)) {
      deflateOutputStream.write(BINARY_RESPONSE_BODY);
      deflateOutputStream.finish();
      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }


  // In Jetty 12, security APIs changed:
  // - ConstraintSecurityHandler → SecurityHandler.PathMapped
  // - ConstraintMapping → Use PathMapped.put() with PathSpec and Constraint
  // - Constraint is now an interface with static factory methods from()
  // - Password → Credential.getCredential()
  private void configureAuthHandler(Server server, Authenticator authenticator,
                                   String mechanism) {
    SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
    String[] roles = new String[] {"can-access"};
    
    // Create constraint using Constraint.from() with roles
    // In Jetty 12, when roles are specified, must use SPECIFIC_ROLE, not KNOWN_ROLE
    Constraint constraint = Constraint.from(Constraint.Transport.ANY, 
        Constraint.Authorization.SPECIFIC_ROLE, 
        java.util.Set.of(roles));
    
    // Use PathSpec to map constraint to all paths
    PathSpec pathSpec = PathSpec.from("/*");
    securityHandler.put(pathSpec, constraint);
    
    securityHandler.setAuthenticator(authenticator);
    securityHandler.setRealmName(AUTH_REALM);
    securityHandler.setLoginService(buildLoginService(roles));
    
    // Get the current handler and wrap it with security
    org.eclipse.jetty.server.Handler currentHandler = server.getHandler();
    securityHandler.setHandler(currentHandler);
    server.setHandler(securityHandler);
  }

  private HashLoginService buildLoginService(String[] roles) {
    UserStore userStore = new UserStore();
    // In Jetty 12, Password is replaced by Credential.getCredential()
    Credential credential = Credential.getCredential(AUTH_PASSWORD);
    userStore.addUser(AUTH_USERNAME, credential, roles);

    HashLoginService loginService = new HashLoginService();
    loginService.setName(AUTH_REALM);
    loginService.setUserStore(userStore);
    return loginService;
  }

  public static class TeardownableServer extends Server {

    @Override
    protected void doStop() throws Exception {
      Arrays.stream(this.getConnectors()).forEach(
          serverConnector -> {
            try {
              serverConnector.stop();
            } catch (Exception e) {
              e.printStackTrace();
            }
          });
      super.doStop();
    }

    @Override
    protected void stop(LifeCycle l) throws Exception {
      Arrays.stream(this.getConnectors()).forEach(
          serverConnector -> {
            try {
              serverConnector.stop();
            } catch (Exception e) {
              e.printStackTrace();
            }
          });
      l.stop();
      super.stop(l);
    }
  }
}
