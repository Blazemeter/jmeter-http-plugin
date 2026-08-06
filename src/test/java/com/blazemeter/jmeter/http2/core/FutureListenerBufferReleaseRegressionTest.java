package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.client.AbstractResponseListener;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * After the body is copied into {@code ContentResponseWrapper}, Jetty's buffering listener used to
 * keep a second full copy in {@code AbstractResponseListener.content} / {@code accumulator} until
 * the listener was GC'd — doubling peak RAM for large async/embed responses and HE losers.
 */
public class FutureListenerBufferReleaseRegressionTest extends HTTP2TestBase {

  @Test
  public void onCompleteReleasesJettyContentCopyAfterWrapping() throws Exception {
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(-1);
    byte[] body = "x".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);

    Field contentField = AbstractResponseListener.class.getDeclaredField("content");
    contentField.setAccessible(true);
    contentField.set(listener, body);

    Response httpResponse = Mockito.mock(Response.class);
    Mockito.when(httpResponse.getStatus()).thenReturn(200);
    Mockito.when(httpResponse.getReason()).thenReturn("OK");
    Mockito.when(httpResponse.getVersion()).thenReturn(HttpVersion.HTTP_1_1);
    Mockito.when(httpResponse.getHeaders()).thenReturn(HttpFields.EMPTY);

    Result result = Mockito.mock(Result.class);
    Mockito.when(result.getResponse()).thenReturn(httpResponse);
    Mockito.when(result.getFailure()).thenReturn(null);

    listener.onComplete(result);

    assertThat(listener.get()).isNotNull();
    assertThat(listener.get().getContent()).isEqualTo(body);
    assertThat(contentField.get(listener))
        .as("listener must not keep a second full body after the wrapper owns it")
        .isSameAs(BufferUtil.EMPTY_BYTES);
  }

  @Test
  public void releaseAfterSampleMaterialisedDropsRequest() throws Exception {
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(-1);
    org.eclipse.jetty.client.Request request = Mockito.mock(org.eclipse.jetty.client.Request.class);
    listener.setRequest(request);

    // Seed a content response the way onComplete would, then materialise.
    org.eclipse.jetty.client.ContentResponse contentResponse =
        Mockito.mock(org.eclipse.jetty.client.ContentResponse.class);
    listener.completeWith(contentResponse, System.currentTimeMillis(), System.currentTimeMillis());

    listener.releaseAfterSampleMaterialised();

    assertThat(listener.getRequest()).isNull();
    java.lang.reflect.Field responseField =
        HTTP2FutureResponseListener.class.getDeclaredField("response");
    responseField.setAccessible(true);
    assertThat(responseField.get(listener))
        .as("materialised samples must not keep ContentResponse→Request on the listener")
        .isNull();
  }

  @Test
  public void failureOnlyOnCompleteMustReleaseTransportBuffers() throws Exception {
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(-1);
    byte[] body = "partial".getBytes(StandardCharsets.UTF_8);
    Field contentField = AbstractResponseListener.class.getDeclaredField("content");
    contentField.setAccessible(true);
    contentField.set(listener, body);

    Result result = Mockito.mock(Result.class);
    Mockito.when(result.getResponse()).thenReturn(null);
    Mockito.when(result.getFailure()).thenReturn(new java.io.IOException("boom"));

    listener.onComplete(result);

    assertThat(contentField.get(listener))
        .as("failure-only HE/abort completion must still drop Jetty's content copy")
        .isSameAs(BufferUtil.EMPTY_BYTES);
  }

  @Test
  public void sealedAbortPathsMustNotThrowAlreadyReleased() {
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(-1);
    Response httpResponse = Mockito.mock(Response.class);
    org.eclipse.jetty.client.ContentResponse contentResponse =
        Mockito.mock(org.eclipse.jetty.client.ContentResponse.class);

    // completeWith seals and releases; Jetty then notifies onFailure/onComplete for the abort.
    listener.completeWith(contentResponse, System.currentTimeMillis(), System.currentTimeMillis());

    assertThat(org.assertj.core.api.Assertions.catchThrowable(
        () -> listener.onFailure(httpResponse, new java.util.concurrent.CancellationException())))
        .isNull();
    assertThat(org.assertj.core.api.Assertions.catchThrowable(
        () -> listener.onComplete(Mockito.mock(Result.class))))
        .isNull();
    assertThat(org.assertj.core.api.Assertions.catchThrowable(
        () -> listener.releaseAfterSampleMaterialised()))
        .isNull();
  }
}
