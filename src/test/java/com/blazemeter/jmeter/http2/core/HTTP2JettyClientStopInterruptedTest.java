package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import org.junit.After;
import org.junit.Test;

/**
 * JMeter Stop interrupts the worker before {@code threadFinished}; Jetty shutdown must absorb that
 * instead of surfacing ERROR / ClosedSelectorException noise.
 */
public class HTTP2JettyClientStopInterruptedTest extends HTTP2TestBase {

  private HTTP2JettyClient client;

  @After
  public void tearDown() throws Exception {
    // Ensure later tests do not inherit a sticky interrupt from assertions below.
    Thread.interrupted();
    if (client != null) {
      try {
        client.stop();
      } catch (Exception ignored) {
        // already stopped in the test body
      }
      client = null;
    }
  }

  @Test
  public void stopWhileInterruptedCompletesAndRestoresInterruptFlag() throws Exception {
    client = new HTTP2JettyClient(false, "stop-interrupted-test");
    client.start();

    Thread.currentThread().interrupt();
    assertThatCode(() -> client.stop()).doesNotThrowAnyException();
    assertThat(Thread.interrupted())
        .as("interrupt status must be restored after stop so JMeter still sees the Stop request")
        .isTrue();
    client = null;
  }

  @Test
  public void isExpectedShutdownExceptionRecognizesInterruptAndClosedSelector() {
    assertThat(HTTP2JettyClient.isExpectedShutdownException(new InterruptedException())).isTrue();
    assertThat(HTTP2JettyClient.isExpectedShutdownException(new ClosedSelectorException()))
        .isTrue();
    assertThat(HTTP2JettyClient.isExpectedShutdownException(new ClosedChannelException()))
        .isTrue();
    assertThat(HTTP2JettyClient.isExpectedShutdownException(
        new RuntimeException(new InterruptedException("nested")))).isTrue();
    assertThat(HTTP2JettyClient.isExpectedShutdownException(new IllegalStateException("boom")))
        .isFalse();
  }
}
