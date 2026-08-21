package com.blazemeter.jmeter.http2.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.http.conn.DnsResolver;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * Resolves host names through JMeter's DNS Cache Manager instead of {@code InetAddress}, so a test
 * plan's custom DNS servers, static host entries and per-thread DNS cache apply to this plugin
 * exactly as they do to {@code HTTPHC4Impl}.
 *
 * <p>{@code DNSCacheManager} is an {@code org.apache.http.conn.DnsResolver}, which is what
 * {@code HTTPHC4Impl} hands to its connection operator on the one-time client init. This class is
 * the Jetty-side equivalent: {@code HttpClient} resolves through a {@link SocketAddressResolver},
 * and installing one is enough to cover every protocol, because HTTP/1.1, h2, h2c and HTTP/3 all
 * reach the network through {@code HttpClient.newConnection}.
 *
 * <p>Deliberately modelled on {@link SocketAddressResolver.Async}, which it replaces: the lookup
 * runs on the client executor rather than on the caller (which may be a selector thread), and a
 * scheduled task fails the promise if the lookup outlives the timeout. The guard is not optional
 * here - JMeter never calls {@code DNSCacheManager.setTimeoutMs}, so a custom resolver inherits
 * dnsjava's own retry behaviour and a black-holed DNS server would otherwise pin an executor
 * thread with nothing failing the request.
 *
 * <p>The executor and scheduler are read lazily because the resolver is installed while the
 * {@code HttpClient} is still being built: {@code HttpClient.doStart} only creates its default
 * {@code Async} resolver when none was set, so ours has to be in place before {@code start()},
 * at which point {@code getExecutor()} and {@code getScheduler()} are still {@code null}.
 */
public class JMeterDnsSocketAddressResolver implements SocketAddressResolver {

  private final DnsResolver dnsResolver;
  private final Supplier<Executor> executorSupplier;
  private final Supplier<Scheduler> schedulerSupplier;
  private final long timeoutMs;

  public JMeterDnsSocketAddressResolver(DnsResolver dnsResolver,
                                        Supplier<Executor> executorSupplier,
                                        Supplier<Scheduler> schedulerSupplier,
                                        long timeoutMs) {
    this.dnsResolver = dnsResolver;
    this.executorSupplier = executorSupplier;
    this.schedulerSupplier = schedulerSupplier;
    this.timeoutMs = timeoutMs;
  }

  @Override
  public void resolve(String host, int port, Map<String, Object> context,
                      Promise<List<InetSocketAddress>> promise) {
    Executor executor = executorSupplier.get();
    if (executor == null) {
      // Only reachable if a caller resolves before the client is started; resolving inline is
      // still better than dropping the request on the floor.
      resolveAndComplete(host, port, promise, new AtomicBoolean());
      return;
    }
    executor.execute(() -> {
      AtomicBoolean complete = new AtomicBoolean();
      Scheduler.Task timeoutTask = scheduleTimeout(host, Thread.currentThread(), complete, promise);
      try {
        resolveAndComplete(host, port, promise, complete);
      } finally {
        if (timeoutTask != null) {
          timeoutTask.cancel();
        }
        // The timeout task interrupts this thread to unblock the lookup; clear the flag so the
        // pooled thread does not carry it into unrelated work.
        Thread.interrupted();
      }
    });
  }

  private Scheduler.Task scheduleTimeout(String host, Thread resolvingThread,
                                         AtomicBoolean complete,
                                         Promise<List<InetSocketAddress>> promise) {
    Scheduler scheduler = schedulerSupplier.get();
    if (timeoutMs <= 0 || scheduler == null) {
      return null;
    }
    return scheduler.schedule(() -> {
      if (complete.compareAndSet(false, true)) {
        promise.failed(new TimeoutException(
            "DNS timeout " + timeoutMs + " ms resolving " + host));
        resolvingThread.interrupt();
      }
    }, timeoutMs, TimeUnit.MILLISECONDS);
  }

  private void resolveAndComplete(String host, int port,
                                  Promise<List<InetSocketAddress>> promise,
                                  AtomicBoolean complete) {
    try {
      InetAddress[] addresses = resolveAddresses(host);
      // DNSCacheManager returns null when a custom lookup cannot parse the name, and an empty
      // array when a static host entry is matched case-insensitively by isStaticHost but read
      // case-sensitively by fromStaticHost (a JMeter bug still present on master). Neither may
      // reach Jetty as a success: HttpClient indexes straight into the returned list.
      if (addresses == null || addresses.length == 0) {
        throw new UnknownHostException(host);
      }
      List<InetSocketAddress> result = new ArrayList<>(addresses.length);
      for (InetAddress address : addresses) {
        result.add(new InetSocketAddress(address, port));
      }
      if (complete.compareAndSet(false, true)) {
        promise.succeeded(result);
      }
    } catch (Throwable failure) {
      if (complete.compareAndSet(false, true)) {
        promise.failed(failure);
      }
    }
  }

  private InetAddress[] resolveAddresses(String host) throws UnknownHostException {
    // DNSCacheManager keeps its cache in a plain LinkedHashMap and JMeter clones one instance per
    // thread, but a single JMeter thread resolves concurrently here (embedded resources, and the
    // HTTP/3 vs HTTP/2 race, run on plugin executors). Serializing keeps that map consistent;
    // cache hits make the critical section negligible.
    synchronized (dnsResolver) {
      return dnsResolver.resolve(host);
    }
  }
}
