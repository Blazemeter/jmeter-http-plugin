package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.http.HttpTokens;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.http2.hpack.CustomHpackDecoder;
import org.junit.Test;

/**
 * Reproduces Jetty HPACK failures seen with Akamai-style status values such as
 * {@code :status: 299 Akamai}, and verifies the tolerant decoder accepts them.
 *
 * <p>HTTP/2 {@code :status} must be a 3-digit integer only (RFC 9113). Some intermediaries
 * still emit an HTTP/1-style reason phrase in the pseudo-header. Stock Jetty fails while
 * parsing that value as an int. {@link CustomHpackDecoder} normalizes leading digits
 * (Firefox-inspired soft handling for load testing).
 */
public class HpackAkamaiStatusDecoderTest {

  private static final String AKAMAI_STATUS_WITH_REASON = "299 Akamai";

  @Test
  public void akamaiStatusWithReasonIsLegalFieldValue() {
    assertThat(HttpTokens.isLegalFieldValue(AKAMAI_STATUS_WITH_REASON)).isTrue();
    assertThat(HttpTokens.isLegalFieldValue("299")).isTrue();
  }

  @Test
  public void trailingSpaceMakesAkamaiStatusIllegalFieldValue() {
    assertThat(HttpTokens.isLegalFieldValue(AKAMAI_STATUS_WITH_REASON + " ")).isFalse();
    assertThat(HttpTokens.isLegalFieldValue(" " + AKAMAI_STATUS_WITH_REASON)).isFalse();
  }

  @Test
  public void stockDecoderFailsOnStatusPseudoHeaderWithAkamaiReasonPhrase() {
    ByteBuffer block = literalStatusHeader(AKAMAI_STATUS_WITH_REASON, false);
    HpackDecoder decoder = new HpackDecoder(16 * 1024, System::nanoTime);

    assertThatThrownBy(() -> decoder.decode(block))
        .isInstanceOf(HpackException.SessionException.class)
        .hasMessageContaining("HPACK decoding failure");
  }

  @Test
  public void stockDecoderFailsOnIndexedStatusPseudoHeaderWithAkamaiReasonPhrase() {
    ByteBuffer block = literalStatusHeader(AKAMAI_STATUS_WITH_REASON, true);
    HpackDecoder decoder = new HpackDecoder(16 * 1024, System::nanoTime);

    assertThatThrownBy(() -> decoder.decode(block))
        .isInstanceOf(HpackException.SessionException.class)
        .hasMessageContaining("HPACK decoding failure");
  }

  @Test
  public void tolerantDecoderAcceptsStatusPseudoHeaderWithAkamaiReasonPhrase() throws Exception {
    ByteBuffer block = literalStatusHeader(AKAMAI_STATUS_WITH_REASON, false);
    MetaData metadata = new CustomHpackDecoder(16 * 1024, System::nanoTime).decode(block);

    assertThat(metadata).isInstanceOf(MetaData.Response.class);
    assertThat(((MetaData.Response) metadata).getStatus()).isEqualTo(299);
  }

  @Test
  public void tolerantDecoderAcceptsIndexedStatusPseudoHeaderWithAkamaiReasonPhrase()
      throws Exception {
    ByteBuffer block = literalStatusHeader(AKAMAI_STATUS_WITH_REASON, true);
    MetaData metadata = new CustomHpackDecoder(16 * 1024, System::nanoTime).decode(block);

    assertThat(metadata).isInstanceOf(MetaData.Response.class);
    assertThat(((MetaData.Response) metadata).getStatus()).isEqualTo(299);
  }

  @Test
  public void shouldDecodeNumericStatus299WithoutReason() throws Exception {
    ByteBuffer block = literalStatusHeader("299", false);
    MetaData metadata = new CustomHpackDecoder(16 * 1024, System::nanoTime).decode(block);

    assertThat(metadata).isInstanceOf(MetaData.Response.class);
    assertThat(((MetaData.Response) metadata).getStatus()).isEqualTo(299);
  }

