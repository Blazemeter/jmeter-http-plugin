package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Field;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.junit.Test;

/**
 * Gzip {@link InflaterPool} is created outside the HttpClient bean tree. Stopping the client used
 * to leave the pool (and Brotli/Zstd Compression LifeCycles) running — one oversized pool per
 * {@link HTTP2JettyClient} for the JVM lifetime of that wrapper.
 */
public class CompressionResourcesStopRegressionTest extends HTTP2TestBase {

  @Test
  public void stopMustShutDownGzipInflaterPool() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient(false, "compression-stop-test");
    client.start();
    // Force decoder init (Accept-Encoding path runs on first request; invoke ensure via sample
    // preparation helpers by reflecting after a no-op configure through a started client).
    forceDecoderInit(client);

    InflaterPool pool = (InflaterPool) field(client, "gzipInflaterPool");
    assertThat(pool).as("gzip inflater pool must be created").isNotNull();
    assertThat(pool.isRunning()).isTrue();
    assertThat(pool.getCapacity())
        .as("pool capacity must stay small; 1024×N clients retained a huge high-water mark")
        .isEqualTo(32);

    client.stop();

    assertThat(field(client, "gzipInflaterPool"))
        .as("stop must drop the inflater pool reference")
        .isNull();
    assertThat(pool.isRunning())
        .as("stop must stop the inflater pool LifeCycle")
        .isFalse();
    assertThat(field(client, "gzipCompression")).isNull();
    assertThat(field(client, "brotliCompression")).isNull();
    assertThat(field(client, "zstdCompression")).isNull();
  }

  private static void forceDecoderInit(HTTP2JettyClient client) throws Exception {
    java.lang.reflect.Method ensure =
        HTTP2JettyClient.class.getDeclaredMethod("ensureDecoderFactoriesInitialized");
    ensure.setAccessible(true);
    ensure.invoke(client);
  }

  private static Object field(HTTP2JettyClient client, String name) throws Exception {
    Field field = HTTP2JettyClient.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(client);
  }
}
