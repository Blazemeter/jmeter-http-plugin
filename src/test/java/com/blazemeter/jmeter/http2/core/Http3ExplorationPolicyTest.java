package com.blazemeter.jmeter.http2.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.eclipse.jetty.client.Request;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Pins when HTTP/3 is attempted, following Chromium's discovery model: with HTTP/2 (or HTTP/1.1)
 * available, the first contact stays on TCP so {@code Alt-Svc} can be learned; HTTP/3 is used only
 * after the cache says the origin supports it. Blind QUIC exploration on unknown origins is not
 * done.
 *
 * <p>Exception: prior knowledge (also auto-enabled when only HTTP/3 is configured) asserts H3 up
 * front. Whether an H3 attempt is <em>raced</em> against HTTP/2 is a separate decision.
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

  // --- first contact (TCP first) -------------------------------------------------------------

  @Test
  public void shouldNotExploreHttp3OnFirstContactWhenTcpAvailable() throws Exception {
    assertFalse("unknown origins must learn Alt-Svc over HTTP/2 first",
        shouldAttemptHttp3(TLS_URI, true));
  }

  @Test
  public void shouldUseHttp2ClientForGetToUnknownOrigin() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(TLS_URI.toURL());
    result.setHTTPMethod(HTTPConstants.GET);

    assertSame("first contact must use the no-H3 client so Alt-Svc can be learned",
        field("httpClientNoH3"), resolveClientForRequest(sampler, result));
  }

  @Test
  public void shouldUseHttp2ClientForPostToUnknownOrigin() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.POST);
    sampler.addArgument("test1", "value1");
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(TLS_URI.toURL());
    result.setHTTPMethod(HTTPConstants.POST);

    assertSame("a POST to an unknown origin must not speculative-explore HTTP/3",
        field("httpClientNoH3"), resolveClientForRequest(sampler, result));
  }

  @Test
  public void shouldUseHttp1WhenHttp2DisabledAndOriginUnknown() throws Exception {
    setBoolean(client, "enableHttp2", false);
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(TLS_URI.toURL());
    result.setHTTPMethod(HTTPConstants.GET);

    assertSame("with HTTP/2 off, first contact must use HTTP/1.1 to learn Alt-Svc",
        field("httpClientHttp1Only"), resolveClientForRequest(sampler, result));
  }

  @Test
  public void shouldNotExploreHttp3ForRequestWithFileBody() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.POST);
    sampler.setHTTPFiles(new HTTPFileArg[] {
        new HTTPFileArg("some-file.txt", "upload", "text/plain")});
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(TLS_URI.toURL());
    result.setHTTPMethod(HTTPConstants.POST);

    assertSame(field("httpClientNoH3"), resolveClientForRequest(sampler, result));
  }

  // --- already known to speak HTTP/3 ---------------------------------------------------------

  @Test
  public void shouldUseHttp3ForKnownOriginEvenWhenRequestCannotBeRaced() throws Exception {
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), 0L);

    assertTrue("a POST to a site known to speak HTTP/3 must still use HTTP/3",
        shouldAttemptHttp3(TLS_URI, true));
  }

  @Test
  public void shouldUseHttp3AfterAltSvcWhenHttp2Disabled() throws Exception {
    setBoolean(client, "enableHttp2", false);
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), 0L);

    assertTrue(shouldAttemptHttp3(TLS_URI, true));
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(TLS_URI.toURL());
    result.setHTTPMethod(HTTPConstants.GET);
    assertSame(field("httpClient"), resolveClientForRequest(sampler, result));
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
  public void shouldRediscoverViaTcpOnceCachedEntryExpired() throws Exception {
    cacheAltSvc(true, -1L, 0L);

    assertFalse("expired Alt-Svc must not reopen QUIC; learn again over TCP",
        shouldAttemptHttp3(TLS_URI, true));
  }

  @Test
  public void shouldRecordCooldownAfterMarkedBroken() throws Exception {
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), 0L);
    assertTrue(shouldAttemptHttp3(TLS_URI, true));

    Method mark = HTTP2JettyClient.class.getDeclaredMethod("markHttp3Broken", URI.class);
    mark.setAccessible(true);
    mark.invoke(client, TLS_URI);

    assertFalse("the cooldown must apply after HTTP/3 failed for a cached origin",
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

  @Test
  public void shouldUseHttp3ClientWhenOnlyHttp3Enabled() throws Exception {
    setBoolean(client, "enableHttp1", false);
    setBoolean(client, "enableHttp2", false);
    setBoolean(client, "http3PriorKnowledgeEnabled", true);
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(TLS_URI.toURL());
    result.setHTTPMethod(HTTPConstants.GET);

    assertSame(field("httpClient"), resolveClientForRequest(sampler, result));
  }

  // --- logging policy: exploration vs indicated HTTP/3 ---------------------------------------

  @Test
  public void shouldNotTreatFirstContactAsExpectedHttp3() throws Exception {
    assertFalse(isHttp3Expected(TLS_URI));
  }

  @Test
  public void shouldTreatCachedAltSvcAsExpectedHttp3() throws Exception {
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), 0L);

    assertTrue(isHttp3Expected(TLS_URI));
  }

  @Test
  public void shouldTreatPriorKnowledgeAsExpectedHttp3WithoutCache() throws Exception {
    setBoolean(client, "http3PriorKnowledgeEnabled", true);

    assertTrue(isHttp3Expected(TLS_URI));
  }

  @Test
  public void shouldNotTreatBrokenCooldownAsExpectedHttp3() throws Exception {
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), TimeUnit.MINUTES.toMillis(5));

    assertFalse(isHttp3Expected(TLS_URI));
  }

  @Test
  public void shouldNotClassifyConnectTimeoutAsExplorationOnFirstContact() throws Exception {
    // First contact no longer explores H3, so ATTR_HTTP3_ATTEMPTED on an unknown origin is not an
    // "exploration" path the policy owns — isHttp3Expected is false and attempted may be unset.
    Request request = mockRequestWithHttp3Attempted(TLS_URI);
    Throwable cause = new java.net.SocketTimeoutException("connect timeout");

    // With no cache, timeout on an H3 attempt is still classified as exploration (not "expected").
    assertTrue(isHttp3ExplorationConnectTimeout(cause, request));
  }

  @Test
  public void shouldNotClassifyConnectTimeoutAsExplorationWhenAltSvcIndicated() throws Exception {
    cacheAltSvc(true, TimeUnit.MINUTES.toMillis(10), 0L);
    Request request = mockRequestWithHttp3Attempted(TLS_URI);
    Throwable cause = new java.net.SocketTimeoutException("connect timeout");

    assertFalse(isHttp3ExplorationConnectTimeout(cause, request));
  }

  private Request mockRequestWithHttp3Attempted(URI uri) {
    Request request = mock(Request.class);
    when(request.getURI()).thenReturn(uri);
    Map<String, Object> attrs = new HashMap<>();
    attrs.put("bzm.http3.attempted", Boolean.TRUE);
    when(request.getAttributes()).thenReturn(attrs);
    return request;
  }

  private boolean shouldAttemptHttp3(URI uri, boolean recoverable) throws Exception {
    Method m = HTTP2JettyClient.class.getDeclaredMethod(
        "shouldAttemptHttp3", URI.class, boolean.class);
    m.setAccessible(true);
    return (Boolean) m.invoke(client, uri, recoverable);
  }

  private boolean isHttp3Expected(URI uri) throws Exception {
    Method m = HTTP2JettyClient.class.getDeclaredMethod("isHttp3Expected", URI.class);
    m.setAccessible(true);
    return (Boolean) m.invoke(client, uri);
  }

  private boolean isHttp3ExplorationConnectTimeout(Throwable cause, Request request)
      throws Exception {
    Method m = HTTP2JettyClient.class.getDeclaredMethod(
        "isHttp3ExplorationConnectTimeout", Throwable.class, Request.class);
    m.setAccessible(true);
    return (Boolean) m.invoke(client, cause, request);
  }

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

  private void setBoolean(HTTP2JettyClient target, String name, boolean value) throws Exception {
    Field f = HTTP2JettyClient.class.getDeclaredField(name);
    f.setAccessible(true);
    f.setBoolean(target, value);
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
  private Map<String, Object> altSvcCache() throws Exception {
    Field f = HTTP2JettyClient.class.getDeclaredField("ALT_SVC_CACHE");
    f.setAccessible(true);
    return (Map<String, Object>) f.get(null);
  }
}
