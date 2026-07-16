package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.junit.Test;

/**
 * {@link HTTP2JettyClient}'s manual deflate decode must accept both zlib-wrapped (RFC 1950,
 * what {@link java.util.zip.Deflater} produces by default) and raw/headerless (RFC 1951) deflate
 * streams, matching HttpClient4 (some servers send raw deflate under a {@code Content-Encoding:
 * deflate} header).
 */
public class HTTP2JettyClientDeflateDecodeTest extends HTTP2TestBase {

  private static final String PLAIN_TEXT =
      "HttpClient4 accepts both zlib-wrapped and raw deflate streams.";

  @Test
  public void decodesZlibWrappedDeflateContent() throws Exception {
    byte[] compressed = deflate(PLAIN_TEXT, false);

    byte[] decoded = decodeDeflate(compressed);

    assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo(PLAIN_TEXT);
  }

  @Test
  public void decodesRawHeaderlessDeflateContent() throws Exception {
    byte[] compressed = deflate(PLAIN_TEXT, true);

    byte[] decoded = decodeDeflate(compressed);

    assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo(PLAIN_TEXT);
  }

  @Test
  public void returnsOriginalBytesWhenContentIsNotValidDeflate() throws Exception {
    byte[] garbage = "not compressed at all".getBytes(StandardCharsets.UTF_8);

    byte[] decoded = decodeDeflate(garbage);

    assertThat(decoded).isEqualTo(garbage);
  }

  private static byte[] deflate(String text, boolean nowrap) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, nowrap);
    try (DeflaterOutputStream deflaterOut = new DeflaterOutputStream(out, deflater)) {
      deflaterOut.write(text.getBytes(StandardCharsets.UTF_8));
    } finally {
      deflater.end();
    }
    return out.toByteArray();
  }

  private static byte[] decodeDeflate(byte[] content) throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient(false, "deflate-decode-test");
    Method method = HTTP2JettyClient.class
        .getDeclaredMethod("decodeDeflate", byte[].class, String.class);
    method.setAccessible(true);
    return (byte[]) method.invoke(client, content, "deflate");
  }
}
