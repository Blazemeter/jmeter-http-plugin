package com.blazemeter.jmeter.http2.core.jetty.custom.http1;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class KeepAliveParityEndPointTest {

  @Test
  public void patchesHttp10RequestLineToHttp11() {
    ByteBuffer buffer = ByteBuffer.wrap(
        "GET /test HTTP/1.0\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

    KeepAliveParityEndPoint.patchRequestLineToHttp11(buffer);

    assertThat(StandardCharsets.US_ASCII.decode(buffer).toString())
        .startsWith("GET /test HTTP/1.1\r\n");
  }

  @Test
  public void leavesHttp11RequestLineUnchanged() {
    ByteBuffer buffer = ByteBuffer.wrap(
        "GET /test HTTP/1.1\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

    KeepAliveParityEndPoint.patchRequestLineToHttp11(buffer);

    assertThat(StandardCharsets.US_ASCII.decode(buffer).toString())
        .startsWith("GET /test HTTP/1.1\r\n");
  }
}
