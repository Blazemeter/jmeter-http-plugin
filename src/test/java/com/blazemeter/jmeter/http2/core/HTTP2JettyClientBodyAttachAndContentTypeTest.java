package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Method;
import java.net.URL;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Test;

/**
 * HttpClient4 only attaches an entity for POST/PUT/PATCH, or for GET/DELETE when
 * {@code postBodyRaw} is enabled; and it never overwrites a {@code Content-Type} header the
 * user already set. {@link HTTP2JettyClient#setBody} used to attach a body regardless of the
 * request method, and its {@code hasContentTypeHeader} check was inverted
 * ({@code contentTypeHeader != null && contentTypeHeader.isEmpty()}, true only when the header
 * exists AND is blank), so it treated almost every real Content-Type header as absent and
 * clobbered it. Fixing that also lets a bodied cleartext request skip the {@code Upgrade: h2c}
 * dance entirely (see {@code ATTR_SKIP_H2C_UPGRADE}), since attaching a body to an in-flight h2c
 * upgrade isn't well-supported.
 */
public class HTTP2JettyClientBodyAttachAndContentTypeTest extends HTTP2TestBase {

  private TeardownableServer server;
  private HTTP2JettyClient client;

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void preservesExplicitContentTypeHeaderForRawPostBody() throws Exception {
    HTTPSampleResult result = sampleRawPostBody("application/json");

    assertThat(result.getRequestHeaders()).contains("Content-Type: application/json");
  }

  @Test
  public void defaultsRawPostBodyContentTypeToTextPlainWhenNotSet() throws Exception {
    HTTPSampleResult result = sampleRawPostBody(null);

    assertThat(result.getRequestHeaders()).contains("Content-Type: text/plain; charset=UTF-8");
  }

  @Test
  public void skipsH2cUpgradeHeadersForCleartextRequestWithBody() throws Exception {
    int port = startHttp1OnlyServer();
    // http1UpgradeRequired=true: the client would normally attempt the Upgrade: h2c dance on
    // this cleartext origin (see HTTP2JettyClientH2cFallbackTest for that base behavior with a
    // bodyless GET); a bodied request must skip it instead.
    client = new HTTP2JettyClient(true, "skip-h2c-upgrade-test");
    client.start();

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.POST);
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setPath(ServerBuilder.SERVER_PATH_200_WITH_BODY);
    sampler.setProtocol("http");
    sampler.setPostBodyRaw(true);
    sampler.addArgument("", "payload");

    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("POST_SKIP_H2C_UPGRADE");
    result.setHTTPMethod(HTTPConstants.POST);
    result.setURL(new URL("http", "localhost", port, ServerBuilder.SERVER_PATH_200_WITH_BODY));

    HTTPSampleResult sampled = client.sample(sampler, result, false, 0);

    assertThat(sampled.isSuccessful()).isTrue();
    assertThat(sampled.getRequestHeaders()).doesNotContainIgnoringCase("upgrade");
  }

  @Test
  public void attachesBodyForPostRegardlessOfPostBodyRawFlag() throws Exception {
    assertThat(invokeShouldAttachRequestBody(HTTPConstants.POST, false, false)).isTrue();
    assertThat(invokeShouldAttachRequestBody(HTTPConstants.PUT, false, false)).isTrue();
    assertThat(invokeShouldAttachRequestBody(HTTPConstants.PATCH, false, false)).isTrue();
  }

  @Test
  public void onlyAttachesBodyForGetOrDeleteWhenPostBodyRawEnabled() throws Exception {
    assertThat(invokeShouldAttachRequestBody(HTTPConstants.GET, false, false)).isFalse();
    assertThat(invokeShouldAttachRequestBody(HTTPConstants.GET, true, false)).isTrue();
    assertThat(invokeShouldAttachRequestBody(HTTPConstants.DELETE, false, false)).isFalse();
    assertThat(invokeShouldAttachRequestBody(HTTPConstants.DELETE, true, false)).isTrue();
  }

  @Test
  public void neverAttachesBodyWhenFollowingARedirectToANonBodyMethod() throws Exception {
    // A 302/303 redirect downgrades the follow-up request to GET, dropping any original body -
    // matching HttpClient4, which never resends an entity after that kind of redirect.
    assertThat(invokeShouldAttachRequestBody(HTTPConstants.GET, true, true)).isFalse();
    assertThat(invokeShouldAttachRequestBody(HTTPConstants.POST, false, true)).isTrue();
  }

  private HTTPSampleResult sampleRawPostBody(String explicitContentType) throws Exception {
    int port = startHttp1OnlyServer();
    client = new HTTP2JettyClient(false, "raw-post-body-content-type-test");
    client.start();

    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.POST);
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setPath(ServerBuilder.SERVER_PATH_200_WITH_BODY);
    sampler.setProtocol("http");
    sampler.setPostBodyRaw(true);
    sampler.addArgument("", "raw-body-content");
    if (explicitContentType != null) {
      HeaderManager headerManager = new HeaderManager();
      headerManager.add(new Header(HttpHeader.CONTENT_TYPE.asString(), explicitContentType));
      sampler.setHeaderManager(headerManager);
    }

    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("POST_RAW_BODY_CONTENT_TYPE");
    result.setHTTPMethod(HTTPConstants.POST);
    result.setURL(new URL("http", "localhost", port, ServerBuilder.SERVER_PATH_200_WITH_BODY));

    return client.sample(sampler, result, false, 0);
  }

  private int startHttp1OnlyServer() throws Exception {
    server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private boolean invokeShouldAttachRequestBody(String method, boolean postBodyRaw,
                                                boolean areFollowingRedirect) throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(method);
    sampler.setPostBodyRaw(postBodyRaw);

    HTTPSampleResult result = new HTTPSampleResult();
    result.setHTTPMethod(method);

    HTTP2JettyClient reflectionClient = new HTTP2JettyClient(false, "should-attach-body-test");
    Method shouldAttachRequestBody = HTTP2JettyClient.class.getDeclaredMethod(
        "shouldAttachRequestBody", HTTP2Sampler.class, HTTPSampleResult.class, boolean.class);
    shouldAttachRequestBody.setAccessible(true);
    return (boolean) shouldAttachRequestBody.invoke(
        reflectionClient, sampler, result, areFollowingRedirect);
  }
}
