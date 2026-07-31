package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.After;
import org.junit.Test;

/**
 * When {@code autoRedirects} is enabled, Jetty follows a redirect chain silently at the
 * transport layer and {@code ContentResponse.getRequest()} carries the LAST request actually
 * sent, not the one {@link HTTP2JettyClient#postContentResponse} originally built. HttpClient4
 * reports headers/sentBytes for that effective, final request; this used to instead report the
 * pre-redirect request's headers (e.g. the wrong {@code Host}).
 */
public class HTTP2JettyClientEffectiveRequestHeadersTest extends HTTP2TestBase {

  private Server redirectOrigin;
  private Server redirectTarget;
  private HTTP2JettyClient client;

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (redirectOrigin != null) {
      redirectOrigin.stop();
    }
    if (redirectTarget != null) {
      redirectTarget.stop();
    }
  }

  @Test
  public void reportsHeadersAndSentBytesFromTheFinalRequestAfterAutoRedirect() throws Exception {
    redirectTarget = new Server();
    int targetPort = startPlainHttpServer(redirectTarget, new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) {
        response.setStatus(HttpStatus.OK_200);
        Content.Sink.write(response, true, "target reached", callback);
        return true;
      }
    });

    redirectOrigin = new Server();
    int originPort = startPlainHttpServer(redirectOrigin, new Handler.Abstract() {
      @Override
      public boolean handle(Request request, Response response, Callback callback) {
        response.getHeaders().add(HTTPConstants.HEADER_LOCATION,
            "http://localhost:" + targetPort + "/landed");
        response.setStatus(HttpStatus.FOUND_302);
        callback.succeeded();
        return true;
      }
    });

    client = new HTTP2JettyClient(false, "effective-request-headers-test");
    client.start();

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("localhost");
    sampler.setPort(originPort);
    sampler.setPath("/start");
    sampler.setProtocol("http");
    sampler.setAutoRedirects(true);

    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("GET_EFFECTIVE_REQUEST_HEADERS");
    result.setHTTPMethod(HTTPConstants.GET);
    result.setURL(new URL("http", "localhost", originPort, "/start"));

    HTTPSampleResult sampled = client.sample(sampler, result, false, 0);

    assertThat(sampled.isSuccessful()).isTrue();
    assertThat(sampled.getResponseCode()).isEqualTo("200");
    assertThat(sampled.getRequestHeaders())
        .as("must report the Host of the final (post-redirect) request, not the original one")
        .contains("Host: localhost:" + targetPort)
        .doesNotContain("Host: localhost:" + originPort);
    assertThat(sampled.getSentBytes()).isGreaterThan(0);
  }

  private int startPlainHttpServer(Server server, Handler handler) throws Exception {
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0);
    server.addConnector(connector);
    server.setHandler(handler);
    server.start();
    return connector.getLocalPort();
  }
}
