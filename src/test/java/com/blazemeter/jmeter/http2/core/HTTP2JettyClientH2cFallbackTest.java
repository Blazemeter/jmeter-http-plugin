package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A cleartext ({@code http://}) server that never negotiates {@code h2c} shouldn't ever surface
 * as a sample error or a hung request - {@link HTTP2JettyClient} must detect the failed upgrade
 * and retry as plain HTTP/1.1, exactly like a compliant client talking to an HTTP/1.1-only
 * server.
 */
public class HTTP2JettyClientH2cFallbackTest extends HTTP2TestBase {

  private TeardownableServer server;
  private HttpClient probeClient;

  @Before
  public void setUp() throws Exception {
    // A leaked legacy profile (enableHttp2=false) makes the upgrade client skip h2c entirely, so
    // the HTTP/1-only cache is never populated and this assertion fails. Shared caches can also
    // retain stale cleartext origins across recycled ports.
    HTTP2JettyClientTestIsolation.resetSharedClientState();
  }

  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.stop();
    }
    if (probeClient != null) {
      probeClient.stop();
    }
  }

  @Test
  public void sampleAgainstHttp1OnlyServerSucceedsDespiteAttemptedH2cUpgrade() throws Exception {
    int port = startHttp1OnlyServer();
    // http1UpgradeRequired=true: the client already believes this origin needs the explicit
    // Upgrade: h2c header dance (e.g. a prior response on this origin wasn't HTTP/2).
    HTTP2JettyClient client = new HTTP2JettyClient(true, "h2c-fallback-test");
    client.start();
    try {
      HTTPSampleResult result = sampleGet(client, port);
      assertThat(result.isSuccessful()).isTrue();
      assertThat(result.getResponseCode()).isEqualTo("200");
    } finally {
      client.stop();
    }
  }

  @Test
  public void secondRequestToSameOriginSkipsRepeatedFailedH2cUpgrade() throws Exception {
    int port = startHttp1OnlyServer();
    HTTP2JettyClient client = new HTTP2JettyClient(true, "h2c-fallback-cache-test");
    client.start();
    try {
      sampleGet(client, port);
      URI origin = URI.create("http://localhost:" + port);
      assertThat((Boolean) invokePrivate(client, "isHttp1Only", new Class<?>[] {URI.class},
          origin)).isTrue();
    } finally {
      client.stop();
    }
  }

  /**
   * A server that ignores {@code Upgrade: h2c} and answers the request over HTTP/1.1 has answered
   * it: that response is the sample. Re-sending would hit the server twice for one sampler, which
   * for anything other than a GET means the side effect happens twice, and would report only the
   * second attempt's time.
   */
  @Test
  public void anUpgradeAttemptAnsweredOverHttp11PutsOneRequestOnTheWire() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    int port = startHttp1OnlyServer(requests);
    // http1UpgradeRequired=true is what makes this request carry the h2c upgrade headers.
    HTTP2JettyClient client = new HTTP2JettyClient(true, "h2c-single-request-test");
    client.start();
    try {
      HTTPSampleResult result = sampleGet(client, port);

      assertThat(result.isSuccessful()).isTrue();
      assertThat(result.getResponseCode()).isEqualTo("200");
      assertThat(requests.get()).as("requests that reached the server").isEqualTo(1);
    } finally {
      client.stop();
    }
  }

  /** And the origin is remembered, so the requests after it are not upgrade attempts either. */
  @Test
  public void furtherRequestsToAnHttp11OriginPutOneRequestEachOnTheWire() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    int port = startHttp1OnlyServer(requests);
    HTTP2JettyClient client = new HTTP2JettyClient(true, "h2c-single-request-cache-test");
    client.start();
    try {
      sampleGet(client, port);
      sampleGet(client, port);
      sampleGet(client, port);

      assertThat(requests.get()).as("requests that reached the server for three samplers")
          .isEqualTo(3);
    } finally {
      client.stop();
    }
  }

  /**
   * The other half of the rule: a server or proxy that answers the upgrade headers by refusing them
   * did not serve the request, and that failure is one this client caused by adding headers the test
   * plan never asked for. Those are still sent again without them.
   */
  @Test
  public void onlyAnAnswerThatRefusesTheUpgradeIsSentAgain() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient(true, "h2c-retry-decision-test");
    Request upgradeAttempt = newProbeRequest("http://example.invalid/")
        .headers(h -> h.put(HttpHeader.UPGRADE, "h2c"));

    for (int refused : new int[] {400, 426, 501, 505}) {
      assertThat(shouldRetry(client, upgradeAttempt, refused))
          .as("status %s refuses the upgrade, so the request must be sent again", refused)
          .isTrue();
    }
    for (int served : new int[] {200, 201, 204, 301, 401, 403, 404, 500, 503}) {
      assertThat(shouldRetry(client, upgradeAttempt, served))
          .as("status %s is an answer to the request, so it must not be sent again", served)
          .isFalse();
    }
  }

  private static boolean shouldRetry(HTTP2JettyClient client, Request request, int status)
      throws Exception {
    ContentResponse response = mock(ContentResponse.class);
    when(response.getVersion()).thenReturn(HttpVersion.HTTP_1_1);
    when(response.getStatus()).thenReturn(status);
    return (Boolean) invokePrivate(client, "shouldRetryAfterFailedH2cUpgrade",
        new Class<?>[] {Request.class, ContentResponse.class}, request, response);
  }

  @Test
  public void wasH2cUpgradeAttemptDetectsUpgradeHeader() throws Exception {
    HTTP2JettyClient client = newClientForReflection();
    Request withUpgrade = newProbeRequest("http://example.invalid/")
        .headers(h -> h.put(HttpHeader.UPGRADE, "h2c"));
    Request withoutUpgrade = newProbeRequest("http://example.invalid/");

    assertThat((Boolean) invokePrivate(client, "wasH2cUpgradeAttempt",
        new Class<?>[] {Request.class}, withUpgrade)).isTrue();
    assertThat((Boolean) invokePrivate(client, "wasH2cUpgradeAttempt",
        new Class<?>[] {Request.class}, withoutUpgrade)).isFalse();
  }

  @Test
  public void buildHttp11FallbackRequestStripsH2cHeadersAndMarksAttempted() throws Exception {
    HTTP2JettyClient client = newClientForReflection();
    Request original = newProbeRequest("http://example.invalid/")
        .headers(h -> h
            .put(HttpHeader.UPGRADE, "h2c")
            .put(HttpHeader.HTTP2_SETTINGS, "AAMAAABkAAQAoAAAAAIAAAAA")
            .put(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings")
            .put("X-Custom", "keep-me"));

    Request fallback = (Request) invokePrivate(client, "buildHttp11FallbackRequest",
        new Class<?>[] {Request.class}, original);

    HttpFields fallbackHeaders = fallback.getHeaders();
    assertThat(fallbackHeaders.get(HttpHeader.UPGRADE)).isNull();
    assertThat(fallbackHeaders.get(HttpHeader.HTTP2_SETTINGS)).isNull();
    assertThat(fallbackHeaders.get("X-Custom")).isEqualTo("keep-me");
    String fallbackAttemptedAttribute = (String) readPrivateStaticField(
        HTTP2JettyClient.class, "ATTR_H2C_FALLBACK_ATTEMPTED");
    assertThat(fallback.getAttributes().get(fallbackAttemptedAttribute)).isEqualTo(Boolean.TRUE);
  }

  @Test
  public void isClosedChannelFailureFindsItAnywhereInCauseChain() throws Exception {
    HTTP2JettyClient client = newClientForReflection();
    Exception wrapped = new ExecutionException(
        new RuntimeException("network blip", new ClosedChannelException()));
    Exception unrelated = new ExecutionException(new RuntimeException("unrelated failure"));

    assertThat((Boolean) invokePrivate(client, "shouldFallbackToHttp11AfterTransportFailure",
        new Class<?>[] {Throwable.class, Throwable.class}, wrapped.getCause(), wrapped))
        .isTrue();
    assertThat((Boolean) invokePrivate(client, "shouldFallbackToHttp11AfterTransportFailure",
        new Class<?>[] {Throwable.class, Throwable.class}, unrelated.getCause(), unrelated))
        .isFalse();
  }

  private int startHttp1OnlyServer() throws Exception {
    return startHttp1OnlyServer(null);
  }

  /**
   * @param requestCounter counts every request the server answered, or {@code null} to not count
   */
  private int startHttp1OnlyServer(AtomicInteger requestCounter) throws Exception {
    server = new ServerBuilder().withHTTP1().buildServer();
    if (requestCounter != null) {
      server.setRequestLog((request, response) -> requestCounter.incrementAndGet());
    }
    server.start();
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private static HTTPSampleResult sampleGet(HTTP2JettyClient client, int port) throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod("GET");
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setPath(ServerBuilder.SERVER_PATH_200);
    sampler.setProtocol("http");

    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("GET_H2C_FALLBACK");
    result.setHTTPMethod("GET");
    result.setURL(new URL("http", "localhost", port, ServerBuilder.SERVER_PATH_200));

    return client.sample(sampler, result, false, 0);
  }

  private HTTP2JettyClient newClientForReflection() {
    return new HTTP2JettyClient(false, "h2c-reflection-test");
  }

  private Request newProbeRequest(String uri) {
    if (probeClient == null) {
      probeClient = new HttpClient();
    }
    return probeClient.newRequest(URI.create(uri));
  }

  private static Object invokePrivate(Object target, String methodName, Class<?>[] paramTypes,
      Object... args) throws Exception {
    Method method = HTTP2JettyClient.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private static Object readPrivateStaticField(Class<?> type, String fieldName) throws Exception {
    java.lang.reflect.Field field = type.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(null);
  }
}
