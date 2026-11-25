package com.blazemeter.jmeter.http2.visualizers.waterfall;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A {@link FlowLayout} that reports the height it will actually need once its rows wrap.
 *
 * <p>Plain {@code FlowLayout} does wrap its children, but it reports a preferred size as if they
 * all fitted on one line. Inside anything that honours the preferred height - a {@code BoxLayout}
 * column, a {@code BorderLayout} north region - that means the wrapped rows are laid out below the
 * container's own bottom edge and simply never appear.
 *
 * <p>Which is exactly what the toolbar needs to avoid: the viewer is embedded in JMeter's listener
 * panel, which is a fraction of a screen wide, and controls silently vanishing off the right-hand
 * side is worse than a toolbar that grows a second line.
 */
public final class WrapLayout extends FlowLayout {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a left-aligned wrapping layout.
   *
   * @param horizontalGap gap between components on a row
   * @param verticalGap   gap between rows
   */
  public WrapLayout(int horizontalGap, int verticalGap) {
    super(FlowLayout.LEFT, horizontalGap, verticalGap);
  }

  @Override
  public Dimension preferredLayoutSize(Container target) {
    return layoutSize(target, true);
  }

  @Override
  public Dimension minimumLayoutSize(Container target) {
    Dimension minimum = layoutSize(target, false);
    // FlowLayout consults the minimum size when it decides whether to wrap, so a minimum equal to
    // the preferred size would make the container refuse to shrink and never wrap at all.
    minimum.width -= getHgap() + 1;
    return minimum;
  }

  /**
   * Measures the container by walking its children and breaking rows at the available width.
   *
   * @param target    the container being measured
   * @param preferred whether to use each child's preferred size rather than its minimum
   * @return the size the container needs
   */
  private Dimension layoutSize(Container target, boolean preferred) {
    synchronized (target.getTreeLock()) {
      int targetWidth = availableWidth(target);
      Insets insets = target.getInsets();
      int horizontalInsets = insets.left + insets.right + getHgap() * 2;
      int maxWidth = targetWidth - horizontalInsets;
      Dimension total = new Dimension(0, 0);
      int rowWidth = 0;
      int rowHeight = 0;
      for (int i = 0; i < target.getComponentCount(); i++) {
        Component component = target.getComponent(i);
        if (!component.isVisible()) {
          continue;
        }
        Dimension size = preferred ? component.getPreferredSize() : component.getMinimumSize();
        if (rowWidth + size.width > maxWidth && rowWidth > 0) {
          addRow(total, rowWidth, rowHeight);
          rowWidth = 0;
          rowHeight = 0;
        }
        if (rowWidth > 0) {
          rowWidth += getHgap();
        }
        rowWidth += size.width;
        rowHeight = Math.max(rowHeight, size.height);
      }
      addRow(total, rowWidth, rowHeight);
      total.width += horizontalInsets;
      total.height += insets.top + insets.bottom + getVgap() * 2;
      return total;
    }
  }

  /**
   * The width to wrap against.
   *
   * <p>The awkward part of a wrapping layout: the preferred height depends on the width, but the
   * width is only assigned after the parent has asked for the preferred height. A container being
   * measured for the first time therefore reports a width of zero, and measuring against zero would
   * put every child on a row of its own.
   *
   * <p>So the search walks up the ancestry to the first container that does have a width. By the
   * time a toolbar is measured, the panel and the window above it have been sized, and their width
   * is the width this row is about to be given - which makes the first measurement the right one
   * rather than something a second layout pass has to correct.
   *
   * @param target the container being measured
   * @return the width available for a row
   */
  private static int availableWidth(Container target) {
    for (Container candidate = target; candidate != null; candidate = candidate.getParent()) {
      if (candidate.getWidth() > 0) {
        Insets insets = candidate.getInsets();
        return candidate.getWidth() - insets.left - insets.right;
      }
    }
    return Integer.MAX_VALUE;
  }

  private void addRow(Dimension total, int rowWidth, int rowHeight) {
    total.width = Math.max(total.width, rowWidth);
    if (total.height > 0) {
      total.height += getVgap();
    }
    total.height += rowHeight;
  }
}
