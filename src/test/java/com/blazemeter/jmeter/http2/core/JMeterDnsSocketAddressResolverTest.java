package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.conn.DnsResolver;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the contract {@link JMeterDnsSocketAddressResolver} owes Jetty when it hands over a JMeter
 * DNS Cache Manager: every address, in order, or a failure - never an empty success, because
 * {@code HttpClient} indexes straight into the resolved list, and never an unbounded wait, because
 * JMeter leaves the manager's own DNS timeout unset.
 */
public class JMeterDnsSocketAddressResolverTest {

  private static final long TIMEOUT_MS = 300;
  private static final int AWAIT_SECONDS = 10;

  private ExecutorService executor;
  private ScheduledExecutorScheduler scheduler;

  @Before
  public void setUp() throws Exception {
    executor = Executors.newCachedThreadPool();
    scheduler = new ScheduledExecutorScheduler("dns-resolver-test-scheduler", true);
    scheduler.start();
  }

  @After
  public void tearDown() throws Exception {
    if (scheduler != null) {
      scheduler.stop();
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  @Test
  public void resolvesEveryAddressInTheOrderTheManagerReturnedThem() throws Exception {
    InetAddress first = InetAddress.getByName("127.0.0.1");
    InetAddress second = InetAddress.getByName("::1");
    CapturingPromise promise = resolve(host -> new InetAddress[] {first, second});

    assertThat(promise.awaitAddresses())
        .containsExactly(new InetSocketAddress(first, 443), new InetSocketAddress(second, 443));
  }

  @Test
  public void failsWithUnknownHostWhenTheManagerResolvesToNull() throws Exception {
    // DNSCacheManager.customRequestLookup returns null when dnsjava cannot parse the name, and its
    // cache is allowed to hold a null value for a host.
    CapturingPromise promise = resolve(host -> null);

    assertThat(promise.awaitFailure()).isInstanceOf(UnknownHostException.class);
  }

  @Test
  public void failsWithUnknownHostWhenTheManagerResolvesToNoAddress() throws Exception {
    // JMeter's isStaticHost matches case-insensitively but fromStaticHost re-reads the entry
    // case-sensitively, so a static host declared with different casing resolves to an empty
    // array instead of throwing.
    CapturingPromise promise = resolve(host -> new InetAddress[0]);

    assertThat(promise.awaitFailure()).isInstanceOf(UnknownHostException.class);
  }

  @Test
  public void propagatesTheFailureRaisedByTheManager() throws Exception {
    UnknownHostException raised = new UnknownHostException("no.such.host");
    CapturingPromise promise = resolve(host -> {
      throw raised;
    });

    assertThat(promise.awaitFailure()).isSameAs(raised);
  }

  @Test
  public void failsWithTimeoutWhenTheLookupOutlivesTheConfiguredTimeout() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try {
      CapturingPromise promise = resolve(host -> {
        awaitQuietly(release);
        return new InetAddress[] {InetAddress.getLoopbackAddress()};
      });

      assertThat(promise.awaitFailure()).isInstanceOf(TimeoutException.class);
    } finally {
      release.countDown();
    }
  }

  @Test
  public void serializesConcurrentLookupsOverTheSameManager() throws Exception {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    JMeterDnsSocketAddressResolver resolver = newResolver(host -> {
      maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
      try {
        Thread.sleep(30);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        inFlight.decrementAndGet();
      }
      return new InetAddress[] {InetAddress.getLoopbackAddress()};
    }, TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));

    CapturingPromise[] promises = new CapturingPromise[4];
    for (int i = 0; i < promises.length; i++) {
      promises[i] = new CapturingPromise();
      resolver.resolve("localhost", 80, Map.of(), promises[i]);
    }
    for (CapturingPromise promise : promises) {
      assertThat(promise.awaitAddresses()).hasSize(1);
    }

    assertThat(maxInFlight.get()).isEqualTo(1);
  }

  @Test
  public void resolvesInlineWhileTheClientHasNotProvidedAnExecutorYet() throws Exception {
    JMeterDnsSocketAddressResolver resolver = new JMeterDnsSocketAddressResolver(
        host -> new InetAddress[] {InetAddress.getLoopbackAddress()},
        () -> null, () -> scheduler, TIMEOUT_MS);
    CapturingPromise promise = new CapturingPromise();

    resolver.resolve("localhost", 8080, Map.of(), promise);

    assertThat(promise.awaitAddresses())
        .containsExactly(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080));
  }

  private CapturingPromise resolve(DnsResolver dnsResolver) throws Exception {
    CapturingPromise promise = new CapturingPromise();
    newResolver(dnsResolver, TIMEOUT_MS).resolve("localhost", 443, Map.of(), promise);
    return promise;
  }

  private JMeterDnsSocketAddressResolver newResolver(DnsResolver dnsResolver, long timeoutMs) {
    return new JMeterDnsSocketAddressResolver(dnsResolver, () -> executor, () -> scheduler,
        timeoutMs);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class CapturingPromise implements Promise<List<InetSocketAddress>> {

    private final CountDownLatch done = new CountDownLatch(1);

    private volatile List<InetSocketAddress> addresses;
    private volatile Throwable failure;

    @Override
    public void succeeded(List<InetSocketAddress> result) {
      addresses = result;
      done.countDown();
    }

    @Override
    public void failed(Throwable throwable) {
      failure = throwable;
      done.countDown();
    }

    private List<InetSocketAddress> awaitAddresses() throws InterruptedException {
      await();
      assertThat(failure).isNull();
      return addresses;
    }

    private Throwable awaitFailure() throws InterruptedException {
      await();
      assertThat(addresses).isNull();
      return failure;
    }

    private void await() throws InterruptedException {
      assertThat(done.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
  }
}
