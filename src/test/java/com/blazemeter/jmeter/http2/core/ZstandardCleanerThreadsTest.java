package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jetty.client.ContentDecoder;
import org.eclipse.jetty.client.HttpClient;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * In Jetty 12.1.11 every {@code ZstandardCompression} starts a {@code Cleaner} thread that only
 * ends when a GC collects it, and {@code HttpClient.doStart()} creates one through
 * {@code ServiceLoader} whenever it has no decoder factories. Each {@link HTTP2JettyClient} starts
 * five {@link HttpClient}s and used to create one more instance of its own, so a load test parked
 * thousands of {@code Cleaner-N} threads. The plugin now registers a placeholder before every start
 * and shares a single instance for its own decoding; these tests fail if either stops happening.
 */
public class ZstandardCleanerThreadsTest {

  private static final int COUNT = 10;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Test
  public void startedPluginClientsDoNotEachStartCleanerThreads() throws Exception {
    Set<Thread> before = cleanerThreads();
    List<HTTP2JettyClient> clients = new ArrayList<>();
    try {
      for (int i = 0; i < COUNT; i++) {
        HTTP2JettyClient client = new HTTP2JettyClient(false, "zstd-cleaner-" + i);
        client.start();
        clients.add(client);
      }
      // At most the shared instance's thread, if no earlier test created it yet.
      assertThat(newCleanerThreads(before))
          .as("Cleaner threads started by %d plugin clients", COUNT)
          .isLessThanOrEqualTo(1);
    } finally {
      for (HTTP2JettyClient client : clients) {
        client.stop();
      }
    }
  }

  @Test
  public void restartedPluginClientDoesNotStartCleanerThreads() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient(false, "zstd-cleaner-restart");
    client.start();
    try {
      // HttpClient.doStop() clears the decoder factories, so the placeholder has to come back
      // before the next start or that start loads the ServiceLoader decoders again.
      Set<Thread> before = cleanerThreads();
      client.stop();
      client.start();
      assertThat(newCleanerThreads(before)).as("Cleaner threads started by a restart").isZero();
    } finally {
      client.stop();
    }
  }

  @Test
  public void startedInternalClientsDoNotLoadServiceLoaderDecoders() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient(false, "zstd-cleaner-decoders");
    client.start();
    try {
      for (String field : new String[] {"httpClient", "httpClientNoH3", "httpClientHttp1Only",
          "httpClientH2cPrior", "httpClientH2cUpgrade"}) {
        assertThat(encodings(internalClient(client, field)))
            .as("decoders %s found at start", field)
            .containsExactly("identity");
      }
    } finally {
      client.stop();
    }
  }

  private static HttpClient internalClient(HTTP2JettyClient client, String name) throws Exception {
    java.lang.reflect.Field field = HTTP2JettyClient.class.getDeclaredField(name);
    field.setAccessible(true);
    return (HttpClient) field.get(client);
  }

  private static List<String> encodings(HttpClient client) {
    List<String> encodings = new ArrayList<>();
    for (ContentDecoder.Factory factory : client.getContentDecoderFactories()) {
      encodings.add(factory.getEncoding());
    }
    return encodings;
  }

  private static Set<Thread> cleanerThreads() {
    // Cleaner.create() threads are named "Cleaner-N"; the JDK's shared one is "Common-Cleaner".
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .filter(t -> t.getName().startsWith("Cleaner-"))
        .collect(Collectors.toSet());
  }

  /**
   * A set difference, not a count difference: Cleaner threads left by earlier tests exit whenever a
   * GC collects their owners, which would hide the new ones from a plain count.
   */
  private static long newCleanerThreads(Set<Thread> before) {
    Set<Thread> now = cleanerThreads();
    now.removeAll(before);
    return now.size();
  }
}
