package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Test;

/**
 * Item #14, phase 2: {@code HTTP2JettyClient#buildHttp11FallbackRequest} reuses the failed
 * request's {@code Request.Content} instead of rebuilding the body from scratch (unlike
 * {@code retryWithHTTP11Only}, the sampler-based last-resort fallback). Jetty's own retry paths
 * ({@code AuthenticationProtocolHandler}, {@code HttpRedirector}) always call
 * {@code Request.Content#rewind()} before reusing content on a retry, and abort the retry if it
 * returns {@code false}. {@code buildHttp11FallbackRequest} used to skip that call: if the
 * original content had already been (partially or fully) read before the transport failure was
 * detected, the fallback request would declare the original {@code Content-Length} but send no
 * body bytes, hanging the server until its idle timeout instead of failing fast or resending the
 * body correctly.
 */
public class HTTP2JettyClientFallbackBodyReuseInvestigationTest extends HTTP2TestBase {

  private TeardownableServer server;
  private HTTP2JettyClient client;
  private HttpClient probeClient;

  @After
  public void tearDown() throws Exception {
    if (probeClient != null) {
      probeClient.stop();
    }
    if (client != null) {
      client.stop();
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void reusingOriginalRequestBodyAfterAFailedSendStillDeliversFullContent()
      throws Exception {
    int port = startHttp1OnlyServer();
    client = new HTTP2JettyClient(false, "fallback-body-reuse-test");
    client.start();
    probeClient = new HttpClient();
    probeClient.start();

    String bodyText = "hello=world&keep=this-data-intact";
    Request originalRequest = probeClient.newRequest(
            URI.create("http://localhost:" + port + ServerBuilder.SERVER_PATH_200_WITH_BODY))
        .method("POST")
        .body(new StringRequestContent(bodyText, StandardCharsets.UTF_8));

    // Simulate "the original request already failed once" by sending it for real first -
    // this drains its Request.Content exactly like a real send would, before the fallback
    // reuses the same content object.
    ContentResponse firstAttempt = originalRequest.send();
    assertThat(firstAttempt.getContentAsString()).isEqualTo(bodyText);

    Request fallbackRequest = buildFallback(originalRequest);
    ContentResponse fallbackAttempt = fallbackRequest.send();

    assertThat(fallbackAttempt.getContentAsString())
        .as("the fallback request must resend the full original body, not an empty/stale one")
        .isEqualTo(bodyText);
  }

  @Test
  public void throwsInsteadOfSendingAnEmptyBodyWhenContentCannotBeRewound() throws Exception {
    int port = startHttp1OnlyServer();
    client = new HTTP2JettyClient(false, "fallback-body-reuse-test");
    client.start();
    probeClient = new HttpClient();
    probeClient.start();

    Request originalRequest = probeClient.newRequest(
            URI.create("http://localhost:" + port + ServerBuilder.SERVER_PATH_200_WITH_BODY))
        .method("POST")
        .body(new NonRewindableStringContent("cannot-reuse-me"));

    InvocationTargetException thrown = org.junit.Assert.assertThrows(
        InvocationTargetException.class, () -> buildFallback(originalRequest));

    assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
  }

  private Request buildFallback(Request originalRequest) throws Exception {
    Method buildFallback = HTTP2JettyClient.class
        .getDeclaredMethod("buildHttp11FallbackRequest", Request.class);
    buildFallback.setAccessible(true);
    return (Request) buildFallback.invoke(client, originalRequest);
  }

  private int startHttp1OnlyServer() throws Exception {
    server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private static final class NonRewindableStringContent extends StringRequestContent {
    private NonRewindableStringContent(String content) {
      super(content, StandardCharsets.UTF_8);
    }

    @Override
    public boolean rewind() {
      return false;
    }
  }
}
