package com.blazemeter.jmeter.http2.regression;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Loads JMeter XML sample logs (JTL or Result Collector output). */
public final class JtlSampleLoader {

  private JtlSampleLoader() {
  }

  public static List<SampleRecord> load(File xmlFile) throws Exception {
    if (!xmlFile.isFile()) {
      throw new IOException("Sample file not found: " + xmlFile);
    }
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.parse(xmlFile);
    Element root = doc.getDocumentElement();
    List<SampleRecord> samples = new ArrayList<>();
  if ("testResults".equals(root.getTagName())) {
      collectTopLevelSamples(root, samples);
    } else if (isSampleElement(root)) {
      samples.add(parseSample(root));
    } else {
      collectTopLevelSamples(root, samples);
    }
    return samples;
  }

  private static void collectTopLevelSamples(Element parent, List<SampleRecord> out) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node instanceof Element && isSampleElement((Element) node)) {
        out.add(parseSample((Element) node));
      }
    }
  }

  private static boolean isSampleElement(Element element) {
    String tag = element.getTagName();
    return "httpSample".equals(tag)
        || "sample".equals(tag)
        || "sampleResult".equals(tag);
  }

  private static SampleRecord parseSample(Element element) {
    String label = element.getAttribute("lb");
    if (label.isEmpty()) {
      label = element.getAttribute("label");
    }
    boolean success = Boolean.parseBoolean(element.getAttribute("s"));
    String rc = element.getAttribute("rc");
    String rm = element.getAttribute("rm");
    String responseData = childText(element, "responseData");
    String responseHeaders = childText(element, "responseHeader");
    if (responseHeaders.isEmpty()) {
      responseHeaders = childText(element, "responseHeaders");
    }

    List<SampleRecord> sub = new ArrayList<>();
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node instanceof Element && isSampleElement((Element) node)) {
        sub.add(parseSample((Element) node));
      }
    }
    return new SampleRecord(label, success, rc, rm, responseData, responseHeaders, sub);
  }

  private static String childText(Element parent, String tagName) {
    NodeList nodes = parent.getElementsByTagName(tagName);
    if (nodes.getLength() == 0) {
      return "";
    }
    Node node = nodes.item(0);
    return node.getTextContent() == null ? "" : node.getTextContent();
  }

  static String readTextFile(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }
}
