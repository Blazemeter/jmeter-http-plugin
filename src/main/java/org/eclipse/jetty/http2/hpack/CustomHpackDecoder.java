package org.eclipse.jetty.http2.hpack;

import com.blazemeter.jmeter.http2.core.jetty.custom.http2.CustomHttp2HeaderNormalizer;
import java.nio.ByteBuffer;
import java.util.function.LongSupplier;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTokens;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.compression.EncodingException;
import org.eclipse.jetty.http.compression.HuffmanDecoder;
import org.eclipse.jetty.http.compression.NBitIntegerDecoder;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.http2.hpack.internal.AuthorityHttpField;
import org.eclipse.jetty.http2.hpack.internal.MetaDataBuilder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty 12.1.11 {@link HpackDecoder} with Firefox-inspired soft normalization for load testing.
 *
 * <p>Keep in sync when upgrading Jetty; only the normalize hooks differ from upstream.
 *
 * <p>This is not thread safe and may only be called by 1 thread at a time.
 */
public class CustomHpackDecoder extends HpackDecoder {
  private static final Logger LOG = LoggerFactory.getLogger(CustomHpackDecoder.class);
  private static final HttpField LOWER_CASE_CONTENT_LENGTH_0 =
      new PreEncodedHttpField(HttpHeader.CONTENT_LENGTH, "content-length", "0");

  private final HpackContext context;
  private final MetaDataBuilder builder;
  private final HuffmanDecoder huffmanDecoder;
  private final NBitIntegerDecoder integerDecoder;
  private final LongSupplier beginNanoTimeSupplier;
  private int maxTableCapacity;

  /**
   * @param maxHeaderSize the maximum allowed size of a decoded headers block, expressed as total
   *     of all name and value bytes, plus 32 bytes per field
   * @param beginNanoTimeSupplier the supplier of a nano timestamp taken at the time the first byte
   *     was read
   */
  public CustomHpackDecoder(int maxHeaderSize, LongSupplier beginNanoTimeSupplier) {
    super(maxHeaderSize, beginNanoTimeSupplier);
    this.beginNanoTimeSupplier = beginNanoTimeSupplier;
    context = new HpackContext(HpackContext.DEFAULT_MAX_TABLE_CAPACITY);
    builder = new MetaDataBuilder(maxHeaderSize);
    huffmanDecoder = new HuffmanDecoder();
    integerDecoder = new NBitIntegerDecoder();
    setMaxTableCapacity(HpackContext.DEFAULT_MAX_TABLE_CAPACITY);
  }

  @Override
  public HpackContext getHpackContext() {
    return context;
  }

  @Override
  public int getMaxTableCapacity() {
    return maxTableCapacity;
  }

  /**
   * Sets the limit for the capacity of the dynamic header table.
   *
   * <p>This value acts as a limit for the values received from the remote peer via the HPACK
   * dynamic table size update instruction.
   *
   * <p>After calling this method, a SETTINGS frame must be sent to the other peer, containing the
   * {@code SETTINGS_HEADER_TABLE_SIZE} setting with the value passed as argument to this method.
   *
   * @param maxTableCapacity the limit for capacity of the dynamic header table
   */
  @Override
  public void setMaxTableCapacity(int maxTableCapacity) {
    this.maxTableCapacity = maxTableCapacity;
  }

  @Override
  public int getMaxHeaderListSize() {
    return builder.getMaxSize();
  }

  @Override
  public void setMaxHeaderListSize(int maxHeaderListSize) {
    builder.setMaxSize(maxHeaderListSize);
  }

  @Override
  public MetaData decode(ByteBuffer buffer)
      throws HpackException.SessionException, HpackException.StreamException {
    if (LOG.isDebugEnabled()) {
      LOG.debug(String.format("CtxTbl[%x] decoding %d octets", context.hashCode(),
          buffer.remaining()));
    }

    // If the buffer is larger than the max headers size, don't even start decoding it.
    int maxSize = builder.getMaxSize();
    if (maxSize > 0 && buffer.remaining() > maxSize) {
      throw new HpackException.SessionException("Header fields size too large");
    }

    try {
      boolean emitted = false;
      while (buffer.hasRemaining()) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("decode {}", BufferUtil.toHexString(buffer));
        }

        byte b = buffer.get();
        if (b < 0) {
          // 7.1 indexed if the high bit is set
          int index = integerDecode(buffer, 7);
          Entry entry = context.get(index);
          if (entry == null) {
            throw new HpackException.SessionException("Unknown index %d", index);
          }

          HttpField field = entry.getHttpField();
          if (entry.isStatic()) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("decode IdxStatic {}", entry);
            }
            emitted = true;
            builder.emit(field);
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug("decode Idx {}", entry);
            }

            String name = field.getName();
            if (!HttpTokens.isLegalH2H3FieldName(name)) {
              builder.streamException("Illegal header name %s", name);
            }

            String value = CustomHttp2HeaderNormalizer.normalize(field.getHeader(),
                field.getName(), field.getValue());
            if (!value.equals(field.getValue())) {
              field = new HttpField(field.getHeader(), field.getName(), value);
            }
            if (!HttpTokens.isLegalFieldValue(value)) {
              builder.streamException("Illegal header value %s", value);
            }

