package com.blazemeter.jmeter.http2.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Pins when HTTP/3 is attempted, which is the difference between discovering HTTP/3 on a first
 * contact and turning an unreachable QUIC path into a stalled request.
 *
 * <p>The decision depends on what is known about the origin, not on the request: an origin worth
 * attempting HTTP/3 on is attempted regardless of method, so neither is a POST to a known HTTP/3
 * site downgraded, nor is an origin first contacted by a POST left unable to ever learn HTTP/3.
 * Whether the attempt is <em>raced</em> against HTTP/2 is a separate decision, covered by
 * {@link ProtocolFlagClientSelectionTest} and the race tests.
 */
public class Http3ExplorationPolicyTest extends HTTP2TestBase {

  private static final URI TLS_URI = URI.create("https://h3-policy.example.com/x");
  private static final URI CLEARTEXT_URI = URI.create("http://h3-policy.example.com/x");

  private HTTP2JettyClient client;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    altSvcCache().clear();
    client = new HTTP2JettyClient();
    setBoolean(client, "enableHttp3", true);
    setBoolean(client, "enableHttp2", true);
    setBoolean(client, "enableHttp1", true);
    setBoolean(client, "altSvcCacheEnabled", true);
    setBoolean(client, "http3PriorKnowledgeEnabled", false);
  }

  // --- first contact -------------------------------------------------------------------------

  @Test
  public void shouldExploreHttp3OnFirstContact() throws Exception {
    assertTrue(shouldAttemptHttp3(TLS_URI, true));
  }


  /**
   * The same rule seen through the real entry point, where the method is known: a POST to an origin
   * nothing is known about still goes to the HTTP/3 client. It gets no concurrent HTTP/2 attempt to
   * cover it, so what makes this safe is that the handshake has its own short deadline and that the
   * fallback only fires when the connection could not be established - the request never reached
   * the wire, so resending it cannot repeat a side effect. Without this, an origin first contacted
   * by a POST could never learn HTTP/3 at all.
   */
  @Test
  public void shouldExploreHttp3ForPostToUnknownOrigin() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.POST);
    sampler.addArgument("test1", "value1");
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(TLS_URI.toURL());
    result.setHTTPMethod(HTTPConstants.POST);

    assertSame("a POST to an unknown origin must still explore HTTP/3",
        field("httpClient"), resolveClientForRequest(sampler, result));
  }

  /**
   * And the limit of that: a body read from a file cannot be rewound, so the fallback has nothing to
   * hand the retry and the request would have no way back once HTTP/3 failed. Such a request must
   * not be used to explore an unknown origin - it goes over HTTP/2, and exploration is left to the
   * requests that can afford it.
   */
  @Test
  public void shouldNotExploreHttp3ForRequestWithFileBody() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.POST);
    sampler.setHTTPFiles(new HTTPFileArg[] {
        new HTTPFileArg("some-file.txt", "upload", "text/plain")});
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(TLS_URI.toURL());
    result.setHTTPMethod(HTTPConstants.POST);

    assertSame("a file body cannot be replayed, so it must not explore HTTP/3",
        field("httpClientNoH3"), resolveClientForRequest(sampler, result));
  }

  // --- already known to speak HTTP/3 ---------------------------------------------------------

  @Test
  public void shouldUseHttp3ForKnownOriginEvenWhenRequestCannotBeRaced() throws Exception {
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), 0L);

    assertTrue("a POST to a site known to speak HTTP/3 must still use HTTP/3",
        shouldAttemptHttp3(TLS_URI, true));
  }

  @Test
  public void shouldNotUseHttp3WhenCachedEntrySaysOriginDoesNotSupportIt() throws Exception {
    cacheAltSvc(false, TimeUnit.MINUTES.toMillis(10), 0L);

    assertFalse(shouldAttemptHttp3(TLS_URI, true));
  }

  // --- cooldown and expiry --------------------------------------------------------------------

  @Test
  public void shouldNotUseHttp3WhileBrokenCooldownIsActive() throws Exception {
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), TimeUnit.MINUTES.toMillis(5));

    assertFalse("a recent HTTP/3 failure must be respected, whatever the request is",
        shouldAttemptHttp3(TLS_URI, true));
  }

  @Test
  public void shouldExploreAgainOnceCachedEntryExpired() throws Exception {
    cacheAltSvc(true, -1L, 0L);

    assertTrue("an expired answer must not be trusted; explore again",
        shouldAttemptHttp3(TLS_URI, true));
  }

  /**
   * A failure on an origin that never advertised Alt-Svc has to be remembered too, otherwise every
   * later request explores an origin whose HTTP/3 just failed.
   */
  @Test
  public void shouldRecordCooldownForExploredOriginWithoutAltSvcEntry() throws Exception {
    assertTrue(shouldAttemptHttp3(TLS_URI, true));

    Method mark = HTTP2JettyClient.class.getDeclaredMethod("markHttp3Broken", URI.class);
    mark.setAccessible(true);
    mark.invoke(client, TLS_URI);

    assertFalse("the cooldown must apply even though the origin had no Alt-Svc entry",
        shouldAttemptHttp3(TLS_URI, true));
  }

  // --- flags and scheme -----------------------------------------------------------------------

  @Test
  public void shouldNeverAttemptHttp3WhenDisabled() throws Exception {
    setBoolean(client, "enableHttp3", false);
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), 0L);

    assertFalse(shouldAttemptHttp3(TLS_URI, true));
  }

  @Test
  public void shouldNeverAttemptHttp3OverCleartext() throws Exception {
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), 0L);

    assertFalse("QUIC always uses TLS, so http:// can never be HTTP/3",
        shouldAttemptHttp3(CLEARTEXT_URI, true));
  }

  @Test
  public void shouldAttemptHttp3WithPriorKnowledgeWithoutAnyCacheEntry() throws Exception {
    setBoolean(client, "http3PriorKnowledgeEnabled", true);

    assertTrue(shouldAttemptHttp3(TLS_URI, true));
  }

  // --- helpers --------------------------------------------------------------------------------

  private Object resolveClientForRequest(HTTP2Sampler sampler, HTTPSampleResult result)
      throws Exception {
    Method m = HTTP2JettyClient.class.getDeclaredMethod(
        "resolveClientForRequest", HTTP2Sampler.class, HTTPSampleResult.class);
    m.setAccessible(true);
    return m.invoke(client, sampler, result);
  }

  private Object field(String name) throws Exception {
    Field f = HTTP2JettyClient.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(client);
  }

  private boolean shouldAttemptHttp3(URI uri, boolean recoverable) throws Exception {
    Method m = HTTP2JettyClient.class.getDeclaredMethod(
        "shouldAttemptHttp3", URI.class, boolean.class);
    m.setAccessible(true);
    return (boolean) m.invoke(client, uri, recoverable);
  }

  private void cacheAltSvc(boolean h3, long expiresInMs, long brokenForMs) throws Exception {
    Class<?> entryClass = Class.forName(
        "com.blazemeter.jmeter.http2.core.HTTP2JettyClient$AltSvcEntry");
    Constructor<?> ctor = entryClass.getDeclaredConstructor();
    ctor.setAccessible(true);
    Object entry = ctor.newInstance();
    long now = System.currentTimeMillis();
    setEntryField(entry, "h3", h3);
    setEntryField(entry, "expiresAt", now + expiresInMs);
    setEntryField(entry, "brokenUntil", brokenForMs > 0 ? now + brokenForMs : 0L);
    setEntryField(entry, "lastH3SuccessAt", 0L);
    altSvcCache().put(originKey(TLS_URI), entry);
  }

  private static void setEntryField(Object entry, String name, Object value) throws Exception {
    Field f = entry.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(entry, value);
  }

  private String originKey(URI uri) throws Exception {
    Method m = HTTP2JettyClient.class.getDeclaredMethod("originKey", URI.class);
    m.setAccessible(true);
    return (String) m.invoke(client, uri);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> altSvcCache() throws Exception {
    Field f = HTTP2JettyClient.class.getDeclaredField("ALT_SVC_CACHE");
    f.setAccessible(true);
    return (Map<String, Object>) f.get(null);
  }

  private static void setBoolean(HTTP2JettyClient client, String name, boolean value)
      throws Exception {
    Field f = HTTP2JettyClient.class.getDeclaredField(name);
    f.setAccessible(true);
    f.setBoolean(client, value);
  }
}
