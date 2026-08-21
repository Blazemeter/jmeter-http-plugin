package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.http.conn.DnsResolver;
import org.apache.jmeter.protocol.http.control.DNSCacheManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.junit.After;
import org.junit.Test;

/**
 * A DNS Cache Manager in the plan has to reach this plugin's transport the same way it reaches
 * {@code HTTPHC4Impl}: bound once when the per-thread client is created, applied to every protocol
 * variant, and consulted for the host Jetty actually connects to - which under a proxy is the
 * proxy, not the target.
 *
 * <p>End-to-end coverage here uses a static host entry rather than a mock DNS server. JMeter's own
 * {@code DNSCacheManagerTest} points a mock server at an ephemeral port, which its
 * {@code parseHostPort} makes possible only on master; on the 5.5 line this plugin targets,
 * {@code createResolver} feeds host names straight to dnsjava's {@code ExtendedResolver} and a
 * custom port cannot be expressed. A static host entry exercises the same integration and is the
 * configuration load tests actually use.
 */
public class HTTP2JettyClientDnsCacheManagerTest extends HTTP2TestBase {

  private static final String[] PROTOCOL_CLIENT_FIELDS = {
      "httpClient", "httpClientNoH3", "httpClientHttp1Only", "httpClientH2cPrior",
      "httpClientH2cUpgrade"
  };
  private static final String STATIC_HOST = "jmeter.example.org";

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
  public void installsJmeterResolverOnEveryProtocolClientWhenThePlanHasADnsCacheManager()
      throws Exception {
    client = new HTTP2JettyClient(false, "dns-manager-test", null, staticHostManager());

    for (HttpClient protocolClient : protocolClients(client)) {
      assertThat(protocolClient.getSocketAddressResolver())
          .isInstanceOf(JMeterDnsSocketAddressResolver.class);
    }
  }

  @Test
  public void keepsJettyDefaultResolverWhenThePlanHasNoDnsCacheManager() throws Exception {
    client = new HTTP2JettyClient(false, "dns-default-test");
    client.start();

    for (HttpClient protocolClient : protocolClients(client)) {
      assertThat(protocolClient.getSocketAddressResolver())
          .isInstanceOf(SocketAddressResolver.Async.class);
    }
  }

  @Test
  public void samplesThroughAStaticHostEntryInsteadOfSystemResolution() throws Exception {
    int port = startServer();
    client = new HTTP2JettyClient(false, "dns-static-host-test", null, staticHostManager());
    client.start();

    HTTPSampleResult result = sampleGet(STATIC_HOST, port);

    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseCode()).isEqualTo("200");
  }

  @Test
  public void resolvesTheProxyHostAndNeverTheTargetHost() throws Exception {
    int port = startServer();
    RecordingDnsResolver recordingResolver = new RecordingDnsResolver();
    client = new HTTP2JettyClient(false, "dns-proxy-test", null, recordingResolver);
    client.start();

    HTTP2Sampler sampler = newSampler("target.invalid", port);
    sampler.setProxyHost("localhost");
    sampler.setProxyPortInt(String.valueOf(port));
    sampler.setProxyScheme("http");
    try {
      client.sample(sampler, newResult("target.invalid", port), false, 0);
    } catch (Exception acceptable) {
      // The proxy here is a plain HTTP server, so the exchange may well fail. What is under test
      // is which host reached the resolver, and that is recorded before any of that matters.
    }

    assertThat(recordingResolver.resolvedHosts).contains("localhost");
    assertThat(recordingResolver.resolvedHosts).doesNotContain("target.invalid");
  }

  @Test
  public void clientCacheKeySeparatesSamplersByDnsCacheManager() throws Exception {
    HTTP2Sampler withoutManager = newSampler("localhost", 8080);
    HTTP2Sampler withManager = newSampler("localhost", 8080);
    withManager.setDNSResolver(staticHostManager());
    HTTP2Sampler withAnotherManager = newSampler("localhost", 8080);
    withAnotherManager.setDNSResolver(staticHostManager());

    String keyWithoutManager = profileKey(withoutManager);
    String keyWithManager = profileKey(withManager);
    String keyWithAnotherManager = profileKey(withAnotherManager);

    assertThat(keyWithoutManager).isNotEqualTo(keyWithManager);
    assertThat(keyWithManager).isNotEqualTo(keyWithAnotherManager);
    assertThat(profileKey(withManager)).isEqualTo(keyWithManager);
  }

  private int startServer() throws Exception {
    server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private DNSCacheManager staticHostManager() {
    DNSCacheManager dnsCacheManager = new DNSCacheManager();
    dnsCacheManager.addHost(STATIC_HOST, "127.0.0.1");
    return dnsCacheManager;
  }

  private HTTPSampleResult sampleGet(String domain, int port) throws Exception {
    return client.sample(newSampler(domain, port), newResult(domain, port), false, 0);
  }

  private HTTP2Sampler newSampler(String domain, int port) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod("GET");
    sampler.setDomain(domain);
    sampler.setPort(port);
    sampler.setPath(ServerBuilder.SERVER_PATH_200);
    sampler.setProtocol("http");
    return sampler;
  }

  private HTTPSampleResult newResult(String domain, int port) throws Exception {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel("GET_DNS_CACHE_MANAGER");
    result.setHTTPMethod("GET");
    result.setURL(new URL("http", domain, port, ServerBuilder.SERVER_PATH_200));
    return result;
  }

  private static List<HttpClient> protocolClients(HTTP2JettyClient client) throws Exception {
    List<HttpClient> clients = new CopyOnWriteArrayList<>();
    for (String fieldName : PROTOCOL_CLIENT_FIELDS) {
      Field field = HTTP2JettyClient.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      clients.add((HttpClient) field.get(client));
    }
    return clients;
  }

  private static String profileKey(HTTP2Sampler sampler) throws Exception {
    Method method = HTTP2Sampler.class.getDeclaredMethod("buildProfileKey");
    method.setAccessible(true);
    return (String) method.invoke(sampler);
  }

  private static final class RecordingDnsResolver implements DnsResolver {

    private final List<String> resolvedHosts = new CopyOnWriteArrayList<>();

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
      resolvedHosts.add(host);
      return InetAddress.getAllByName("127.0.0.1");
    }
  }
}
