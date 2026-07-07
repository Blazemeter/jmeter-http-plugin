package com.blazemeter.jmeter.http2.core;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.eclipse.jetty.client.ContentDecoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;

public class DeflateContentDecoderFactory extends ContentDecoder.Factory {

  private final ByteBufferPool bufferPool;
  private final int bufferSize;

  public DeflateContentDecoderFactory(ByteBufferPool bufferPool) {
    super("deflate");
    this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool");
    this.bufferSize = Math.max(32, IO.DEFAULT_BUFFER_SIZE);
  }

  @Override
  public Content.Source newDecoderContentSource(Content.Source contentSource) {
    return new DeflateDecoderSource(contentSource, bufferPool, bufferSize);
  }

  private static final class DeflateDecoderSource extends ContentSourceTransformer {

    private final ByteBufferPool bufferPool;
    private final int bufferSize;
    private final Inflater inflater = new Inflater(false);
    private boolean inputEof;

    private DeflateDecoderSource(Content.Source rawSource, ByteBufferPool bufferPool,
        int bufferSize) {
      super(rawSource);
      this.bufferPool = bufferPool;
      this.bufferSize = bufferSize;
    }

    @Override
    protected Content.Chunk transform(Content.Chunk inputChunk) {
      if (inputChunk.isEmpty()) {
        if (inputChunk.isLast()) {
          inputEof = true;
          if (inflater.finished() || inflater.needsInput()) {
            return Content.Chunk.EOF;
          }
        }
        return inputChunk.isLast() ? Content.Chunk.EOF : Content.Chunk.EMPTY;
      }

      ByteBuffer compressed = inputChunk.getByteBuffer();
      if (inflater.needsInput() && compressed.hasRemaining()) {
        inflater.setInput(compressed);
      }

      while (true) {
        RetainableByteBuffer buffer = bufferPool.acquire(bufferSize, true);
        try {
          ByteBuffer decoded = buffer.getByteBuffer();
          int pos = BufferUtil.flipToFill(decoded);
          inflater.inflate(decoded);
          BufferUtil.flipToFlush(decoded, pos);
          if (buffer.hasRemaining()) {
            return Content.Chunk.asChunk(decoded, false, buffer);
          }
          buffer.release();
        } catch (DataFormatException e) {
          buffer.release();
          throw new IllegalArgumentException("Invalid deflate content", e);
        }

        if (inflater.finished()) {
          return (inputChunk.isLast() || inputEof) ? Content.Chunk.EOF : Content.Chunk.EMPTY;
        }

        if (inflater.needsInput()) {
          if (!compressed.hasRemaining()) {
            return Content.Chunk.EMPTY;
          }
          inflater.setInput(compressed);
        }
      }
    }

    @Override
    protected void release() {
      inflater.end();
    }
  }
}
