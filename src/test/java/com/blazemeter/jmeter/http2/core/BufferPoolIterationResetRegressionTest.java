package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.ConcurrentPool;
import org.junit.Test;

/**
 * The new-user iteration reset used to call {@code clear()} on the client's {@code
 * ArrayByteBufferPool}. Under load that leaves Jetty's pool unusable and every later sample fails
 * with an NPE from inside {@code ArrayByteBufferPool.acquire()} (reported as issue #171).
 *
 * <p>The pool is built with an unbounded {@code maxBucketSize}, so each bucket is a {@code
 * CompoundPool} whose secondary is a {@code QueuedPool}; {@code QueuedPool.QueuedEntry.remove()}
 * nulls the pooled buffer but leaves the entry in its queue, and the next {@code acquire()} polls
 * that dead entry. Buffers are reset on release and carry no user state, so the reset has no reason
 * to touch the pool at all.
 *
 * <p>The regression itself has to go through the sampler's real reset path, {@code
 * HTTP2Sampler#iterationStart}, so it lives in {@link
 * NewUserIterationConnectionResetTest#newUserIterationLeavesTheBufferPoolUsable()}, which reuses
 * {@link #primeSecondaryPool(ByteBufferPool)}. This class pins the Jetty behaviour behind it.
 */
public class BufferPoolIterationResetRegressionTest extends HTTP2TestBase {

  /** Network-buffer sized, and below the pool's 65536 max capacity so it is really pooled. */
  static final int POOLED_BUFFER_SIZE = 16 * 1024;
  /** Above {@code ConcurrentPool.OPTIMAL_MAX_SIZE}, so the secondary QueuedPool holds entries. */
  private static final int BUFFERS_BEYOND_PRIMARY_POOL = ConcurrentPool.OPTIMAL_MAX_SIZE + 44;

  /**
   * Pins the Jetty behaviour the reset has to stay away from. When this test starts failing, Jetty
   * has fixed {@code QueuedPool} and {@link HTTP2JettyClient} may pool-clear again if it ever needs
   * to.
   */
  @Test
  public void clearingALiveBufferPoolPoisonsItInThisJettyVersion() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient(false, "buffer-pool-clear-pin");
    try {
      ByteBufferPool bufferPool = client.getBufferPool();
      primeSecondaryPool(bufferPool);

      bufferPool.clear();

      assertThatThrownBy(() -> bufferPool.acquire(POOLED_BUFFER_SIZE, false))
          .as("Jetty's QueuedPool leaves removed entries in its queue, so acquire() returns a dead "
              + "entry with a null buffer")
          .isInstanceOf(NullPointerException.class);
    } finally {
      client.stop();
    }
  }

  /** Fills the bucket past the primary ConcurrentPool so its secondary QueuedPool is populated. */
  static void primeSecondaryPool(ByteBufferPool bufferPool) {
    List<RetainableByteBuffer> buffers = new ArrayList<>(BUFFERS_BEYOND_PRIMARY_POOL);
    for (int i = 0; i < BUFFERS_BEYOND_PRIMARY_POOL; i++) {
      buffers.add(bufferPool.acquire(POOLED_BUFFER_SIZE, false));
    }
    buffers.forEach(RetainableByteBuffer::release);
    assertThat(buffers).hasSize(BUFFERS_BEYOND_PRIMARY_POOL);
  }
}
