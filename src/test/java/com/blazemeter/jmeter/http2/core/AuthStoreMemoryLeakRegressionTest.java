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
import java.net.URL;
import java.util.List;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Long runs with an Auth Manager used to append a new Jetty {@code Authentication} on every sample
 * (no dedup in {@code HttpAuthenticationStore}). Memory then grew with iterations and never
 * recycled until the thread finished.
 *
 * <p>Registration must stay idempotent without {@code clearAuthentications()} on every sample:
 * clearing raced with in-flight 401 handling on the shared store under concurrent async samples.
 */
public class AuthStoreMemoryLeakRegressionTest extends HTTP2TestBase {

  private static final int SAMPLE_COUNT = 40;

  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;
  private TeardownableServer server;
  private String originalSharedThreadPoolProperty;
  private String originalEnableHttp1Property;
  private String originalEnableHttp2Property;
  private String originalEnableHttp3Property;
  private String originalAuthPreemptiveProperty;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    originalSharedThreadPoolProperty = JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    originalEnableHttp1Property = JMeterUtils.getProperty("httpJettyClient.enableHttp1");
    originalEnableHttp2Property = JMeterUtils.getProperty("httpJettyClient.enableHttp2");
    originalEnableHttp3Property = JMeterUtils.getProperty("httpJettyClient.enableHttp3");
    originalAuthPreemptiveProperty = JMeterUtils.getProperty("httpJettyClient.auth.preemptive");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");
    // Server is HTTP/1.1 only — keep HE/HTTP3 off so 40 samples are not burned on QUIC races.
    JMeterUtils.setProperty("httpJettyClient.enableHttp1", "true");
    JMeterUtils.setProperty("httpJettyClient.enableHttp2", "false");
    JMeterUtils.setProperty("httpJettyClient.enableHttp3", "false");
    // This test counts Jetty's Authentication list. Preemptive mode registers Results instead,
    // leaving authentications empty (0) — and another unit test can leave preemptive=true in the
    // shared JMeter properties (filesystem run order differs on Linux CI vs Windows).
    JMeterUtils.setProperty("httpJettyClient.auth.preemptive", "false");
    HTTP2JettyClientTestIsolation.resetSharedClientState();

    server = new ServerBuilder().withHTTP1().withSSL().withBasicAuth().buildServer();
    server.start();
    int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

    sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain(HOST_NAME);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(port);
    sampler.setPath(SERVER_PATH_200);

    Authorization authorization = new Authorization();
    authorization.setURL(new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, port,
        SERVER_PATH_200).toString());
    authorization.setUser(AUTH_USERNAME);
    authorization.setPass(AUTH_PASSWORD);
    authorization.setRealm(AUTH_REALM);
    authorization.setMechanism(Mechanism.BASIC);
    AuthManager authManager = new AuthManager();
    authManager.addAuth(authorization);
    sampler.setAuthManager(authManager);

    client = new HTTP2JettyClient(false, "auth-store-leak",
        HTTP2ClientProfileConfig.builder()
            .enableHttp1(true)
            .enableHttp2(false)
            .enableHttp3(false)
            .build());
    client.start();
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
    }
    if (server != null && server.isStarted()) {
      server.stop();
    }
    restoreProperty("httpJettyClient.sharedThreadPool", originalSharedThreadPoolProperty);
    restoreProperty("httpJettyClient.enableHttp1", originalEnableHttp1Property);
    restoreProperty("httpJettyClient.enableHttp2", originalEnableHttp2Property);
    restoreProperty("httpJettyClient.enableHttp3", originalEnableHttp3Property);
    restoreProperty("httpJettyClient.auth.preemptive", originalAuthPreemptiveProperty);
  }

  private static void restoreProperty(String key, String originalValue) {
    if (originalValue == null) {
      JMeterUtils.getJMeterProperties().remove(key);
    } else {
      JMeterUtils.setProperty(key, originalValue);
    }
  }

  @Test
  public void jettyAuthStoreMustNotGrowWithSampleCount() throws Exception {
    for (int i = 0; i < SAMPLE_COUNT; i++) {
      HTTPSampleResult result = client.sample(sampler,
          buildResult(sampler), false, 0);
      assertThat(result.isSuccessful())
          .as("sample %d should authenticate", i)
          .isTrue();
    }

    // H1-only profile samples via httpClientHttp1Only; registration fans out to every store.
    int authentications = countAuthentications(http1OnlyClient(client));
    assertThat(authentications)
        .as("after %d samples the Jetty auth list must stay at the configured Auth Manager size, "
            + "not grow per iteration (pre-fix it grew unboundedly). Size 0 usually means "
            + "httpJettyClient.auth.preemptive was left true by another test.", SAMPLE_COUNT)
        .isEqualTo(1);
  }

  private static HTTPSampleResult buildResult(HTTP2Sampler sampler) throws Exception {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(sampler.getUrl());
    result.setHTTPMethod(sampler.getMethod());
    return result;
  }

  private static HttpClient http1OnlyClient(HTTP2JettyClient client) throws Exception {
    Field field = HTTP2JettyClient.class.getDeclaredField("httpClientHttp1Only");
    field.setAccessible(true);
    return (HttpClient) field.get(client);
  }

  @SuppressWarnings("unchecked")
  private static int countAuthentications(HttpClient httpClient) throws Exception {
    AuthenticationStore store = httpClient.getAuthenticationStore();
    Field field = store.getClass().getDeclaredField("authentications");
    field.setAccessible(true);
    return ((List<?>) field.get(store)).size();
  }
}
