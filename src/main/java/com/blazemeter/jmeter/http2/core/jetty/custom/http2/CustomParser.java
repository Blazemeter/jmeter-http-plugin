package com.blazemeter.jmeter.http2.core.jetty.custom.http2;

import java.lang.reflect.Field;
import org.eclipse.jetty.http2.hpack.CustomHpackDecoder;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RateControl;

/**
 * Jetty HTTP/2 {@link Parser} that installs {@link CustomHpackDecoder} before {@link #init}.
 *
 * <p>{@code hpackDecoder} is {@code final} in Jetty; we replace it reflectively after
 * {@code super(...)} and before {@code init(...)} so {@code HeaderBlockParser} captures the
 * custom decoder.
 */
public class CustomParser extends Parser {

  public CustomParser(ByteBufferPool bufferPool, int maxHeaderSize) {
    super(bufferPool, maxHeaderSize);
    replaceHpackDecoder(maxHeaderSize);
  }

  public CustomParser(ByteBufferPool bufferPool, int maxHeaderSize, RateControl rateControl) {
    super(bufferPool, maxHeaderSize, rateControl);
    replaceHpackDecoder(maxHeaderSize);
  }

  private void replaceHpackDecoder(int maxHeaderSize) {
    try {
      Field field = Parser.class.getDeclaredField("hpackDecoder");
      field.setAccessible(true);
      field.set(this, new CustomHpackDecoder(maxHeaderSize, this::getBeginNanoTime));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unable to install CustomHpackDecoder into Parser", e);
    }
  }
}
