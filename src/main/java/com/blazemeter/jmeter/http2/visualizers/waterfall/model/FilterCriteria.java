package com.blazemeter.jmeter.http2.visualizers.waterfall.model;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The set of conditions a row has to meet to stay visible.
 *
 * <p>Mutable on purpose: the toolbar edits it in place and asks the table model to re-apply it,
 * which happens on the event dispatch thread only. Derived state that is expensive to rebuild -
 * the compiled pattern, the lower-cased needle - is recomputed when the corresponding setter runs
 * rather than once per row, because {@link #matches(SampleRecord)} is called for every retained
 * sample on every keystroke.
 */
public final class FilterCriteria {

  private final Set<Integer> statusClasses = new LinkedHashSet<>();
  private final Set<String> protocols = new LinkedHashSet<>();
  private String text = "";
  private String lowerText = "";
  private Pattern pattern;
  private boolean regex;
  private boolean regexValid = true;
  private boolean errorsOnly;
  private long minTimeMs;

  /**
   * Tests one row.
   *
   * @param record the row to test
   * @return {@code true} when every active condition accepts it
   */
  public boolean matches(SampleRecord record) {
    if (errorsOnly && record.isSuccess()) {
      return false;
    }
    if (record.getElapsed() < minTimeMs) {
      return false;
    }
    if (!statusClasses.isEmpty() && !statusClasses.contains(record.getStatusClass())) {
      return false;
    }
    if (!protocols.isEmpty() && !protocols.contains(record.getProtocol())) {
      return false;
    }
    return matchesText(record);
  }

  private boolean matchesText(SampleRecord record) {
    if (text.isEmpty()) {
      return true;
    }
    if (regex) {
      // An in-progress regex such as "foo(" must not hide every row while it is being typed.
      return !regexValid || pattern.matcher(record.getLabel()).find()
          || pattern.matcher(record.getUrl()).find();
    }
    return record.getLabel().toLowerCase(Locale.ROOT).contains(lowerText)
        || record.getUrl().toLowerCase(Locale.ROOT).contains(lowerText);
  }

  /**
   * Whether any condition would currently hide rows.
   *
   * @return {@code true} when the filter is not pass-through
   */
  public boolean isActive() {
    return errorsOnly || minTimeMs > 0 || !statusClasses.isEmpty() || !protocols.isEmpty()
        || !text.isEmpty();
  }

  /** Resets every condition, making the filter pass-through. */
  public void reset() {
    statusClasses.clear();
    protocols.clear();
    setText("");
    errorsOnly = false;
    minTimeMs = 0;
  }

  /**
   * The label and URL search term.
   *
   * @return the term, empty when not filtering on text
   */
  public String getText() {
    return text;
  }

  /**
   * Sets the label and URL search term.
   *
   * @param text the term, {@code null} treated as empty
   */
  public void setText(String text) {
    this.text = text == null ? "" : text.trim();
    this.lowerText = this.text.toLowerCase(Locale.ROOT);
    compilePattern();
  }

  /**
   * Whether the search term is a regular expression.
   *
   * @return {@code true} when the term is compiled as a pattern
   */
  public boolean isRegex() {
    return regex;
  }

  /**
   * Sets whether the search term is a regular expression.
   *
   * @param regex {@code true} to compile the term as a pattern
   */
  public void setRegex(boolean regex) {
    this.regex = regex;
    compilePattern();
  }

  /**
   * Whether the current search term compiles, so the UI can flag a broken expression.
   *
   * @return {@code true} when the term is usable, or when regex mode is off
   */
  public boolean isRegexValid() {
    return regexValid;
  }

  private void compilePattern() {
    if (!regex || text.isEmpty()) {
      pattern = null;
      regexValid = true;
      return;
    }
    try {
      pattern = Pattern.compile(text, Pattern.CASE_INSENSITIVE);
      regexValid = true;
    } catch (PatternSyntaxException e) {
      pattern = null;
      regexValid = false;
    }
  }

  /**
   * The status classes kept, as leading digits.
   *
   * @return the accepted classes, empty when every status is accepted
   */
  public Set<Integer> getStatusClasses() {
    return statusClasses;
  }

  /**
   * Adds or removes a status class from the accepted set.
   *
   * @param statusClass the leading digit of the response code, {@code 0} for non-numeric codes
   * @param accepted    {@code true} to keep rows in that class
   */
  public void setStatusClassAccepted(int statusClass, boolean accepted) {
    if (accepted) {
      statusClasses.add(statusClass);
    } else {
      statusClasses.remove(statusClass);
    }
  }

  /**
   * The protocols kept.
   *
   * @return the accepted protocols, empty when every protocol is accepted
   */
  public Set<String> getProtocols() {
    return protocols;
  }

  /**
   * Adds or removes a protocol from the accepted set.
   *
   * @param protocol the protocol as shown in the column, for instance {@code HTTP/2}
   * @param accepted {@code true} to keep rows using it
   */
  public void setProtocolAccepted(String protocol, boolean accepted) {
    if (accepted) {
      protocols.add(protocol);
    } else {
      protocols.remove(protocol);
    }
  }

  /**
   * Whether only failed samples are shown.
   *
   * @return {@code true} when successful samples are hidden
   */
  public boolean isErrorsOnly() {
    return errorsOnly;
  }

  /**
   * Sets whether only failed samples are shown.
   *
   * @param errorsOnly {@code true} to hide successful samples
   */
  public void setErrorsOnly(boolean errorsOnly) {
    this.errorsOnly = errorsOnly;
  }

  /**
   * The elapsed-time floor.
   *
   * @return the minimum elapsed time in milliseconds, {@code 0} when not filtering on duration
   */
  public long getMinTimeMs() {
    return minTimeMs;
  }

  /**
   * Sets the elapsed-time floor, for isolating the slow samples.
   *
   * @param minTimeMs the minimum elapsed time in milliseconds
   */
  public void setMinTimeMs(long minTimeMs) {
    this.minTimeMs = Math.max(0, minTimeMs);
  }
}
