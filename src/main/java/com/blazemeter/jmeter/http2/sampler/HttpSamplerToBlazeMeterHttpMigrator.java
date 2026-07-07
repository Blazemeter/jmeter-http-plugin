package com.blazemeter.jmeter.http2.sampler;

import java.util.Iterator;
import org.apache.jmeter.protocol.http.sampler.HTTPSampler;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;

/**
 * Converts Apache HTTP Request elements ({@link HTTPSamplerProxy} or legacy
 * {@link HTTPSampler}) into {@link HTTP2Sampler}.
 * Tags BlazeMeter GUI/classes via {@link BlazeMeterHttpSamplerFactory}.
 */
public final class HttpSamplerToBlazeMeterHttpMigrator {

  private HttpSamplerToBlazeMeterHttpMigrator() {
  }

  /**
   * @return {@code true} for typical HTTP Request tree elements ({@link HTTPSamplerProxy}
   *     or legacy {@link HTTPSampler}); never for {@link HTTP2Sampler}.
   */
  public static boolean isMigratableApacheHttpSampler(TestElement element) {
    if (element instanceof HTTP2Sampler) {
      return false;
    }
    return element instanceof HTTPSamplerProxy || element instanceof HTTPSampler;
  }

  /**
   * Copies Apache sampler properties into a new BlazeMeter HTTP sampler; swaps GUI/test class via
   * {@link BlazeMeterHttpSamplerFactory}.
   */
  public static HTTP2Sampler migrateFromApacheHttpSampler(HTTPSamplerBase source) {
    HTTP2Sampler dest = new HTTP2Sampler();
    Iterator<JMeterProperty> iterator = source.propertyIterator();
    while (iterator.hasNext()) {
      JMeterProperty prop = iterator.next();
      String name = prop.getName();
      if (TestElement.GUI_CLASS.equals(name) || TestElement.TEST_CLASS.equals(name)) {
        continue;
      }
      dest.setProperty(prop.clone());
    }
    BlazeMeterHttpSamplerFactory.setGuiAndTestClassForBlazeMeterHttp(dest);
    return dest;
  }
}
