package org.eclipse.jetty.client;

import java.util.Collection;

/**
 * Deliberately placed in this Jetty package, not a {@code com.blazemeter} package: it exposes
 * {@link AbstractConnectionPool}'s package-private {@code getIdleConnections()} to our code without
 * reflection.
 *
 * <p>{@code com.blazemeter...core.HTTP2JettyClient#closeIdleConnections} closes every pooled
 * connection that carries no exchange on a new-user iteration, the way JMeter's own HttpClient4
 * sampler does, while leaving the in-use ones alone. Jetty has no public way to list a pool's idle
 * connections, so this shim exposes it.
 *
 * <p>Keep in sync when upgrading Jetty: if {@code getIdleConnections} is ever made public or
 * removed, this shim (and the caller) should be updated accordingly.
 */
public final class CustomConnectionPoolAccessor {

  private CustomConnectionPoolAccessor() {
  }

  public static Collection<Connection> getIdleConnections(AbstractConnectionPool pool) {
    return pool.getIdleConnections();
  }
}
