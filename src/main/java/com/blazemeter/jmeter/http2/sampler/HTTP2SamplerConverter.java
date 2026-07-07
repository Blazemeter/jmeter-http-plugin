package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.sampler.gui.HTTP2SamplerGui;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.mapper.Mapper;
import java.util.Arrays;
import java.util.List;
import javax.swing.JOptionPane;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.save.converters.TestElementConverter;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.visualizers.ViewResultsFullVisualizer;

public class HTTP2SamplerConverter extends TestElementConverter {

  private static final String GUI_CLASS = "guiclass";
  private static final String TEST_PLAN_GUI_CLASS = "TestPlanGui";
  private static final String HTTP2_REQUEST_GUI_CLASS = "com.blazemeter.jmeter.http2.sampler.gui"
      + ".HTTP2RequestGui";
  private static final String HTTP2_DEFAULT_GUI_CLASS = "com.blazemeter.jmeter.http2.sampler.gui"
      + ".Http2DefaultsGui";
  private static final String HTTP2_VIEW_RESULTS_GUI_CLASS = "com.blazemeter.jmeter.http2"
      + ".visualizers.ViewResultsFullVisualizer";
  private boolean isConverting = false;

  public HTTP2SamplerConverter(Mapper arg0) {
    super(arg0);
  }

  @Override
  public boolean canConvert(Class elementClass) {
    return HTTP2Request.class.isAssignableFrom(elementClass) || ConfigTestElement.class
        .isAssignableFrom(elementClass) || ResultCollector.class.isAssignableFrom(elementClass)
        || TestPlan.class.isAssignableFrom(elementClass);
  }

  @Override
  public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
    String guiClassName = reader.getAttribute(GUI_CLASS);
    List<String> oldGuiClasses = Arrays.asList(HTTP2_REQUEST_GUI_CLASS, HTTP2_DEFAULT_GUI_CLASS,
        HTTP2_VIEW_RESULTS_GUI_CLASS);
    if (guiClassName.equals(TEST_PLAN_GUI_CLASS)) {
      isConverting = false;
      return super.unmarshal(reader, context);
    } else if (oldGuiClasses.contains(guiClassName)) {
      if (!isConverting) {
        isConverting = shouldUpdateTestPlanMessage();
      }
      if (isConverting) {
        TestElement testElement = (TestElement) super.unmarshal(reader, context);
        if (guiClassName.equals(HTTP2_REQUEST_GUI_CLASS) || guiClassName
            .equals(HTTP2_DEFAULT_GUI_CLASS)) {
          return convertSampler(testElement);
        } else {
          return convertViewResult(
              (com.blazemeter.jmeter.http2.visualizers.ResultCollector) testElement);
        }
      } else {
        throw new UnsupportedOperationException("This test plan can not be loaded "
            + "since it is created with an old version of the HTTP2 plugins.");
      }

    }
    return super.unmarshal(reader, context);
  }

  private static ResultCollector convertViewResult(
      com.blazemeter.jmeter.http2.visualizers.ResultCollector resultCollector) {
    resultCollector.setProperty(TestElement.GUI_CLASS, ViewResultsFullVisualizer.class.getName());
    resultCollector.setProperty(TestElement.TEST_CLASS, ResultCollector.class.getName());
    return resultCollector;

  }

  private static TestElement convertSampler(TestElement testElement) {
    TestElement testElementReturn;
    if (testElement instanceof HTTP2Request) {
      testElementReturn = new HTTP2Sampler();
      testElementReturn
          .setProperty(new StringProperty(TestElement.GUI_CLASS, HTTP2SamplerGui.class.getName()));
      testElementReturn
          .setProperty(new StringProperty(TestElement.TEST_CLASS, HTTP2Sampler.class.getName()));
    } else if (testElement instanceof ConfigTestElement) {
      testElementReturn = new ConfigTestElement();
      testElementReturn.setProperty(TestElement.GUI_CLASS, HttpDefaultsGui.class.getName());
      testElementReturn.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
    } else {
      throw new UnsupportedOperationException(
          String.format("Error while convert class %s", testElement.getClass()));
    }
    testElementReturn.setComment(testElement.getComment());
    testElementReturn.setName(testElement.getName());
    testElementReturn.setEnabled(testElement.isEnabled());
    testElementReturn.setProperty(HTTPSamplerBase.POST_BODY_RAW,
        testElement.getPropertyAsBoolean(HTTP2Request.POST_BODY_RAW, false),
        HTTPSamplerBase.POST_BODY_RAW_DEFAULT);
    Arguments arguments =
        (Arguments) testElement.getProperty(HTTP2Request.ARGUMENTS).getObjectValue();
    if (arguments == null) {
      arguments = new Arguments();
    }
    testElementReturn.setProperty(
        new TestElementProperty(HTTPSamplerBase.ARGUMENTS, arguments));
    testElementReturn.setProperty(HTTPSamplerBase.DOMAIN,
        testElement.getPropertyAsString(HTTP2Request.DOMAIN, ""));
    testElementReturn
        .setProperty(HTTPSamplerBase.PORT, testElement.getPropertyAsString(HTTP2Request.PORT, ""));
    testElementReturn.setProperty(HTTPSamplerBase.RESPONSE_TIMEOUT,
        testElement.getPropertyAsString(HTTP2Request.RESPONSE_TIMEOUT, ""));
    testElementReturn.setProperty(HTTPSamplerBase.PROTOCOL,
        testElement.getPropertyAsString(HTTP2Request.PROTOCOL, ""));
    testElementReturn.setProperty(HTTPSamplerBase.CONTENT_ENCODING,
        testElement.getPropertyAsString(HTTP2Request.CONTENT_ENCODING, ""));
    testElementReturn
        .setProperty(HTTPSamplerBase.PATH, testElement.getPropertyAsString(HTTP2Request.PATH, ""));
    testElementReturn.setProperty(HTTPSamplerBase.METHOD,
        testElement.getPropertyAsString(HTTP2Request.METHOD, ""));
    testElementReturn.setProperty(HTTPSamplerBase.FOLLOW_REDIRECTS,
        testElement.getPropertyAsBoolean(HTTP2Request.FOLLOW_REDIRECTS, false));
    testElementReturn.setProperty(HTTPSamplerBase.AUTO_REDIRECTS,
        testElement.getPropertyAsBoolean(HTTP2Request.AUTO_REDIRECTS, false));
    testElementReturn.setProperty(HTTPSamplerBase.IMAGE_PARSER,
        testElement.getPropertyAsBoolean(HTTP2Request.EMBEDDED_RESOURCES, false));
    testElementReturn.setProperty(HTTPSamplerBase.EMBEDDED_URL_RE,
        testElement.getPropertyAsString(HTTP2Request.EMBEDDED_URL_REGEX, ""));
    return testElementReturn;
  }

  private boolean shouldUpdateTestPlanMessage() {
    return JOptionPane.showConfirmDialog(null, "Your test plan is not compatible with this "
            + "version of "
            + "the plugin, do you want to migrate your test plan to the new version? (If you "
            + "migrate your test plan it will not be able to open with a lower version of the "
            + "plugin)",
        "bzm HTTP2 Sampler plugin - test plan update?", JOptionPane.YES_NO_OPTION,
        JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
  }
}
