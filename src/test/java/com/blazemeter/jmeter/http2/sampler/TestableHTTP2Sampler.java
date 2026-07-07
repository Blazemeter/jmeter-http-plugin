package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import java.net.URL;
import java.util.concurrent.Callable;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;

/**
 * Test helper exposing {@link HTTP2Sampler}'s protected {@code sample} entry point.
 */
public class TestableHTTP2Sampler extends HTTP2Sampler {

  public TestableHTTP2Sampler(Callable<HTTP2JettyClient> clientFactory) {
    super(clientFactory);
  }

  public HTTPSampleResult runSample(URL url, String method) throws Exception {
    return sample(url, method, false, 0);
  }

}
