package com.blazemeter.jmeter.http2.parity;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/** Minimal HTTP/1 server that returns configurable redirect responses for parity tests. */
public final class ParityRedirectServer implements AutoCloseable {

  private final Server server;
  private final int port;

  public ParityRedirectServer() throws Exception {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0);
    server.addConnector(connector);
    ServletContextHandler context = new ServletContextHandler();
    context.addServlet(new ServletHolder(new RedirectServlet()), "/*");
    server.setHandler(context);
    server.start();
    port = connector.getLocalPort();
  }

  public int getPort() {
    return port;
  }

  public String url(String path) {
    String normalized = path.startsWith("/") ? path : "/" + path;
    return "http://localhost:" + port + normalized;
  }

  @Override
  public void close() throws Exception {
    server.stop();
  }

  private final class RedirectServlet extends HttpServlet {

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      String path = req.getRequestURI();
      if ("/target".equals(path)) {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write("ok");
        return;
      }
      if ("/some-location".equals(path)) {
        int status = parseStatus(req.getParameter("status"), HttpServletResponse.SC_FOUND);
        resp.setStatus(status);
        resp.setHeader(HTTPConstants.HEADER_LOCATION, url("/target"));
        return;
      }
      resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    private int parseStatus(String raw, int defaultStatus) {
      if (raw == null || raw.isBlank()) {
        return defaultStatus;
      }
      try {
        return Integer.parseInt(raw.trim());
      } catch (NumberFormatException e) {
        return defaultStatus;
      }
    }
  }
}
