package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.SourceType;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * HC4 binds the source address per request; Jetty only offers it per client, so the binding has to
 * reach every protocol-variant client and every connector this wrapper builds - including the QUIC
 * one, which no transport {@code doStart} propagates to - and the per-thread client cache has to
 * stop two samplers spoofing different IPs from sharing one client.
 */
public class HTTP2JettyClientSourceAddressTest extends HTTP2TestBase {

  private static final String[] PROTOCOL_CLIENT_FIELDS = {
      "httpClient", "httpClientNoH3", "httpClientHttp1Only", "httpClientH2cPrior",
      "httpClientH2cUpgrade"
  };

  private final List<String[]> savedProperties = new ArrayList<>();

  private TeardownableServer server;
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;

  @Before
  public void setUp() throws Exception {
    HTTP2JettyClientTestIsolation.resetSharedClientState();
  }

  @After
  public void tearDown() throws Exception {
    if (sampler != null) {
      // Drops any client the real factory cached on this thread while sampling.
      sampler.threadFinished();
    }
    if (client != null) {
      client.stop();
    }
    if (server != null && server.isStarted()) {
      server.stop();
    }
    for (String[] saved : savedProperties) {
      if (saved[1] == null) {
        JMeterUtils.getJMeterProperties().remove(saved[0]);
      } else {
        JMeterUtils.setProperty(saved[0], saved[1]);
      }
    }
  }

  @Test
  public void bindsEveryProtocolClientToTheSourceAddress() throws Exception {
    client = new HTTP2JettyClient(false, "source-address-clients");
    InetAddress loopback = InetAddress.getLoopbackAddress();

    client.setSourceAddress(loopback);

    SocketAddress expected = new InetSocketAddress(loopback, 0);
    for (HttpClient protocolClient : protocolClients(client)) {
      assertThat(protocolClient.getBindAddress()).isEqualTo(expected);
    }
  }

  @Test
  public void bindsEveryConnectorToTheSourceAddress() throws Exception {
    client = new HTTP2JettyClient(false, "source-address-connectors");
    InetAddress loopback = InetAddress.getLoopbackAddress();

    client.setSourceAddress(loopback);

    SocketAddress expected = new InetSocketAddress(loopback, 0);
    List<ClientConnector> connectors = trackedConnectors(client);
    assertThat(connectors).isNotEmpty();
    for (ClientConnector connector : connectors) {
      assertThat(connector.getBindAddress()).isEqualTo(expected);
    }
  }

  @Test
  public void leavesTheBindAddressUnsetWhenNoSourceAddressApplies() throws Exception {
    client = new HTTP2JettyClient(false, "source-address-none");

    for (HttpClient protocolClient : protocolClients(client)) {
      assertThat(protocolClient.getBindAddress()).isNull();
    }
  }

  @Test
  public void clearsTheBindAddressWhenTheSourceAddressIsRemoved() throws Exception {
    client = new HTTP2JettyClient(false, "source-address-cleared");
    client.setSourceAddress(InetAddress.getLoopbackAddress());

    client.setSourceAddress(null);

    for (HttpClient protocolClient : protocolClients(client)) {
      assertThat(protocolClient.getBindAddress()).isNull();
    }
  }

