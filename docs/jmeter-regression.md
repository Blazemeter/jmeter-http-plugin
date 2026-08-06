# JMeter HTTP regression parity tests

This suite compares Apache JMeter **HttpClient4** (reference) with the migrated **BlazeMeter HTTP** sampler (`HTTP2Sampler`) using official JMeter `bin/testfiles` plans from release **5.6.3**.

## What it does

1. Runs the stock JMX with `-Jjmeter.httpsampler=HttpClient4` (no plugin).
2. Migrates HTTP Request samplers headlessly via `JmxBlazeMeterHttpMigrator`.
3. Runs the migrated plan with the plugin installed under `lib/ext/`.
4. Compares sample trees semantically (`HttpsampleResultComparator`): labels, success, response codes/messages, bodies, and normalized response headers. Timings and volatile headers are ignored.

## Regression groups

These groups are this project's own way of organizing which JMeter `bin/testfiles` plans run in which CI stage — not an Apache JMeter classification. All vendored plans (including the ones below) come from the same upstream source (see [Resources](#resources)).

| Group | Maven | Plans | Notes |
|-------|-------|-------|-------|
| **Core** (`tier=core`) | `-Pjmeter-regression` | `TEST_HTTP`, `ResponseDecompression`, `TestHeaderManager`, `TestCookieManager` | Default CI; mirror + managers |
| **Extended — HTTPS/Auth/HTML** (`tier=extended`) | `-Pjmeter-regression-extended` | `HTMLParserTestFile_2`, `TEST_HTTPS`, `Http4ImplDigestAuth`, `Http4ImplPreemptiveBasicAuth` | HTTPS + embedded HTML + auth |
| **External services** (`tier=external`) | `-Pjmeter-regression-external` | `TestKeepAlive`, `TestRedirectionPolicies` | Calls real third-party hosts; opt-in only, not in default CI |
| **JUnit parity** | `mvn test -Dtest=com.blazemeter.jmeter.http2.parity.*` | JUnit ports of Apache HTTP module tests | HttpClient4 vs plugin in-process; HTTP/1.1 — see [below](#junit-parity-apache-srcprotocolhttp-tests) |

`BUG_62847` / `Bug54685` are vendored under `5.6.3/` but exercise JMeter controllers/Java samplers only — not HTTP plugin parity.

`SlowCharsFeature` (optional): run with `-Djmeter.regression.tests=SlowCharsFeature -Djmeter.regression.enableSlowChars=true` once Jetty supports HttpClient4-style **CPS** throttling (`httpclient.socket.*.cps`). See [SlowCharsFeature](#slowcharsfeature) below.

### SlowCharsFeature

Apache batch plan `SlowCharsFeature.jmx`:

1. A setup thread sets `httpclient.socket.http.cps` and `httpclient.socket.https.cps` to **1500** (bytes per second).
2. The main sampler requests `https://jmeter.apache.org/...` with `Range: bytes=0-7000` (partial content).
3. Assertions expect **HTTP 206**, ~7001 bytes, and **elapsed time &gt; 5s** (download throttled to ~1.5 KB/s).

HttpClient4 implements CPS by wrapping socket streams with a rate limiter (`org.apache.http.impl.conn.SocketFactory` / connection socket config). **Jetty has no equivalent property**: you would need a custom `ClientConnector` / `EndPoint` wrapper that throttles `fill()`/`flush()` per connection, and decide how CPS applies across HTTP/2 multiplexing and HTTP/3 QUIC streams (HC4's model is per-TCP-socket). Until that exists, the plan stays behind `-Djmeter.regression.enableSlowChars=true`.

`TestKeepAlive` is compared on **http1-only** only (HttpClient4 never speaks HTTP/2; keep-alive / `Connection: close` are HTTP/1.1 semantics).

Use `-Djmeter.regression.tier=extended|external|all` instead of a profile, or override with `-Djmeter.regression.tests=PlanA,PlanB`.

## Prerequisites

- **Java 17+**
- **Maven 3.6+**
- Network access (first run downloads JMeter 5.6.3; Core/Extended plans may call external hosts).

## Build the plugin JAR

```bash
mvn -Dcheckstyle.skip=true package
```

## Run regression tests

```bash
mvn -Pjmeter-regression verify
```

### Options

| Property | Default | Description |
|----------|---------|-------------|
| `jmeter.regression` | `false` (`true` with `-Pjmeter-regression*`) | Must be `true` to execute regression ITs |
| `jmeter.regression.tests` | Core group list (see `pom.xml`) | Comma-separated JMX base names (no `.jmx`) |
| `jmeter.regression.tier` | _(unset)_ | `core`, `extended`, `external`, or `all` when `jmeter.regression.tests` is unset |
| `jmeter.regression.version` | `5.6.3` | JMeter distribution version to download |
| `jmeter.home` | _(auto)_ | Use an existing JMeter install instead of downloading |
| `it.http3` | `false` | Also run each plan with HTTP/3 profile |
| `jmeter.regression.protocol` | _(all profiles)_ | Filter: `http1-only`, `http2`, or `http3` (with `it.http3`) |
| `jmeter.regression.timeoutMinutes` | `10` | Per-run timeout |
| `jmeter.regression.tolerateExternalServiceDrift` | `false` (auto for Extended auth + External-services plans) | Skip strict compare when ref/plugin saw 5xx vs 2xx on the same sample (sequential runs vs flaky hosts) |

### Examples

Core group only, HTTP/1.1 profile:

```bash
mvn -Pjmeter-regression -Djmeter.regression.protocol=http1-only verify
```

Extended group — start with local HTML parser (no network):

```bash
mvn -Pjmeter-regression-extended -Djmeter.regression.tests=HTMLParserTestFile_2 verify
```

Full Extended group:

```bash
mvn -Pjmeter-regression-extended verify
```

External-services / optional batch plans:

```bash
mvn -Pjmeter-regression-external verify
```

### JUnit parity (Apache `src/protocol/http` tests)

Runs in the normal unit-test phase (`mvn test`), not Failsafe. Each test executes the same sampler configuration through **HttpClient4** and **HTTP2JettyClient** (HTTP/1.1 forced).

| Class | Apache source |
|-------|----------------|
| `HttpMirrorParityTest` | `TestHTTPSamplersAgainstHttpMirrorServer` (GET/PUT, POST urlencoded/multipart/raw, file upload) |
| `HttpMirrorItemisedParityTest` | same source — `itemised_testPostRequest_UrlEncoded` (0–7), `itemised_testGetRequest_Parameters` (0–5) |
| `HttpMirrorMultipartParityTest` | same source — `testPostRequest_FormMultipart` (0–6) |
| `HttpMirrorRawBodyParityTest` | same source — `testPostRequest_BodyFromParameterValues` (0–9) |
| `HttpMirrorFileUploadParityTest` | same source — `testPostRequest_FileUpload` (0–2) |
| `HttpRedirectsParityTest` | `TestRedirects` (301–308, no follow; incl. HEAD/PUT/DELETE) |
| `HttpRedirectsFollowParityTest` | redirect follow (`followRedirects` + `autoRedirects`) |
| `HttpCookieManagerParityTest` | `TestHC4CookieManager` (set/echo cookies) |
| `HttpCacheManagerParityTest` | `TestCacheManagerHC4` (embedded cache hit) |
| `HttpDisableArgumentsParityTest` | skippable args (5.6.3); `enabled` checkbox → JMeter 5.7+ |

```bash
mvn test -Dcheckstyle.skip=true -Dtest=com.blazemeter.jmeter.http2.parity.*
```

## CI

`ci-jmeter-compatibility.yaml` runs **all regression groups** (`tier=all`: Core + Extended + External-services) via `-Pjmeter-regression` after `mvn clean install` (which also runs JUnit parity via Surefire). External-service drift tolerance is enabled in CI for auth/keep-alive plans.

## Resources

Vendored under `src/test/resources/jmeter-regression/5.6.3/` from [apache/jmeter rel/v5.6.3 bin/testfiles](https://github.com/apache/jmeter/tree/rel/v5.6.3/bin/testfiles).
