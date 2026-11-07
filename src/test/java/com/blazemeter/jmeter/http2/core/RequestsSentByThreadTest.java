package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.client.HttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link RequestsSentByThread} tells a collecting sampler whether it sent its request again by
 * counting what its own thread sent. That rests on one thing Jetty does: it notifies a request as
 * queued on the thread that sends it. Pinned here against the Jetty the plugin ships with, for the
 * two ways the plugin sends and for a thread that is not the one asking.
 */
public class RequestsSentByThreadTest {

  private HttpClient client;
  private URI refused;

  @Before
  public void setUp() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    refused = URI.create("http://127.0.0.1:" + closedPort + "/");
    client = new HttpClient();
    // The same registration HTTP2JettyClient makes on each of its clients.
    client.getRequestListeners().addQueuedListener(request -> RequestsSentByThread.recordSent());
    client.start();
  }

  @After
  public void tearDown() throws Exception {
    client.stop();
  }

  @Test
  public void aBlockingSendIsCountedOnTheSendingThread() {
    long before = RequestsSentByThread.current();
    try {
      client.newRequest(refused).send();
    } catch (Exception refusedConnection) {
      // Nothing listens on the port; the send is what is counted, not its outcome.
    }
    assertThat(RequestsSentByThread.current() - before).isEqualTo(1);
  }

  @Test
  public void aSendWithAListenerIsCountedOnTheSendingThread() throws Exception {
    long before = RequestsSentByThread.current();
    CountDownLatch done = new CountDownLatch(1);
    client.newRequest(refused).send(result -> done.countDown());
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(RequestsSentByThread.current() - before).isEqualTo(1);
  }

  @Test
  public void aRequestAnotherThreadSendsIsNotCountedOnThisOne() throws Exception {
    long before = RequestsSentByThread.current();
    Thread other = new Thread(() -> {
      try {
        client.newRequest(refused).send();
      } catch (Exception refusedConnection) {
        // As above.
      }
    }, "another-sampler-thread");
    other.start();
    other.join(10_000);
    assertThat(RequestsSentByThread.current())
        .as("a request sent by another thread is not one this thread sent")
        .isEqualTo(before);
  }
}
