package com.blazemeter.jmeter.http2.core;

import java.io.IOException;
import java.util.Objects;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

/**
 * Request tag that routes HTTPS connections through a per-alias destination and injects the
 * resolved client certificate alias into the Jetty connection context for TLS handshakes.
 */
final class SslClientCertAliasTag implements ClientConnectionFactory.Decorator {

  private final String alias;

  SslClientCertAliasTag(String alias) {
    this.alias = Objects.requireNonNull(alias, "alias");
  }

  String getAlias() {
    return alias;
  }

  @Override
  public ClientConnectionFactory apply(ClientConnectionFactory factory) {
    return new ClientConnectionFactory() {
      @Override
      public Connection newConnection(EndPoint endPoint, java.util.Map<String, Object> context)
          throws IOException {
        context.put(SslClientCertAliasContext.CONNECTION_CONTEXT_KEY, alias);
        return factory.newConnection(endPoint, context);
      }
    };
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SslClientCertAliasTag)) {
      return false;
    }
    return alias.equals(((SslClientCertAliasTag) other).alias);
  }

  @Override
  public int hashCode() {
    return alias.hashCode();
  }

  @Override
  public String toString() {
    return "SslClientCertAliasTag[" + alias + "]";
  }

}
