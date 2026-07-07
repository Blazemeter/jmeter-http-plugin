package com.blazemeter.jmeter.http2;

import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import org.junit.BeforeClass;

/**
 * The purpose of this class is to instantiate a JMeter environment for the whole test suite case.
 * It's mandatory to extend this class for every new test class created.
 *
 * Reason:
 * HTTP2Sampler class contains a static initializer (static {...}) which runs when the class is
 * loaded by the JVM. In other words it executes the static initializer once when the class is
 * first referenced or accessed.
 * As an example, the class HTTP2ControllerTest uses the HTTP2Sampler class without setting up the
 * JMeter environment (because it's not needed) therefore, when the static initializer code runs,
 * it's not possible to retrieve some JMeterProperties like ´HTTPResponse.parsers´ (result stored
 * in RESPONSE_PARSERS). Since the static initializer runs only once even if we set up the JMeter
 * environment for tests who require RESPONSE_PARSERS like HTTP2JettyClientTest it will be too
 * late since the static initializer already run.
 *
 * Issue manifestation:
 * This solution was discovered when running the whole suite of tests and some tests will fail.
 * Even though, running the failed tests separately or even the whole class which contains the
 * failing tests will actually pass. The problem was when running the whole suite of tests.
 *
 * Other possible solutions:
 * In case this solution is not viable in the long term:
 * There is another workaround for this problem. Use separated JVMs for each test class.
 * By modifying the sure-file plugin configuration to something like:
 *       <plugin>
 *         <groupId>org.apache.maven.plugins</groupId>
 *         <artifactId>maven-surefire-plugin</artifactId>
 *         <version>2.22.2</version>
 *         <configuration>
 *           <forkCount>1</forkCount>
 *           <reuseForks>false</reuseForks>
 *         </configuration>
 *       </plugin>
 * Link below:
 * <a href="https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html#forked-test-execution">See Here</a>
 *
 */
public class HTTP2TestBase {

  @BeforeClass
  public static void once() {
    JMeterTestUtils.setupJmeterEnv();
  }
}
