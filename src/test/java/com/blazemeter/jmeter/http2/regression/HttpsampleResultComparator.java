package com.blazemeter.jmeter.http2.regression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Semantic diff of two JMeter sample trees (ignores timings and volatile headers). */
public final class HttpsampleResultComparator {

  private static final Set<String> IGNORED_RESPONSE_HEADER_NAMES = new HashSet<>(Arrays.asList(
      "date", "server", "connection", "transfer-encoding", "keep-alive",
      "content-length", "alt-svc", "via", "x-firefox-spdy", "x-powered-by",
      "cf-ray", "age", "cf-cache-status", "accept-ranges", "set-cookie"));

  /** Headers compared between ref/plugin runs; others vary by timing or CDN edge. */
  private static final Set<String> COMPARED_RESPONSE_HEADER_NAMES = new HashSet<>(Arrays.asList(
      "content-encoding", "content-type", "location"));

  private static final Pattern EMBEDDED_FILE_LABEL_INDEX =
      Pattern.compile("^(?:file:.+|.+)-(\\d+)$");

  private HttpsampleResultComparator() {
  }

  public static ComparisonResult compare(List<SampleRecord> reference,
      List<SampleRecord> actual) {
    return compare(reference, actual, false);
  }

  public static ComparisonResult compare(List<SampleRecord> reference,
      List<SampleRecord> actual, boolean tolerateExternalServiceDrift) {
    List<String> differences = new ArrayList<>();
    compareLists(reference, actual, "", differences, tolerateExternalServiceDrift);
    return new ComparisonResult(differences.isEmpty(), differences);
  }

  private static void compareLists(List<SampleRecord> reference, List<SampleRecord> actual,
      String path, List<String> differences, boolean tolerateExternalServiceDrift) {
    if (reference.size() != actual.size()) {
      differences.add(path + "sample count: expected " + reference.size()
          + " but was " + actual.size());
      int limit = Math.min(reference.size(), actual.size());
      for (int i = 0; i < limit; i++) {
        compareSample(reference.get(i), actual.get(i), path + "[" + i + "].", differences,
            tolerateExternalServiceDrift);
      }
      return;
    }
    for (int i = 0; i < reference.size(); i++) {
      compareSample(reference.get(i), actual.get(i), path + "[" + i + "].", differences,
          tolerateExternalServiceDrift);
    }
  }

  private static void compareSample(SampleRecord expected, SampleRecord actual, String path,
      List<String> differences, boolean tolerateExternalServiceDrift) {
    if (!labelsEquivalent(expected.getLabel(), actual.getLabel())) {
      differences.add(path + "label: expected '" + expected.getLabel()
          + "' but was '" + actual.getLabel() + "'");
    }
    if (tolerateExternalServiceDrift && isExternalServiceDrift(expected, actual)) {
      return;
    }
    if (expected.isSuccessful() != actual.isSuccessful()) {
      differences.add(path + "success: expected " + expected.isSuccessful()
          + " but was " + actual.isSuccessful()
          + " (code=" + actual.getResponseCode() + " msg=" + actual.getResponseMessage() + ")");
    }
    if (!normalizeResponseCode(expected.getResponseCode())
        .equals(normalizeResponseCode(actual.getResponseCode()))) {
      differences.add(path + "responseCode: expected " + expected.getResponseCode()
          + " but was " + actual.getResponseCode());
    }
    if (!normalizeMessage(expected.getResponseMessage())
        .equals(normalizeMessage(actual.getResponseMessage()))) {
      differences.add(path + "responseMessage: expected '" + expected.getResponseMessage()
          + "' but was '" + actual.getResponseMessage() + "'");
    }
    if (!normalizeBody(expected.getResponseData()).equals(normalizeBody(actual.getResponseData()))) {
      differences.add(path + "responseData differs for label '" + expected.getLabel() + "'");
    }
    Map<String, String> expectedHeaders = normalizeHeaders(expected.getResponseHeaders());
    Map<String, String> actualHeaders = normalizeHeaders(actual.getResponseHeaders());
    for (Map.Entry<String, String> entry : expectedHeaders.entrySet()) {
      String key = entry.getKey();
      if (!actualHeaders.containsKey(key)) {
        differences.add(path + "missing response header '" + key + "'");
      } else if (!entry.getValue().equals(actualHeaders.get(key))) {
        differences.add(path + "response header '" + key + "': expected '" + entry.getValue()
            + "' but was '" + actualHeaders.get(key) + "'");
      }
    }
    compareLists(expected.getChildren(), actual.getChildren(), path + "children.",
        differences, tolerateExternalServiceDrift);
  }

