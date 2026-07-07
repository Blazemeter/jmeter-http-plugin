package com.blazemeter.jmeter.http2.gui;

import com.blazemeter.jmeter.http2.PluginJavaRequirements;
import java.awt.event.ActionEvent;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import org.apache.jmeter.gui.action.AbstractActionWithNoRunningTest;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPI entry point compiled for Java 8 so JMeter can load it on older runtimes.
 * When Java 17+ is available, delegates to {@link BlazeMeterHttpMenuCommand};
 * otherwise logs once and exposes no Tools menu or actions.
 */
public class BlazeMeterHttpMenuCommandBootstrap
    extends AbstractActionWithNoRunningTest
    implements MenuCreator {

  private static final Logger LOG =
      LoggerFactory.getLogger(BlazeMeterHttpMenuCommandBootstrap.class);

  private static final String DELEGATE_CLASS_NAME =
      "com.blazemeter.jmeter.http2.gui.BlazeMeterHttpMenuCommand";

  private static volatile Object delegate;
  private static volatile boolean loadAttempted;
  private static volatile boolean unsupportedLogged;

  static {
    if (!PluginJavaRequirements.isRuntimeSupported()) {
      logUnsupportedRuntimeOnce();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public Set<String> getActionNames() {
    Object target = resolveDelegate();
    if (target == null) {
      return Collections.emptySet();
    }
    return invokeDelegate(target, "getActionNames", Set.class);
  }

  @Override
  protected void doActionAfterCheck(ActionEvent event) {
    Object target = resolveDelegate();
    if (target == null) {
      return;
    }
    try {
      Method method = target.getClass().getMethod("doActionAfterCheck", ActionEvent.class);
      method.invoke(target, event);
    } catch (ReflectiveOperationException e) {
      LOG.error("BlazeMeter HTTP menu action failed", e);
    }
  }

  @Override
  public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
    Object target = resolveDelegate();
    if (target == null) {
      return new JMenuItem[0];
    }
    try {
      Method method =
          target.getClass().getMethod("getMenuItemsAtLocation", MENU_LOCATION.class);
      return (JMenuItem[]) method.invoke(target, location);
    } catch (ReflectiveOperationException e) {
      LOG.error("BlazeMeter HTTP menu delegate call failed: getMenuItemsAtLocation", e);
      return new JMenuItem[0];
    }
  }

  @Override
  public JMenu[] getTopLevelMenus() {
    Object target = resolveDelegate();
    if (target == null) {
      return new JMenu[0];
    }
    return invokeDelegate(target, "getTopLevelMenus", JMenu[].class);
  }

  @Override
  public boolean localeChanged(MenuElement menu) {
    Object target = resolveDelegate();
    if (target == null) {
      return false;
    }
    try {
      Method method = target.getClass().getMethod("localeChanged", MenuElement.class);
      return (Boolean) method.invoke(target, menu);
    } catch (ReflectiveOperationException e) {
      LOG.error("BlazeMeter HTTP menu delegate call failed: localeChanged", e);
      return false;
    }
  }

  @Override
  public void localeChanged() {
    Object target = resolveDelegate();
    if (target == null) {
      return;
    }
    invokeDelegateVoid(target, "localeChanged");
  }

  private static Object resolveDelegate() {
    if (!PluginJavaRequirements.isRuntimeSupported()) {
      return null;
    }
    Object loaded = delegate;
    if (loaded != null) {
      return loaded;
    }
    synchronized (BlazeMeterHttpMenuCommandBootstrap.class) {
      loaded = delegate;
      if (loaded != null) {
        return loaded;
      }
      if (loadAttempted) {
        return null;
      }
      loadAttempted = true;
      try {
        Class<?> delegateClass = Class.forName(DELEGATE_CLASS_NAME);
        loaded = delegateClass.getDeclaredConstructor().newInstance();
        delegate = loaded;
      } catch (UnsupportedClassVersionError e) {
        LOG.warn(PluginJavaRequirements.unsupportedRuntimeMessage(), e);
      } catch (ReflectiveOperationException | LinkageError e) {
        LOG.error(
            PluginJavaRequirements.LOG_PREFIX
                + "Could not initialize BlazeMeter HTTP menu command",
            e);
      }
      return delegate;
    }
  }

  private static void logUnsupportedRuntimeOnce() {
    if (unsupportedLogged) {
      return;
    }
    unsupportedLogged = true;
    LOG.warn(PluginJavaRequirements.unsupportedRuntimeMessage());
  }

  private static void invokeDelegateVoid(Object target, String methodName, Object... args) {
    try {
      Class<?>[] paramTypes = parameterTypes(args);
      Method method = target.getClass().getMethod(methodName, paramTypes);
      method.invoke(target, args);
    } catch (ReflectiveOperationException e) {
      LOG.error("BlazeMeter HTTP menu delegate call failed: {}", methodName, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T invokeDelegate(
      Object target, String methodName, Class<T> returnType, Object... args) {
    try {
      Class<?>[] paramTypes = parameterTypes(args);
      Method method = target.getClass().getMethod(methodName, paramTypes);
      return returnType.cast(method.invoke(target, args));
    } catch (ReflectiveOperationException e) {
      LOG.error("BlazeMeter HTTP menu delegate call failed: {}", methodName, e);
      if (returnType == Boolean.TYPE) {
        return (T) Boolean.FALSE;
      }
      if (returnType.isArray()) {
        return (T) java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
      }
      if (Set.class.equals(returnType)) {
        return (T) Collections.emptySet();
      }
      return null;
    }
  }

  private static Class<?>[] parameterTypes(Object[] args) {
    if (args == null || args.length == 0) {
      return new Class<?>[0];
    }
    Class<?>[] types = new Class<?>[args.length];
    for (int i = 0; i < args.length; i++) {
      types[i] = args[i].getClass();
    }
    return types;
  }
}
