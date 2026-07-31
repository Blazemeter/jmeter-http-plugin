package org.eclipse.jetty.http2.client.transport;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http2.client.HTTP2Client;

/**
 * Deliberately placed in this Jetty package, not a {@code com.blazemeter} package: it exposes
 * {@link HttpClientTransportOverHTTP2}'s package-private {@code configure(HttpClient, HTTP2Client)}
 * to our code without reflection.
 *
 * <p>{@code com.blazemeter...custom.http2.CustomClientConnectionFactoryOverHTTP2} is a
 * {@code ClientConnectionFactory} plus {@code HttpClient.Aware} (used inside
 * {@code HttpClientTransportDynamic}), not an {@code HttpClientTransport} subclass, so unlike
 * {@code CustomHttpClientTransportOverHTTP2} it cannot inherit
 * {@code HttpClientTransportOverHTTP2.doStart()} — which calls {@code configure} for free
 * from within Jetty's own package. It must call {@code configure} itself from
 * {@code setHttpClient(HttpClient)}, and since that method is package-private, this shim
 * exposes it publicly instead of relying on reflection.
 *
 * <p>Keep in sync when upgrading Jetty: if {@code configure} is ever made public or removed, this
 * shim (and the caller) should be updated accordingly.
 */
public final class CustomHttp2ClientConfigurer {

  private CustomHttp2ClientConfigurer() {
  }

  public static void configure(HttpClient httpClient, HTTP2Client http2Client) {
    HttpClientTransportOverHTTP2.configure(httpClient, http2Client);
  }
}