  @Test
  public void stockDecoderReportsIllegalHeaderValueWhenWarningHasTrailingSpace() {
    ByteBuffer block = status200PlusWarning(AKAMAI_STATUS_WITH_REASON + " ");
    HpackDecoder decoder = new HpackDecoder(16 * 1024, System::nanoTime);

    assertThatThrownBy(() -> decoder.decode(block))
        .isInstanceOf(HpackException.StreamException.class)
        .hasMessageContaining("Illegal header value")
        .hasMessageContaining(AKAMAI_STATUS_WITH_REASON);
  }

  @Test
  public void tolerantDecoderTrimsTrailingSpaceOnWarningHeader() throws Exception {
    ByteBuffer block = status200PlusWarning(AKAMAI_STATUS_WITH_REASON + " ");
    MetaData metadata = new CustomHpackDecoder(16 * 1024, System::nanoTime).decode(block);

    assertThat(metadata).isInstanceOf(MetaData.Response.class);
    assertThat(((MetaData.Response) metadata).getStatus()).isEqualTo(200);
    assertThat(metadata.getHttpFields().get("warning")).isEqualTo(AKAMAI_STATUS_WITH_REASON);
  }

  @Test
  public void realOversizedBlockSessionExceptionIsDetectedAsHpackFailure() {
    ByteBuffer block = literalStatusHeader("200", false);
    // Buffer is 5 bytes (0x08, len=3, '2','0','0'); cap below that to trip the
    // whole-block guard at the top of CustomHpackDecoder.decode().
    CustomHpackDecoder decoder = new CustomHpackDecoder(4, System::nanoTime);

    Throwable thrown = catchThrowable(() -> decoder.decode(block));

    assertThat(thrown)
        .isInstanceOf(HpackException.SessionException.class)
        .hasMessageContaining("Header fields size too large");
    assertThat(HpackFailureDetector.indicatesHpackFailure(thrown))
        .as("HpackFailureDetector must recognize CustomHpackDecoder's own SessionException")
        .isTrue();
  }

  @Test
  public void realOversizedFieldSessionExceptionIsDetectedAsHpackFailure() {
    // Encoded block is only 12 bytes (0x08, len=10, 10 value bytes), but the decoded
    // ":status" field accounts for 7 (name) + 10 (value) + 32 (overhead) = 49 logical bytes,
    // so maxHeaderSize=20 clears the whole-block guard yet still trips MetaDataBuilder.emit().
    ByteBuffer block = literalStatusHeader("2000000000", false);
    CustomHpackDecoder decoder = new CustomHpackDecoder(20, System::nanoTime);

    Throwable thrown = catchThrowable(() -> decoder.decode(block));

    assertThat(thrown)
        .isInstanceOf(HpackException.SessionException.class)
        .hasMessageContaining("Header size")
        .hasMessageContaining("49 > 20");
    assertThat(HpackFailureDetector.indicatesHpackFailure(thrown))
        .as("HpackFailureDetector must recognize CustomHpackDecoder's own SessionException")
        .isTrue();
  }

  private static ByteBuffer literalStatusHeader(String value, boolean indexed) {
    byte[] valueBytes = value.getBytes(StandardCharsets.ISO_8859_1);
    ByteBuffer buffer = ByteBuffer.allocate(2 + valueBytes.length);
    buffer.put(indexed ? (byte) 0x48 : (byte) 0x08);
    buffer.put((byte) valueBytes.length);
    buffer.put(valueBytes);
    buffer.flip();
    return buffer;
  }

  private static ByteBuffer status200PlusWarning(String warningValue) {
    byte[] name = "warning".getBytes(StandardCharsets.ISO_8859_1);
    byte[] value = warningValue.getBytes(StandardCharsets.ISO_8859_1);
    ByteBuffer buffer = ByteBuffer.allocate(4 + name.length + value.length);
    buffer.put((byte) 0x88);
    buffer.put((byte) 0x00);
    buffer.put((byte) name.length);
    buffer.put(name);
    buffer.put((byte) value.length);
    buffer.put(value);
    buffer.flip();
    return buffer;
  }
}