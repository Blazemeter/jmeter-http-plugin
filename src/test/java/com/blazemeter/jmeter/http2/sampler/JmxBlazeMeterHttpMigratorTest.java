package com.blazemeter.jmeter.http2.sampler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.io.File;
import java.nio.file.Path;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.junit.BeforeClass;
import org.junit.Test;

public class JmxBlazeMeterHttpMigratorTest extends HTTP2TestBase {

  private static File testHttpJmx;

  @BeforeClass
  public static void locateTestHttpJmx() {
    Path path = Path.of("src", "test", "resources", "jmeter-regression", "5.6.3", "TEST_HTTP.jmx");
    testHttpJmx = path.toFile();
    assertTrue("TEST_HTTP.jmx must exist at " + path, testHttpJmx.isFile());
  }

  @Test
  public void migratesAllApacheHttpSamplersInTestHttpPlan() throws Exception {
    HashTree tree = JmxBlazeMeterHttpMigrator.loadTree(testHttpJmx);
    int before = JmxBlazeMeterHttpMigrator.countMigratableSamplers(tree);
    assertTrue(before > 0);

    JmxBlazeMeterHttpMigrator.MigrationResult result =
        JmxBlazeMeterHttpMigrator.migrateTreeWithDetails(tree);
    assertEquals(before, result.getReplacedCount());
    assertEquals(0, JmxBlazeMeterHttpMigrator.countMigratableSamplers(tree));
    assertEquals(before, JmxBlazeMeterHttpMigrator.countHttp2Samplers(tree));
  }

  @Test
  public void preservesSamplerPropertiesDuringMigration() throws Exception {
    HTTPSamplerProxy src = new HTTPSamplerProxy();
    src.setName("api-call");
    src.setDomain("example.org");
    src.setPort(443);
    src.setPath("/v1/items");
    src.setMethod("POST");
    src.setFollowRedirects(true);

    HashTree tree = new org.apache.jorphan.collections.ListedHashTree();
    tree.add(src);

    JmxBlazeMeterHttpMigrator.migrateTree(tree);
    Object migrated = tree.list().iterator().next();
    assertTrue(migrated instanceof HTTP2Sampler);
    HTTP2Sampler dest = (HTTP2Sampler) migrated;
    assertEquals("example.org", dest.getDomain());
    assertEquals(443, dest.getPort());
    assertEquals("/v1/items", dest.getPath());
    assertEquals("POST", dest.getMethod());
    assertTrue(dest.getFollowRedirects());
    assertEquals(HTTP2Sampler.class.getName(), dest.getPropertyAsString(TestElement.TEST_CLASS));
    assertFalse(dest.getPropertyAsString(TestElement.GUI_CLASS).contains("HTTPSampler"));
  }

  @Test
  public void migrateCopyPreservesChildElements() throws Exception {
    HTTPSamplerProxy parent = new HTTPSamplerProxy();
    parent.setName("parent");
    org.apache.jmeter.assertions.ResponseAssertion assertion =
        new org.apache.jmeter.assertions.ResponseAssertion();
    assertion.setName("assert-ok");
    HashTree tree = new org.apache.jorphan.collections.ListedHashTree();
    HashTree sub = tree.add(parent);
    sub.add(assertion);

    HashTree migrated = JmxBlazeMeterHttpMigrator.migrateCopy(tree);
    Object key = migrated.list().iterator().next();
    assertTrue(key instanceof HTTP2Sampler);
    HashTree childTree = migrated.getTree(key);
    assertEquals(1, childTree.list().size());
    assertTrue(childTree.list().iterator().next()
        instanceof org.apache.jmeter.assertions.ResponseAssertion);
  }

  @Test
  public void migrateFileWritesNewJmx() throws Exception {
    File target = File.createTempFile("migrated-", ".jmx");
    target.deleteOnExit();
    JmxBlazeMeterHttpMigrator.migrateFile(testHttpJmx, target);

    HashTree loaded = JmxBlazeMeterHttpMigrator.loadTree(target);
    assertEquals(0, JmxBlazeMeterHttpMigrator.countMigratableSamplers(loaded));
    assertTrue(JmxBlazeMeterHttpMigrator.countHttp2Samplers(loaded) > 0);
  }
}
