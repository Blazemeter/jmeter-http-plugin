# HTTP debug notes

This page documents toggles for diagnosing the BlazeMeter HTTP client.

## Turning on trace logs

Enable frame-level tracing and optional file logging:

```bash
java -Dblazemeter.http.lowLevelLog=true ...
```

Tracing stays **disabled by default**: it adds overhead and I/O, so a single flag (`blazemeter.http.lowLevelLog`) gates all low-level HTTP diagnostics.

## JVM diagnostic properties (reference)

| **Attribute** | **Description** | **Default** |
|---|---|---:|
| **blazemeter.http.lowLevelLog** | Enables low-level HTTP/2/3 client tracing (may write under **`target/`**, e.g. **`http2-debug.log`**) | false |
| **blazemeter.http.skipHttp2Settings** | Skips custom HTTP/2 SETTINGS tuning in the client | false |
| **blazemeter.http.directSend** | Uses Jetty **`request.send()`** directly (diagnostic; bypasses listener path) | false |
| **blazemeter.http.disableBrotliDecoder** | Disables Brotli content decoder registration | false |
| **blazemeter.http.disableZstdDecoder** | Disables Zstandard content decoder registration | false |
| **blazemeter.http.disableGzipDecoder** | Disables gzip content decoder registration | false |
| **blazemeter.http.disableDeflateDecoder** | Disables deflate content decoder registration | false |
| **blazemeter.http.skipManualDecodeWhenAdvertised** | Skips redundant manual body decode when **`Content-Encoding`** is already advertised | true |

## JMeter diagnostic properties (reference)

Recording / proxy tracing uses normal JMeter properties instead — see **[README § Properties](../README.md#properties)** (`blazemeter.http.debugProxyRecorderTypeUi`).
