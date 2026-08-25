package com.blazemeter.jmeter.http2.core;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.util.Promise;

/**
 * Keeps the connection failures Jetty throws away, so a failed sample can report every address it
 * actually tried instead of only the last one.
 *
 * <p>{@code HttpClient.connect(List, int, Map)} walks the resolved addresses recursively and, when
 * one fails, calls itself for the next while <em>discarding</em> the exception - no
 * {@code addSuppressed}, no log. Only the last address's failure survives. With the JDK default
 * ({@code java.net.preferIPv6Addresses=false}) IPv4 is tried first, so a dual-stack host that
 * fails on both reports the IPv6 error alone and the IPv4 one - usually the interesting one - is
 * gone. Same code in 12.0.x and 12.1.x, so this is not a version regression.
 *
 * <p>The recovery point is {@code Connection.PROMISE_CONTEXT_KEY}: the promise Jetty puts in the
 * context for one address, whose {@code failed} is exactly what decides to move on to the next.
 * <em>Every</em> way of failing to establish a connection ends there - a refused socket, a TLS
 * handshake, ALPN, and the HTTP/2 preface, since
 * {@code HTTPSessionListenerPromise.failConnectionPromise} fails that same promise. Wrapping it
 * in {@link #instrument} therefore covers all of them at once, with the address in hand, and
 * without depending on per-phase listeners that each see only their own slice.
 *
 * <p>The recovered failures are attached to the sample's failure as <em>suppressed</em>
 * exceptions: {@code HTTPSamplerBase.errorResult} writes {@code printStackTrace} into the response
 * data, and that prints suppressed entries, so they show up in View Results Tree with no new
 * formatting and without going through SLF4J - which the shaded jar has no provider for.
 */
public class ConnectAttemptRecorder {

  /**
   * Recent failures kept per client. A run that cannot connect at all would otherwise grow this
   * without bound; the last few are all a failing sample can use, since it only reads the ones
   * recorded while it was running.
   */
  private static final int DEFAULT_CAPACITY = 64;

  /** Cap on how many attempts are attached to a single failure, to keep the trace readable. */
  private static final int MAX_ATTACHED = 8;

  private static final String TCP_PHASE = "Connect to";
  private static final String TLS_PHASE = "TLS handshake with";
  private static final String SESSION_PHASE = "Connection to";

  private final int capacity;
  private final Deque<FailedAttempt> attempts = new ArrayDeque<>();

  public ConnectAttemptRecorder() {
    this(DEFAULT_CAPACITY);
  }

  public ConnectAttemptRecorder(int capacity) {
    this.capacity = capacity;
  }

  /**
   * Wraps the per-address connection promise in {@code context} so this recorder sees its failure
   * before Jetty decides to move on to the next address.
   *
   * <p>Called from the transport's {@code connect(SocketAddress, Map)}, which Jetty invokes once
   * per resolved address with the promise for that address already in the context. Replacing it
   * here is safe because everything downstream reads it back out of the context by key:
   * {@code HttpClient.connect} binds {@code CONNECTION_PROMISE_CONTEXT_KEY} to it, and the HTTP/2
   * session listener resolves it lazily through {@code httpConnectionPromise()}.
   */
  public void instrument(SocketAddress address, Map<String, Object> context) {
    if (!(address instanceof InetSocketAddress) || context == null) {
      return;
    }
    @SuppressWarnings("unchecked")
    Promise<Connection> promise =
        (Promise<Connection>) context.get(Connection.PROMISE_CONTEXT_KEY);
    if (promise == null) {
      return;
    }
    InetSocketAddress inetAddress = (InetSocketAddress) address;
    context.put(Connection.PROMISE_CONTEXT_KEY, new Promise.Wrapper<Connection>(promise) {
      @Override
      public void failed(Throwable failure) {
        record(inetAddress, failure);
        super.failed(failure);
      }
    });
  }

