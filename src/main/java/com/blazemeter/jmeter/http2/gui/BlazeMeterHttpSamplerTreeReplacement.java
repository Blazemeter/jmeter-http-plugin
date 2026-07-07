package com.blazemeter.jmeter.http2.gui;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.HttpSamplerToBlazeMeterHttpMigrator;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JTree;
import javax.swing.tree.TreePath;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replaces stock HTTP Request nodes (HTTPSamplerProxy, or HTTPSampler on old plans)
 * with {@link HTTP2Sampler}, preserving attached child nodes.
 */
final class BlazeMeterHttpSamplerTreeReplacement {

  private static final Logger LOG =
      LoggerFactory.getLogger(BlazeMeterHttpSamplerTreeReplacement.class);

  private BlazeMeterHttpSamplerTreeReplacement() {
  }

  /**
   * @param selectReplacement pass true for immediate tree focus;
   *     pass false during batched migrations until selection is reapplied manually.
   * @return inserted tree node for the {@link HTTP2Sampler}
   */
  static JMeterTreeNode replaceHttpSamplerNode(JMeterTreeNode node, boolean selectReplacement) {
    if (node == null) {
      throw new IllegalStateException("Tree node is null");
    }
    TestElement te = node.getTestElement();
    if (!HttpSamplerToBlazeMeterHttpMigrator.isMigratableApacheHttpSampler(te)) {
      throw new IllegalStateException("Node is not an Apache HTTP Request");
    }
    HTTPSamplerBase source = (HTTPSamplerBase) te;

    GuiPackage gui = GuiPackage.getInstance();
    JMeterTreeModel model = gui.getTreeModel();

    HTTP2Sampler replacement =
        HttpSamplerToBlazeMeterHttpMigrator.migrateFromApacheHttpSampler(source);
    JMeterTreeNode parent = (JMeterTreeNode) node.getParent();
    if (parent == null) {
      throw new IllegalStateException("Cannot migrate root-less node");
    }
    int index = parent.getIndex(node);

    List<JMeterTreeNode> detachedChildren = new ArrayList<>();
    while (node.getChildCount() > 0) {
      JMeterTreeNode child = (JMeterTreeNode) node.getChildAt(0);
      detachedChildren.add(child);
      model.removeNodeFromParent(child);
    }

    model.removeNodeFromParent(node);

    JMeterTreeNode newNode = new JMeterTreeNode(replacement, model);
    model.insertNodeInto(newNode, parent, index);
    for (JMeterTreeNode child : detachedChildren) {
      model.insertNodeInto(child, newNode, newNode.getChildCount());
    }

    gui.setDirty(true);
    if (selectReplacement) {
      try {
        selectNode(gui.getTreeListener().getJTree(), newNode);
      } catch (RuntimeException e) {
        LOG.debug("Could not update selection/GUI after migration", e);
      }
      gui.updateCurrentGui();
    }
    return newNode;
  }

  /**
   * Applies tree selection from {@code snapshot}.
   * Entries in {@code oldToNew} replace migrated nodes matched by identity.
   */
  static void restoreSelectionSnapshot(JTree tree, JMeterTreeNode[] snapshot,
      IdentityHashMap<JMeterTreeNode, JMeterTreeNode> oldToNew) {
    if (tree == null || snapshot == null || snapshot.length == 0) {
      return;
    }
    Set<JMeterTreeNode> unique = new LinkedHashSet<>();
    for (JMeterTreeNode n : snapshot) {
      if (n == null) {
        continue;
      }
      JMeterTreeNode resolved = oldToNew.containsKey(n) ? oldToNew.get(n) : n;
      if (resolved.getParent() != null) {
        unique.add(resolved);
      }
    }
    if (unique.isEmpty()) {
      return;
    }
    List<TreePath> paths = new ArrayList<>(unique.size());
    for (JMeterTreeNode n : unique) {
      paths.add(new TreePath(n.getPath()));
    }
    tree.setSelectionPaths(paths.toArray(new TreePath[0]));
    try {
      tree.scrollPathToVisible(paths.get(0));
    } catch (RuntimeException e) {
      LOG.debug("Could not scroll tree to restored selection", e);
    }
  }

  static void selectNode(JTree tree, JMeterTreeNode node) {
    TreePath path = new TreePath(node.getPath());
    tree.setSelectionPath(path);
    tree.scrollPathToVisible(path);
  }
}