            emitted = true;
            builder.emit(field);
          }
        } else {
          // look at the first nibble in detail
          byte f = (byte) ((b & 0xF0) >> 4);
          String name;
          HttpHeader header = null;
          String value;

          boolean indexed;
          int nameIndex;

          switch (f) {
            case 2: // 7.3
            case 3: // 7.3
              // change table size
              int size = integerDecode(buffer, 5);
              if (LOG.isDebugEnabled()) {
                LOG.debug("decode resize={}", size);
              }
              if (size > getMaxTableCapacity()) {
                throw new HpackException.CompressionException(
                    "Dynamic table resize exceeded max limit");
              }
              if (emitted) {
                throw new HpackException.CompressionException(
                    "Dynamic table resize after fields");
              }
              context.resize(size);
              continue;

            case 0: // 7.2.2
            case 1: // 7.2.3
              indexed = false;
              nameIndex = integerDecode(buffer, 4);
              break;

            case 4: // 7.2.1
            case 5: // 7.2.1
            case 6: // 7.2.1
            case 7: // 7.2.1
              indexed = true;
              nameIndex = integerDecode(buffer, 6);
              break;

            default:
              throw new IllegalStateException();
          }

          boolean huffmanName = false;

          // decode the name
          if (nameIndex > 0) {
            Entry nameEntry = context.get(nameIndex);
            if (nameEntry == null) {
              throw new HpackException.CompressionException("Unknown index %d", nameIndex);
            }
            name = nameEntry.getHttpField().getName();
            header = nameEntry.getHttpField().getHeader();
          } else {
            huffmanName = (buffer.get() & 0x80) == 0x80;
            int length = integerDecode(buffer, 7);
            if (huffmanName) {
              name = huffmanDecode(buffer, length);
            } else {
              name = toISO88591String(buffer, length);
            }
          }

          boolean illegalName = !HttpTokens.isLegalH2H3FieldName(name);
          if (illegalName) {
            builder.streamException("Illegal header name %s", name);
          } else {
            header = HttpHeader.CACHE.get(name);
          }

          // decode the value
          boolean huffmanValue = (buffer.get() & 0x80) == 0x80;
          int length = integerDecode(buffer, 7);
          if (huffmanValue) {
            value = huffmanDecode(buffer, length);
          } else {
            value = toISO88591String(buffer, length);
          }

          value = CustomHttp2HeaderNormalizer.normalize(header, name, value);

          boolean illegalValue = !HttpTokens.isLegalFieldValue(value);
          if (illegalValue) {
            builder.streamException("Illegal header value %s", value);
          }

          HttpField httpField = processField(header, name, value, indexed);
          emitted = true;
          builder.emit(httpField);

          // If indexed add to dynamic table.
          if (indexed) {
            context.add(httpField);
          }

          if (LOG.isDebugEnabled()) {
            LOG.debug("decoded '{}' by {}/{}/{}", httpField,
                nameIndex > 0 ? "IdxName" : (huffmanName ? "HuffName" : "LitName"),
                huffmanValue ? "HuffVal" : "LitVal", indexed ? "Idx" : "");
          }
        }
      }

      builder.setBeginNanoTime(beginNanoTimeSupplier.getAsLong());
      return builder.build();
    } catch (HpackException ex) {
      throw ex;
    } catch (Throwable t) {
      throw new HpackException.SessionException(t, "HPACK decoding failure: %s", t.getMessage());
    }
  }

  private HttpField processField(HttpHeader header, String name, String value, boolean indexed)
      throws HpackException.SessionException {
    HttpField field;
    if (header == null) {
      // just make a normal field and bypass header name lookup
      field = new HttpField(null, name, value);
    } else {
      try {
        // might be worthwhile to create a value HttpField if it is indexed
        // and/or of a type that may be looked up multiple times.
        switch (header) {
          case C_STATUS:
            if (indexed) {
              field = new HttpField.IntValueHttpField(header, name, value);
            } else {
              field = new HttpField(header, name, value);
            }
            break;
          case C_AUTHORITY:
            field = new AuthorityHttpField(value);
            break;
          case CONTENT_LENGTH:
            if ("0".equals(value)) {
              field = LOWER_CASE_CONTENT_LENGTH_0;
            } else {
              field = new HttpField.LongValueHttpField(header, name, value);
            }
            break;
          default:
            field = new HttpField(header, name, value);
            break;
        }
      } catch (Throwable t) {
        builder.streamException(t);
        field = new HttpField(header, name, value);
      }
    }

    return field;
  }

  private int integerDecode(ByteBuffer buffer, int prefix)
      throws HpackException.CompressionException {
    try {
      if (prefix != 8) {
        buffer.position(buffer.position() - 1);
      }

      integerDecoder.setPrefix(prefix);
      int decodedInt = integerDecoder.decodeInt(buffer);
      if (decodedInt < 0) {
        throw new EncodingException("invalid integer encoding");
      }
      return decodedInt;
    } catch (EncodingException e) {
      throw new HpackException.CompressionException(e, e.getMessage());
    } finally {
      integerDecoder.reset();
    }
  }

  private String huffmanDecode(ByteBuffer buffer, int length)
      throws HpackException.CompressionException {
    try {
      huffmanDecoder.setLength(length);
      String decoded = huffmanDecoder.decode(buffer);
      if (decoded == null) {
        throw new HpackException.CompressionException("invalid string encoding");
      }
      return decoded;
    } catch (EncodingException e) {
      throw new HpackException.CompressionException(e, e.getMessage());
    } finally {
      huffmanDecoder.reset();
    }
  }

  public static String toISO88591String(ByteBuffer buffer, int length) {
    CharsetStringBuilder.Iso88591StringBuilder stringBuilder =
        new CharsetStringBuilder.Iso88591StringBuilder();
    for (int i = 0; i < length; ++i) {
      stringBuilder.append(buffer.get());
    }
    return stringBuilder.build();
  }

  @Override
  public String toString() {
    return String.format("CustomHpackDecoder@%x{%s}", hashCode(), context);
  }
}
