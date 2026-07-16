package com.blazemeter.jmeter.http2.core;

import java.lang.reflect.Field;
import org.apache.jmeter.util.JMeterUtils;
import sun.misc.Unsafe;

/**
 * JMeter snapshots {@code cache_manager.cached_resource_mode} in static final fields on
 * {@code HTTPAbstractImpl} class init. Tests that change the property must refresh that snapshot.
 */
public final class JmeterCachedResourceModeSupport {

  private static final String HTTP_ABSTRACT_IMPL =
      "org.apache.jmeter.protocol.http.sampler.HTTPAbstractImpl";
  private static final String CACHED_RESOURCE_MODE_ENUM =
      "org.apache.jmeter.protocol.http.sampler.HTTPAbstractImpl$CachedResourceMode";

  private JmeterCachedResourceModeSupport() {
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void refreshSnapshotFromProperties() throws ReflectiveOperationException {
    Class<?> abstractImpl = Class.forName(HTTP_ABSTRACT_IMPL);
    Class<Enum> modeEnum = (Class<Enum>) Class.forName(CACHED_RESOURCE_MODE_ENUM);
    String modeName = JMeterUtils.getPropDefault("cache_manager.cached_resource_mode",
        "RETURN_NO_SAMPLE");
    setStaticFinal(abstractImpl, "CACHED_RESOURCE_MODE",
        Enum.valueOf(modeEnum, modeName));
    setStaticFinal(abstractImpl, "RETURN_200_CACHE_MESSAGE",
        JMeterUtils.getPropDefault("RETURN_200_CACHE.message", "(ex cache)"));
    setStaticFinal(abstractImpl, "RETURN_CUSTOM_STATUS_CODE",
        JMeterUtils.getProperty("RETURN_CUSTOM_STATUS.code"));
    setStaticFinal(abstractImpl, "RETURN_CUSTOM_STATUS_MESSAGE",
        JMeterUtils.getPropDefault("RETURN_CUSTOM_STATUS.message", "(ex cache)"));
  }

  private static void setStaticFinal(Class<?> clazz, String name, Object value)
      throws ReflectiveOperationException {
    Field field = clazz.getDeclaredField(name);
    field.setAccessible(true);
    Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    Unsafe unsafe = (Unsafe) unsafeField.get(null);
    Object base = unsafe.staticFieldBase(field);
    long offset = unsafe.staticFieldOffset(field);
    unsafe.putObject(base, offset, value);
  }
}
