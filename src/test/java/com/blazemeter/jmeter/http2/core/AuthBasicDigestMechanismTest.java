package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.AUTH_PASSWORD;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.AUTH_REALM;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.AUTH_USERNAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.AbstractAuthentication;
import org.eclipse.jetty.client.Authentication;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * {@code BASIC_DIGEST} is deprecated in JMeter but still selectable and still present in older
 * plans, and {@code HTTPHC4Impl} keeps honouring it: the credentials are registered without being
 * bound to a scheme, so they answer either challenge. This plugin used to drop those rows in
 * {@code isSupportedMechanism} without a word, leaving the plan silently unauthenticated.
 *
 * <p>Jetty binds an {@code Authentication} to one challenge type, so a single {@code BASIC_DIGEST}
 * row has to become two registrations - which is what these tests pin, along with the dedup that
 * keeps them from piling up per sample.
 */
public class AuthBasicDigestMechanismTest extends HTTP2TestBase {

  private static final String PREEMPTIVE_PROPERTY = "httpJettyClient.auth.preemptive";

  private String originalPreemptiveProperty;
  private TeardownableServer server;
  private HTTP2JettyClient client;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    originalPreemptiveProperty = JMeterUtils.getProperty(PREEMPTIVE_PROPERTY);
    // Registering Jetty Authentications is the reactive path; preemptive mode registers Results
    // instead and another test may have left the shared property on.
    JMeterUtils.setProperty(PREEMPTIVE_PROPERTY, "false");
    HTTP2JettyClientTestIsolation.resetSharedClientState();
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (server != null && server.isStarted()) {
      server.stop();
    }
    if (originalPreemptiveProperty == null) {
      JMeterUtils.getJMeterProperties().remove(PREEMPTIVE_PROPERTY);
    } else {
      JMeterUtils.setProperty(PREEMPTIVE_PROPERTY, originalPreemptiveProperty);
    }
  }

  @Test
  public void authenticatesAgainstABasicServerWithABasicDigestRow() throws Exception {
    int port = startAuthServer(new ServerBuilder().withHTTP1().withSSL().withBasicAuth());
    HTTP2Sampler sampler = samplerWith(Mechanism.BASIC_DIGEST, port);

    HTTPSampleResult result = sample(sampler);

    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseCode()).isEqualTo("200");
  }

  @Test
  public void authenticatesAgainstADigestServerWithABasicDigestRow() throws Exception {
    int port = startAuthServer(new ServerBuilder().withHTTP1().withSSL().withDigestAuth());
    HTTP2Sampler sampler = samplerWith(Mechanism.BASIC_DIGEST, port);

    HTTPSampleResult result = sample(sampler);

    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseCode()).isEqualTo("200");
  }

  @Test
  public void registersOneBasicAndOneDigestAuthenticationForABasicDigestRow() throws Exception {
    int port = startAuthServer(new ServerBuilder().withHTTP1().withSSL().withBasicAuth());
    HTTP2Sampler sampler = samplerWith(Mechanism.BASIC_DIGEST, port);

    sample(sampler);

    assertThat(authenticationTypes()).containsExactlyInAnyOrder("Basic", "Digest");
  }

  @Test
  public void registersOnlyTheMatchingAuthenticationForASingleMechanismRow() throws Exception {
    int port = startAuthServer(new ServerBuilder().withHTTP1().withSSL().withBasicAuth());
    HTTP2Sampler sampler = samplerWith(Mechanism.BASIC, port);

    sample(sampler);

    assertThat(authenticationTypes()).containsExactly("Basic");
  }

  @Test
  public void keepsBothRegistrationsIdempotentAcrossSamples() throws Exception {
    int port = startAuthServer(new ServerBuilder().withHTTP1().withSSL().withBasicAuth());
    HTTP2Sampler sampler = samplerWith(Mechanism.BASIC_DIGEST, port);

    for (int i = 0; i < 5; i++) {
      assertThat(sample(sampler).isSuccessful())
          .as("sample %d should authenticate", i)
          .isTrue();
    }

    assertThat(authenticationTypes()).containsExactlyInAnyOrder("Basic", "Digest");
  }

  @Test
  public void sendsThePreemptiveBasicHeaderForABasicDigestRow() throws Exception {
    JMeterUtils.setProperty(PREEMPTIVE_PROPERTY, "true");
    client = new HTTP2JettyClient(false, "auth-basic-digest-preemptive", http1OnlyProfile());
    client.start();
    URL url = new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, 8443, SERVER_PATH_200);
    HttpClient probeClient = http1OnlyClient(client);
    Request request = probeClient.newRequest(URI.create(url.toString()));

    invokePreemptiveHeader(client, request, url, authManagerWith(Mechanism.BASIC_DIGEST, url));

    assertThat(request.getHeaders().get(HttpHeader.AUTHORIZATION)).startsWith("Basic ");
  }

  private int startAuthServer(ServerBuilder builder) throws Exception {
    server = builder.buildServer();
    server.start();
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private HTTP2Sampler samplerWith(Mechanism mechanism, int port) throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain(HOST_NAME);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(port);
    sampler.setPath(SERVER_PATH_200);
    sampler.setAuthManager(authManagerWith(mechanism,
        new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, port, SERVER_PATH_200)));
    return sampler;
  }

  private AuthManager authManagerWith(Mechanism mechanism, URL url) {
    Authorization authorization = new Authorization();
    authorization.setURL(url.toString());
    authorization.setUser(AUTH_USERNAME);
    authorization.setPass(AUTH_PASSWORD);
    authorization.setRealm(AUTH_REALM);
    authorization.setMechanism(mechanism);
    AuthManager authManager = new AuthManager();
    authManager.addAuth(authorization);
    return authManager;
  }

  private HTTPSampleResult sample(HTTP2Sampler sampler) throws Exception {
    if (client == null) {
      client = new HTTP2JettyClient(false, "auth-basic-digest", http1OnlyProfile());
      client.start();
    }
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(sampler.getUrl());
    result.setHTTPMethod(sampler.getMethod());
    return client.sample(sampler, result, false, 0);
  }

  private List<String> authenticationTypes() throws Exception {
    return registeredAuthentications(http1OnlyClient(client)).stream()
        .map(authentication -> ((AbstractAuthentication) authentication).getType())
        .collect(Collectors.toList());
  }

  private static HTTP2ClientProfileConfig http1OnlyProfile() {
    // The auth servers here speak HTTP/1.1 only; racing HTTP/3 would just burn the samples.
    return HTTP2ClientProfileConfig.builder()
        .enableHttp1(true)
        .enableHttp2(false)
        .enableHttp3(false)
        .build();
  }

  private static HttpClient http1OnlyClient(HTTP2JettyClient client) throws Exception {
    Field field = HTTP2JettyClient.class.getDeclaredField("httpClientHttp1Only");
    field.setAccessible(true);
    return (HttpClient) field.get(client);
  }

  private static List<Authentication> registeredAuthentications(HttpClient httpClient)
      throws Exception {
    AuthenticationStore store = httpClient.getAuthenticationStore();
    Field field = store.getClass().getDeclaredField("authentications");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<Authentication> authentications = (List<Authentication>) field.get(store);
    return authentications;
  }

  private static void invokePreemptiveHeader(HTTP2JettyClient client, Request request, URL url,
                                             AuthManager authManager) throws Exception {
    Method method = HTTP2JettyClient.class.getDeclaredMethod("addPreemptiveAuthorizationHeader",
        Request.class, URL.class, AuthManager.class);
    method.setAccessible(true);
    method.invoke(client, request, url, authManager);
  }
}
