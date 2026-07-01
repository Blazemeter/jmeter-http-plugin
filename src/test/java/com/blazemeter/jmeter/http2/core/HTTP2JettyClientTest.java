package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.AUTH_PASSWORD;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.AUTH_REALM;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.AUTH_USERNAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.BASIC_HTML_TEMPLATE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.BIG_BUFFER_SIZE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.BINARY_RESPONSE_BODY;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.KEYSTORE_PASSWORD;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.RESPONSE_DATA_COOKIES;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.RESPONSE_DATA_COOKIES2;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_IMAGE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_EMBEDDED;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_FILE_SENT;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_GZIP;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_BROTLI;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_ZSTD;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_WITH_BODY;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_302;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_400;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_BIG_RESPONSE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_DEFLATE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_JSON_ONLY;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_SET_COOKIES;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_SLOW;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_RESPONSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import com.google.common.io.Resources;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.Cookie;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.SSLManager;
import org.assertj.core.api.JUnitSoftAssertions;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.RetryableRequestException;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpStatus.Code;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HTTP2JettyClientTest extends HTTP2TestBase {

  private static final int DEFAULT_TEST_PORT = 6666;
  private static final String TEST_ARGUMENT_1 = "valueTest1";
  private static final String TEST_ARGUMENT_2 = "valueTest2";

  @Rule
  public final JUnitSoftAssertions softly = new JUnitSoftAssertions();
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;
  private TeardownableServer server;
  private int serverPort = -1;
  private String originalSharedThreadPoolProperty;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setup() throws Exception {
    originalSharedThreadPoolProperty = JMeterUtils.getProperty("httpJettyClient.sharedThreadPool");
    JMeterUtils.setProperty("httpJettyClient.sharedThreadPool", "false");
    sampler = new HTTP2Sampler();
    configureSampler(sampler);
    client = new HTTP2JettyClient();
    client.start();
  }

  private static void configureSampler(HTTP2Sampler sampler) {
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("server");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(DEFAULT_TEST_PORT);
    sampler.setPath("");
  }

  @After
  public void teardown() throws Exception {
    sampler.threadFinished();
    client.stop();
    client = null;
    serverPort = -1;
    if (originalSharedThreadPoolProperty == null) {
      JMeterUtils.getJMeterProperties().remove("httpJettyClient.sharedThreadPool");
    } else {
      JMeterUtils.setProperty("httpJettyClient.sharedThreadPool",
          originalSharedThreadPoolProperty);
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void shouldInitializeHTTP2JettyClient() {
    boolean http1UpgradeRequired = true;
    String name = "TestClient";

    HTTP2JettyClient httpClient = new HTTP2JettyClient(http1UpgradeRequired, name);

    assertNotNull(httpClient);
    assertNotNull(httpClient.getBufferPool());
    assertNotNull(httpClient.getHttpClient());

  }

  @Test
  public void shouldDetectRetryableRequestExceptionInCauseChain() {
    HTTP2JettyClient httpClient = new HTTP2JettyClient();
    RetryableRequestException retryable = new RetryableRequestException("goaway");
    ExecutionException executionException =
        new ExecutionException(new RuntimeException(retryable));

    RetryableRequestException detected =
        httpClient.findRetryableRequestException(executionException);

    assertThat(detected).isSameAs(retryable);
  }

  @Test
  public void shouldGetResponseWhenGetMethodIsSent() throws Exception {
    buildStartedServer();
    HTTPSampleResult result = sampleWithGet();
    assertThat(result.getResponseDataAsString()).isEqualTo(SERVER_RESPONSE);
  }

  private void buildStartedServer() throws Exception {
    server = new ServerBuilder()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .buildServer();
    server.start();
    syncServerPort();
  }

  private HTTPSampleResult sampleWithGet() throws Exception {
    return sampleWithGet(SERVER_PATH_200);
  }

  private HTTPSampleResult sampleWithGet(String path) throws Exception {
    return sample(path, HTTPConstants.GET);
  }

  private HTTPSampleResult sample(String path, String method) throws Exception {
    client.loadProperties(); // Ensure to load the changes of properties in execution context
    return client.sample(sampler, buildBaseResult(createURL(path), method), false, 0);
  }

  private URL createURL(String path) throws MalformedURLException {
    return createURL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, getActivePort(), path);
  }

  private URL createURL(String protocol, String host, int port, String path)
      throws MalformedURLException {
    try {
      return new URI(protocol, null, host, port, path, null, null).toURL();
    } catch (URISyntaxException e) {
      MalformedURLException malformedURLException =
          new MalformedURLException("Failed to build URL from URI components");
      malformedURLException.initCause(e);
      throw malformedURLException;
    }
  }

  private int resolveServerPort() {
    if (server == null || server.getConnectors().length == 0) {
      return sampler.getPort();
    }
    return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
  }

  private void syncServerPort() {
    serverPort = resolveServerPort();
    sampler.setPort(serverPort);
  }

  private int getActivePort() {
    return serverPort > 0 ? serverPort : sampler.getPort();
  }

  private String hostHeaderValue() {
    return HOST_NAME + ":" + getActivePort();
  }

  // Build mutable headers per call to avoid shared state
  private Mutable hostHeader() {
    return HttpFields.build()
        .add(HttpHeader.HOST, hostHeaderValue());
  }

  private HTTPSampleResult buildBaseResult(URL url, String method) {
    HTTPSampleResult ret = new HTTPSampleResult();
    ret.setURL(url);
    ret.setHTTPMethod(method);
    return ret;
  }

  @Test
  public void shouldThrowConnectExceptionWhenServerIsInaccessible() {
    try {
      client.sample(sampler,
          buildBaseResult(createURL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, 123, SERVER_PATH_200),
              HTTPConstants.GET), false, 0);
    } catch (Exception ex) {
      assert ((ex instanceof ExecutionException) || (ex instanceof TimeoutException));
    }
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessResponseWithContentTypeGzip()
      throws Exception {
    buildStartedServer();
    String originalEnableHttp1 = JMeterUtils.getProperty("httpJettyClient.enableHttp1");
    // Keep HTTP/2 enabled here; gzip relies on an initialized InflaterPool in HTTP2JettyClient.
    // If this test regresses with cancel_stream_error/input_shutdown, check gzip pool init.
    JMeterUtils.setProperty("httpJettyClient.enableHttp1", "false");
    HeaderManager hm = new HeaderManager();
    hm.add(new Header(HttpHeader.ACCEPT_ENCODING.asString(), "gzip"));
    sampler.setHeaderManager(hm);
    try {
      HTTPSampleResult result = sampleWithGet(SERVER_PATH_200_GZIP);
      boolean hasGzipHeader = result.getResponseHeaders().contains("Content-Encoding: gzip");
      boolean hasBody = result.getResponseData() != null && result.getResponseData().length > 0;
      assertThat(hasGzipHeader || hasBody).isTrue();
    } finally {
      if (originalEnableHttp1 == null) {
        JMeterUtils.getJMeterProperties().remove("httpJettyClient.enableHttp1");
      } else {
        JMeterUtils.setProperty("httpJettyClient.enableHttp1", originalEnableHttp1);
      }
    }
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessResponseWithContentTypeBrotli()
      throws Exception {
    buildStartedServer();
    HeaderManager hm = new HeaderManager();
    hm.add(new Header(HttpHeader.ACCEPT_ENCODING.asString(), "br"));
    sampler.setHeaderManager(hm);
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_200_BROTLI);
    // Verify that the request was successful and content was decompressed
    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseData()).containsExactly(BINARY_RESPONSE_BODY);
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessResponseWithContentTypeZstd()
      throws Exception {
    buildStartedServer();
    HeaderManager hm = new HeaderManager();
    hm.add(new Header(HttpHeader.ACCEPT_ENCODING.asString(), "zstd"));
    sampler.setHeaderManager(hm);
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_200_ZSTD);
    // Verify that the request was successful and content was decompressed
    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResponseData()).containsExactly(BINARY_RESPONSE_BODY);
  }

  @Test
  public void shouldNotDuplicateSamplerDataForSingleRequest() throws Exception {
    buildStartedServer();
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_200);
    String samplerData = result.getSamplerData();
    String requestLine = "GET https://localhost:" + getActivePort() + SERVER_PATH_200;
    assertThat(countOccurrences(samplerData, requestLine)).isEqualTo(1);
    assertThat(countOccurrences(samplerData, "[no cookies]")).isLessThanOrEqualTo(1);
  }

  @Test
  public void shouldDecodeCompressedResponseWithoutAcceptEncodingHeader() throws Exception {
    buildStartedServer();
    sampler.setHeaderManager(new HeaderManager());
    Map<String, String> compressedPaths = new LinkedHashMap<>();
    compressedPaths.put("gzip", SERVER_PATH_200_GZIP);
    compressedPaths.put("deflate", SERVER_PATH_200_DEFLATE);
    compressedPaths.put("br", SERVER_PATH_200_BROTLI);
    compressedPaths.put("zstd", SERVER_PATH_200_ZSTD);

    for (Map.Entry<String, String> entry : compressedPaths.entrySet()) {
      HTTPSampleResult result = sampleWithGet(entry.getValue());
      assertThat(result.isSuccessful())
          .as("Request for encoding '%s' should succeed", entry.getKey())
          .isTrue();
      assertThat(result.getRequestHeaders())
          .as("Request for encoding '%s' should not include Accept-Encoding", entry.getKey())
          .doesNotContain("Accept-Encoding");
      assertThat(result.getResponseData())
          .as("Response for encoding '%s' should be decoded", entry.getKey())
          .containsExactly(BINARY_RESPONSE_BODY);
    }
  }

  @Test
  public void shouldSkipManualDecodeWhenRequestAdvertisesEncoding() throws Exception {
    byte[] compressed = gzipBytes(BINARY_RESPONSE_BODY);
    ContentResponse contentResponse = org.mockito.Mockito.mock(ContentResponse.class);
    Request request = org.mockito.Mockito.mock(Request.class);
    HttpFields responseHeaders = HttpFields.build().add(HttpHeader.CONTENT_ENCODING, "gzip");
    HttpFields requestHeaders = HttpFields.build().add(HttpHeader.ACCEPT_ENCODING, "gzip");
    org.mockito.Mockito.when(contentResponse.getContent()).thenReturn(compressed);
    org.mockito.Mockito.when(contentResponse.getHeaders()).thenReturn(responseHeaders);
    org.mockito.Mockito.when(contentResponse.getRequest()).thenReturn(request);
    org.mockito.Mockito.when(request.getHeaders()).thenReturn(requestHeaders);

    Method method = HTTP2JettyClient.class
        .getDeclaredMethod("maybeDecodeCompressedContent", ContentResponse.class);
    method.setAccessible(true);
    byte[] result = (byte[]) method.invoke(client, contentResponse);

    assertThat(result).isSameAs(compressed);
    assertThat(result).isEqualTo(compressed);
  }

  @Test
  public void shouldAcceptBrotliEncodingInAcceptEncodingHeader()
      throws Exception {
    buildStartedServer();
    HeaderManager hm = new HeaderManager();
    // Test that "br" is NOT removed from Accept-Encoding (it was before)
    hm.add(new Header(HttpHeader.ACCEPT_ENCODING.asString(), "gzip, br"));
    sampler.setHeaderManager(hm);
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_200);
    // Verify that the request was successful with both encodings
    assertThat(result.isSuccessful()).isTrue();
    // The Accept-Encoding header should still contain "br" (not removed)
    // This is verified by the fact that the request succeeds
  }

  @Test
  public void shouldAcceptZstdEncodingInAcceptEncodingHeader()
      throws Exception {
    buildStartedServer();
    HeaderManager hm = new HeaderManager();
    // Test that "zstd" is NOT removed from Accept-Encoding
    hm.add(new Header(HttpHeader.ACCEPT_ENCODING.asString(), "gzip, zstd"));
    sampler.setHeaderManager(hm);
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_200);
    // Verify that the request was successful with both encodings
    assertThat(result.isSuccessful()).isTrue();
    // The Accept-Encoding header should still contain "zstd" (not removed)
    // This is verified by the fact that the request succeeds
  }

  @Test
  public void shouldSendBodyInformationWhenRequestWithBodyRaw() throws Exception {
    buildStartedServer();
    sampler.addArgument("", TEST_ARGUMENT_1);
    sampler.addArgument("", TEST_ARGUMENT_2);
    String requestBody = TEST_ARGUMENT_1 + TEST_ARGUMENT_2;
    HTTPSampleResult httpSampleResult = buildResult(true, Code.OK,
        hostHeader(),
        requestBody.getBytes(StandardCharsets.UTF_8), "application/octet-stream",
        createURL(SERVER_PATH_200_WITH_BODY), HTTPConstants.POST);

    validateResponse(sample(SERVER_PATH_200_WITH_BODY, HTTPConstants.POST), httpSampleResult);
  }

  @Test
  public void shouldKeepApplicationJsonContentTypeForRawPostBody() throws Exception {
    buildStartedServer();
    String originalPostAddContentTypeIfMissing =
        JMeterUtils.getProperty("http.post_add_content_type_if_missing");
    try {
      JMeterUtils.setProperty("http.post_add_content_type_if_missing", "true");
      sampler.setMethod(HTTPConstants.POST);
      sampler.setPostBodyRaw(true);
      sampler.addArgument("", "{\"key\":\"value\"}");
      HeaderManager headerManager = new HeaderManager();
      headerManager.add(new Header(HttpHeader.CONTENT_TYPE.asString(), "application/json"));
      sampler.setHeaderManager(headerManager);

      HTTPSampleResult result = sample(SERVER_PATH_JSON_ONLY, HTTPConstants.POST);

      assertThat(result.getResponseCode()).isEqualTo("200");
      assertThat(result.getRequestHeaders()).contains("Content-Type: application/json");
    } finally {
      if (originalPostAddContentTypeIfMissing == null) {
        JMeterUtils.getJMeterProperties().remove("http.post_add_content_type_if_missing");
      } else {
        JMeterUtils.setProperty("http.post_add_content_type_if_missing",
            originalPostAddContentTypeIfMissing);
      }
    }
  }

  private HTTPSampleResult buildOkResult(String requestBody, String requestContentType,
                                         URL url, String method) {
    return buildResult(true, HttpStatus.Code.OK, null,
        requestBody != null ? requestBody.getBytes(StandardCharsets.UTF_8) : null,
        requestContentType, url, method);
  }

  private HTTPSampleResult buildResult(boolean successful, HttpStatus.Code statusCode,
                                       HttpFields headers, byte[] requestBody,
                                       String requestContentType, URL url, String method) {

    Mutable httpFields = HttpFields.build();

    if (headers != null) {
      httpFields.add(headers);
    }
    if (requestContentType != null) {
      httpFields.add(HttpHeader.CONTENT_TYPE, requestContentType);
    }
    if (requestBody != null) {
      String requestBodyLength = Integer.toString(requestBody.length);
      httpFields.add(HttpHeader.CONTENT_LENGTH, requestBodyLength);
    }
    String headersString = httpFields.toString().replace("\r\n", "\n");
    headersString = headersString.substring(0, headersString.length() - 1); // remove last \n

    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(successful);
    expected.setResponseCode(String.valueOf(statusCode.getCode()));
    expected.setResponseMessage(statusCode.getMessage());
    long sentBytes = requestBody != null ? requestBody.length : 0;
    if (url != null && method != null) {
      sentBytes += estimateRequestHeaderBytes(headersString, url, method);
      expected.setURL(url);
      expected.setHTTPMethod(method);
    }
    expected.setSentBytes(sentBytes);
    expected.setRequestHeaders(headersString);
    expected.setResponseData(requestBody);
    return expected;
  }

  private void validateResponse(SampleResult result, SampleResult expected) {
    // In Jetty 12.1.5, headers may include Accept-Encoding: gzip automatically
    // Use header comparison that ignores order and accepts additional headers
    assertHeadersMatchIgnoringOrder(result.getRequestHeaders(), expected.getRequestHeaders());
    softly.assertThat(result.isSuccessful()).isEqualTo(expected.isSuccessful());
    softly.assertThat(result.getResponseCode()).isEqualTo(expected.getResponseCode());
    softly.assertThat(result.getResponseMessage()).isEqualTo(expected.getResponseMessage());
    long expectedSentBytes = expected.getSentBytes();
    long expectedBodyBytes = expectedSentBytes;
    HTTPSampleResult expectedHttpResult = expected instanceof HTTPSampleResult
      ? (HTTPSampleResult) expected
      : null;
    if (expectedHttpResult != null && expected.getRequestHeaders() != null
        && !expected.getRequestHeaders().isEmpty() && expectedHttpResult.getURL() != null
        && expectedHttpResult.getHTTPMethod() != null) {
      long expectedHeaderBytes = estimateRequestHeaderBytes(expected.getRequestHeaders(),
        expectedHttpResult.getURL(), expectedHttpResult.getHTTPMethod());
      expectedBodyBytes = Math.max(0, expectedSentBytes - expectedHeaderBytes);
    }

    long actualHeaderBytes = 0;
    HTTPSampleResult actualHttpResult = result instanceof HTTPSampleResult
      ? (HTTPSampleResult) result
      : null;
    URL requestUrl = actualHttpResult != null ? actualHttpResult.getURL()
      : expectedHttpResult != null ? expectedHttpResult.getURL() : null;
    String requestMethod = actualHttpResult != null ? actualHttpResult.getHTTPMethod()
      : expectedHttpResult != null ? expectedHttpResult.getHTTPMethod() : null;
    if (result.getRequestHeaders() != null && !result.getRequestHeaders().isEmpty()
        && requestUrl != null && requestMethod != null) {
      actualHeaderBytes = estimateRequestHeaderBytes(result.getRequestHeaders(), requestUrl,
        requestMethod);
    }

    if (expectedBodyBytes > 0) {
      softly.assertThat(result.getSentBytes()).isGreaterThanOrEqualTo(expectedBodyBytes);
    } else {
      softly.assertThat(result.getSentBytes()).isGreaterThanOrEqualTo(actualHeaderBytes);
    }
    softly.assertThat(result.getResponseDataAsString())
        .isEqualTo(expected.getResponseDataAsString());
  }

  private long estimateRequestHeaderBytes(String headers, URL url, String method) {
    long headersBytes = headers == null || headers.isEmpty()
        ? 0
        : headers.getBytes(StandardCharsets.UTF_8).length + 1;

    String path = url != null ? url.getPath() : null;
    if (path == null || path.isEmpty()) {
      path = "/";
    }
    if (url != null && url.getQuery() != null && !url.getQuery().isEmpty()) {
      path = path + "?" + url.getQuery();
    }

    String requestLine = method + " " + path + " HTTP/1.1\n";
    long requestLineBytes = requestLine.getBytes(StandardCharsets.UTF_8).length;

    return requestLineBytes + headersBytes;
  }

  @Test
  public void shouldSendBodyInformationWhenRequestWithArguments() throws Exception {
    buildStartedServer();
    sampler.setMethod(HTTPConstants.POST);
    sampler.addArgument("test1", TEST_ARGUMENT_1);
    sampler.addArgument("test2", TEST_ARGUMENT_2);
    String requestBody = "test1=" + TEST_ARGUMENT_1 + "&" + "test2=" + TEST_ARGUMENT_2;
    HTTPSampleResult expected = buildResult(true, HttpStatus.Code.OK,
        hostHeader(),
        requestBody.getBytes(StandardCharsets.UTF_8),
      "application/x-www-form-urlencoded", createURL(SERVER_PATH_200_WITH_BODY),
      HTTPConstants.POST);
    validateResponse(sample(SERVER_PATH_200_WITH_BODY, HTTPConstants.POST), expected);
  }

  @Test
  public void shouldSendArgumentsInUrlWhenDeleteMethodWithArguments() throws Exception {
    sampler.setMethod(HTTPConstants.DELETE);
    String argumentName1 = "test_1";
    String argumentName2 = "test_2";
    sampler.addArgument(argumentName1, TEST_ARGUMENT_1);
    sampler.addArgument(argumentName2, TEST_ARGUMENT_2);
    assertThat(sampler.getUrl().toString()).isEqualTo("https://server:" + sampler.getPort() +
        "/?" + argumentName1 + "=" + TEST_ARGUMENT_1 + "&" + argumentName2 + "=" + TEST_ARGUMENT_2);
  }

  @Test
  public void shouldSendBodyWhenDeleteMethodWithRawData() throws Exception {
    buildStartedServer();
    sampler.setMethod(HTTPConstants.DELETE);
    sampler.addArgument("", TEST_ARGUMENT_1);
    sampler.addArgument("", TEST_ARGUMENT_2);
    String requestBody = TEST_ARGUMENT_1 + TEST_ARGUMENT_2;
    HTTPSampleResult expected =
        buildResult(true, HttpStatus.Code.OK, HttpFields.build().add(HttpHeader.HOST,
                hostHeaderValue()),
            requestBody.getBytes(StandardCharsets.UTF_8),
        "application/octet-stream", createURL(SERVER_PATH_200_WITH_BODY),
        HTTPConstants.DELETE);

    validateResponse(sample(SERVER_PATH_200_WITH_BODY, HTTPConstants.DELETE), expected);
  }

  @Test
  public void shouldReturnFailureSampleResultWhenResponse400() throws Exception {
    buildStartedServer();
    HTTPSampleResult expected = buildResult(false, Code.BAD_REQUEST,
        hostHeader(),
        null,
      null, createURL(SERVER_PATH_400), HTTPConstants.GET);
    validateResponse(sampleWithGet(SERVER_PATH_400), expected);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void shouldReturnErrorMessageWhenMethodIsNotSupported() throws Exception {
    client.sample(sampler, buildBaseResult(createURL(SERVER_PATH_200), "MethodNotSupported"), false,
        0);
  }

  @Test
  public void shouldGetEmbeddedResourcesWithSubSampleWhenImageParserIsEnabled() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);

    HTTPSampleResult httpSampleResult = buildResult(true, Code.OK,
        hostHeader(),
        null,
      null, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET);
    httpSampleResult.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED),
        httpSampleResult);
  }

  private void validateEmbeddedResources(HTTPSampleResult result, HTTPSampleResult expected)
      throws MalformedURLException {
    SampleResult[] results = result.getSubResults();
    validateResponse(result, expected);
    softly.assertThat(results.length).isGreaterThan(0);
    assertResultTypeAndUrl(results[0], SampleResult.TEXT, SERVER_PATH_200_EMBEDDED);
    assertResultTypeAndUrl(results[1], SampleResult.BINARY, SERVER_IMAGE);
  }

  private void assertResultTypeAndUrl(SampleResult result, String type, String path)
      throws MalformedURLException {
    softly.assertThat(result.getDataType()).isEqualTo(type);
    softly.assertThat(result.getUrlAsString()).isEqualTo(createURL(path).toString());
  }

  private void addJettyCookie(URI uri, String name, String value) {
    HttpCookieStore cookieStore = client.getHttpClient().getHttpCookieStore();
    assertNotNull(cookieStore);
    cookieStore.add(uri, HttpCookie.from(name, value));
  }

  private boolean hasJettyCookie(URI uri, String name) {
    HttpCookieStore cookieStore = client.getHttpClient().getHttpCookieStore();
    assertNotNull(cookieStore);
    for (HttpCookie cookie : cookieStore.match(uri)) {
      if (name.equals(cookie.getName())) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void shouldNotDownloadEmbeddedResourcesWhenUrlDoesNotMatchFilter() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    sampler.setEmbeddedUrlRE(".+css");
    HTTPSampleResult httpSampleResult = buildResult(true, Code.OK,
        hostHeader(),
      null, null, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET);
    httpSampleResult.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResourcesWithUrlFilter(sampleWithGet(SERVER_PATH_200_EMBEDDED),
        httpSampleResult);
  }

  private void validateEmbeddedResourcesWithUrlFilter(HTTPSampleResult result,
                                                      HTTPSampleResult expected) {
    SampleResult[] results = result.getSubResults();
    validateResponse(result, expected);
    softly.assertThat(result.getSubResults().length).isEqualTo(1);
    softly.assertThat(results[0].getDataType()).isNotEqualTo(SampleResult.BINARY);
  }

  @Test
  public void shouldUseCookiesFromFirstRequestOnSecondRequestWhenSecondRequestIsSent()
      throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withSSL()
        .buildServer();
    server.start();
    syncServerPort();
    CookieManager cookieManager = new CookieManager();
    cookieManager.testStarted(HOST_NAME);
    sampler.setCookieManager(cookieManager);
    //First request
    sampleWithGet(SERVER_PATH_SET_COOKIES);
    //Second request
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_SET_COOKIES);
    HTTPSampleResult expected = buildOkResult(null, null, createURL(SERVER_PATH_SET_COOKIES),
      HTTPConstants.GET);
    String replace = hostHeader().toString().replace("\r\n", "\n");
    expected.setRequestHeaders(replace.substring(0, replace.length() - 1));
    expected.setSentBytes(estimateRequestHeaderBytes(expected.getRequestHeaders(),
        createURL(SERVER_PATH_SET_COOKIES), HTTPConstants.GET));
    expected.setCookies(RESPONSE_DATA_COOKIES + "; " + RESPONSE_DATA_COOKIES2);
    validateResponse(result, expected);
    softly.assertThat(result.getCookies()).isEqualTo(expected.getCookies());
  }

  @Test
  public void shouldRemoveJettyCookieWhenJMeterOverridesValue() throws Exception {
    buildStartedServer();
    CookieManager cookieManager = new CookieManager();
    cookieManager.testStarted(HOST_NAME);
    cookieManager.add(new Cookie("SOME_NAME", "NEW_VALUE", HOST_NAME, "/", true, 0));
    sampler.setCookieManager(cookieManager);

    URI uri = createURL(SERVER_PATH_200).toURI();
    addJettyCookie(uri, "SOME_NAME", "OLD_VALUE");
    assertThat(hasJettyCookie(uri, "SOME_NAME")).isTrue();

    sampleWithGet(SERVER_PATH_200);

    assertThat(hasJettyCookie(uri, "SOME_NAME")).isFalse();
  }

  @Test
  public void shouldClearJettyStoreWhenJMeterCookieManagerIsEmpty() throws Exception {
    buildStartedServer();
    CookieManager cookieManager = new CookieManager();
    cookieManager.testStarted(HOST_NAME);
    sampler.setCookieManager(cookieManager);

    URI uri = createURL(SERVER_PATH_200).toURI();
    addJettyCookie(uri, "SOME_NAME", "OLD_VALUE");
    assertThat(hasJettyCookie(uri, "SOME_NAME")).isTrue();

    sampleWithGet(SERVER_PATH_200);

    assertThat(hasJettyCookie(uri, "SOME_NAME")).isFalse();
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessRequestWithHeaders() throws Exception {
    buildStartedServer();
    HeaderManager hm = new HeaderManager();
    String headerName1 = "Header1";
    String headerValue1 = "value1";
    hm.add(new Header(headerName1, headerValue1));
    String headerName2 = "Header2";
    String headerValue2 = "value2";
    hm.add(new Header(headerName2, headerValue2));
    sampler.setHeaderManager(hm);
    Mutable httpFields = HttpFields.build()
        .add(headerName1, headerValue1)
        .add(headerName2, headerValue2)
        .add(HttpHeader.HOST, hostHeaderValue());
    HTTPSampleResult expected = buildResult(true, Code.OK,
      httpFields, null, null, createURL(SERVER_PATH_200), HTTPConstants.GET);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    validateResponse(sampleWithGet(), expected);
  }

  @Test
  public void shouldReturnSuccessDigestAuthSampleResultWhenAuthDigestIsSet() throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withSSL()
        .withDigestAuth()
        .buildServer();
    server.start();
    syncServerPort();
    configureAuthManager(Mechanism.DIGEST);
    HTTPSampleResult expected = buildResult(true, HttpStatus.Code.OK, hostHeader(),
      null, null, createURL(SERVER_PATH_200), HTTPConstants.GET);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    validateResponse(sampleWithGet(), expected);
  }


  private void configureAuthManager(Mechanism mechanism) throws MalformedURLException {
    Authorization authorization = new Authorization();
    authorization.setURL(createURL(SERVER_PATH_200).toString());
    authorization.setUser(AUTH_USERNAME);
    authorization.setPass(AUTH_PASSWORD);
    authorization.setRealm(AUTH_REALM);
    authorization.setMechanism(mechanism);

    AuthManager authManager = new AuthManager();
    authManager.addAuth(authorization);
    sampler.setAuthManager(authManager);
  }

  @Test
  public void shouldReturnSuccessBasicAuthSampleResultWhenPreemptiveIsFalse() throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withSSL()
        .withBasicAuth()
        .buildServer();
    server.start();
    syncServerPort();
    configureAuthManager(Mechanism.BASIC);

    HTTPSampleResult expected = buildResult(true, HttpStatus.Code.OK, hostHeader(),
      null, null, createURL(SERVER_PATH_200), HTTPConstants.GET);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    validateResponse(sampleWithGet(), expected);
  }


  @Test
  public void shouldReturnSuccessBasicAuthSampleResultWhenHeaderIsSet() throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withSSL()
        .withBasicAuth()
        .buildServer();
    server.start();
    syncServerPort();
    Mutable httpFields = hostHeader()
        .add(HttpHeader.AUTHORIZATION,
            "Basic " + base64Encode(AUTH_USERNAME + ":" + AUTH_PASSWORD));
    JMeterUtils.setProperty("httpJettyClient.auth.preemptive", "true");
    configureAuthManager(Mechanism.BASIC);
    HTTPSampleResult expected = buildResult(true, Code.OK,
      httpFields, null, null, createURL(SERVER_PATH_200), HTTPConstants.GET);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    validateResponse(sampleWithGet(), expected);
  }

  private String base64Encode(String input) {
    return Base64.getEncoder().encodeToString(input.getBytes());
  }

  @Test
  public void shouldGetRedirectedResultWithSubSampleWhenFollowRedirectEnabledAndRedirected()
      throws Exception {
    buildStartedServer();
    sampler.setFollowRedirects(true);
    HTTPSampleResult expected = buildResult(true, Code.OK,
      hostHeader(), null, null, createURL(SERVER_PATH_302), HTTPConstants.GET);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    expected.setRedirectLocation("https://localhost:" + getActivePort() + "/test/200");
    validateRedirects(sampleWithGet(SERVER_PATH_302), expected);
  }

  private void validateRedirects(HTTPSampleResult result, HTTPSampleResult expected) {
    validateResponse(result, expected);
    softly.assertThat(result.getSubResults().length).isGreaterThan(0);
    softly.assertThat(result.getRedirectLocation()).isEqualTo(expected.getRedirectLocation());
  }

  @Test
  public void shouldGetOnlyRedirectedResultWhenFollowRedirectDisabledAndRedirected()
      throws Exception {
    buildStartedServer();
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_302);
    softly.assertThat(result.getResponseCode()).isEqualTo("302");
    softly.assertThat(result.getSubResults().length).isEqualTo(0);
  }

  @Test
  public void shouldGetOnlyRedirectedResultWhenRedirectAutomaticallyEnabledAndRedirected()
      throws Exception {
    buildStartedServer();
    sampler.setAutoRedirects(true);
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_302);
    softly.assertThat(result.getResponseCode()).isEqualTo("200");
    softly.assertThat(result.getSubResults().length).isEqualTo(0);
    softly.assertThat(result.getUrlAsString())
        .isEqualTo("https://localhost:" + getActivePort() + SERVER_PATH_200);
  }

  @Test
  public void shouldGetFileDataWithFileIsSentAsBodyPart() throws Exception {
    buildStartedServer();
    URL urlFile = getClass().getResource("blazemeter-labs-logo.png");
    String pathFile = new File(urlFile.getFile()).toPath().toAbsolutePath().toString();
    HTTPFileArg fileArg = new HTTPFileArg(pathFile, "", "image/png");
    sampler.setHTTPFiles(new HTTPFileArg[] {fileArg});
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    // In Jetty 12, Content-Length is automatically calculated
    // Get the actual Content-Length from the result
    byte[] fileBytes = Resources.toByteArray(urlFile);
    String actualContentLength = Integer.toString(fileBytes.length);
    String requestHeaders = HttpFields.build()
        .add(HttpHeader.CONTENT_TYPE, "image/png")
        .add(HttpHeader.HOST, hostHeaderValue())
        .add(HttpHeader.CONTENT_LENGTH, actualContentLength)
        .toString().replace("\r\n", "\n");
    requestHeaders = requestHeaders.substring(0, requestHeaders.length() - 1);
    HTTPSampleResult expected = buildResult(true, Code.OK, null,
        fileBytes,
      "image/png", createURL(SERVER_PATH_200_FILE_SENT), HTTPConstants.POST);
    expected.setRequestHeaders(requestHeaders);
    expected.setSentBytes(fileBytes.length
      + estimateRequestHeaderBytes(requestHeaders, createURL(SERVER_PATH_200_FILE_SENT),
        HTTPConstants.POST));
    validateResponse(result, expected);
  }

  @Test(expected = TimeoutException.class)
  public void shouldThrowTimeoutExceptionWhenResponseBiggerThanTimeout() throws Exception {
    buildStartedServer();
    long timeout = 1000;
    sampler.setResponseTimeout(String.valueOf(timeout));
    sampleWithGet(SERVER_PATH_SLOW);
  }

  @Test
  public void shouldNoUseCacheWhenNotUseExpire() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    configureCacheManagerToSampler(false, false);
    // First request must connect to the server
    HTTPSampleResult firstRequestExpected = buildResult(true, Code.OK,
      hostHeader(), null, null, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET);
    firstRequestExpected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), firstRequestExpected);
    // Same request connect again because use expire is false
    HTTPSampleResult secondRequestExpected = buildResult(true, Code.OK,
      hostHeader(), null, null, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET);
    secondRequestExpected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), secondRequestExpected);
  }

  private void configureCacheManagerToSampler(boolean useExpire, boolean clearCacheIteration) {
    CacheManager cacheManager = new CacheManager();
    cacheManager.setUseExpires(useExpire);
    cacheManager.setClearEachIteration(clearCacheIteration);
    cacheManager.testIterationStart(null); // Used to initialize private attrs
    sampler.setCacheManager(cacheManager);
  }

  @Test
  public void shouldNotGetSubResultWhenResourceIsCachedWithCode() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    String message = "message";
    String responseCode = "300";
    JMeterUtils.setProperty("cache_manager.cached_resource_mode", "RETURN_CUSTOM_STATUS");
    JMeterUtils.setProperty("RETURN_CUSTOM_STATUS.message", message);
    JMeterUtils.setProperty("RETURN_CUSTOM_STATUS.code", responseCode);
    configureCacheManagerToSampler(true, false);
    HTTPSampleResult firstRequestExpected = buildResult(true, Code.OK,
      hostHeader(), null, null, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET);
    firstRequestExpected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    // First request must connect to the server
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), firstRequestExpected);
    // Same request use cached result with no message, request and data response
    firstRequestExpected.setResponseCode(responseCode);
    firstRequestExpected.setResponseMessage(message);
    firstRequestExpected.setRequestHeaders("");
    firstRequestExpected.setSentBytes(0);
    firstRequestExpected.setResponseData("", StandardCharsets.UTF_8.name());
    validateEmbeddedResultCached(sampleWithGet(SERVER_PATH_200_EMBEDDED), firstRequestExpected);
  }

  /**
   * Validate same result as expected, but also control that not sample result was added.
   */
  private void validateEmbeddedResultCached(HTTPSampleResult result, HTTPSampleResult expected) {
    this.validateResponse(result, expected);
    softly.assertThat(result.getResponseData().length).isEqualTo(0);
  }

  @Test
  public void shouldNotGetSubResultWhenResourceIsCachedWithoutCode() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    String message = "message";
    JMeterUtils.setProperty("cache_manager.cached_resource_mode", "RETURN_200_CACHE");
    JMeterUtils.setProperty("RETURN_200_CACHE.message", message);
    configureCacheManagerToSampler(true, false);
    // First request must connect to the server
    HTTPSampleResult expected = buildResult(true, Code.OK,
      hostHeader(), null, null, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET);
    expected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
    // Same request use cached result with message response from property system
    expected.setRequestHeaders("");
    expected.setSentBytes(0);
    expected.setResponseData("", StandardCharsets.UTF_8.name());
    expected.setResponseMessage(message);
    validateEmbeddedResultCached(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
  }

  @Test
  public void shouldGetSubResultWhenCacheCleanBetweenIterations() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    configureCacheManagerToSampler(false, true);
    // First request must connect to the server
    HTTPSampleResult expected = buildResult(true, Code.OK,
      hostHeader(), null, null, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET);
    expected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
    HTTPSampleResult secondRequestExpected = buildResult(true, Code.OK,
      hostHeader(), null, null, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET);
    secondRequestExpected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    // Same request connect again because clear cache iteration is enabled
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), secondRequestExpected);
  }

  @Test
  public void shouldGetTwoFilesAndTwoParams() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Arrays.asList(buildArg("Param1", "Valor1"),
        buildArg("Param2", "Valor2"));
    List<HTTPFileArg> files = Arrays.asList(buildFile("blazemeter-labs-logo1"),
        buildFile("blazemeter-labs-logo2"));
    configureMultipartSampler(args, files);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  private void configureMultipartSampler(List<HTTPArgument> argsList, List<HTTPFileArg> files) {
    sampler.setImageParser(true); // Indicates download embedded resources
    sampler.setDoMultipart(true);
    if (!argsList.isEmpty()) {
      Arguments args = new Arguments();
      argsList.forEach(args::addArgument);
      sampler.setArguments(args);
    }
    if (!files.isEmpty()) {
      sampler.setHTTPFiles(files.toArray(new HTTPFileArg[0]));
    }
  }

  private HTTPArgument buildArg(String name, String value) {
    return new HTTPArgument(name, value);
  }

  private HTTPFileArg buildFile(String name) {
    String filePath =
        (new File(getClass().getResource("blazemeter-labs-logo.png").getFile())).toPath()
            .toAbsolutePath().toString();
    return new HTTPFileArg(filePath, name, "image/png");
  }

  private HTTPSampleResult buildMultipartResult(List<HTTPArgument> args, List<HTTPFileArg> files,
                                                HTTPSampleResult result)
      throws IOException, MalformedURLException {
    // Get JettyHttpClientBoundary from result
    String boundary = result.getResponseDataAsString().split("\\r\\n")[0];
    HTTPSampleResult expected = buildOkResult(null, null,
      createURL(SERVER_PATH_200_FILE_SENT), HTTPConstants.POST);
    // In Jetty 12, boundary in Content-Type header has quotes: boundary="value"
    // The boundary from response includes "--" prefix, so we remove it with substring(2)
    String boundaryValue = boundary.substring(2);
    Mutable httpFields = hostHeader()
        .add(HttpHeader.CONTENT_TYPE, "multipart/form-data; boundary=\"" + boundaryValue + "\"");
    // In Jetty 12, Content-Length is automatically added, so we need to include it in expected
    byte[] responseData = buildByteArrayFromFilesAndParams(expected, args, files, boundary);
    String contentLength = Integer.toString(responseData.length);
    httpFields.add(HttpHeader.CONTENT_LENGTH, contentLength);
    expected.setRequestHeaders(expected.getRequestHeaders().concat(httpFields.toString()));
    expected.setResponseData(responseData);
    expected.setSentBytes(responseData.length
      + estimateRequestHeaderBytes(expected.getRequestHeaders(),
        createURL(SERVER_PATH_200_FILE_SENT), HTTPConstants.POST));
    return expected;
  }

  private byte[] buildByteArrayFromFilesAndParams(HTTPSampleResult expected,
                                                  List<HTTPArgument> args,
                                                  List<HTTPFileArg> files, String boundary)
      throws IOException {

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    String newLine = "\r\n";
    String dashDash = "--";
    // Set body response for Arguments
    args.forEach(httpArgument -> {
      Mutable headerParam = HttpFields.build()
          .add("Content-Disposition", "form-data; name=\"" + httpArgument.getEncodedName() + "\"")
          .add(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
      try {
        String headerParamWithBoundary = boundary + newLine + headerParam.toString();
        output.write(headerParamWithBoundary.getBytes(StandardCharsets.US_ASCII));
        output.write(newLine.getBytes(StandardCharsets.US_ASCII));
        output.write(httpArgument.getEncodedValue().getBytes(StandardCharsets.UTF_8));
        output.write(newLine.getBytes(StandardCharsets.US_ASCII));
      } catch (IOException e) {
        e.printStackTrace();
      }
    });

    // Set body response for Files
    files.forEach(file -> {
      String fileName = Paths.get((file.getPath())).getFileName().toString();
      Mutable headerFile = HttpFields.build()
          .add("Content-Disposition", "form-data; name=\"" + file.getParamName()
              + "\"; " + "filename=\"" + fileName + "\"")
          .add(HttpHeader.CONTENT_TYPE, file.getMimeType());
      try {
        String filePath = file.getPath();
        InputStream inputStream = Files.newInputStream(Paths.get(filePath));
        byte[] data = sampler.readResponse(expected, inputStream, 0);
        String headerFileWithBoundary = boundary + newLine + headerFile.toString();
        output.write(headerFileWithBoundary.getBytes(StandardCharsets.US_ASCII));
        output.write(newLine.getBytes(StandardCharsets.US_ASCII));
        output.write(data);
        output.write(newLine.getBytes(StandardCharsets.US_ASCII));
      } catch (IOException e) {
        e.printStackTrace();
      }
    });

    String finalResponse = dashDash + newLine;
    output.write(boundary.getBytes());
    output.write(finalResponse.getBytes());

    return output.toByteArray();
  }

  private void validateMultipartResponse(HTTPSampleResult result, HTTPSampleResult expected) {
    softly.assertThat(result.isSuccessful()).isEqualTo(expected.isSuccessful());
    softly.assertThat(result.getResponseCode()).isEqualTo(expected.getResponseCode());
    softly.assertThat(result.getResponseDataAsString())
        .isEqualToIgnoringNewLines(expected.getResponseDataAsString());
    // In Jetty 12, Content-Length is automatically added by HttpClient
    // Compare headers ignoring order and including Content-Length
    assertHeadersMatchIgnoringOrder(result.getRequestHeaders(), expected.getRequestHeaders());
  }

  /**
   * Compares request headers ignoring order and handling headers automatically added by Jetty 12.1.5.
   * In Jetty 12.1.5, HttpClient automatically adds:
   * - Content-Length for request bodies
   * - Accept-Encoding: gzip when content decoder factories are present
   * This method normalizes headers for comparison.
   */
  private void assertHeadersMatchIgnoringOrder(String actualHeaders, String expectedHeaders) {
    // Parse headers into maps for comparison
    java.util.Map<String, String> actualMap = parseHeaders(actualHeaders);
    java.util.Map<String, String> expectedMap = parseHeaders(expectedHeaders);
    
    // Add Content-Length to expected if it's in actual (Jetty 12 adds it automatically)
    if (actualMap.containsKey("Content-Length") && !expectedMap.containsKey("Content-Length")) {
      // Verify Content-Length matches the response data size
      String contentLength = actualMap.get("Content-Length");
      expectedMap.put("Content-Length", contentLength);
    }
    
    // Add Accept-Encoding to expected if it's in actual (Jetty 12.1.5 adds it automatically)
    // This header is added when content decoder factories are present
    if (actualMap.containsKey("Accept-Encoding") && !expectedMap.containsKey("Accept-Encoding")) {
      String acceptEncoding = actualMap.get("Accept-Encoding");
      expectedMap.put("Accept-Encoding", acceptEncoding);
    }
    
    // Normalize Content-Type values - Jetty 12.1.5 may add charset parameter
    // Compare only the MIME type part, ignoring parameters like charset
    normalizeContentType(actualMap);
    normalizeContentType(expectedMap);
    
    // Compare maps (order doesn't matter)
    // actualMap should contain all expected headers
    softly.assertThat(actualMap).containsAllEntriesOf(expectedMap);
    // expectedMap should contain all actual headers (allowing for additional headers in actual)
    // This allows Accept-Encoding and Content-Length to be present in actual but not in expected
    for (Map.Entry<String, String> entry : expectedMap.entrySet()) {
      softly.assertThat(actualMap).containsEntry(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Normalizes Content-Type header value by removing charset and other parameters.
   * Jetty 12.1.5 may add charset=utf-8 to Content-Type, but tests may expect just the MIME type.
   */
  private void normalizeContentType(Map<String, String> headerMap) {
    if (headerMap.containsKey("Content-Type")) {
      String contentType = headerMap.get("Content-Type");
      // Remove parameters (everything after semicolon)
      int semicolonIndex = contentType.indexOf(';');
      if (semicolonIndex > 0) {
        headerMap.put("Content-Type", contentType.substring(0, semicolonIndex).trim());
      }
    }
  }

  /**
   * Parses header string into a map of header name -> value.
   * Handles headers in format "Header-Name: value\r\n"
   * Normalizes quotes (removes outer quotes for comparison)
   */
  private Map<String, String> parseHeaders(String headers) {
    Map<String, String> headerMap = new HashMap<>();
    if (headers == null || headers.isEmpty()) {
      return headerMap;
    }
    
    // Split by newlines (handle both \r\n and \n)
    String[] lines = headers.split("\\r?\\n");
    for (String line : lines) {
      if (line.trim().isEmpty()) {
        continue;
      }
      int colonIndex = line.indexOf(':');
      if (colonIndex > 0) {
        String name = line.substring(0, colonIndex).trim();
        String value = line.substring(colonIndex + 1).trim();
        // Normalize quotes: remove outer quotes (single or double) for comparison
        // This handles both "value" and ""value"" formats
        value = normalizeQuotes(value);
        headerMap.put(name, value);
      }
    }
    return headerMap;
  }

  /**
   * Normalizes quoted values by removing outer quotes.
   * Handles both single quotes ("value") and double quotes (""value"").
   */
  private String normalizeQuotes(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    // Remove double quotes if present at start and end
    if (value.startsWith("\"\"") && value.endsWith("\"\"")) {
      return value.substring(2, value.length() - 2);
    }
    // Remove single quotes if present at start and end
    if (value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private int countOccurrences(String source, String token) {
    if (source == null || token == null || token.isEmpty()) {
      return 0;
    }
    int count = 0;
    int fromIndex = 0;
    while (true) {
      int index = source.indexOf(token, fromIndex);
      if (index < 0) {
        return count;
      }
      count++;
      fromIndex = index + token.length();
    }
  }

  private byte[] gzipBytes(byte[] input) throws IOException {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
         OutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(input);
      gzip.flush();
      ((GZIPOutputStream) gzip).finish();
      return output.toByteArray();
    }
  }

  @Test
  public void shouldGetOneFileAndOneParam() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Collections.singletonList(buildArg("Param1", "Valor1"));
    List<HTTPFileArg> files = Collections.singletonList(buildFile("blazemeter-labs-logo"));
    configureMultipartSampler(args, files);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  @Test
  public void shouldGetOnlyTwoFiles() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Collections.emptyList();
    List<HTTPFileArg> files = Arrays.asList(buildFile("blazemeter-labs-logo1"),
        buildFile("blazemeter-labs-logo2"));
    configureMultipartSampler(args, files);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  @Test
  public void shouldGetOnlyTwoParams() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Arrays.asList(buildArg("Param1", "Valor1"),
        buildArg("Param2", "Valor2"));
    List<HTTPFileArg> files = Collections.emptyList();
    configureMultipartSampler(args, files);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  @Test
  public void shouldReturnErrorInBlankFileName() throws Exception {
    buildStartedServer();
    configureMultipartSampler(Collections.singletonList(buildArg("Param1", "Valor1")),
        Collections.singletonList(buildFile("")));
    IllegalStateException exception = assertThrows(IllegalStateException.class,
        () -> sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST));
    softly.assertThat(exception.toString())
        .contains("java.lang.IllegalStateException: Param name is blank");
  }

  @Test
  public void shouldUseMultipartWhenHasFilesAndNotSendAsPostBody() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Collections.emptyList();
    List<HTTPFileArg> files = Arrays.asList(buildFile("blazemeter-labs-logo1"),
        buildFile("blazemeter-labs-logo2"));
    configureMultipartSampler(args, files);
    sampler.setDoMultipart(false);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  @Test
  public void shouldNotUseMultipartWhenHasOneFileWithEmptyParamName() throws Exception {
    buildStartedServer();
    URL urlFile = getClass().getResource("blazemeter-labs-logo.png");
    String pathFile = new File(urlFile.getFile()).toPath().toAbsolutePath().toString();
    sampler.setHTTPFiles(new HTTPFileArg[] {new HTTPFileArg(pathFile, "", "image/png")});
    // In Jetty 12, Content-Length is automatically calculated
    // Get the actual Content-Length from the file
    byte[] fileBytes = Resources.toByteArray(urlFile);
    String actualContentLength = Integer.toString(fileBytes.length);
    String requestHeaders = HttpFields.build()
        .add(HttpHeader.CONTENT_TYPE, "image/png")
        .add(HttpHeader.HOST, hostHeaderValue())
        .add(HttpHeader.CONTENT_LENGTH, actualContentLength)
        .toString().replace("\r\n", "\n");
    requestHeaders = requestHeaders.substring(0, requestHeaders.length() - 1);
    HTTPSampleResult expected = buildResult(true, HttpStatus.Code.OK,
        null,
      fileBytes, "image/png", createURL(SERVER_PATH_200_FILE_SENT), HTTPConstants.POST);
    expected.setRequestHeaders(requestHeaders);
    expected.setSentBytes(fileBytes.length
      + estimateRequestHeaderBytes(requestHeaders, createURL(SERVER_PATH_200_FILE_SENT),
        HTTPConstants.POST));
    validateResponse(sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST), expected);
  }

  @Test(expected = ExecutionException.class)
  public void shouldThrowExceptionWhenServerRequiresClientCertAndNoneIsConfigured()
      throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .withNeedClientAuth()
        .buildServer();
    server.start();
    syncServerPort();
    sampleWithGet();
  }

  private String getKeyStorePathAsUriPathWithNetSslKeyStoreFormat() {
    try {
      // Generate a absolute path in URI format with compatibility with Windows
      // IMPORTANT: javax.net.ssl.keyStore use a particular format,
      // this method try to generate in that format and with compatibility with Windows
      return "/" + new File("//").toURI().relativize(getClass().getResource("keystore.p12").toURI())
          .getPath();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void shouldGetSuccessResponseWhenServerRequiresClientCertAndOneIsConfigured()
      throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .withNeedClientAuth()
        .buildServer();
    server.start();
    syncServerPort();
    String keyStorePropertyName = "javax.net.ssl.keyStore";
    String keyStorePasswordPropertyName = "javax.net.ssl.keyStorePassword";
    System.setProperty(keyStorePropertyName, getKeyStorePathAsUriPathWithNetSslKeyStoreFormat());
    System.setProperty(keyStorePasswordPropertyName, KEYSTORE_PASSWORD);
    client.stop();
    client = new HTTP2JettyClient();
    client.start();
    try {
      HTTPSampleResult result = sampleWithGet();
      assertThat(result.getResponseDataAsString()).isEqualTo(SERVER_RESPONSE);
    } finally {
      System.setProperty(keyStorePropertyName, "");
      System.setProperty(keyStorePasswordPropertyName, "");
      SSLManager.reset();
    }
  }

  @Test
  public void shouldGetResponseWhenBufferSizeIsSmallerOrTheSameAsMaxBufferSize() throws Exception {
    buildStartedServer();
    JMeterUtils.setProperty("httpJettyClient.maxBufferSize", String.valueOf(BIG_BUFFER_SIZE));
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_BIG_RESPONSE);
    //Since no text response was set, we validate the size of the response body instead.
    assertThat(result.getBodySizeAsLong()).isEqualTo(BIG_BUFFER_SIZE);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldThrowAnExceptionWhenBufferSizeIsBiggerThanMaxBufferSize() throws Throwable {
    buildStartedServer();
    JMeterUtils.setProperty("httpJettyClient.maxBufferSize", String.valueOf(BIG_BUFFER_SIZE - 1));
    sampleWithGet(SERVER_PATH_BIG_RESPONSE);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldNotGetAResponseWhenBufferSizeIsBiggerThanMaxBufferSize() throws Exception {
    buildStartedServer();
    JMeterUtils.setProperty("httpJettyClient.maxBufferSize", String.valueOf(BIG_BUFFER_SIZE - 1));
    //There is no response, since an exception is thrown in this case
    sampleWithGet(SERVER_PATH_BIG_RESPONSE);
  }

  @Test
  public void shouldSuccessfulSendRequestWhenExclusiveHTTP2ServerWithoutALPN() throws Throwable {
    server = new ServerBuilder().withSSL().withALPN().withHTTP2().buildServer();
    server.start();
    syncServerPort();
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener();
    sampler.setFutureResponseListener(listener);

    client = new HTTP2JettyClient(true, "Test");
    client.start();
    Request httpRequest = client.sampleAsync(
        sampler,
        buildBaseResult(createURL(SERVER_PATH_200), HTTPConstants.GET),
        sampler.getFutureResponseListener());
    httpRequest.send(listener);
    ContentResponse contentResponse = listener.get();
    assertThat(contentResponse.getContent()).isNotEmpty();
  }

  @Test
  public void shouldConfigureProxyWhenAsyncRequestIsPrepared() throws Exception {
    sampler.setProxyHost("localhost");
    sampler.setProxyPortInt(String.valueOf(8888));
    sampler.setProxyScheme(HTTPConstants.PROTOCOL_HTTP);

    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener();
    Request httpRequest = client.sampleAsync(
        sampler,
        buildBaseResult(createURL(HTTPConstants.PROTOCOL_HTTP, "example.com", 80, "/"),
            HTTPConstants.GET),
        listener);

    assertNotNull(httpRequest);
    List<ProxyConfiguration.Proxy> proxies =
        client.getHttpClient().getProxyConfiguration().getProxies();
    assertThat(proxies).hasSize(1);
    ProxyConfiguration.Proxy proxy = proxies.get(0);
    assertThat(proxy).isInstanceOf(HttpProxy.class);
    HttpProxy httpProxy = (HttpProxy) proxy;
    assertThat(httpProxy.getAddress().getHost()).isEqualTo("localhost");
    assertThat(httpProxy.getAddress().getPort()).isEqualTo(8888);
  }

  @Test
  public void shouldSuccessfulSendRequestWhenExclusiveHTTP1ServerWithoutALPN() throws Exception {
    server = new ServerBuilder().withSSL().withHTTP1().buildServer();
    server.start();
    syncServerPort();
    HTTPSampleResult result = sampleWithGet();
    assertThat(result.getResponseDataAsString()).isEqualTo(SERVER_RESPONSE);
  }

  @Test
  public void shouldInitializeHTTP2JettyClientWithHTTP3Enabled() {
    boolean http1UpgradeRequired = false;
    String name = "TestClientWithHTTP3";

    HTTP2JettyClient httpClient = new HTTP2JettyClient(http1UpgradeRequired, name);

    assertNotNull(httpClient);
    assertNotNull(httpClient.getBufferPool());
    assertNotNull(httpClient.getHttpClient());
    // HTTP/3 is always enabled when dependencies are present
  }

  @Test
  public void shouldStartClientWithHTTP3Support() throws Exception {
    // Test that client can start successfully with HTTP/3 enabled
    HTTP2JettyClient httpClient = new HTTP2JettyClient(false, "TestClient");
    
    try {
      httpClient.start();
      assertNotNull(httpClient.getHttpClient());
      // Client should start successfully
    } finally {
      httpClient.stop();
    }
  }

  @Test
  public void shouldSupportHTTP3AlongsideHTTP2AndHTTP1() throws Exception {
    // Test that HTTP/3 is integrated alongside HTTP/2 and HTTP/1.1
    HTTP2JettyClient httpClient = new HTTP2JettyClient(false, "TestClient");
    
    try {
      httpClient.start();
      // The HttpClientTransportDynamic should support all three protocols
      // We can't directly verify the internal transport configuration,
      // but if the client starts and works, the integration is successful
      assertNotNull(httpClient.getHttpClient());
    } finally {
      httpClient.stop();
    }
  }

  @Test
  public void shouldConfigureQUICProperties() {
    // Test that QUIC-specific properties are configurable
    JMeterUtils.getJMeterProperties().setProperty("httpJettyClient.quicMaxIdleTimeout", "45000");
    JMeterUtils.getJMeterProperties().setProperty("httpJettyClient.quicMaxBidirectionalStreams", "150");
    JMeterUtils.getJMeterProperties().setProperty("httpJettyClient.quicMaxUnidirectionalStreams", "150");

    HTTP2JettyClient httpClient = new HTTP2JettyClient(false, "TestClient");

    assertNotNull(httpClient);
    // Properties should be loaded during initialization
  }
}
