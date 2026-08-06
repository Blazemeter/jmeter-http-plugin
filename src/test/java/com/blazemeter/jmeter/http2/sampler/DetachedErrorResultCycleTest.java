package com.blazemeter.jmeter.http2.sampler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.Test;

/**
 * {@link HTTPSampleResult#HTTPSampleResult(HTTPSampleResult)} aliases the parent's
 * {@code subResults} list. Adding that copy back onto the parent makes the error a child of itself;
 * View Results Tree then walks the cycle until {@link StackOverflowError}.
 *
 * <p>These tests pin {@link HTTP2Sampler#detachedErrorResult} as the safe builder for that pattern,
 * and prove the naive copy+add combination still reproduces the cycle so a future rewrite cannot
 * "simplify" the helper away without failing CI.
 */
public class DetachedErrorResultCycleTest extends HTTP2TestBase {

  private static final int RECURSIVE_WALK_DEPTH_CAP = 10_000;

  @Test
  public void naiveCopyAddedBackOntoParentCreatesSelfReferentialCycle() {
    HTTPSampleResult parent = parentWithChild("page");
    HTTPSampleResult naiveCopy = new HTTPSampleResult(parent);
    parent.addRawSubResult(naiveCopy);

    assertThat(containsIdentity(naiveCopy.getSubResults(), naiveCopy))
        .as("the naive pattern must keep creating a self-referential graph; if this assertion "
            + "fails, JMeter's copy constructor no longer aliases subResults and the helper can "
            + "be reconsidered")
        .isTrue();
    // Do not recurse into the cycle: that would StackOverflowError and can destabilise the JVM
    // fork for later tests. The identity check above is the regression signal.
  }

  @Test
  public void detachedErrorResultMustNotCreateSelfReferentialCycle() {
    HTTP2Sampler sampler = new HTTP2Sampler();
    HTTPSampleResult parent = parentWithChild("page");

    HTTPSampleResult err = sampler.detachedErrorResult(
        new Exception("Error downloading embedded resources, execution timeout"), parent);
    // addRawSubResult: production uses addSubResult; we only need the graph shape here, not parent
    // timing aggregation (which requires a fully stamped error sample).
    parent.addRawSubResult(err);

    assertThat(err.getSubResults())
        .as("the error stub must not inherit the parent's children")
        .isEmpty();
    assertThat(containsIdentity(parent.getSubResults(), err))
        .as("the error is still attached as a normal child of the parent")
        .isTrue();
    assertThat(containsIdentity(err.getSubResults(), err))
        .as("the error must not appear in its own sub-result list")
        .isFalse();
    assertThatCode(() -> recursiveWalkWithoutIdentitySet(parent))
        .as("a tree walk like View Results Tree must finish without StackOverflowError")
        .doesNotThrowAnyException();
  }

  private static HTTPSampleResult parentWithChild(String label) {
    HTTPSampleResult child = new HTTPSampleResult();
    child.setSampleLabel(label + "-child");
    child.setSuccessful(true);
    child.sampleStart();
    child.sampleEnd();

    HTTPSampleResult parent = new HTTPSampleResult();
    parent.setSampleLabel(label);
    parent.setSuccessful(true);
    parent.sampleStart();
    parent.sampleEnd();
    parent.addRawSubResult(child);
    return parent;
  }

  private static boolean containsIdentity(SampleResult[] results, SampleResult needle) {
    if (results == null) {
      return false;
    }
    for (SampleResult result : results) {
      if (result == needle) {
        return true;
      }
    }
    return false;
  }

  /**
   * Mirrors a naive viewer walk: recurse into every sub-result with no identity set. A cyclic
   * graph overflows; an acyclic one returns the node count.
   */
  private static int recursiveWalkWithoutIdentitySet(SampleResult root) {
    return recursiveWalkWithoutIdentitySet(root, 0);
  }

  private static int recursiveWalkWithoutIdentitySet(SampleResult node, int depth) {
    if (depth > RECURSIVE_WALK_DEPTH_CAP) {
      // Guard for environments that raise the stack size enough to avoid SOE on a shallow cycle.
      throw new StackOverflowError("sub-result walk exceeded depth " + RECURSIVE_WALK_DEPTH_CAP);
    }
    int count = 1;
    SampleResult[] subs = node.getSubResults();
    if (subs != null) {
      for (SampleResult sub : subs) {
        count += recursiveWalkWithoutIdentitySet(sub, depth + 1);
      }
    }
    return count;
  }
}
