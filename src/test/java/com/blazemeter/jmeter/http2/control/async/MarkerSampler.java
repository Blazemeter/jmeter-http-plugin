package com.blazemeter.jmeter.http2.control.async;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;

/**
 * A non-HTTP sampler, standing in for the Debug Sampler / JSR223 Sampler / Test Action that users
 * put between requests. HTTP2Controller treats any element that is not an HTTP2Sampler as a
 * "checkpoint", so its presence changes the controller's control flow.
 */
public class MarkerSampler extends AbstractSampler {

  private static final long serialVersionUID = 1L;

  private final List<Long> sampledAt = Collections.synchronizedList(new ArrayList<>());

  public MarkerSampler(String name) {
    setName(name);
  }

  @Override
  public SampleResult sample(Entry entry) {
    sampledAt.add(System.currentTimeMillis());
    SampleResult result = new SampleResult();
    result.setSampleLabel(getName());
    result.sampleStart();
    result.setResponseCodeOK();
    result.setResponseMessage("OK");
    result.setSuccessful(true);
    result.setDataType(SampleResult.TEXT);
    result.setResponseData("marker", StandardCharsets.UTF_8.name());
    result.sampleEnd();
    return result;
  }

  public int invocations() {
    return sampledAt.size();
  }
}
