package com.blazemeter.jmeter.http2.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * De-duplicates a multi-selection and orders nodes by depth (deepest first) so inner HTTP
 * samplers are migrated before outer ones when both are selected.
 */
final class BlazeMeterHttpTreeSelectionNormalizer {

  private BlazeMeterHttpTreeSelectionNormalizer() {
  }

  static List<JMeterTreeNode> descendantsOutwardFirst(JMeterTreeNode[] selected) {
    if (selected == null || selected.length == 0) {
      return List.of();
    }
    Set<JMeterTreeNode> unique = new LinkedHashSet<>(List.of(selected));
    List<JMeterTreeNode> list = new ArrayList<>(unique);
    Comparator<JMeterTreeNode> byDepth = Comparator.<JMeterTreeNode>comparingInt(
            BlazeMeterHttpTreeSelectionNormalizer::depthOf)
        .reversed();
    list.sort(byDepth);
    return list;
  }

  private static int depthOf(JMeterTreeNode node) {
    int d = 0;
    JMeterTreeNode n = node;
    while (n != null && n.getParent() != null) {
      d++;
      n = (JMeterTreeNode) n.getParent();
    }
    return d;
  }
}
