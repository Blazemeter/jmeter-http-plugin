package com.blazemeter.jmeter.http2.sampler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.sampler.gui.HTTP2SamplerGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSampler;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.junit.Test;

public class HttpSamplerToBlazeMeterHttpMigratorTest {

  @Test
  public void migratableApacheHttpRequestTypes() {
    assertTrue(HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(new HTTPSampler()));
    assertTrue(HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(new HTTPSamplerProxy()));
    HTTP2Sampler plugin = new HTTP2Sampler();
    assertFalse(HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(plugin));
  }

  @Test
  public void copiesSamplerFieldsFromLegacyHttpsSamplerAndOverridesGuiClasses() {
    HTTPSampler src = new HTTPSampler();
    src.setName("req1");
    src.setDomain("example.org");
    src.setPort(443);

    HTTP2Sampler dest =
        HttpSamplerToBlazeMeterHttpMigrator.migrateFromApacheHttpSampler(src);

    assertEquals(
        HTTP2SamplerGui.class.getName(), dest.getPropertyAsString(TestElement.GUI_CLASS));
    assertEquals(HTTP2Sampler.class.getName(), dest.getPropertyAsString(TestElement.TEST_CLASS));
    assertEquals("example.org", dest.getDomain());
    assertEquals(443, dest.getPort());
    assertEquals("req1", dest.getName());
  }

  @Test
  public void migratesHttpsSamplerProxyAsUsedByGuiHttpRequestElement() {
    HTTPSamplerProxy src = new HTTPSamplerProxy();
    src.setName("api");
    src.setDomain("api.example.net");
    src.setPort(8080);

    HTTP2Sampler dest =
        HttpSamplerToBlazeMeterHttpMigrator.migrateFromApacheHttpSampler(src);

    assertEquals(HTTP2SamplerGui.class.getName(), dest.getPropertyAsString(TestElement.GUI_CLASS));
    assertEquals(HTTP2Sampler.class.getName(), dest.getPropertyAsString(TestElement.TEST_CLASS));
    assertEquals("api.example.net", dest.getDomain());
    assertEquals(8080, dest.getPort());
    assertEquals("api", dest.getName());
  }
}
