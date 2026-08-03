package com.blazemeter.jmeter.http2.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CancellationException;
import org.junit.Test;

/**
 * Once a protocol race hands a winning response to the shared listener, the abort of the losing
 * request must not turn that win into a failure.
 *
 * <p>The race resolves by calling {@link HTTP2FutureResponseListener#completeWith} on the listener
 * of one attempt with the other attempt's response, then aborting the loser. That abort makes Jetty
 * call back into this same listener with a CancellationException, and {@code getResult()} treats any
 * stored failure as an error even when a response is present.
 */
public class HTTP2FutureResponseListenerSealingTest {

  @Test
  public void shouldKeepAdoptedResponseAfterLosingRequestIsAborted() throws Exception {
    HTTP2FutureResponseListener listener =
        new HTTP2FutureResponseListener(-1);

    listener.completeWith(null, 100L, 200L);
    assertTrue("listener should be done once a result was adopted", listener.isDone());

    // Jetty delivering the loser's abort after the race was already resolved.
    listener.onFailure(null, new CancellationException("race lost"));
    listener.onComplete(null);

    // Without sealing, the abort's failure would surface here instead of the adopted result.
    assertEquals(200L, listener.getResponseEnd());
    assertEquals(100L, listener.getResponseStart());
    assertEquals("adopted result must survive the loser's callbacks", null, listener.get());
  }
}
