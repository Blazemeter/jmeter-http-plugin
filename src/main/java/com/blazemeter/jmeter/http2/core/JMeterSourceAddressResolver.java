package com.blazemeter.jmeter.http2.core;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.SourceType;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the local address a sample must go out from - JMeter's "Source address" field, also
 * known as IP spoofing.
 *
 * <p>Port of {@code HTTPAbstractImpl.getIpSourceAddress()} plus the {@code httpclient.localaddress}
 * fallback that {@code HTTPHCAbstractImpl} reads. It has to be a port rather than a delegation
 * because that method is {@code protected} on the {@code HTTPAbstractImpl} hierarchy, which
 * {@code HTTP2Sampler} does not extend - it extends {@link HTTPSamplerBase} directly.
 *
 * <p>Precedence matches {@code HTTPHC4Impl.setupRequest}: the sampler's own field wins, the global
 * property is the fallback, and neither one means the OS picks the interface.
 */
public final class JMeterSourceAddressResolver {

  private static final String LOCAL_ADDRESS_PROPERTY = "httpclient.localaddress";

  private static final Logger LOG = LoggerFactory.getLogger(JMeterSourceAddressResolver.class);

  private JMeterSourceAddressResolver() {
  }

  /**
   * Returns the local address for {@code sampler}, or {@code null} to let the OS choose.
   *
   * @param sampler the sampler whose "Source address" field and type are read; its properties are
   *     already variable-expanded by the time a sample runs, so the value may differ per iteration
   * @return the address to bind outgoing connections to, or {@code null} when none applies
   * @throws UnknownHostException if the configured host name, IP or interface cannot be resolved,
   *     which is what HC4 propagates out of {@code setupRequest} to fail the sample
   * @throws SocketException if the interface list cannot be read
   */
  public static InetAddress resolve(HTTPSamplerBase sampler)
      throws UnknownHostException, SocketException {
    InetAddress fromSampler = resolveIpSource(sampler.getIpSource(), sampler.getIpSourceType());
    return fromSampler != null ? fromSampler : resolveLocalAddressProperty();
  }

  /**
   * Whether {@code sampler} asks for any source address at all, without resolving it.
   *
   * <p>Lets callers keep an unconfigured plan on the cheap path: no interface enumeration, and no
   * failure from a global property that a sampler-level field would have overridden anyway.
   */
  public static boolean isConfigured(HTTPSamplerBase sampler) {
    String ipSource = sampler.getIpSource();
    return (ipSource != null && !ipSource.trim().isEmpty())
        || !JMeterUtils.getPropDefault(LOCAL_ADDRESS_PROPERTY, "").isEmpty();
  }

  private static InetAddress resolveIpSource(String ipSource, int ipSourceType)
      throws UnknownHostException, SocketException {
    if (ipSource == null || ipSource.trim().isEmpty()) {
      return null; // did not want to spoof the IP address
    }
    Class<? extends InetAddress> ipClass;
    switch (SourceType.values()[ipSourceType]) {
      case DEVICE:
        ipClass = InetAddress.class;
        break;
      case DEVICE_IPV4:
        ipClass = Inet4Address.class;
        break;
      case DEVICE_IPV6:
        ipClass = Inet6Address.class;
        break;
      case HOSTNAME:
      default:
        return InetAddress.getByName(ipSource);
    }
    return firstInterfaceAddress(ipSource, ipClass);
  }

  private static InetAddress firstInterfaceAddress(String device,
                                                   Class<? extends InetAddress> ipClass)
      throws UnknownHostException, SocketException {
    NetworkInterface net = NetworkInterface.getByName(device);
    if (net == null) {
      throw new UnknownHostException("Cannot find interface " + device);
    }
    for (InterfaceAddress interfaceAddress : net.getInterfaceAddresses()) {
      InetAddress address = interfaceAddress.getAddress();
      if (ipClass.isInstance(address)) {
        return address;
      }
    }
    throw new UnknownHostException("Interface " + device
        + " does not have address of type " + ipClass.getSimpleName());
  }

  /**
   * HC4 logs a warning and carries on when this property does not resolve, so a typo degrades to
   * "let the OS choose" instead of breaking every sample in the plan. Only a sampler-level field
   * is allowed to fail a sample.
   */
  private static InetAddress resolveLocalAddressProperty() {
    String localHostOrIp = JMeterUtils.getPropDefault(LOCAL_ADDRESS_PROPERTY, "");
    if (localHostOrIp.isEmpty()) {
      return null;
    }
    try {
      return InetAddress.getByName(localHostOrIp);
    } catch (UnknownHostException e) {
      LOG.warn("Ignoring {}={}: {}", LOCAL_ADDRESS_PROPERTY, localHostOrIp,
          e.getLocalizedMessage());
      return null;
    }
  }
}
