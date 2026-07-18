package com.blazemeter.jmeter.http2.util;

import org.apache.jmeter.protocol.http.util.HTTPConstants;

/**
 * Shared by {@code HTTP2Sampler} and {@code HTTP2JettyClient}: JMeter 5.6.3's
 * {@code HTTPSampleResult.isRedirect()} only recognizes a {@code 307} response as a redirect
 * when the request method is GET or HEAD, and {@code HTTPSamplerBase.computeMethodForRedirect}
 * rewrites every redirected method to GET regardless of status code - both violate RFC 9110
 * sections 15.4.8/15.4.9, which require 307/308 to preserve the original method (and therefore
 * its body). Apache JMeter fixed this upstream in
 * <a href="https://github.com/apache/jmeter/pull/6658">apache/jmeter PR #6658</a> (merged
 * 2026-03-19), but that fix is not in 5.6.3 or any released JMeter version yet.
 *
 * <p>Set {@code -Dblazemeter.http.legacyRedirectMethodHandling=true} (or the legacy
 * {@code HTTP2Sampler.*}/{@code httpJettyClient.*} equivalents) to opt back into JMeter 5.6.3's
 * original behavior, bug included, for users who need byte-for-byte parity with stock JMeter.
 */
public final class Rfc9110Redirects {

  private static final String LEGACY_REDIRECT_METHOD_HANDLING_PROPERTY =
      "blazemeter.http.legacyRedirectMethodHandling";

  private Rfc9110Redirects() {
  }

  public static boolean useLegacyMethodHandling() {
    return BzmHttpPluginProperties.getPropDefault(LEGACY_REDIRECT_METHOD_HANDLING_PROPERTY, false);
  }

  /** Matches {@code HTTPSampleResult.isRedirect()} post apache/jmeter PR #6658: unlike the
   *  unfixed JMeter 5.6.3 version, 307 is a redirect for any method, not just GET/HEAD. */
  public static boolean isRedirect(String responseCode) {
    return HTTPConstants.SC_MOVED_PERMANENTLY.equals(responseCode)
        || HTTPConstants.SC_MOVED_TEMPORARILY.equals(responseCode)
        || HTTPConstants.SC_SEE_OTHER.equals(responseCode)
        || HTTPConstants.SC_TEMPORARY_REDIRECT.equals(responseCode)
        || HTTPConstants.SC_PERMANENT_REDIRECT.equals(responseCode);
  }
}
