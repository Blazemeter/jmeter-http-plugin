package com.blazemeter.jmeter.http2.core;

import static org.junit.Assert.assertSame;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Pins which client a bodied cleartext request gets.
 *
 * <p>Such requests are diverted to the HTTP/1.1-only client to sidestep the h2c Upgrade dance,
 * whose first request travels as plain HTTP/1.1 and which servers handle inconsistently when it
 * carries a body. That is a shortcut around a negotiation, not a protocol choice, so it must not
 * survive where HTTP/1.1 is disabled or where h2c is spoken from the first byte anyway — issue
 * #157, where POSTs to an h2c-only endpoint went out as HTTP/1.1 and the server's HTTP/2 frames
 * came back through the HTTP/1.1 parser as {@code Illegal character CNTL=0x0}.
 */
public class CleartextBodyClientSelectionTest extends HTTP2TestBase {

  private static final URI CLEARTEXT_URI = URI.create("http://h2c-only.example.com:8000/policies");

  private HTTP2JettyClient client;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    HTTP2JettyClient.clearStaticProtocolCaches();
    client = new HTTP2JettyClient();
    setBoolean("enableHttp1", true);
    setBoolean("enableHttp2", true);
    setBoolean("enableHttp3", false);
    setBoolean("http1UpgradeRequired", false);
    setBoolean("http2PriorKnowledgeEnabled", false);
    setBoolean("http3PriorKnowledgeEnabled", false);
    setBoolean("h2cCacheEnabled", false);
  }

  @Test
  public void shouldUseHttp1OnlyForCleartextPostWhenUpgradeIsTheAlternative() throws Exception {
    setBoolean("http1UpgradeRequired", true);

    assertSame("a bodied cleartext POST must skip the h2c Upgrade dance",
        field("httpClientHttp1Only"), resolveClientForRequest(post()));
  }

  @Test
  public void shouldNotUseHttp1OnlyForCleartextPostWhenHttp1IsDisabled() throws Exception {
    setBoolean("enableHttp1", false);
    setBoolean("http1UpgradeRequired", true);

    assertSame("with HTTP/1.1 disabled a bodied POST must still be spoken as h2c",
        field("httpClientH2cPrior"), resolveClientForRequest(post()));
  }

  @Test
  public void shouldNotUseHttp1OnlyForCleartextPostWithH2cPriorKnowledge() throws Exception {
    setBoolean("http2PriorKnowledgeEnabled", true);

    assertSame("prior knowledge means there is no Upgrade to avoid",
        field("httpClientH2cPrior"), resolveClientForRequest(post()));
  }

  @Test
  public void shouldUseHttp1OnlyForCleartextGetSendingParametersAsBody() throws Exception {
    setBoolean("http1UpgradeRequired", true);
    HTTP2Sampler sampler = sampler(HTTPConstants.GET);
    sampler.setPostBodyRaw(true);

    assertSame(field("httpClientHttp1Only"),
        resolveClientForRequest(sampler, result(HTTPConstants.GET)));
  }

  private HTTP2Sampler post() {
    HTTP2Sampler sampler = sampler(HTTPConstants.POST);
    sampler.addArgument("policy", "value");
    return sampler;
  }

  private HTTP2Sampler sampler(String method) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(method);
    return sampler;
  }

  private HTTPSampleResult result(String method) throws Exception {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(CLEARTEXT_URI.toURL());
    result.setHTTPMethod(method);
    return result;
  }

  private Object resolveClientForRequest(HTTP2Sampler sampler) throws Exception {
    return resolveClientForRequest(sampler, result(HTTPConstants.POST));
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

  private void setBoolean(String name, boolean value) throws Exception {
    Field f = HTTP2JettyClient.class.getDeclaredField(name);
    f.setAccessible(true);
    f.setBoolean(client, value);
  }

}
