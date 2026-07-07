package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.BINARY_RESPONSE_BODY;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_GZIP;
import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.BeforeClass;
import org.junit.Test;

public class HTTP2JettyClientDecodeBenchmarkTest extends HTTP2TestBase {

  private static final String PROP_SKIP_REDUNDANT_DECODE =
      "blazemeter.http.skipManualDecodeWhenAdvertised";

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Test
  public void benchmarkDecodeFastPath() throws Exception {
    TeardownableServer server = new ServerBuilder()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .buildServer();
    server.start();
    int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();

    // Warmup + measurement tuned to finish quickly in CI-like environments.
    int warmupIterations = 30;
    int benchmarkIterations = 250;

    try {
      BenchResult alwaysManualDecode = runScenario(port, false, warmupIterations,
          benchmarkIterations);
      BenchResult skipRedundantDecode = runScenario(port, true, warmupIterations,
          benchmarkIterations);

      System.out.println("[decode-benchmark] always-manual " + alwaysManualDecode);
      System.out.println("[decode-benchmark] skip-redundant " + skipRedundantDecode);
      writeSummaryFile(alwaysManualDecode, skipRedundantDecode);

      assertThat(alwaysManualDecode.successfulSamples).isEqualTo(benchmarkIterations);
      assertThat(skipRedundantDecode.successfulSamples).isEqualTo(benchmarkIterations);
    } finally {
      server.stop();
      System.clearProperty(PROP_SKIP_REDUNDANT_DECODE);
    }
  }

  private BenchResult runScenario(int port, boolean skipRedundantDecode, int warmupIterations,
                                  int benchmarkIterations) throws Exception {
    System.setProperty(PROP_SKIP_REDUNDANT_DECODE, String.valueOf(skipRedundantDecode));

    HTTP2JettyClient client = new HTTP2JettyClient();
    client.start();
    client.loadProperties();
    HTTP2Sampler sampler = buildSampler(port);

    try {
      URL url = new URI("https", null, HOST_NAME, port, SERVER_PATH_200_GZIP, null, null).toURL();

      for (int i = 0; i < warmupIterations; i++) {
        HTTPSampleResult warmup = client.sample(sampler, buildBaseResult(url), false, 0);
        assertThat(warmup.isSuccessful()).isTrue();
      }

      Runtime runtime = Runtime.getRuntime();
      long usedHeapBefore = usedHeap(runtime);
      long peakUsedHeap = usedHeapBefore;
      long startNanos = System.nanoTime();
      int successful = 0;

      for (int i = 0; i < benchmarkIterations; i++) {
        HTTPSampleResult result = client.sample(sampler, buildBaseResult(url), false, 0);
        if (result.isSuccessful()) {
          successful++;
        }
        assertThat(result.getResponseData()).containsExactly(BINARY_RESPONSE_BODY);
        if ((i & 15) == 0) {
          peakUsedHeap = Math.max(peakUsedHeap, usedHeap(runtime));
        }
      }

      long elapsedNanos = System.nanoTime() - startNanos;
      long usedHeapAfter = usedHeap(runtime);
      double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
      double requestsPerSecond = benchmarkIterations / elapsedSeconds;

      return new BenchResult(skipRedundantDecode, benchmarkIterations, successful, elapsedSeconds,
          requestsPerSecond, usedHeapBefore, peakUsedHeap, usedHeapAfter);
    } finally {
      sampler.threadFinished();
      client.stop();
    }
  }

  private HTTP2Sampler buildSampler(int port) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain(HOST_NAME);
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(port);
    sampler.setPath(SERVER_PATH_200_GZIP);
    HeaderManager headerManager = new HeaderManager();
    headerManager.add(new Header("Accept-Encoding", "gzip"));
    sampler.setHeaderManager(headerManager);
    return sampler;
  }

  private HTTPSampleResult buildBaseResult(URL url) {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setURL(url);
    result.setHTTPMethod(HTTPConstants.GET);
    return result;
  }

  private long usedHeap(Runtime runtime) {
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private void writeSummaryFile(BenchResult alwaysManualDecode, BenchResult skipRedundantDecode)
      throws Exception {
    double improvementPct =
        ((skipRedundantDecode.requestsPerSecond / alwaysManualDecode.requestsPerSecond) - 1.0)
            * 100.0;
    String summary = String.format(Locale.ROOT,
        "always.elapsedSec=%.6f%n"
            + "always.rps=%.6f%n"
            + "always.usedBeforeMB=%.6f%n"
            + "always.peakUsedMB=%.6f%n"
            + "always.usedAfterMB=%.6f%n"
            + "skip.elapsedSec=%.6f%n"
            + "skip.rps=%.6f%n"
            + "skip.usedBeforeMB=%.6f%n"
            + "skip.peakUsedMB=%.6f%n"
            + "skip.usedAfterMB=%.6f%n"
            + "improvement.rpsPct=%.6f%n",
        alwaysManualDecode.elapsedSeconds,
        alwaysManualDecode.requestsPerSecond,
        bytesToMb(alwaysManualDecode.usedHeapBefore),
        bytesToMb(alwaysManualDecode.peakUsedHeap),
        bytesToMb(alwaysManualDecode.usedHeapAfter),
        skipRedundantDecode.elapsedSeconds,
        skipRedundantDecode.requestsPerSecond,
        bytesToMb(skipRedundantDecode.usedHeapBefore),
        bytesToMb(skipRedundantDecode.peakUsedHeap),
        bytesToMb(skipRedundantDecode.usedHeapAfter),
        improvementPct);
    Path summaryPath = Path.of("target", "decode-benchmark-result.properties");
    Files.write(summaryPath, summary.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  private double bytesToMb(long bytes) {
    return bytes / (1024.0 * 1024.0);
  }

  private static class BenchResult {

    private final boolean skipRedundantDecode;
    private final int totalSamples;
    private final int successfulSamples;
    private final double elapsedSeconds;
    private final double requestsPerSecond;
    private final long usedHeapBefore;
    private final long peakUsedHeap;
    private final long usedHeapAfter;

    private BenchResult(boolean skipRedundantDecode, int totalSamples, int successfulSamples,
                        double elapsedSeconds, double requestsPerSecond, long usedHeapBefore,
                        long peakUsedHeap, long usedHeapAfter) {
      this.skipRedundantDecode = skipRedundantDecode;
      this.totalSamples = totalSamples;
      this.successfulSamples = successfulSamples;
      this.elapsedSeconds = elapsedSeconds;
      this.requestsPerSecond = requestsPerSecond;
      this.usedHeapBefore = usedHeapBefore;
      this.peakUsedHeap = peakUsedHeap;
      this.usedHeapAfter = usedHeapAfter;
    }

    @Override
    public String toString() {
      return "mode=" + (skipRedundantDecode ? "skip-redundant" : "always-manual")
          + ", total=" + totalSamples
          + ", ok=" + successfulSamples
          + ", elapsedSec=" + String.format("%.3f", elapsedSeconds)
          + ", rps=" + String.format("%.2f", requestsPerSecond)
          + ", usedBeforeMB=" + bytesToMb(usedHeapBefore)
          + ", peakUsedMB=" + bytesToMb(peakUsedHeap)
          + ", usedAfterMB=" + bytesToMb(usedHeapAfter);
    }

    private static String bytesToMb(long bytes) {
      return String.format("%.2f", bytes / (1024.0 * 1024.0));
    }
  }
}
