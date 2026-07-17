package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.URL;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Test;

/**
 * HttpClient4 reports whatever {@code Cookie} header actually went out on the wire, regardless
 * of whether a {@code CookieManager} built it. {@link HTTP2JettyClient} only populated
 * {@code result.getCookies()} when a {@code CookieManager} was configured, missing cookies set
 * directly via a {@code HeaderManager}.
 */
public class HTTP2JettyClientManualCookieHeaderTest extends HTTP2TestBase {

  private TeardownableServer server;

  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void capturesCookieSetDirectlyViaHeaderManagerWithoutCookieManager() throws Exception {
    HTTPSampleResult result = sampleGetWithCookieHeader("session=abc123");

    assertThat(result.getCookies()).isEqualTo("session=abc123");
  }

  @Test
  public void leavesCookiesBlankWhenNoCookieHeaderAndNoCookieManager() throws Exception {
    HTTPSampleResult result = sampleGetWithCookieHeader(null);

    assertThat(result.getCookies()).isEmpty();
  }

  private HTTPSampleResult sampleGetWithCookieHeader(String cookieHeaderValue) throws Exception {
    int port = startHttp1OnlyServer();
    HTTP2JettyClient client = new HTTP2JettyClient(false, "manual-cookie-header-test");
    client.start();
    try {
      HTTP2Sampler sampler = new HTTP2Sampler();
      sampler.setMethod("GET");
      sampler.setDomain("localhost");
      sampler.setPort(port);
      sampler.setPath(ServerBuilder.SERVER_PATH_200);
      sampler.setProtocol("http");
      if (cookieHeaderValue != null) {
        HeaderManager headerManager = new HeaderManager();
        headerManager.add(new Header(HttpHeader.COOKIE.asString(), cookieHeaderValue));
        sampler.setHeaderManager(headerManager);
      }

      HTTPSampleResult result = new HTTPSampleResult();
      result.setSampleLabel("GET_MANUAL_COOKIE");
      result.setHTTPMethod("GET");
      result.setURL(new URL("http", "localhost", port, ServerBuilder.SERVER_PATH_200));

      return client.sample(sampler, result, false, 0);
    } finally {
      client.stop();
    }
  }

  private int startHttp1OnlyServer() throws Exception {
    server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }
}
