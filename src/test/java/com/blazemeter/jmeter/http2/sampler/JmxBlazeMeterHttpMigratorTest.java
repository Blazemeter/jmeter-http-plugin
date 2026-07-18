package com.blazemeter.jmeter.http2.sampler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import java.io.File;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JmxBlazeMeterHttpMigratorTest extends HTTP2TestBase {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private File samplePlanJmx;

  @Before
  public void writeSamplePlan() throws Exception {
    samplePlanJmx = tempFolder.newFile("sample-plan.jmx");
    JmxBlazeMeterHttpMigrator.saveTree(buildSamplePlanWithTwoSamplers(), samplePlanJmx);
  }

  @Test
  public void migratesAllApacheHttpSamplersInSamplePlan() throws Exception {
    HashTree tree = JmxBlazeMeterHttpMigrator.loadTree(samplePlanJmx);
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

    HashTree tree = new ListedHashTree();
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
    HashTree tree = new ListedHashTree();
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
    File target = tempFolder.newFile("migrated.jmx");
    JmxBlazeMeterHttpMigrator.migrateFile(samplePlanJmx, target);

    HashTree loaded = JmxBlazeMeterHttpMigrator.loadTree(target);
    assertEquals(0, JmxBlazeMeterHttpMigrator.countMigratableSamplers(loaded));
    assertTrue(JmxBlazeMeterHttpMigrator.countHttp2Samplers(loaded) > 0);
  }

  private static HashTree buildSamplePlanWithTwoSamplers() {
    HashTree tree = new ListedHashTree();

    HTTPSamplerProxy first = new HTTPSamplerProxy();
    first.setName("first-call");
    first.setDomain("example.org");
    first.setPath("/a");
    first.setMethod("GET");
    first.setArguments(new Arguments());
    setGuiClass(first);
    tree.add(first);

    HTTPSamplerProxy second = new HTTPSamplerProxy();
    second.setName("second-call");
    second.setDomain("example.org");
    second.setPath("/b");
    second.setMethod("POST");
    second.setArguments(new Arguments());
    setGuiClass(second);
    tree.add(second);

    return tree;
  }

  /**
   * A real .jmx always has {@code guiclass} on each element (set by the GUI when the element is
   * created); {@code SaveService.loadTree} NPEs reading it back otherwise, since
   * {@code TestElementConverter} passes the (then-null) {@code guiclass} XML attribute straight
   * into a {@code Properties} lookup.
   */
  private static void setGuiClass(TestElement element) {
    element.setProperty(TestElement.GUI_CLASS,
        "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
  }
}
