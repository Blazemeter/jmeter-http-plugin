package com.blazemeter.jmeter.http2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.blazemeter.jmeter.http2.gui.BlazeMeterHttpMenuCommand;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Set;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.junit.Test;

public class PluginJavaRequirementsTest {

  @Test
  public void currentRuntimeIsAtLeastRequiredVersion() {
    assertTrue(
        PluginJavaRequirements.getRuntimeMajorVersion()
            >= PluginJavaRequirements.JAVA_VERSION_REQUIRED);
    assertTrue(PluginJavaRequirements.isRuntimeSupported());
  }

  @Test
  public void unsupportedMessageMentionsRequiredVersion() {
    String message = PluginJavaRequirements.unsupportedRuntimeMessage();
    assertTrue(message.contains("Java 17"));
    assertTrue(message.contains(PluginJavaRequirements.LOG_PREFIX));
  }

  @Test
  public void bootstrapClassIsJava8Bytecode() throws Exception {
    String resource =
        "com/blazemeter/jmeter/http2/gui/BlazeMeterHttpMenuCommandBootstrap.class";
    try (InputStream raw = getClass().getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(resource, raw);
      DataInputStream in = new DataInputStream(raw);
      in.readInt(); // magic
      in.readUnsignedShort(); // minor
      int major = in.readUnsignedShort();
      assertEquals(52, major);
    }
  }

  @Test
  public void menuCommandIsNotJmeterPluginType() {
    assertFalse(MenuCreator.class.isAssignableFrom(BlazeMeterHttpMenuCommand.class));
    assertFalse(Command.class.isAssignableFrom(BlazeMeterHttpMenuCommand.class));
  }

  @Test
  public void bootstrapExposesMenuActionsOnSupportedRuntime() throws Exception {
    if (!PluginJavaRequirements.isRuntimeSupported()) {
      return;
    }
    Class<?> bootstrapClass =
        Class.forName("com.blazemeter.jmeter.http2.gui.BlazeMeterHttpMenuCommandBootstrap");
    Object bootstrap = bootstrapClass.getDeclaredConstructor().newInstance();
    Method getActionNames = bootstrapClass.getMethod("getActionNames");
    @SuppressWarnings("unchecked")
    Set<String> names = (Set<String>) getActionNames.invoke(bootstrap);
    assertFalse(names.isEmpty());
  }
}
