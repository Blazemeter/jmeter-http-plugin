package com.blazemeter.jmeter.http2.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.core.HTTP2ClientProfileConfig;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.lang.reflect.Method;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerFactory;
import org.apache.jmeter.util.JMeterUtils;

/** Runs the same sampler configuration through HttpClient4 and the BlazeMeter HTTP Jetty client. */
public final class HttpClient4PluginParitySupport {

  private static final String HTTP_CLIENT4 = "HttpClient4";

  private HttpClient4PluginParitySupport() {
  }

  public static void forceHttp1ClientProfile() {
    JMeterUtils.setProperty("httpJettyClient.enableHttp1", "true");
    JMeterUtils.setProperty("httpJettyClient.enableHttp2", "false");
    JMeterUtils.setProperty("httpJettyClient.enableHttp3", "false");
    JMeterUtils.setProperty("httpJettyClient.alpnEnabled", "false");
  }

  public static HTTP2JettyClient newHttp1PluginClient(String name) throws Exception {
    forceHttp1ClientProfile();
    HTTP2JettyClient client = new HTTP2JettyClient(false, name,
        HTTP2ClientProfileConfig.builder().enableHttp1(true).enableHttp2(false).enableHttp3(false)
            .build());
    client.start();
    return client;
  }

  public static void copyHttpSamplerConfig(HTTPSamplerBase from, HTTPSamplerBase to) {
    to.setDomain(from.getDomain());
    to.setPort(from.getPort());
    to.setProtocol(from.getProtocol());
    to.setPath(from.getPath());
    to.setMethod(from.getMethod());
    to.setFollowRedirects(from.getFollowRedirects());
    to.setAutoRedirects(from.getAutoRedirects());
    to.setUseKeepAlive(from.getUseKeepAlive());
    to.setDoMultipart(from.getDoMultipart());
    to.setPostBodyRaw(from.getPostBodyRaw());
    to.setContentEncoding(from.getContentEncoding());
    to.setArguments(from.getArguments());
    to.setHTTPFiles(from.getHTTPFiles());
    to.setCookieManager(from.getCookieManager());
    to.setCacheManager(from.getCacheManager());
    to.setHeaderManager(from.getHeaderManager());
    to.setAuthManager(from.getAuthManager());
    to.setImageParser(from.isImageParser());
  }

  public static HTTPSampleResult sampleHttpClient4(HTTP2Sampler sampler, URL url)
      throws Exception {
    HTTPSamplerBase hc4 = HTTPSamplerFactory.newInstance(HTTP_CLIENT4);
    copyHttpSamplerConfig(sampler, hc4);
    Method sample = HTTPSamplerBase.class.getDeclaredMethod(
        "sample", URL.class, String.class, boolean.class, int.class);
    sample.setAccessible(true);
    return (HTTPSampleResult) sample.invoke(
        hc4, url, sampler.getMethod(), sampler.getFollowRedirects(), 1);
  }

  public static HTTPSampleResult samplePlugin(HTTP2JettyClient client, HTTP2Sampler sampler,
      URL url) throws Exception {
    HTTPSampleResult shell = new HTTPSampleResult();
    shell.setURL(url);
    shell.setHTTPMethod(sampler.getMethod());
    shell.setSampleLabel(sampler.getName());
    client.loadProperties();
    return client.sample(sampler, shell, false, 0);
  }

  public static void assertCoreParity(HTTPSampleResult reference, HTTPSampleResult plugin,
      String context) {
    assertThat(plugin.isSuccessful())
        .as("%s success", context)
        .isEqualTo(reference.isSuccessful());
    assertThat(plugin.getResponseCode())
        .as("%s response code", context)
        .isEqualTo(reference.getResponseCode());
    assertThat(normalizeMessage(plugin.getResponseMessage()))
        .as("%s response message", context)
        .isEqualTo(normalizeMessage(reference.getResponseMessage()));
    assertThat(plugin.getRedirectLocation())
        .as("%s redirect location", context)
        .isEqualTo(reference.getRedirectLocation());
  }

  public static void assertResponseBodyParity(HTTPSampleResult reference, HTTPSampleResult plugin,
      String context) {
    assertThat(plugin.getResponseDataAsString())
        .as("%s response body", context)
        .isEqualTo(reference.getResponseDataAsString());
  }

  private static String normalizeMessage(String message) {
    return message == null ? "" : message.trim();
  }
}
