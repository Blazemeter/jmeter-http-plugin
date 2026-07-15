package org.eclipse.jetty.http2.client;

import org.eclipse.jetty.http2.SessionContainer;

/**
 * Deliberately placed in this Jetty package, not a {@code com.blazemeter} package: it exposes
 * {@link HTTP2Client}'s package-private {@code getSessionContainer()} to our code without
 * reflection.
 *
 * <p>{@code com.blazemeter...custom.http2.CustomHTTP2ClientConnectionFactory} rebuilds the
 * connection setup that {@code HTTP2ClientConnectionFactory#newConnection} does upstream,
 * including registering the client's session container as an event listener on the new
 * connection. That container is only reachable via this package-private accessor, so this shim
 * exposes it publicly instead of relying on reflection.
 *
 * <p>Keep in sync when upgrading Jetty: if {@code getSessionContainer} is ever made public or
 * removed, this shim (and the caller) should be updated accordingly.
 */
public final class CustomHttp2SessionContainerAccessor {

  private CustomHttp2SessionContainerAccessor() {
  }

  public static SessionContainer getSessionContainer(HTTP2Client client) {
    return client.getSessionContainer();
  }
}