  @Test
  public void samplesThroughTheConfiguredSourceAddress() throws Exception {
    int port = startServer();
    client = new HTTP2JettyClient(false, "source-address-sample");
    client.setSourceAddress(InetAddress.getLoopbackAddress());
    client.start();

    HTTPSampleResult result = client.sample(newSampler("localhost", port),
        newResult("localhost", port), false, 0);

    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseCode()).isEqualTo("200");
  }

  @Test
  public void connectionsActuallyLeaveFromTheConfiguredSourceAddress() throws Exception {
    // The only assertion here that proves the bind reached the socket: without it the OS would
    // pick 127.0.0.1 for a loopback connection. Needs an alias the platform actually owns -
    // Linux and Windows hold all of 127.0.0.0/8, macOS only 127.0.0.1 unless one is added.
    InetAddress alias = InetAddress.getByName("127.0.0.2");
    assumeTrue("127.0.0.2 is not bindable on this platform", isBindable(alias));
    int port = startServer();
    List<String> remoteAddresses = new CopyOnWriteArrayList<>();
    ((ServerConnector) server.getConnectors()[0]).addBean(new Connection.Listener() {
      @Override
      public void onOpened(Connection connection) {
        remoteAddresses.add(connection.getEndPoint().getRemoteSocketAddress().toString());
      }

      @Override
      public void onClosed(Connection connection) {
        // Only the accepted address matters here.
      }
    });
    client = new HTTP2JettyClient(false, "source-address-onwire");
    client.setSourceAddress(alias);
    client.start();

    HTTPSampleResult result = client.sample(newSampler("127.0.0.1", port),
        newResult("127.0.0.1", port), false, 0);

    assertThat(result.isSuccessful()).isTrue();
    assertThat(remoteAddresses).isNotEmpty();
    assertThat(remoteAddresses).allSatisfy(
        remote -> assertThat(remote).contains("127.0.0.2"));
  }

  @Test
  public void failsTheSampleWhenTheConfiguredDeviceDoesNotExist() throws Exception {
    int port = startServer();
    sampler = newSampler("localhost", port);
    sampler.setIpSource("no-such-interface");
    sampler.setIpSourceType(SourceType.DEVICE.ordinal());

    SampleResult result = sampler.sample();

    // Same outcome as HC4, where getIpSourceAddress throws out of setupRequest.
    assertThat(result.isSuccessful()).isFalse();
    assertThat(result.getResponseCode())
        .isEqualTo("Non HTTP response code: java.net.UnknownHostException");
  }

  @Test
  public void clientCacheKeySeparatesSamplersByConfiguredSourceAddress() throws Exception {
    HTTP2Sampler withoutSource = newSampler("localhost", 8080);
    HTTP2Sampler withSource = newSampler("localhost", 8080);
    withSource.setIpSource("127.0.0.1");
    HTTP2Sampler withAnotherSource = newSampler("localhost", 8080);
    withAnotherSource.setIpSource("127.0.0.2");
    HTTP2Sampler withAnotherType = newSampler("localhost", 8080);
    withAnotherType.setIpSource("127.0.0.1");
    withAnotherType.setIpSourceType(SourceType.DEVICE.ordinal());

    assertThat(profileKey(withoutSource)).isNotEqualTo(profileKey(withSource));
    assertThat(profileKey(withSource)).isNotEqualTo(profileKey(withAnotherSource));
    assertThat(profileKey(withSource)).isNotEqualTo(profileKey(withAnotherType));
    assertThat(profileKey(withSource)).isEqualTo(profileKey(withSource));
  }

  @Test
  public void clientCacheKeyFollowsTheLocalAddressPropertyWhenNoSamplerFieldIsSet()
      throws Exception {
    // Two samplers with no Source address field bind to whatever httpclient.localaddress says, so
    // sharing a client is right while that value holds - but a cached client keeps the address it
    // was built with, and a plan can change a JMeter property at runtime.
    HTTP2Sampler sampler = newSampler("localhost", 8080);
    String withoutProperty = profileKey(sampler);

    overrideProperty("httpclient.localaddress", "127.0.0.1");
    String withProperty = profileKey(sampler);
    overrideProperty("httpclient.localaddress", "127.0.0.2");
    String withAnotherProperty = profileKey(sampler);

    assertThat(withoutProperty).isNotEqualTo(withProperty);
    assertThat(withProperty).isNotEqualTo(withAnotherProperty);
  }

  @Test
  public void clientCacheKeyPrefersTheSamplerFieldOverTheLocalAddressProperty() throws Exception {
    // The field wins in resolve(), so it has to win in the key too: the property is irrelevant to
    // what these clients bind to.
    HTTP2Sampler sampler = newSampler("localhost", 8080);
    sampler.setIpSource("127.0.0.1");
    String withoutProperty = profileKey(sampler);

    overrideProperty("httpclient.localaddress", "127.0.0.2");

    assertThat(profileKey(sampler)).isEqualTo(withoutProperty);
  }

  private void overrideProperty(String key, String value) {
    savedProperties.add(new String[] {key, JMeterUtils.getProperty(key)});
    JMeterUtils.setProperty(key, value);
  }

  private static boolean isBindable(InetAddress address) {
    try (Socket probe = new Socket()) {
      probe.bind(new InetSocketAddress(address, 0));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private int startServer() throws Exception {
    server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private static HTTP2Sampler newSampler(String domain, int port) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain(domain);
    sampler.setPort(port);
    sampler.setPath(SERVER_PATH_200);
    sampler.setProtocol("http");
    return sampler;
  }

  private static HTTPSampleResult newResult(String domain, int port) throws Exception {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("GET_SOURCE_ADDRESS");
    result.setHTTPMethod(HTTPConstants.GET);
    result.setURL(new URL("http", domain, port, SERVER_PATH_200));
    return result;
  }

  private static List<HttpClient> protocolClients(HTTP2JettyClient client) throws Exception {
    List<HttpClient> clients = new ArrayList<>();
    for (String fieldName : PROTOCOL_CLIENT_FIELDS) {
      clients.add((HttpClient) readField(client, fieldName));
    }
    return clients;
  }

  @SuppressWarnings("unchecked")
  private static List<ClientConnector> trackedConnectors(HTTP2JettyClient client) throws Exception {
    return (List<ClientConnector>) readField(client, "connectors");
  }

  private static Object readField(HTTP2JettyClient client, String fieldName) throws Exception {
    Field field = HTTP2JettyClient.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(client);
  }

  private static String profileKey(HTTP2Sampler sampler) throws Exception {
    Method method = HTTP2Sampler.class.getDeclaredMethod("buildProfileKey");
    method.setAccessible(true);
    return (String) method.invoke(sampler);
  }
}
