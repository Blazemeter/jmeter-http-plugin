package com.blazemeter.jmeter.http2.core.jetty;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jetty.client.Authentication;
import org.eclipse.jetty.client.Authentication.HeaderInfo;
import org.eclipse.jetty.client.AuthenticationProtocolHandler;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpRequestException;
import org.eclipse.jetty.client.ProtocolHandler;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.RetainingResponseListener;
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler;
import org.eclipse.jetty.client.internal.HttpContentResponse;
import org.eclipse.jetty.client.transport.HttpConversation;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.ResponseListeners;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom replacement for Jetty's {@link WWWAuthenticationProtocolHandler}.
 *
 * <h2>Why Jetty's default is too strict for load-testing clients</h2>
 *
 * <p>Jetty's handler assumes that <em>every</em> HTTP 401 is an HTTP Authentication challenge
 * (Basic, Digest, etc.) in the sense of RFC 7235 / RFC 9110. Its {@code accept()} matches on
 * status 401 alone (before response headers are available). Later, if {@code WWW-Authenticate} is
 * missing or unparseable, it fails the exchange with {@code HttpResponseException}:
 * {@code "HTTP protocol violation: Authentication challenge without WWW-Authenticate header"}.
 *
 * <p>That reading of 401 is too narrow. A service is not required to use the
 * {@code WWW-Authenticate} / {@code Authorization} HTTP Auth framework. It may return 401 simply
 * to mean "not authorized" while using another mechanism entirely, for example:
 * <ul>
 *   <li>Bearer / JWT (or other) tokens in {@code Authorization} or a custom header</li>
 *   <li>API keys, cookies / session, OAuth / OIDC flows</li>
 *   <li>Application-level errors with a JSON/XML body and no challenge header</li>
 * </ul>
 * In those cases there is no HTTP Auth challenge to advertise, so omitting
 * {@code WWW-Authenticate} is normal. Treating it as a protocol violation turns a usable 401
 * sample into a Non-HTTP error in the sampler.
 *
 * <h2>Parity with JMeter HTTP (HttpClient4)</h2>
 *
 * <p>Apache JMeter's HttpClient4 path does not fail the exchange when 401 lacks
 * {@code WWW-Authenticate}: the sampler receives a normal 401 result. This handler restores that
 * behavior for the BlazeMeter HTTP (Jetty) client.
 *
 * <h2>What this changes vs Jetty's original</h2>
 *
 * <ul>
 *   <li><b>401 without a usable {@code WWW-Authenticate} challenge:</b> forward the response to
 *       the application (same outcome as HttpClient4).</li>
 *   <li><b>401 with a valid challenge and matching credentials</b> in the
 *       {@link org.eclipse.jetty.client.AuthenticationStore}: keep Jetty-style challenge-response
 *       Basic/Digest retry.</li>
 * </ul>
 *
 * <h2>Why a full custom handler (not a small subclass override)</h2>
 *
 * <p>The protocol-violation branch lives in Jetty's private inner class
 * {@code AuthenticationProtocolHandler.AuthenticationListener#onComplete}. There is no protected
 * hook to override only the "401 without {@code WWW-Authenticate}" path, and helpers such as
 * {@code forwardSuccessComplete} are private to that listener. Extending
 * {@link WWWAuthenticationProtocolHandler} therefore cannot change that behavior without
 * reimplementing {@link #getResponseListener()}. This class replaces the stock handler and keeps
 * challenge-response Basic/Digest when a real challenge is present.
 *
 * <p>Install via {@link #install(HttpClient)} after {@link HttpClient#start()} so it replaces the
 * handler Jetty registers in {@code doStart()}.
 */
public final class CustomWwwAuthenticationProtocolHandler implements ProtocolHandler {

  private static final Logger LOG =
      LoggerFactory.getLogger(CustomWwwAuthenticationProtocolHandler.class);
  private static final String ATTRIBUTE =
      CustomWwwAuthenticationProtocolHandler.class.getName() + ".attribute";
  private static final Pattern CHALLENGE_PATTERN = Pattern.compile(
      "(?<schemeOnly>[!#$%&'*+\\-.^_`|~0-9A-Za-z]+)"
          + "|(?:(?<scheme>[!#$%&'*+\\-.^_`|~0-9A-Za-z]+)\\s+)?"
          + "(?:(?<token68>[a-zA-Z0-9\\-._~+/]+=*)"
          + "|(?<paramName>[!#$%&'*+\\-.^_`|~0-9A-Za-z]+)\\s*=\\s*(?:(?<paramValue>.*)))");

  private final HttpClient client;
  private final int maxContentLength;

  public CustomWwwAuthenticationProtocolHandler(HttpClient client) {
    this(client, AuthenticationProtocolHandler.DEFAULT_MAX_CONTENT_LENGTH);
  }

  public CustomWwwAuthenticationProtocolHandler(HttpClient client, int maxContentLength) {
    this.client = client;
    this.maxContentLength = maxContentLength;
  }

  /**
   * Removes Jetty's default www-authenticate handler and installs this one. Must be called after
   * {@link HttpClient#start()}.
   */
  public static void install(HttpClient httpClient) {
    if (httpClient == null) {
      return;
    }
    httpClient.getProtocolHandlers().remove(WWWAuthenticationProtocolHandler.NAME);
    httpClient.getProtocolHandlers()
        .put(new CustomWwwAuthenticationProtocolHandler(httpClient));
  }

  @Override
  public String getName() {
    return WWWAuthenticationProtocolHandler.NAME;
  }

  @Override
  public boolean accept(Request request, Response response) {
    return response.getStatus() == HttpStatus.UNAUTHORIZED_401;
  }

  @Override
  public Response.Listener getResponseListener() {
    return new AuthenticationListener();
  }

  private List<HeaderInfo> getHeaderInfo(String header) {
    List<HeaderInfo> headerInfos = new ArrayList<>();
    for (String value : new QuotedCSV(true, header)) {
      Matcher m = CHALLENGE_PATTERN.matcher(value);
      if (!m.matches()) {
        continue;
      }
      if (m.group("schemeOnly") != null) {
        headerInfos.add(new HeaderInfo(HttpHeader.AUTHORIZATION, m.group(1), new HashMap<>()));
        continue;
      }
      if (m.group("scheme") != null) {
        headerInfos.add(
            new HeaderInfo(HttpHeader.AUTHORIZATION, m.group("scheme"), new HashMap<>()));
      }
      if (headerInfos.isEmpty()) {
        throw new IllegalArgumentException("Parameters without auth-scheme");
      }
      Map<String, String> authParams = headerInfos.get(headerInfos.size() - 1).getParameters();
      if (m.group("paramName") != null) {
        String paramVal = QuotedCSV.unquote(m.group("paramValue"));
        authParams.put(m.group("paramName"), paramVal);
      } else if (m.group("token68") != null) {
        if (!authParams.isEmpty()) {
          throw new IllegalArgumentException("token68 after auth-params");
        }
        authParams.put("base64", m.group("token68"));
      }
    }
    return headerInfos;
  }

  private class AuthenticationListener extends RetainingResponseListener {
    private AuthenticationListener() {
      super(maxContentLength);
    }

    @Override
    public void onSuccess(Response response) {
      super.onSuccess(response);
      Request request = response.getRequest();
      if (request.getBody() != null) {
        request.abort(new HttpRequestException(
            "Aborting request after receiving a %d response".formatted(response.getStatus()),
            request));
      }
    }

    @Override
    public void onComplete(Result result) {
      HttpRequest request = (HttpRequest) result.getRequest();
      ContentResponse response =
          new HttpContentResponse(result.getResponse(), getContent(), getMediaType(),
              getEncoding());

      HttpConversation conversation = request.getConversation();
      if (conversation.getAttribute(ATTRIBUTE) != null) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Bad credentials for {}", request);
        }
        forwardSuccessComplete(request, response);
        return;
      }

      List<HeaderInfo> headerInfos = parseAuthenticateHeader(response);
      if (headerInfos.isEmpty()) {
        // JMeter HttpClient4 parity: tolerate 401 without WWW-Authenticate.
        if (LOG.isDebugEnabled()) {
          LOG.debug("401 without WWW-Authenticate for {}; returning response to application",
              request);
        }
        forwardSuccessComplete(request, response);
        return;
      }

      Authentication authentication = null;
      HeaderInfo headerInfo = null;
      URI authURI = resolveURI(request, request.getURI());
      for (HeaderInfo element : headerInfos) {
        authentication = client.getAuthenticationStore()
            .findAuthentication(element.getType(), authURI, element.getRealm());
        if (authentication != null) {
          headerInfo = element;
          break;
        }
      }
      if (authentication == null) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("No authentication available for {}", request);
        }
        forwardSuccessComplete(request, response);
        return;
      }

      Request.Content content = request.getBody();
      if (content != null && !content.rewind()) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Request content not reproducible for {}", request);
        }
        forwardSuccessComplete(request, response);
        return;
      }

      try {
        Authentication.Result authnResult =
            authentication.authenticate(request, response, headerInfo, conversation);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Authentication result {}", authnResult);
        }
        if (authnResult == null) {
          forwardSuccessComplete(request, response);
          return;
        }

        conversation.setAttribute(ATTRIBUTE, true);

        Request newRequest = copyRequest(request, request.getURI());
        if (HttpMethod.CONNECT.is(newRequest.getMethod())) {
          newRequest.path(request.getPath());
        }

        long timeoutNanoTime = request.getTimeoutNanoTime();
        if (timeoutNanoTime < Long.MAX_VALUE) {
          long newTimeout = NanoTime.until(timeoutNanoTime);
          if (newTimeout > 0) {
            newRequest.timeout(newTimeout, TimeUnit.NANOSECONDS);
          } else {
            TimeoutException failure = new TimeoutException(
                "Total timeout " + request.getConversation().getTimeout() + " ms elapsed");
            forwardFailureComplete(request, failure, response, failure);
            return;
          }
        }

        authnResult.apply(newRequest);
        copyIfAbsent(request, newRequest, HttpHeader.AUTHORIZATION);
        copyIfAbsent(request, newRequest, HttpHeader.PROXY_AUTHORIZATION);

        AfterAuthenticationListener listener = new AfterAuthenticationListener(authnResult);
        Connection connection =
            (Connection) request.getAttributes().get(Connection.class.getName());
        if (connection != null) {
          connection.send(newRequest, listener);
        } else {
          newRequest.send(listener);
        }
      } catch (Throwable x) {
        if (LOG.isDebugEnabled()) {
          LOG.atDebug().setCause(x).log("Authentication failed");
        }
        forwardFailureComplete(request, null, response, x);
      }
    }

    private URI resolveURI(HttpRequest request, URI uri) {
      if (uri != null) {
        return uri;
      }
      String target = request.getScheme() + "://" + request.getHost();
      int port = request.getPort();
      if (port > 0) {
        target += ":" + port;
      }
      return URI.create(target);
    }

    private void copyIfAbsent(HttpRequest oldRequest, Request newRequest, HttpHeader header) {
      HttpField field = oldRequest.getHeaders().getField(header);
      if (field != null && !newRequest.getHeaders().contains(header)) {
        newRequest.headers(headers -> headers.put(field));
      }
    }

    private void forwardSuccessComplete(HttpRequest request, Response response) {
      HttpConversation conversation = request.getConversation();
      conversation.updateResponseListeners(null);
      ResponseListeners responseListeners = conversation.getResponseListeners();
      responseListeners.emitSuccessComplete(new Result(request, response));
    }

    private void forwardFailureComplete(HttpRequest request, Throwable requestFailure,
                                        Response response, Throwable responseFailure) {
      HttpConversation conversation = request.getConversation();
      conversation.updateResponseListeners(null);
      ResponseListeners responseListeners = conversation.getResponseListeners();
      if (responseFailure == null) {
        responseListeners.emitSuccess(response);
      } else {
        responseListeners.emitFailure(response, responseFailure);
      }
      responseListeners.notifyComplete(
          new Result(request, requestFailure, response, responseFailure));
    }

    private List<HeaderInfo> parseAuthenticateHeader(Response response) {
      List<HeaderInfo> result = new ArrayList<>();
      List<String> values = response.getHeaders().getValuesList(HttpHeader.WWW_AUTHENTICATE);
      for (String value : values) {
        try {
          result.addAll(getHeaderInfo(value));
        } catch (IllegalArgumentException e) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Failed to parse authentication header", e);
          }
        }
      }
      return result;
    }
  }

  private Request copyRequest(Request request, URI uri) {
    try {
      Method copyRequest =
          HttpClient.class.getDeclaredMethod("copyRequest", Request.class, URI.class);
      copyRequest.setAccessible(true);
      return (Request) copyRequest.invoke(client, request, uri);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unable to copy Jetty request for authentication retry", e);
    }
  }

  private class AfterAuthenticationListener implements Response.Listener {
    private final Authentication.Result authenticationResult;

    private AfterAuthenticationListener(Authentication.Result authenticationResult) {
      this.authenticationResult = authenticationResult;
    }

    @Override
    public void onSuccess(Response response) {
      int status = response.getStatus();
      if (HttpStatus.isSuccess(status) || HttpStatus.isRedirectionWithLocation(status)) {
        client.getAuthenticationStore().addAuthenticationResult(authenticationResult);
      }
    }
  }
}