  /** One run saw a transient upstream 5xx while the other succeeded (or vice versa). */
  private static boolean isExternalServiceDrift(SampleRecord expected, SampleRecord actual) {
    int expectedCode = parseHttpStatus(expected.getResponseCode());
    int actualCode = parseHttpStatus(actual.getResponseCode());
    if (expectedCode < 0 || actualCode < 0) {
      return false;
    }
    boolean expectedTransient = expectedCode >= 502 && expectedCode <= 504;
    boolean actualTransient = actualCode >= 502 && actualCode <= 504;
    boolean expectedOk = expectedCode >= 200 && expectedCode < 300;
    boolean actualOk = actualCode >= 200 && actualCode < 300;
    boolean expectedRedirect = expectedCode >= 301 && expectedCode < 400;
    boolean actualRedirect = actualCode >= 301 && actualCode < 400;
    return (expectedTransient && (actualOk || actualRedirect))
        || (actualTransient && (expectedOk || expectedRedirect));
  }

  private static int parseHttpStatus(String code) {
    if (code == null || code.isBlank()) {
      return -1;
    }
    try {
      return Integer.parseInt(code.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static String normalizeResponseCode(String code) {
    if (code == null) {
      return "";
    }
    if (code.contains("ConnectException")) {
      return "connect-failure";
    }
    return code;
  }

  private static String normalizeMessage(String message) {
    if (message == null) {
      return "";
    }
    String normalized = message.trim();
    if (normalized.contains("Connection refused")) {
      return "connection-refused";
    }
    return normalized;
  }

  private static String normalizeBody(String body) {
    if (body == null) {
      return "";
    }
    return body.replace("\r\n", "\n").trim();
  }

  private static Map<String, String> normalizeHeaders(String raw) {
    Map<String, String> headers = new HashMap<>();
    if (raw == null || raw.trim().isEmpty()) {
      return headers;
    }
    String[] lines = raw.replace("\r\n", "\n").split("\n");
    for (String line : lines) {
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      if (IGNORED_RESPONSE_HEADER_NAMES.contains(name)
          || !COMPARED_RESPONSE_HEADER_NAMES.contains(name)) {
        continue;
      }
      String value = line.substring(colon + 1).trim();
      headers.put(name, value);
    }
    return headers;
  }

  /**
   * HttpClient4 labels file embedded resources as {@code file:<path>-N}; the plugin may use the
   * parent sampler name. The numeric suffix is the stable identity for parity checks.
   */
  private static boolean labelsEquivalent(String expected, String actual) {
    if (expected.equals(actual)) {
      return true;
    }
    Matcher expectedIndex = EMBEDDED_FILE_LABEL_INDEX.matcher(expected);
    Matcher actualIndex = EMBEDDED_FILE_LABEL_INDEX.matcher(actual);
    return expectedIndex.matches() && actualIndex.matches()
        && expectedIndex.group(1).equals(actualIndex.group(1));
  }

  public static final class ComparisonResult {
    private final boolean equal;
    private final List<String> differences;

    ComparisonResult(boolean equal, List<String> differences) {
      this.equal = equal;
      this.differences = differences;
    }

    public boolean isEqual() {
      return equal;
    }

    public List<String> getDifferences() {
      return differences;
    }

    public String formattedDiff() {
      return differences.stream().collect(Collectors.joining(System.lineSeparator()));
    }
  }
}
