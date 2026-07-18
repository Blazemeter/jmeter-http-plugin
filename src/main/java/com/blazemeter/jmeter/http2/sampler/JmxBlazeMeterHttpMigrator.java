package com.blazemeter.jmeter.http2.sampler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

/**
 * Headless migration of JMeter test plans: replaces stock HTTP Request samplers with
 * {@link HTTP2Sampler} while preserving child elements (assertions, timers, etc.).
 */
public final class JmxBlazeMeterHttpMigrator {

  private JmxBlazeMeterHttpMigrator() {
  }

  public static HashTree loadTree(File jmxFile) throws IOException {
    return SaveService.loadTree(jmxFile);
  }

  public static void saveTree(HashTree tree, File jmxFile) throws IOException {
    try (OutputStream out = java.nio.file.Files.newOutputStream(jmxFile.toPath())) {
      SaveService.saveTree(tree, out);
    }
  }

  /**
   * @return number of HTTP Request samplers replaced
   */
  public static int migrateTree(HashTree tree) {
    return migrateTreeWithDetails(tree).getReplacedCount();
  }

  public static MigrationResult migrateTreeWithDetails(HashTree tree) {
    MigrationResult result = new MigrationResult();
    migrateInPlace(tree, result);
    return result;
  }

  public static HashTree migrateCopy(HashTree source) {
    return migrateCopy(source, new MigrationResult());
  }

  public static HashTree migrateCopy(HashTree source, MigrationResult result) {
    // Declared as HashTree (not ListedHashTree) so add(Object) binds to the stable base-class
    // method: JMeter 5.6.3's ListedHashTree added a covariant add(Object) override that older
    // jorphan releases (e.g. 5.5) don't have, which would throw NoSuchMethodError at runtime
    // if this class were compiled against a jar with the override and loaded into an older one.
    HashTree copy = new ListedHashTree();
    for (Object key : source.list()) {
      Object newKey = maybeReplaceSampler(key, result);
      HashTree sub = source.getTree(key);
      if (sub != null && !sub.isEmpty()) {
        copy.add(newKey, migrateCopy(sub, result));
      } else {
        copy.add(newKey);
      }
    }
    return copy;
  }

  public static File migrateFile(File sourceJmx, File targetJmx) throws IOException {
    HashTree tree = loadTree(sourceJmx);
    migrateTree(tree);
    saveTree(tree, targetJmx);
    return targetJmx;
  }

  public static int countMigratableSamplers(HashTree tree) {
    int count = 0;
    for (Object key : tree.list()) {
      if (key instanceof TestElement
          && HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(
              (TestElement) key)) {
        count++;
      }
      HashTree sub = tree.getTree(key);
      if (sub != null && !sub.isEmpty()) {
        count += countMigratableSamplers(sub);
      }
    }
    return count;
  }

  public static int countHttp2Samplers(HashTree tree) {
    int count = 0;
    for (Object key : tree.list()) {
      if (key instanceof HTTP2Sampler) {
        count++;
      }
      HashTree sub = tree.getTree(key);
      if (sub != null && !sub.isEmpty()) {
        count += countHttp2Samplers(sub);
      }
    }
    return count;
  }

  private static void migrateInPlace(HashTree tree, MigrationResult result) {
    List<Object> keys = new ArrayList<>(tree.list());
    for (Object key : keys) {
      HashTree sub = tree.getTree(key);
      if (sub != null && !sub.isEmpty()) {
        migrateInPlace(sub, result);
      }
      if (key instanceof TestElement
          && HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(
              (TestElement) key)) {
        HTTPSamplerBase source = (HTTPSamplerBase) key;
        HTTP2Sampler replacement =
            HttpSamplerToBlazeMeterHttpMigrator.migrateFromApacheHttpSampler(source);
        result.recordReplacement(source, replacement);
        tree.replaceKey(key, replacement);
      }
    }
  }

  private static Object maybeReplaceSampler(Object key, MigrationResult result) {
    if (key instanceof TestElement
        && HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(
            (TestElement) key)) {
      HTTPSamplerBase source = (HTTPSamplerBase) key;
      HTTP2Sampler replacement =
          HttpSamplerToBlazeMeterHttpMigrator.migrateFromApacheHttpSampler(source);
      result.recordReplacement(source, replacement);
      return replacement;
    }
    return key;
  }

  public static final class MigrationResult {
    private int replacedCount;
    private final List<String> replacedLabels = new ArrayList<>();

    private void recordReplacement(HTTPSamplerBase source, HTTP2Sampler replacement) {
      replacedCount++;
      replacedLabels.add(source.getName());
      replacement.setName(source.getName());
    }

    public int getReplacedCount() {
      return replacedCount;
    }

    public List<String> getReplacedLabels() {
      return replacedLabels;
    }
  }
}
