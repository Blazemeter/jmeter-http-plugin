package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.util.Promise;
import org.junit.Test;

/**
 * Jetty keeps only the last address's failure when it walks the resolved addresses of a host, so
 * on a dual-stack origin the IPv4 error - normally the interesting one, since the JDK tries it
 * first - disappears. This pins the bookkeeping that gets it back: what the recorder captures when
 * it wraps the per-address promise, which attempts belong to a given sample, and that wrapping the
 * promise does not disturb the roll-over it is listening to.
 */
public class ConnectAttemptRecorderTest {

  private static final long EVERYTHING = 0;

  @Test
  public void attachesEveryFailureRecordedForTheSampledOrigin() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    failAttempt(recorder, address("127.0.0.1", 8443),
        new ConnectException("Connection refused: connect"));
    failAttempt(recorder, address("::1", 8443),
        new SocketException("Network is unreachable: connect"));
    Throwable reported = new ConnectException("Connection refused: connect");

    recorder.attachTo(reported, new URL("https://localhost:8443/x"), EVERYTHING);

    assertThat(suppressedMessages(reported)).containsExactly(
        "Connect to localhost/127.0.0.1:8443 failed",
        "Connect to localhost/0:0:0:0:0:0:0:1:8443 failed");
  }

  @Test
  public void keepsTheOriginalFailureAsTheCauseOfEachAttempt() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    IOException original = new ConnectException("Connection refused: connect");
    failAttempt(recorder, address("127.0.0.1", 8443), original);
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(reported, new URL("https://localhost:8443/x"), EVERYTHING);

    assertThat(reported.getSuppressed()[0].getCause()).isSameAs(original);
  }

  @Test
  public void namesTheStageEachAttemptDiedAt() throws Exception {
    // One wrapper covers all three because they all fail the same per-address promise: the socket,
    // the TLS handshake, and - after TLS already succeeded - the HTTP/2 preface.
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    failAttempt(recorder, address("127.0.0.1", 8443), new ConnectException("refused"));
    failAttempt(recorder, address("127.0.0.1", 8443), new SSLHandshakeException("bad cert"));
    failAttempt(recorder, address("127.0.0.1", 8443), new ClosedChannelException());
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(reported, new URL("https://localhost:8443/x"), EVERYTHING);

    assertThat(suppressedMessages(reported)).containsExactly(
        "Connect to localhost/127.0.0.1:8443 failed",
        "TLS handshake with localhost/127.0.0.1:8443 failed",
        "Connection to localhost/127.0.0.1:8443 failed");
  }

  @Test
  public void leavesTheWrappedPromiseDrivingTheRollOver() throws Exception {
    // The wrapped promise is what makes Jetty try the next address. Swallowing either outcome
    // here would turn a diagnostic into a hang or a lost connection.
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    AtomicReference<Throwable> delegatedFailure = new AtomicReference<>();
    AtomicReference<Connection> delegatedSuccess = new AtomicReference<>();
    Map<String, Object> context = contextWith(new Promise<Connection>() {
      @Override
      public void succeeded(Connection result) {
        delegatedSuccess.set(result);
      }

      @Override
      public void failed(Throwable failure) {
        delegatedFailure.set(failure);
      }
    });
    recorder.instrument(address("127.0.0.1", 8443), context);
    ConnectException failure = new ConnectException("refused");

    promiseIn(context).succeeded(null);
    promiseIn(context).failed(failure);

    assertThat(delegatedSuccess.get()).isNull();
    assertThat(delegatedFailure.get()).isSameAs(failure);
  }

  @Test
  public void leavesTheContextAloneWhenThereIsNoPromiseToWrap() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    Map<String, Object> context = new HashMap<>();

    recorder.instrument(address("127.0.0.1", 8443), context);
    recorder.instrument(null, contextWith(noOpPromise()));

    assertThat(context).isEmpty();
  }

  @Test
  public void ignoresAttemptsAgainstAnotherPort() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    failAttempt(recorder, address("127.0.0.1", 9999), new ConnectException("refused"));
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(reported, new URL("https://localhost:8443/x"), EVERYTHING);

    assertThat(reported.getSuppressed()).isEmpty();
  }

  @Test
  public void ignoresAttemptsAgainstAnotherHost() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    failAttempt(recorder, address("127.0.0.1", 8443), new ConnectException("refused"));
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(reported, new URL("https://elsewhere.invalid:8443/x"), EVERYTHING);

    assertThat(reported.getSuppressed()).isEmpty();
  }

  @Test
  public void ignoresAttemptsRecordedBeforeTheSampleStarted() throws Exception {
    // Connects run on Jetty threads and concurrent embedded resources share the client, so the
    // sample start is what keeps a neighbour's stale failure out of this result.
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    failAttempt(recorder, address("127.0.0.1", 8443), new ConnectException("refused"));
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(reported, new URL("https://localhost:8443/x"),
        System.currentTimeMillis() + 60_000);

    assertThat(reported.getSuppressed()).isEmpty();
  }

  @Test
  public void matchesTheHostCaseInsensitively() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    failAttempt(recorder, address("127.0.0.1", 8443), new ConnectException("refused"));
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(reported, new URL("https://LOCALHOST:8443/x"), EVERYTHING);

    assertThat(reported.getSuppressed()).hasSize(1);
  }

  @Test
  public void matchesTheDefaultPortOfTheScheme() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    failAttempt(recorder, address("127.0.0.1", 443), new ConnectException("refused"));
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(reported, new URL("https://localhost/x"), EVERYTHING);

    assertThat(reported.getSuppressed()).hasSize(1);
  }

  @Test
  public void skipsTheAttemptThatIsAlreadyTheReportedCause() throws Exception {
    // Re-attaching it would make printStackTrace emit a "[CIRCULAR REFERENCE]" marker in place of
    // the failure. Only the discarded attempts are missing from the trace.
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    ConnectException survivor = new ConnectException("refused");
    failAttempt(recorder, address("127.0.0.1", 8443), survivor);
    Throwable reported = new IOException("wrapper", survivor);

    recorder.attachTo(reported, new URL("https://localhost:8443/x"), EVERYTHING);

    assertThat(reported.getSuppressed()).isEmpty();
  }

  @Test
  public void keepsOnlyTheMostRecentAttemptsWithinItsCapacity() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder(2);
    for (int i = 0; i < 5; i++) {
      failAttempt(recorder, address("127.0.0.1", 8443), new ConnectException("refused " + i));
    }
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(reported, new URL("https://localhost:8443/x"), EVERYTHING);

    assertThat(suppressedCauseMessages(reported)).containsExactly("refused 3", "refused 4");
  }

  @Test
  public void attachesAtMostEightAttemptsToKeepTheTraceReadable() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    for (int i = 0; i < 20; i++) {
      failAttempt(recorder, address("127.0.0.1", 8443), new ConnectException("refused " + i));
    }
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(reported, new URL("https://localhost:8443/x"), EVERYTHING);

    assertThat(reported.getSuppressed()).hasSize(8);
    assertThat(suppressedCauseMessages(reported)).last().isEqualTo("refused 19");
  }

  @Test
  public void toleratesMissingInputsRatherThanReplacingTheReportedFailure() throws Exception {
    ConnectAttemptRecorder recorder = new ConnectAttemptRecorder();
    failAttempt(recorder, address("127.0.0.1", 8443), new ConnectException("refused"));
    Throwable reported = new ConnectException("boom");

    recorder.attachTo(null, new URL("https://localhost:8443/x"), EVERYTHING);
    recorder.attachTo(reported, null, EVERYTHING);

    assertThat(reported.getSuppressed()).isEmpty();
  }

  /** Drives one address through the recorder the way Jetty's transport does. */
  private static void failAttempt(ConnectAttemptRecorder recorder, InetSocketAddress address,
                                  Throwable failure) {
    Map<String, Object> context = contextWith(noOpPromise());
    recorder.instrument(address, context);
    promiseIn(context).failed(failure);
  }

  private static Map<String, Object> contextWith(Promise<Connection> promise) {
    Map<String, Object> context = new HashMap<>();
    context.put(Connection.PROMISE_CONTEXT_KEY, promise);
    return context;
  }

  @SuppressWarnings("unchecked")
  private static Promise<Connection> promiseIn(Map<String, Object> context) {
    return (Promise<Connection>) context.get(Connection.PROMISE_CONTEXT_KEY);
  }

  private static Promise<Connection> noOpPromise() {
    return new Promise<>() {
    };
  }

  /**
   * Mirrors how Jetty builds the address it hands to the transport: an {@code InetAddress} that
   * still carries the name it was resolved from, so {@code getHostString()} returns that name.
   * Built explicitly instead of resolving {@code localhost} so the test does not depend on whether
   * this machine maps it to one address family or two.
   */
  private static InetSocketAddress address(String ip, int port) throws Exception {
    InetAddress resolved =
        InetAddress.getByAddress("localhost", InetAddress.getByName(ip).getAddress());
    return new InetSocketAddress(resolved, port);
  }

  private static List<String> suppressedMessages(Throwable reported) {
    return Arrays.stream(reported.getSuppressed())
        .map(Throwable::getMessage)
        .collect(Collectors.toList());
  }

  private static List<String> suppressedCauseMessages(Throwable reported) {
    return Arrays.stream(reported.getSuppressed())
        .map(suppressed -> suppressed.getCause().getMessage())
        .collect(Collectors.toList());
  }
}
