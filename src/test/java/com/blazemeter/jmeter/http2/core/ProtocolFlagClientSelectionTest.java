package com.blazemeter.jmeter.http2.core;

import static org.junit.Assert.assertSame;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import org.eclipse.jetty.client.HttpClient;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Pins which Jetty client the protocol flags select, so that disabling a protocol in the UI is
 * actually honoured.
 *
 * <p>Reaches {@code selectHttpClient} by reflection and only compares the returned reference against
 * the client fields, so nothing has to be started or connected.
 */
public class ProtocolFlagClientSelectionTest extends HTTP2TestBase {

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Test
  public void shouldUseHttp1OnlyForCleartextWhenOnlyHttp1Enabled() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient();
    setFlags(client, true, false, false);

    assertSame(field(client, "httpClientHttp1Only"),
        selectHttpClient(client, URI.create("http://example.com/x")));
  }

  @Test
  public void shouldUseHttp1OnlyForTlsWhenOnlyHttp1Enabled() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient();
    setFlags(client, true, false, false);

    assertSame(field(client, "httpClientHttp1Only"),
        selectHttpClient(client, URI.create("https://example.com/x")));
  }

  /**
   * With HTTP/2 off and HTTP/3 on there is no race to run and no HTTP/2 client to fall through to:
   * the request has to go out on HTTP/3, and only a failure there may fall back to HTTP/1.1.
   */
  @Test
  public void shouldUseHttp3ForTlsWhenHttp2DisabledAndHttp3Enabled() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient();
    setFlags(client, true, false, true);

    assertSame(field(client, "httpClient"),
        selectHttpClient(client, URI.create("https://example.com/x")));
  }

  /** Same, with HTTP/1.1 disabled too: still HTTP/3, never the client of a disabled protocol. */
  @Test
  public void shouldUseHttp3ForTlsWhenOnlyHttp3Enabled() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient();
    setFlags(client, false, false, true);

    assertSame(field(client, "httpClient"),
        selectHttpClient(client, URI.create("https://example.com/x")));
  }

  /**
   * Selection without a request in hand stays conservative: with nothing known about the origin and
   * no way to tell whether a failed HTTP/3 attempt could be retried, it must not explore HTTP/3.
   * Exploration belongs to the path that does know the request - see
   * {@link Http3ExplorationPolicyTest}.
   */
  @Test
  public void shouldNotExploreHttp3ForUnknownOriginWithoutRequestContext() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient();
    setFlags(client, true, true, true);

    assertSame(field(client, "httpClientNoH3"),
        selectHttpClient(client, URI.create("https://unknown-origin.example.com/x")));
  }

  @Test
  public void shouldUseHttp2ForTlsWhenHttp3Disabled() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient();
    setFlags(client, true, true, false);

    assertSame(field(client, "httpClientNoH3"),
        selectHttpClient(client, URI.create("https://example.com/x")));
  }

  private static void setFlags(HTTP2JettyClient client, boolean http1, boolean http2, boolean http3)
      throws Exception {
    setField(client, "enableHttp1", http1);
    setField(client, "enableHttp2", http2);
    setField(client, "enableHttp3", http3);
    // Keep the decision driven purely by the flags under test.
    setField(client, "http1UpgradeRequired", false);
    setField(client, "http3PriorKnowledgeEnabled", false);
  }

  private static void setField(HTTP2JettyClient client, String name, boolean value)
      throws Exception {
    Field f = HTTP2JettyClient.class.getDeclaredField(name);
    f.setAccessible(true);
    f.setBoolean(client, value);
  }

  private static HttpClient field(HTTP2JettyClient client, String name) throws Exception {
    Field f = HTTP2JettyClient.class.getDeclaredField(name);
    f.setAccessible(true);
    return (HttpClient) f.get(client);
  }

  private static HttpClient selectHttpClient(HTTP2JettyClient client, URI uri) throws Exception {
    Method m = HTTP2JettyClient.class.getDeclaredMethod("selectHttpClient", URI.class);
    m.setAccessible(true);
    return (HttpClient) m.invoke(client, uri);
  }

}
