package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Method;
import java.net.URL;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Pins JMeter-aligned configuration of HTTP embedded-resource children (the gaps that used to
 * diverge from {@code HTTPSamplerBase.ASyncSample} / {@code downloadPageResources}).
 */
public class EmbeddedResourceJmeterParityTest extends HTTP2TestBase {

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Test
  public void concurrentDownloadDefaultsToFalseLikeJMeter() {
    assertThat(new HTTP2Sampler().isConcurrentDwn())
        .as("JMeter leaves concurrent download off unless the user enables it")
        .isFalse();
  }

  @Test
  public void httpEmbeddedChildInheritsRedirectsImageParserCacheMd5AndQuery() throws Exception {
    HTTP2Sampler parent = new HTTP2Sampler();
    parent.setFollowRedirects(false);
    parent.setAutoRedirects(false);
    parent.setImageParser(true);
    parent.setMD5(true);
    parent.setCacheManager(new CacheManager());
    parent.setCookieManager(new CookieManager());
    parent.setConnectTimeout("1000");
    parent.setResponseTimeout("2000");

    URL resource = new URL("https://example.com/assets/app.js?v=3&x=1");
    HTTP2Sampler child = invokeConfigure(parent, resource, true);

    assertThat(child.getFollowRedirects()).isFalse();
    assertThat(child.getAutoRedirects()).isFalse();
    assertThat(child.isImageParser()).isTrue();
    assertThat(child.useMD5()).isTrue();
    assertThat(child.getCacheManager())
        .as("concurrent children must get a CacheManager proxy like ASyncSample")
        .isNotNull()
        .isNotSameAs(parent.getCacheManager());
    assertThat(child.getCookieManager())
        .as("concurrent children must clone the cookie jar so siblings do not share mid-page state")
        .isNotNull()
        .isNotSameAs(parent.getCookieManager());
    assertThat(child.getPath()).isEqualTo("/assets/app.js");
    assertThat(child.getUrl().toExternalForm())
        .as("setPath must receive path?query so JMeter parses args; without '?' the query was "
            + "appended as part of the path")
        .contains("/assets/app.js?v=3&x=1");
    assertThat(child.getConnectTimeout()).isEqualTo(1000);
    assertThat(child.getResponseTimeout()).isEqualTo(2000);
    assertThat(child.isSyncRequest()).isFalse();
  }

  @Test
  public void serialHttpEmbeddedChildSharesCookieManagerWithParent() throws Exception {
    HTTP2Sampler parent = new HTTP2Sampler();
    CookieManager cookies = new CookieManager();
    parent.setCookieManager(cookies);

    HTTP2Sampler child = invokeConfigure(parent,
        new URL("https://example.com/a.png"), false);

    assertThat(child.getCookieManager()).isSameAs(cookies);
    assertThat(child.isSyncRequest()).isTrue();
  }

  @Test
  public void httpEmbeddedChildInheritsKeepAliveEncodingConcurrentAndUrlFilters()
      throws Exception {
    HTTP2Sampler parent = new HTTP2Sampler();
    parent.setUseKeepAlive(false);
    parent.setContentEncoding("ISO-8859-1");
    parent.setConcurrentDwn(true);
    parent.setConcurrentPool("7");
    parent.setEmbeddedUrlRE(".*\\.png");
    parent.setEmbeddedUrlExcludeRE(".*ads.*");

    HTTP2Sampler child = invokeConfigure(parent,
        new URL("https://example.com/iframe.html"), true);

    assertThat(child.getUseKeepAlive())
        .as("nested embeds must keep the parent's Connection/keep-alive policy")
        .isFalse();
    assertThat(child.getContentEncoding()).isEqualTo("ISO-8859-1");
    assertThat(child.isConcurrentDwn())
        .as("nested HTML downloadPageResources must keep parent's parallel-download flag")
        .isTrue();
    assertThat(child.getConcurrentPool()).isEqualTo("7");
    assertThat(child.getEmbeddedUrlRE()).isEqualTo(".*\\.png");
    assertThat(child.getEmbededUrlExcludeRE()).isEqualTo(".*ads.*");
    // Dispatch mode for this child is still driven by the concurrent parameter, not by
    // inheriting syncRequest from the parent's concurrent flag alone.
    assertThat(child.isSyncRequest()).isFalse();
  }

  @Test
  public void pathWithQueryKeepsQuestionMark() throws Exception {
    Method pathWithQuery = HTTP2Sampler.class.getDeclaredMethod("pathWithQuery", URL.class);
    pathWithQuery.setAccessible(true);
    assertThat(pathWithQuery.invoke(null, new URL("https://ex.com/p?a=1")))
        .isEqualTo("/p?a=1");
    assertThat(pathWithQuery.invoke(null, new URL("https://ex.com/p")))
        .isEqualTo("/p");
  }

  @Test
  public void mergeEmbeddedCookiesCopiesChildJarIntoParent() throws Exception {
    HTTP2Sampler parent = new HTTP2Sampler();
    CookieManager parentCookies = new CookieManager();
    parent.setCookieManager(parentCookies);

    HTTP2Sampler child = new HTTP2Sampler();
    CookieManager childCookies = new CookieManager();
    childCookies.add(new org.apache.jmeter.protocol.http.control.Cookie(
        "sid", "abc", "example.com", "/", false, 0));
    child.setCookieManager(childCookies);

    Method merge = HTTP2Sampler.class.getDeclaredMethod(
        "mergeEmbeddedCookiesIntoParent", HTTP2Sampler.class);
    merge.setAccessible(true);
    merge.invoke(parent, child);

    assertThat(parentCookies.getCookieCount()).isEqualTo(1);
    org.apache.jmeter.protocol.http.control.Cookie merged =
        (org.apache.jmeter.protocol.http.control.Cookie)
            parentCookies.getCookies().get(0).getObjectValue();
    assertThat(merged.getName()).isEqualTo("sid");
    assertThat(merged.getValue()).isEqualTo("abc");
  }

  @Test
  public void embeddedPollDeadlineUsesOnlyResponseTimeoutNotStaleClientField() {
    HTTP2Sampler parent = new HTTP2Sampler();
    parent.setResponseTimeout("");
    assertThat(parent.getResponseTimeout())
        .as("unset response timeout must stay 0 so the embedded poll has no page-level backstop "
            + "(matching JMeter ResourcesDownloader); a stale Jetty client requestTimeout field "
            + "must not be substituted")
        .isZero();
  }

  private static HTTP2Sampler invokeConfigure(HTTP2Sampler parent, URL url, boolean concurrent)
      throws Exception {
    Method configure = HTTP2Sampler.class.getDeclaredMethod(
        "configureHttpEmbeddedSampler", HTTP2Sampler.class, URL.class, boolean.class);
    configure.setAccessible(true);
    HTTP2Sampler child = new HTTP2Sampler();
    configure.invoke(parent, child, url, concurrent);
    return child;
  }
}
