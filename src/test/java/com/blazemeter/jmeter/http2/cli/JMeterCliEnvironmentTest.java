package com.blazemeter.jmeter.http2.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Path;
import org.junit.Test;

public class JMeterCliEnvironmentTest {

  @Test
  public void resolvesHomeWhenJarIsUnderLibExt() {
    Path home = Path.of("jmeter-home", "apache-jmeter-5.6.3");
    File jar = home.resolve("lib").resolve("ext").resolve("jmeter-bzm-http2.jar").toFile();
    assertEquals(home.toFile().getAbsolutePath(), JMeterCliEnvironment.homeFromExtDirJar(jar));
  }

  @Test
  public void resolvesHomeWhenExtDirCasingDiffers() {
    Path home = Path.of("jmeter-home", "jmeter");
    File jar = home.resolve("LIB").resolve("EXT").resolve("plugin.jar").toFile();
    assertEquals(home.toFile().getAbsolutePath(), JMeterCliEnvironment.homeFromExtDirJar(jar));
  }

  @Test
  public void returnsNullWhenNotUnderLibExt() {
    File jar = Path.of("some-build-dir", "target", "classes").toFile();
    assertNull(JMeterCliEnvironment.homeFromExtDirJar(jar));
  }

  @Test
  public void returnsNullForNullJarFile() {
    assertNull(JMeterCliEnvironment.homeFromExtDirJar(null));
  }

  @Test
  public void returnsNullWhenParentChainIsTooShort() {
    File jar = Path.of("ext", "plugin.jar").toFile();
    assertNull(JMeterCliEnvironment.homeFromExtDirJar(jar));
  }
}
