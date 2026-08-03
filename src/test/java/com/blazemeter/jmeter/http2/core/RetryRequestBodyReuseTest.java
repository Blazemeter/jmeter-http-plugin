package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Test;

/**
 * Every protocol fallback replaces a request that already failed with an equivalent one, and the
 * body has to survive that hand-over. These tests cover both halves: the Jetty behaviour that makes
 * naive reuse silently wrong, and {@code copyBodyForRetry}, which is what all the fallbacks go
 * through.
 */
public class RetryRequestBodyReuseTest {

  @Test
  public void shouldKeepFailureWhenBodySourceWasFailed() {
    StringRequestContent body = new StringRequestContent("text/plain", "value1");
    SocketTimeoutException failure = new SocketTimeoutException("connect timeout");

    body.fail(failure);
    Content.Chunk chunk = body.read();

    assertThat(Content.Chunk.isFailure(chunk))
        .as("a failed body source must report the failure to whoever reads it next")
        .isTrue();
    assertThat(chunk.getFailure())
        .as("and it must be the very same exception, which is what a retry would surface")
        .isSameAs(failure);
  }

  /** And the way out: rewinding clears the failure, which is what makes a retry possible at all. */
  @Test
  public void shouldRecoverFailedBodySourceByRewinding() {
    StringRequestContent body = new StringRequestContent("text/plain", "value1");
    body.fail(new SocketTimeoutException("connect timeout"));

    assertThat(body.rewind())
        .as("a rewindable body must accept being rewound after a failed attempt")
        .isTrue();

    Content.Chunk chunk = body.read();

    assertThat(Content.Chunk.isFailure(chunk))
        .as("after rewinding, reading must no longer report the previous failure")
        .isFalse();
    assertThat(BufferUtil.toString(chunk.getByteBuffer()))
        .as("and the original content must be readable again, whole")
        .isEqualTo("value1");
  }

  /**
   * The fallbacks rely on this: a body that already failed must reach the retry readable, or the
   * retry dies instantly with the error it was supposed to rescue.
   */
  @Test
  public void shouldHandFailedBodyToRetryReadable() throws Exception {
    HttpClient client = new HttpClient();
    Request original = client.newRequest("http://localhost:1/x");
    Request retry = client.newRequest("http://localhost:1/x");
    StringRequestContent body = new StringRequestContent("text/plain", "value1");
    original.body(body);
    body.fail(new SocketTimeoutException("connect timeout"));

    copyBodyForRetry(original, retry, "test");

    Content.Chunk chunk = retry.getBody().read();
    assertThat(Content.Chunk.isFailure(chunk))
        .as("the retry must not inherit the failure of the attempt it replaces")
        .isFalse();
    assertThat(BufferUtil.toString(chunk.getByteBuffer()))
        .as("the retry must carry the same body content")
        .isEqualTo("value1");
  }

  /**
   * A body that cannot be rewound cannot be retried. Failing loudly is the point: sending the retry
   * without the body would silently turn it into a different request.
   */
  @Test
  public void shouldRefuseRetryWhenBodyCannotBeRewound() throws Exception {
    HttpClient client = new HttpClient();
    Request original = client.newRequest("http://localhost:1/x");
    Request retry = client.newRequest("http://localhost:1/x");
    original.body(new InputStreamRequestContent(
        new ByteArrayInputStream("value1".getBytes(StandardCharsets.UTF_8))));

    assertThatThrownBy(() -> copyBodyForRetry(original, retry, "test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not reproducible");
  }

  private static void copyBodyForRetry(Request original, Request retry, String description)
      throws Exception {
    Method m = HTTP2JettyClient.class.getDeclaredMethod(
        "copyBodyForRetry", Request.class, Request.class, String.class);
    m.setAccessible(true);
    try {
      m.invoke(null, original, retry, description);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw e;
    }
  }
}
