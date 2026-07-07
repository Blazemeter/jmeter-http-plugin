package com.blazemeter.jmeter.http2.core;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.Response;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class HTTP2FutureResponseListenerTest extends HTTP2TestBase {
    private HTTP2FutureResponseListener listener;
    private Request mockRequest;

    @Before
    public void setUp() {
        listener = new HTTP2FutureResponseListener();
        mockRequest = mock(Request.class);
        listener.setRequest(mockRequest);
    }

    @Test
    public void getRequestReturnsSetRequest() {
        assertEquals(mockRequest, listener.getRequest());
    }

    @Test
    public void getResponseStartReturnsResponseStart() {
        listener.setStart();
        long responseStart = listener.getResponseStart();
        assertTrue(responseStart > 0);
    }

    @Test
    public void getResponseEndReturnsResponseEnd() {
        listener.setEnd();
        long responseEnd = listener.getResponseEnd();
        assertTrue(responseEnd > 0);
    }

    @Test
    public void onCompleteSuccessfulResultCreatesContentResponse() throws ExecutionException, InterruptedException {
        Result mockResult = mock(Result.class);
        ContentResponse mockContentResponse = mock(ContentResponse.class);
        when(mockContentResponse.getContent()).thenReturn("".getBytes());
        when(mockResult.getResponse()).thenReturn(mockContentResponse);

        listener.onComplete(mockResult);

        ContentResponse response = listener.get();
        Assert.assertArrayEquals(mockContentResponse.getContent(),response.getContent());
    }

    @Test
    public void cancelAbortsRequest() {
        when(mockRequest.abort(Mockito.any(CancellationException.class))).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));

        boolean cancelled = listener.cancel(true);

        assertTrue(cancelled);
        verify(mockRequest).abort(Mockito.any(CancellationException.class));
    }

    @Test
    public void isCancelledReturnsCancelledFlag() {
        listener.cancel(true);

        assertTrue(listener.isCancelled());
    }

    @Test
    public void isDoneReturnsTrueWhenCountDownIsZero() {
        listener.onComplete(mock(Result.class));

        assertTrue(listener.isDone());
    }

    @Test
    public void isDoneReturnsTrueWhenCancelled() {
        listener.cancel(true);

        assertTrue(listener.isDone());
    }

    @Test
    public void getWithTimeoutWaitsForCompletion() throws ExecutionException, InterruptedException, TimeoutException {
        // Create a proper mock Result with a Response
        Result mockResult = mock(Result.class);
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(mockResponse.getReason()).thenReturn("OK");
        when(mockResponse.getVersion()).thenReturn(org.eclipse.jetty.http.HttpVersion.HTTP_2);
        when(mockResponse.getHeaders()).thenReturn(org.eclipse.jetty.http.HttpFields.EMPTY);
        when(mockResponse.getTrailers()).thenReturn(org.eclipse.jetty.http.HttpFields.EMPTY);
        when(mockResponse.getRequest()).thenReturn(mockRequest);
        when(mockResult.getResponse()).thenReturn(mockResponse);
        when(mockResult.getFailure()).thenReturn(null);
        
        listener.onComplete(mockResult);

        long timeout = 1000; // Timeout in milliseconds
        TimeUnit unit = TimeUnit.MILLISECONDS;

        ContentResponse result = listener.get(timeout, unit);

        assertNotNull(result);
    }

    @Test(expected = TimeoutException.class)
    public void getWithTimeoutExceedsTimeoutThrowsTimeoutException()
            throws InterruptedException, ExecutionException, TimeoutException {
        // Don't call onComplete() to simulate a timeout

        long timeout = 1000; // Timeout in milliseconds
        TimeUnit unit = TimeUnit.MILLISECONDS;

        listener.get(timeout, unit); // Should throw TimeoutException
    }
}
