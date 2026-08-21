package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.SourceType;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * JMeter's "Source address" field (IP spoofing) has four modes and a global fallback property, and
 * {@code HTTPHC4Impl} resolves them in a precise order before binding the socket. This pins the
 * port of that algorithm: the sampler field wins over {@code httpclient.localaddress}, a device
 * mode walks the interface list for an address of the requested family, and a name that cannot be
 * resolved throws instead of quietly falling back to the default interface.
 */
public class JMeterSourceAddressResolverTest extends HTTP2TestBase {

  private static final String LOCAL_ADDRESS_PROPERTY = "httpclient.localaddress";

  private String originalLocalAddressProperty;

  @Before
  public void setUp() {
    originalLocalAddressProperty = JMeterUtils.getProperty(LOCAL_ADDRESS_PROPERTY);
    JMeterUtils.getJMeterProperties().remove(LOCAL_ADDRESS_PROPERTY);
  }

  @After
  public void tearDown() {
    if (originalLocalAddressProperty == null) {
      JMeterUtils.getJMeterProperties().remove(LOCAL_ADDRESS_PROPERTY);
    } else {
      JMeterUtils.setProperty(LOCAL_ADDRESS_PROPERTY, originalLocalAddressProperty);
    }
  }

  @Test
  public void resolvesNothingWhenNeitherFieldNorPropertyIsSet() throws Exception {
    HTTP2Sampler sampler = new HTTP2Sampler();

    assertThat(JMeterSourceAddressResolver.isConfigured(sampler)).isFalse();
    assertThat(JMeterSourceAddressResolver.resolve(sampler)).isNull();
  }

  @Test
  public void resolvesTheFieldAsAHostNameByDefault() throws Exception {
    HTTP2Sampler sampler = samplerWith("127.0.0.1", SourceType.HOSTNAME);

    assertThat(JMeterSourceAddressResolver.isConfigured(sampler)).isTrue();
    assertThat(JMeterSourceAddressResolver.resolve(sampler))
        .isEqualTo(InetAddress.getByName("127.0.0.1"));
  }

  @Test
  public void fallsBackToTheLocalAddressProperty() throws Exception {
    JMeterUtils.setProperty(LOCAL_ADDRESS_PROPERTY, "127.0.0.1");
    HTTP2Sampler sampler = new HTTP2Sampler();

    assertThat(JMeterSourceAddressResolver.isConfigured(sampler)).isTrue();
    assertThat(JMeterSourceAddressResolver.resolve(sampler))
        .isEqualTo(InetAddress.getByName("127.0.0.1"));
  }

  @Test
  public void prefersTheSamplerFieldOverTheLocalAddressProperty() throws Exception {
    JMeterUtils.setProperty(LOCAL_ADDRESS_PROPERTY, "127.0.0.2");
    HTTP2Sampler sampler = samplerWith("127.0.0.1", SourceType.HOSTNAME);

    assertThat(JMeterSourceAddressResolver.resolve(sampler))
        .isEqualTo(InetAddress.getByName("127.0.0.1"));
  }

  @Test
  public void ignoresAnUnresolvableLocalAddressProperty() throws Exception {
    // HC4 logs and carries on rather than breaking every sample in the plan over a typo in a
    // global property; only a sampler-level field is allowed to fail a sample.
    JMeterUtils.setProperty(LOCAL_ADDRESS_PROPERTY, "no.such.host.invalid");
    HTTP2Sampler sampler = new HTTP2Sampler();

    assertThat(JMeterSourceAddressResolver.resolve(sampler)).isNull();
  }

  @Test
  public void resolvesTheLoopbackDeviceToAnIpv4Address() throws Exception {
    String loopbackDevice = loopbackDeviceName();
    assumeTrue("No named interface holds the loopback address here", loopbackDevice != null);
    HTTP2Sampler sampler = samplerWith(loopbackDevice, SourceType.DEVICE_IPV4);

    assertThat(JMeterSourceAddressResolver.resolve(sampler)).isInstanceOf(Inet4Address.class);
  }

  @Test
  public void resolvesTheLoopbackDeviceInAnyFamilyMode() throws Exception {
    String loopbackDevice = loopbackDeviceName();
    assumeTrue("No named interface holds the loopback address here", loopbackDevice != null);
    HTTP2Sampler sampler = samplerWith(loopbackDevice, SourceType.DEVICE);

    assertThat(JMeterSourceAddressResolver.resolve(sampler)).isNotNull();
  }

  @Test
  public void failsWhenTheConfiguredDeviceDoesNotExist() {
    HTTP2Sampler sampler = samplerWith("no-such-interface", SourceType.DEVICE);

    assertThatThrownBy(() -> JMeterSourceAddressResolver.resolve(sampler))
        .isInstanceOf(UnknownHostException.class)
        .hasMessageContaining("Cannot find interface no-such-interface");
  }

  @Test
  public void failsWhenTheConfiguredHostNameCannotBeResolved() {
    HTTP2Sampler sampler = samplerWith("no.such.host.invalid", SourceType.HOSTNAME);

    assertThatThrownBy(() -> JMeterSourceAddressResolver.resolve(sampler))
        .isInstanceOf(UnknownHostException.class);
  }

  private static HTTP2Sampler samplerWith(String ipSource, SourceType sourceType) {
    HTTP2Sampler sampler = new HTTP2Sampler();
    sampler.setIpSource(ipSource);
    sampler.setIpSourceType(sourceType.ordinal());
    return sampler;
  }

  private static String loopbackDeviceName() throws Exception {
    NetworkInterface loopback =
        NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
    return loopback == null ? null : loopback.getName();
  }
}
