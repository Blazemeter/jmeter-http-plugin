package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The end of the thread this started from: a host that resolves to more than one address and
 * refuses all of them used to report only the last failure, because
 * {@code HttpClient.connect(List, int, Map)} drops the earlier ones without a log or an
 * {@code addSuppressed}. With the JDK trying IPv4 first, that meant the IPv6 error was the only
 * one anybody ever saw.
 *
 * <p>{@code localhost} is the portable way to get a multi-address origin: it maps to
 * {@code 127.0.0.1} and, on most machines, {@code ::1} as well. The assertion is written against
 * whatever this machine actually resolves, so it still means something where only one family is
 * configured - it just proves more where both are.
 *
 * <p>The two stages covered here, a refused socket and a failed TLS handshake, share the single
 * recovery point with the third one - a failed HTTP/2 preface. Driving that one end to end would
 * need either a hand-rolled ALPN server that goes quiet after the handshake, or an H2C upgrade
 * timeout to reach {@code sendWithH2cPriorKnowledge}; it is covered in
 * {@link ConnectAttemptRecorderTest} instead.
 */
public class ConnectAttemptVisibilityTest extends HTTP2TestBase {

  private static final String ENABLE_HTTP1 = "httpJettyClient.enableHttp1";
  private static final String ENABLE_HTTP2 = "httpJettyClient.enableHttp2";
  private static final String ENABLE_HTTP3 = "httpJettyClient.enableHttp3";

  private final List<String[]> savedProperties = new ArrayList<>();

  private HTTP2Sampler sampler;

  @Before
  public void setUp() throws Exception {
    // Nothing is listening, so every protocol variant would just repeat the same refusals; one
    // transport keeps the recorded attempts to the addresses actually under test.
    overrideProperty(ENABLE_HTTP1, "true");
    overrideProperty(ENABLE_HTTP2, "false");
    overrideProperty(ENABLE_HTTP3, "false");
    HTTP2JettyClientTestIsolation.resetSharedClientState();
  }

  @After
  public void tearDown() throws Exception {
    if (sampler != null) {
      sampler.threadFinished();
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
  public void reportsTheAttemptsJettyDiscardedNotOnlyTheLastOne() throws Exception {
    InetAddress[] addresses = InetAddress.getAllByName("localhost");
    assumeTrue("localhost resolves to a single address here, so nothing gets discarded",
        addresses.length > 1);
    int closedPort = closedPort();
    sampler = samplerAgainst(closedPort);

    SampleResult result = sampler.sample();

    assertThat(result.isSuccessful()).isFalse();
    String responseData = result.getResponseDataAsString();
    // Jetty walks the addresses in resolution order and propagates only the last failure, so
    // every address but the last is the one that used to vanish.
    for (int i = 0; i < addresses.length - 1; i++) {
      assertThat(responseData)
          .as("the attempt against %s must be visible, not dropped by Jetty's connect loop",
              addresses[i].getHostAddress())
          .contains("Connect to localhost/" + addresses[i].getHostAddress() + ":" + closedPort
              + " failed");
    }
  }

  @Test
  public void doesNotDuplicateTheFailureThatSurvivedJettysLoop() throws Exception {
    sampler = samplerAgainst(closedPort());

    SampleResult result = sampler.sample();

    // Re-attaching the surviving attempt would make printStackTrace emit this marker instead of
    // the failure, which is worse than what it replaced.
    assertThat(result.getResponseDataAsString()).doesNotContain("CIRCULAR REFERENCE");
  }

  @Test
  public void reportsTheTlsHandshakesJettyDiscardedNotOnlyTheLastOne() throws Exception {
    InetAddress[] addresses = InetAddress.getAllByName("localhost");
    assumeTrue("localhost resolves to a single address here, so nothing gets discarded",
        addresses.length > 1);
    // A plain HTTP server answering an https:// request fails the handshake deterministically:
    // the ClientHello gets plaintext back. The socket connects first, so this is invisible to the
    // connector's ConnectListener - it is the case the SslHandshakeListener exists for.
    ServerBuilder.TeardownableServer server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    try {
      int port = ((org.eclipse.jetty.server.ServerConnector) server.getConnectors()[0])
          .getLocalPort();
      sampler = samplerAgainst(port);
      sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);

      SampleResult result = sampler.sample();

      assertThat(result.isSuccessful()).isFalse();
      String responseData = result.getResponseDataAsString();
      for (int i = 0; i < addresses.length - 1; i++) {
        assertThat(responseData)
            .as("the handshake against %s must be visible, not dropped by Jetty's connect loop",
                addresses[i].getHostAddress())
            .contains("TLS handshake with localhost/" + addresses[i].getHostAddress() + ":" + port
                + " failed");
      }
    } finally {
      server.stop();
    }
  }

  @Test
  public void keepsTheReportedFailureItselfUnchanged() throws Exception {
    sampler = samplerAgainst(closedPort());

    SampleResult result = sampler.sample();

    // The attempts are added as suppressed exceptions, so the failure the sample reports - and the
    // HttpClient4-shaped code JMeter puts in the result - must be exactly what it was before.
    assertThat(result.getResponseCode())
        .isEqualTo("Non HTTP response code: org.apache.http.conn.HttpHostConnectException");
  }

  @Test
  public void attachesNothingWhenTheSampleSucceeds() throws Exception {
    ServerBuilder.TeardownableServer server = new ServerBuilder().withHTTP1().buildServer();
    server.start();
    try {
      int port = ((org.eclipse.jetty.server.ServerConnector) server.getConnectors()[0])
          .getLocalPort();
      sampler = samplerAgainst(port);
      sampler.setPath(ServerBuilder.SERVER_PATH_200);

      SampleResult result = sampler.sample();

      assertThat(result.isSuccessful()).isTrue();
      assertThat(result.getResponseDataAsString()).doesNotContain("Connect to localhost/");
    } finally {
      server.stop();
    }
  }

  private HTTP2Sampler samplerAgainst(int port) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("localhost");
    sampler.setPort(port);
    sampler.setPath("/");
    sampler.setProtocol("http");
    return sampler;
  }

  private void overrideProperty(String key, String value) {
    savedProperties.add(new String[] {key, JMeterUtils.getProperty(key)});
    JMeterUtils.setProperty(key, value);
  }

  /** A port nothing is listening on: bound to learn it is free, then released. */
  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