  /**
   * Attaches to {@code target} every connection failure recorded for {@code url}'s origin while
   * the sample was running.
   *
   * <p>Filtering by start time is what keeps the attribution honest without tracking which sample
   * owns which socket: connects run on Jetty threads, and concurrent embedded resources share the
   * client, so time plus origin is the correlation available. Attempts are attached as suppressed
   * exceptions whose cause is the original failure, keeping its stack trace intact.
   *
   * @param target the failure about to be reported for the sample
   * @param url the sampled URL, used to match host and port
   * @param sinceMillis the sample start, in epoch milliseconds; {@code 0} takes everything held
   */
  public void attachTo(Throwable target, URL url, long sinceMillis) {
    if (target == null || url == null) {
      return;
    }
    for (FailedAttempt attempt : failuresFor(url, sinceMillis)) {
      // The attempt that survived Jetty's loop is already the cause of what is being reported;
      // attaching it again would duplicate it and make printStackTrace emit a
      // "[CIRCULAR REFERENCE]" marker. Only the discarded ones are missing.
      if (!isInCauseChain(target, attempt.failure)) {
        target.addSuppressed(new ConnectAttemptFailure(attempt));
      }
    }
  }

  private void record(InetSocketAddress address, Throwable failure) {
    synchronized (attempts) {
      attempts.addLast(new FailedAttempt(address, failure, System.currentTimeMillis()));
      while (attempts.size() > capacity) {
        attempts.removeFirst();
      }
    }
  }

  private static boolean isInCauseChain(Throwable target, Throwable failure) {
    for (Throwable current = target; current != null; current = current.getCause()) {
      if (current == failure) {
        return true;
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return false;
  }

  private List<FailedAttempt> failuresFor(URL url, long sinceMillis) {
    String host = url.getHost();
    if (host == null || host.isEmpty()) {
      return List.of();
    }
    int port = url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
    List<FailedAttempt> matches = new ArrayList<>();
    synchronized (attempts) {
      for (FailedAttempt attempt : attempts) {
        if (attempt.matches(host, port, sinceMillis)) {
          matches.add(attempt);
        }
      }
    }
    if (matches.size() > MAX_ATTACHED) {
      return matches.subList(matches.size() - MAX_ATTACHED, matches.size());
    }
    return matches;
  }

  /**
   * Names the stage the attempt died at, from the failure itself. The cause is printed underneath
   * anyway, so this is a label rather than a diagnosis - which is what makes deriving it cheaper
   * than wiring one listener per phase.
   */
  private static String phaseOf(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SSLException) {
        return TLS_PHASE;
      }
      if (current instanceof SocketException || current instanceof SocketTimeoutException
          || current instanceof ConnectException) {
        return TCP_PHASE;
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return SESSION_PHASE;
  }

  private static final class FailedAttempt {

    private final InetSocketAddress address;
    private final Throwable failure;
    private final long timestamp;

    private FailedAttempt(InetSocketAddress address, Throwable failure, long timestamp) {
      this.address = address;
      this.failure = failure;
      this.timestamp = timestamp;
    }

    private boolean matches(String host, int port, long sinceMillis) {
      if (address.getPort() != port || timestamp < sinceMillis) {
        return false;
      }
      // getHostString() carries the name the address was resolved from and never triggers a
      // reverse lookup, so this stays off the network even for a literal-IP URL.
      return address.getHostString().toLowerCase(Locale.ROOT).equals(host.toLowerCase(Locale.ROOT));
    }

    private String describe() {
      String ip = address.getAddress() != null
          ? address.getAddress().getHostAddress()
          : address.getHostString();
      return phaseOf(failure) + " " + address.getHostString() + "/" + ip + ":" + address.getPort()
          + " failed";
    }
  }

  /**
   * Carrier for one discarded attempt. Its own stack trace is deliberately not filled in: the
   * frames that matter belong to the cause, and this exception is never thrown - it exists only to
   * be printed under {@code Suppressed:}.
   */
  public static class ConnectAttemptFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private ConnectAttemptFailure(FailedAttempt attempt) {
      super(attempt.describe(), attempt.failure, true, false);
    }
  }
}
