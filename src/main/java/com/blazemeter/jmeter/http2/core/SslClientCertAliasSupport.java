package com.blazemeter.jmeter.http2.core;

import org.eclipse.jetty.client.Request;

/**
 * Binds a client certificate alias resolved on the JMeter thread to a Jetty {@link Request}.
 */
public final class SslClientCertAliasSupport {

  private SslClientCertAliasSupport() {
  }

  public static void bindToRequest(Request request, String alias) {
    if (request == null || alias == null || alias.isEmpty()) {
      return;
    }
    request.attribute(SslClientCertAliasContext.REQUEST_ATTRIBUTE, alias);
    request.tag(new SslClientCertAliasTag(alias));
  }

  public static void copyFromRequest(Request source, Request target) {
    if (source == null || target == null) {
      return;
    }
    Object tag = source.getTag();
    if (tag != null) {
      target.tag(tag);
    }
    Object alias = source.getAttributes().get(SslClientCertAliasContext.REQUEST_ATTRIBUTE);
    if (alias != null) {
      target.attribute(SslClientCertAliasContext.REQUEST_ATTRIBUTE, alias);
    }
  }

}
