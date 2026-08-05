package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_IMAGE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_EMBEDDED;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Concurrent embedded completion must keep URL labels (not overwrite with the sampler name) and
 * must not reset frame depth via public {@link HTTP2Sampler#sample()}.
 */
public class EmbeddedResourceConcurrentConsumeParityTest extends HTTP2TestBase {

  private TeardownableServer server;
  private int port = -1;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setUp() throws Exception {
    HTTP2JettyClientTestIsolation.resetSharedClientState();
    server = new ServerBuilder().withHTTP2().withALPN().withHTTP2C().withSSL().buildServer();
    server.start();
    port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @After
  public void tearDown() throws Exception {
    if (server != null && server.isStarted()) {
      server.stop();
    }
  }

  @Test
  public void concurrentEmbeddedSubResultsKeepResourceUrlLabels() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setName("parent-page");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setDomain(HOST_NAME);
    sampler.setPort(port);
    sampler.setPath(SERVER_PATH_200_EMBEDDED);
    sampler.setMethod(HTTPConstants.GET);
    sampler.setImageParser(true);
    sampler.setConcurrentDwn(true);
    sampler.setConcurrentPool("4");
    sampler.setFollowRedirects(true);
    sampler.setAutoRedirects(false);

    HTTPSampleResult result = (HTTPSampleResult) sampler.sample();
    assertThat(result.isSuccessful()).isTrue();

    SampleResult[] subs = result.getSubResults();
    assertThat(subs).isNotEmpty();

    boolean foundImage = false;
    for (SampleResult sub : subs) {
      if (sub.getUrlAsString() != null && sub.getUrlAsString().contains(SERVER_IMAGE)) {
        foundImage = true;
        assertThat(sub.getSampleLabel())
            .as("concurrent consume must not rewrite the label via public sample()/getName()")
            .doesNotContain("bzm - HTTP Sampler");
        assertThat(sub.getUrlAsString()).contains(SERVER_IMAGE);
      }
    }
    assertThat(foundImage)
        .as("expected an embedded image sub-result for %s", SERVER_IMAGE)
        .isTrue();
  }

  @Test
  public void md5FlagReplacesStoredBodyWithDigestLikeJMeter() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setName("md5-sample");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setDomain(HOST_NAME);
    sampler.setPort(port);
    sampler.setPath(SERVER_PATH_200_EMBEDDED);
    sampler.setMethod(HTTPConstants.GET);
    sampler.setImageParser(false);
    sampler.setConcurrentDwn(false);
    sampler.setMD5(true);

    HTTPSampleResult result = (HTTPSampleResult) sampler.sample();
    assertThat(result.isSuccessful()).isTrue();
    String body = result.getResponseDataAsString();
    assertThat(body)
        .as("useMD5 should store a 32-char hex digest instead of the HTML body")
        .hasSize(32)
        .matches("[0-9a-fA-F]{32}");
    assertThat(body).doesNotContain("<html");
  }
}
