package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end coverage of Akamai-style {@code :status: 299 Akamai} responses over HTTP/2.
 *
 * <p>Jetty's normal response encoder never puts a reason phrase into {@code :status}, so this
 * test uses {@link RawHTTP2ServerConnectionFactory} and writes a raw HEADERS frame whose HPACK
 * block contains the non-numeric status value reported in production.
 *
 * <p>Stock Jetty rejects that value; the plugin's tolerant HPACK path (Firefox-inspired soft
 * normalization) accepts it and surfaces response code {@code 299}.
 */
public class HpackAkamaiStatusIntegrationTest extends HTTP2TestBase {

  private static final String AKAMAI_STATUS_WITH_REASON = "299 Akamai";
  private static final byte HEADERS_END_STREAM_END_HEADERS = 0x05;

  private String savedSharedPool;
  private String savedProtocolErrorFallback;
  private String savedPriorKnowledge;
  private Server server;
  private HTTP2JettyClient client;
  private int serverPort = -1;

  @Before
  public void setUp() throws Exception {
    JMeterTestUtils.setupJmeterEnv();
    savedSharedPool = JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    savedProtocolErrorFallback =
        JMeterUtils.getProperty("httpJettyClient.protocolErrorFallbackEnabled");
    savedPriorKnowledge = JMeterUtils.getProperty("httpJettyClient.http2PriorKnowledge");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");
    JMeterUtils.setProperty("httpJettyClient.protocolErrorFallbackEnabled", "false");
    JMeterUtils.setProperty("httpJettyClient.http2PriorKnowledge", "true");

    server = startRawAkamaiStatusServer();
    serverPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.stop();
      client = null;
    }
    if (server != null) {
      server.stop();
      server = null;
    }
    serverPort = -1;
    restoreProperty("httpJettyClient.sharedThreadPool", savedSharedPool);
    restoreProperty("httpJettyClient.protocolErrorFallbackEnabled", savedProtocolErrorFallback);
    restoreProperty("httpJettyClient.http2PriorKnowledge", savedPriorKnowledge);
  }

  @Test
  public void shouldAcceptAkamaiStatusWithReasonPhraseThroughJettyClient() throws Exception {
    client = new HTTP2JettyClient(false, "hpack-akamai-status-it", http2OnlyProfile());
    client.start();

    HTTPSampleResult result = client.sample(buildSampler(), buildBaseResult(), false, 0);

    assertThat(result.isSuccessful())
        .as("Tolerant client must accept :status with an HTTP/1 reason phrase")
        .isTrue();
    assertThat(result.getResponseCode()).isEqualTo("299");
  }

  private static Server startRawAkamaiStatusServer() throws Exception {
    Server jetty = new Server();
    HttpConfiguration httpConfig = new HttpConfiguration();
    RawHTTP2ServerConnectionFactory http2 =
        new RawHTTP2ServerConnectionFactory(httpConfig, new ServerSessionListener() {
          @Override
          public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
            writeRawStatusWithReason(stream);
            return null;
          }
        });
    ServerConnector connector = new ServerConnector(jetty, http2);
    connector.setPort(0);
    jetty.addConnector(connector);
    jetty.start();
    return jetty;
  }

  /**
   * Writes a HEADERS frame with HPACK {@code :status: 299 Akamai} directly to the connection,
   * bypassing Jetty's encoder which would strip the reason phrase.
   */
  private static void writeRawStatusWithReason(Stream stream) {
    try {
      HTTP2Session session = (HTTP2Session) stream.getSession();
      EndPoint endPoint = session.getEndPoint();
      ByteBuffer frame = buildHeadersFrame(stream.getId(),
          literalStatusHpack(AKAMAI_STATUS_WITH_REASON));

      CountDownLatch written = new CountDownLatch(1);
      AtomicReference<Throwable> writeFailure = new AtomicReference<>();
      endPoint.write(new Callback() {
        @Override
        public void succeeded() {
          written.countDown();
        }

        @Override
        public void failed(Throwable x) {
          writeFailure.set(x);
          written.countDown();
        }
      }, frame);
      if (!written.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out writing raw HEADERS frame");
      }
      if (writeFailure.get() != null) {
        throw new IllegalStateException("Failed writing raw HEADERS frame", writeFailure.get());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ByteBuffer buildHeadersFrame(int streamId, byte[] hpack) {
    ByteBuffer frame = ByteBuffer.allocate(9 + hpack.length);
    int length = hpack.length;
    frame.put((byte) ((length >>> 16) & 0xFF));
    frame.put((byte) ((length >>> 8) & 0xFF));
    frame.put((byte) (length & 0xFF));
    frame.put((byte) FrameType.HEADERS.getType());
    frame.put(HEADERS_END_STREAM_END_HEADERS);
    frame.putInt(streamId);
    frame.put(hpack);
    frame.flip();
    return frame;
  }

  private static byte[] literalStatusHpack(String value) {
    byte[] valueBytes = value.getBytes(StandardCharsets.ISO_8859_1);
    byte[] block = new byte[2 + valueBytes.length];
    block[0] = 0x08; // literal header field without indexing - name index 8 (:status)
    block[1] = (byte) valueBytes.length;
    System.arraycopy(valueBytes, 0, block, 2, valueBytes.length);
    return block;
  }

  private HTTP2Sampler buildSampler() {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("localhost");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTP);
    sampler.setPort(serverPort);
    sampler.setPath("/");
    sampler.setEnableHttp1(false);
    sampler.setEnableHttp2(true);
    sampler.setEnableHttp3(false);
    sampler.setFallbackEnabled(false);
    sampler.setProtocolErrorFallbackEnabled(false);
    sampler.setHttp2PriorKnowledgeEnabled(true);
    return sampler;
  }

  private static HTTP2ClientProfileConfig http2OnlyProfile() {
    return HTTP2ClientProfileConfig.builder()
        .profile("browser-like")
        .enableHttp3(false)
        .enableHttp2(true)
        .enableHttp1(false)
        .alpnEnabled(false)
        .fallbackEnabled(false)
        .protocolErrorFallbackEnabled(false)
        .altSvcCacheEnabled(false)
        .http1OnlyCacheEnabled(false)
        .http2PriorKnowledgeEnabled(true)
        .build();
  }

  private HTTPSampleResult buildBaseResult() throws Exception {
    HTTPSampleResult result = new HTTPSampleResult();
    URL url = new URI(HTTPConstants.PROTOCOL_HTTP, null, "localhost", serverPort, "/", null, null)
        .toURL();
    result.setURL(url);
    result.setHTTPMethod(HTTPConstants.GET);
    return result;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      JMeterUtils.getJMeterProperties().remove(key);
    } else {
      JMeterUtils.setProperty(key, value);
    }
  }
}