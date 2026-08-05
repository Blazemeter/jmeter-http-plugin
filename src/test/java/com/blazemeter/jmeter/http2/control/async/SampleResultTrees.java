package com.blazemeter.jmeter.http2.control.async;

import java.util.ArrayList;
import java.util.List;
import org.apache.jmeter.samplers.SampleResult;

/** Renders a SampleResult hierarchy as readable {@code a > b > c} paths for assertions. */
public final class SampleResultTrees {

  private SampleResultTrees() {
  }

  static void appendPaths(SampleResult result, String prefix, List<String> out) {
    String path = prefix.isEmpty()
        ? result.getSampleLabel()
        : prefix + " > " + result.getSampleLabel();
    out.add(path);
    SampleResult[] subs = result.getSubResults();
    if (subs == null) {
      return;
    }
    for (SampleResult sub : subs) {
      appendPaths(sub, path, out);
    }
  }

  public static List<String> paths(SampleResult result) {
    List<String> out = new ArrayList<>();
    appendPaths(result, "", out);
    return out;
  }

  /** Immediate child labels of a result, in order. */
  public static List<String> childLabels(SampleResult result) {
    List<String> out = new ArrayList<>();
    SampleResult[] subs = result.getSubResults();
    if (subs != null) {
      for (SampleResult sub : subs) {
        out.add(sub.getSampleLabel());
      }
    }
    return out;
  }

  public static int depth(SampleResult result) {
    SampleResult[] subs = result.getSubResults();
    if (subs == null || subs.length == 0) {
      return 1;
    }
    int max = 0;
    for (SampleResult sub : subs) {
      max = Math.max(max, depth(sub));
    }
    return 1 + max;
  }
}
