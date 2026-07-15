package org.eclipse.jetty.http3.client.transport;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http3.client.HTTP3Client;

/**
 * Deliberately placed in this Jetty package, not a {@code com.blazemeter} package: it exposes
 * {@link HttpClientTransportOverHTTP3}'s package-private {@code configure(HttpClient, HTTP3Client)}
 * to our code without reflection.
 *
 * <p>{@code com.blazemeter...custom.http3.CustomClientConnectionFactoryOverHTTP3} is a
 * {@code ClientConnectionFactory} plus {@code HttpClient.Aware} (used inside
 * {@code HttpClientTransportDynamic}), not an {@code HttpClientTransport} subclass, so it cannot
 * inherit {@code HttpClientTransportOverHTTP3.doStart()} — which calls {@code configure} for free
 * from within Jetty's own package. It must call {@code configure} itself from
 * {@code setHttpClient(HttpClient)}, and since that method is package-private, this shim exposes
 * it publicly instead of relying on reflection. Mirrors
 * {@code org.eclipse.jetty.http2.client.transport.CustomHttp2ClientConfigurer} for the HTTP/2 case.
 *
 * <p>Keep in sync when upgrading Jetty: if {@code configure} is ever made public or removed, this
 * shim (and the caller) should be updated accordingly.
 */
public final class CustomHttp3ClientConfigurer {

  private CustomHttp3ClientConfigurer() {
  }

  public static void configure(HttpClient httpClient, HTTP3Client http3Client) {
    HttpClientTransportOverHTTP3.configure(httpClient, http3Client);
  }
}
