/*
 * Copyright 1999-2018 Alibaba Group.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.fastjson.serializer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.util.IOUtils;
import com.alibaba.fastjson.util.RyuDouble;
import com.alibaba.fastjson.util.RyuFloat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.List;

import static com.alibaba.fastjson.util.IOUtils.replaceChars;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public final class SerializeWriter extends Writer {
    private final static ThreadLocal<char[]> bufLocal = new ThreadLocal<char[]>();
    private final static ThreadLocal<byte[]> bytesBufLocal = new ThreadLocal<byte[]>();
    private static final char[] VALUE_TRUE = ":true".toCharArray();
    private static final char[] VALUE_FALSE = ":false".toCharArray();
    private static       int                 BUFFER_THRESHOLD = 1024 * 128;

    static {
        try {
            String prop = IOUtils.getStringProperty("fastjson.serializer_buffer_threshold");
            if (prop != null && prop.length() > 0) {
                int serializer_buffer_threshold = Integer.parseInt(prop);
                if (serializer_buffer_threshold >= 64 && serializer_buffer_threshold <= 1024 * 64) {
                    BUFFER_THRESHOLD = serializer_buffer_threshold * 1024;
                }
            }
        } catch (Throwable error) {
            // skip
        }
    }

    protected char                           buf[];

    /**
     * The number of chars in the buffer.
     */
    protected int                            count;

    protected int                            features;

    private final Writer                     writer;

    protected boolean                        useSingleQuotes;
    protected boolean                        quoteFieldNames;
    protected boolean                        sortField;
    protected boolean                        disableCircularReferenceDetect;
    protected boolean                        beanToArray;
    protected boolean                        writeNonStringValueAsString;
    protected boolean                        notWriteDefaultValue;
    protected boolean                        writeEnumUsingName;
    protected boolean                        writeEnumUsingToString;
    protected boolean                        writeDirect;

    protected char                           keySeperator;

    protected int                            maxBufSize = -1;

    protected boolean                        browserSecure;
    protected long                           sepcialBits;

    public SerializeWriter() {
        this((Writer) null);
    }

    public SerializeWriter(Writer writer) {
        this(writer, JSON.DEFAULT_GENERATE_FEATURE, SerializerFeature.EMPTY);
    }

    public SerializeWriter(SerializerFeature... features) {
        this(null, features);
    }

    public SerializeWriter(Writer writer, SerializerFeature... features) {
        this(writer, 0, features);
    }

    /**
     * @since 1.2.9
     * @param writer
     * @param defaultFeatures
     * @param features
     */
    public SerializeWriter(Writer writer, int defaultFeatures, SerializerFeature... features) {
        this.writer = writer;

        buf = bufLocal.get();

        if (buf != null) {
            bufLocal.set(null);
        } else {
            buf = new char[2048];
        }

        int featuresValue = defaultFeatures;
        for (SerializerFeature feature : features) {
            featuresValue |= feature.getMask();
        }
        this.features = featuresValue;

        computeFeatures();
    }

    public int getMaxBufSize() {
        return maxBufSize;
    }

    public void setMaxBufSize(int maxBufSize) {
        if (maxBufSize < this.buf.length) {
            throw new JSONException("must > " + buf.length);
        }

        this.maxBufSize = maxBufSize;
    }

    public int getBufferLength() {
        return this.buf.length;
    }

    public SerializeWriter(int initialSize) {
        this(null, initialSize);
    }

    public SerializeWriter(Writer writer, int initialSize) {
        this.writer = writer;

        if (initialSize <= 0) {
            throw new IllegalArgumentException("Negative initial size: " + initialSize);
        }
        buf = new char[initialSize];

        computeFeatures();
    }

    public void config(SerializerFeature feature, boolean state) {
        if (state) {
            features |= feature.getMask();
            // 由于枚举序列化特性WriteEnumUsingToString和WriteEnumUsingName不能共存，需要检查
            if (feature == SerializerFeature.WriteEnumUsingToString) {
                features &= ~SerializerFeature.WriteEnumUsingName.getMask();
            } else if (feature == SerializerFeature.WriteEnumUsingName) {
                features &= ~SerializerFeature.WriteEnumUsingToString.getMask();
            }
        } else {
            features &= ~feature.getMask();
        }

        computeFeatures();
    }

    final static int nonDirectFeatures = 0 //
            | SerializerFeature.UseSingleQuotes.mask //
            | SerializerFeature.BrowserCompatible.mask //
            | SerializerFeature.PrettyFormat.mask //
            | SerializerFeature.WriteEnumUsingToString.mask
            | SerializerFeature.WriteNonStringValueAsString.mask
            | SerializerFeature.WriteSlashAsSpecial.mask
            | SerializerFeature.IgnoreErrorGetter.mask
            | SerializerFeature.WriteClassName.mask
            | SerializerFeature.NotWriteDefaultValue.mask
            ;
    protected void computeFeatures() {
        quoteFieldNames = (this.features & SerializerFeature.QuoteFieldNames.mask) != 0;
        useSingleQuotes = (this.features & SerializerFeature.UseSingleQuotes.mask) != 0;
        sortField = (this.features & SerializerFeature.SortField.mask) != 0;
        disableCircularReferenceDetect = (this.features & SerializerFeature.DisableCircularReferenceDetect.mask) != 0;
        beanToArray = (this.features & SerializerFeature.BeanToArray.mask) != 0;
        writeNonStringValueAsString = (this.features & SerializerFeature.WriteNonStringValueAsString.mask) != 0;
        notWriteDefaultValue = (this.features & SerializerFeature.NotWriteDefaultValue.mask) != 0;
        writeEnumUsingName = (this.features & SerializerFeature.WriteEnumUsingName.mask) != 0;
        writeEnumUsingToString = (this.features & SerializerFeature.WriteEnumUsingToString.mask) != 0;

        writeDirect = quoteFieldNames //
                      && (this.features & nonDirectFeatures) == 0 //
                      && (beanToArray || writeEnumUsingName)
                      ;

        keySeperator = useSingleQuotes ? '\'' : '"';

        browserSecure = (this.features & SerializerFeature.BrowserSecure.mask) != 0;

        long S0 = 0x4FFFFFFFFL;
        long S1 = 0x8004FFFFFFFFL;
        long S2 = 0x50000304ffffffffL;
//        long s = 0;
//        for (int i = 0; i <= 31; ++i) {
//            s |= (1L << i);
//        }
//        s |= (1L << '"');
//
//        //S0 = s;
//        //S1 = s | (1L << '/');
//
//        s |= (1L << '('); // 41
//        s |= (1L << ')'); // 42
//        s |= (1L << '<'); // 60
//        s |= (1L << '>'); // 62
//        S2 = s;
        sepcialBits = browserSecure
                ? S2
                : (features & SerializerFeature.WriteSlashAsSpecial.mask) != 0 ? S1 : S0;
    }

    public boolean isSortField() {
        return sortField;
    }

    public boolean isNotWriteDefaultValue() {
        return notWriteDefaultValue;
    }

    public boolean isEnabled(SerializerFeature feature) {
        return (this.features & feature.mask) != 0;
    }
    
    public boolean isEnabled(int feature) {
        return (this.features & feature) != 0;
    }

    /**
     * Writes a character to the buffer.
     */
    public void write(int c) {
        int newcount = count + 1;
        if (newcount > buf.length) {
            newcount = handleWriterCapacity(newcount);
        }
        buf[count] = (char) c;
        count = newcount;
    }

    private int handleWriterCapacity(int newcount) {
        if (writer == null) {
            expandCapacity(newcount);
        } else {
            flush();
            newcount = 1;
        }
        return newcount;
    }

    /**
     * Writes characters to the buffer.
     * 
     * @param c the data to be written
     * @param off the start offset in the data
     * @param len the number of chars that are written
     */
    public void write(char c[], int off, int len) {
        if (off < 0 //
                || off > c.length //
                || len < 0 //
                || off + len > c.length //
                || off + len < 0)
            throw new IndexOutOfBoundsException();
        if (len == 0) {
            return;
        }

        int newcount = count + len;
        if (newcount > buf.length) {
            if (writer == null) {
                expandCapacity(newcount);
            }
            else {
                do {
                    int rest = buf.length - count;
                    System.arraycopy(c, off, buf, count, rest);
                    count = buf.length;
                    flush();
                    len -= rest;
                    off += rest;
                } while (len > buf.length);
                newcount = len;
            }
        }
        System.arraycopy(c, off, buf, count, len);
        count = newcount;

    }

    public void expandCapacity(int minimumCapacity) {
        if (maxBufSize != -1 && minimumCapacity >= maxBufSize) {
            throw new JSONException("serialize exceeded MAX_OUTPUT_LENGTH=" + maxBufSize + ", minimumCapacity=" + minimumCapacity);
        }

        int newCapacity = buf.length + (buf.length >> 1) + 1;

        if (newCapacity < minimumCapacity) {
            newCapacity = minimumCapacity;
        }
        char newValue[] = new char[newCapacity];
        System.arraycopy(buf, 0, newValue, 0, count);

        if (buf.length < BUFFER_THRESHOLD) {
            char[] charsLocal = bufLocal.get();
            if (charsLocal == null || charsLocal.length < buf.length) {
                bufLocal.set(buf);
            }
        }

        buf = newValue;
    }

    public SerializeWriter append(CharSequence csq) {
        String s = csq == null ? "null" : csq.toString();
        write(s, 0, s.length());
        return this;
    }

    public SerializeWriter append(CharSequence csq, int start, int end) {
        String s = (csq == null ? "null" : csq).subSequence(start, end).toString();
        write(s, 0, s.length());
        return this;
    }

    public SerializeWriter append(char c) {
        write(c);
        return this;
    }

    /**
     * Write a portion of a string to the buffer.
     * 
     * @param str String to be written from
     * @param off Offset from which to start reading characters
     * @param len Number of characters to be written
     */
    public void write(String str, int off, int len) {
        int newcount = count + len;
        if (newcount > buf.length) {
            if (writer == null) {
                expandCapacity(newcount);
            } else {
                do {
                    int rest = buf.length - count;
                    str.getChars(off, off + rest, buf, count);
                    count = buf.length;
                    flush();
                    len -= rest;
                    off += rest;
                } while (len > buf.length);
                newcount = len;
            }
        }
        str.getChars(off, off + len, buf, count);
        count = newcount;
    }

    /**
     * Writes the contents of the buffer to another character stream.
     * 
     * @param out the output stream to write to
     * @throws IOException If an I/O error occurs.
     */
    public void writeTo(Writer out) throws IOException {
        if (this.writer != null) {
            throw new UnsupportedOperationException("writer not null");
        }
        out.write(buf, 0, count);
    }

    public void writeTo(OutputStream out, String charsetName) throws IOException {
        writeTo(out, Charset.forName(charsetName));
    }
    
    public void writeTo(OutputStream out, Charset charset) throws IOException {
        writeToEx(out, charset);
    }

    public int writeToEx(OutputStream out, Charset charset) throws IOException {
        if (this.writer != null) {
            throw new UnsupportedOperationException("writer not null");
        }
        
        if (charset == IOUtils.UTF8)
            return encodeToUTF8(out);
        byte[] bytes = new String(buf, 0, count).getBytes(charset);
        out.write(bytes);
        return bytes.length;
    }

    /**
     * Returns a copy of the input data.
     * 
     * @return an array of chars copied from the input data.
     */
    public char[] toCharArray() {
        if (this.writer != null) {
            throw new UnsupportedOperationException("writer not null");
        }

        char[] newValue = new char[count];
        System.arraycopy(buf, 0, newValue, 0, count);
        return newValue;
    }
    
    /**
     * only for springwebsocket
     * @return
     */
    public char[] toCharArrayForSpringWebSocket() {
        if (this.writer != null) {
            throw new UnsupportedOperationException("writer not null");
        }

        char[] newValue = new char[count - 2];
        System.arraycopy(buf, 1, newValue, 0, count - 2);
        return newValue;
    }

    public byte[] toBytes(String charsetName) {
        return toBytes(charsetName == null || "UTF-8".equals(charsetName) //
            ? IOUtils.UTF8 //
            : Charset.forName(charsetName));
    }

    public byte[] toBytes(Charset charset) {
        if (this.writer != null) {
            throw new UnsupportedOperationException("writer not null");
        }
        
        if (charset == IOUtils.UTF8)
            return encodeToUTF8Bytes();
        
        return new String(buf, 0, count).getBytes(charset);
    }

    private int encodeToUTF8(OutputStream out) throws IOException {

        int bytesLength = (int) (count * (double) 3);
        byte[] bytes = bytesBufLocal.get();

        if (bytes == null) {
            bytes = new byte[1024 * 8];
            bytesBufLocal.set(bytes);
        }
        byte[] bytesLocal = bytes;

        if (bytes.length < bytesLength) {
            bytes = new byte[bytesLength];
        }

        int position = IOUtils.encodeUTF8(buf, 0, count, bytes);
        out.write(bytes, 0, position);

        if (bytes != bytesLocal && bytes.length <= BUFFER_THRESHOLD) {
            bytesBufLocal.set(bytes);
        }

        return position;
    }
    
    private byte[] encodeToUTF8Bytes() {
        int bytesLength = (int) (count * (double) 3);
        byte[] bytes = bytesBufLocal.get();

        if (bytes == null) {
            bytes = new byte[1024 * 8];
            bytesBufLocal.set(bytes);
        }
        byte[] bytesLocal = bytes;

        if (bytes.length < bytesLength) {
            bytes = new byte[bytesLength];
        }

        int position = IOUtils.encodeUTF8(buf, 0, count, bytes);
        byte[] copy = new byte[position];
        System.arraycopy(bytes, 0, copy, 0, position);

        if (bytes != bytesLocal && bytes.length <= BUFFER_THRESHOLD) {
            bytesBufLocal.set(bytes);
        }

        return copy;
    }
    
    public int size() {
        return count;
    }

    public String toString() {
        return new String(buf, 0, count);
    }

    /**
     * Close the stream. This method does not release the buffer, since its contents might still be required. Note:
     * Invoking this method in this class will have no effect.
     */
    public void close() {
        if (writer != null && count > 0) {
            flush();
        }
        if (buf.length <= BUFFER_THRESHOLD) {
            bufLocal.set(buf);
        }

        this.buf = null;
    }

    public void write(String text) {
        if (text == null) {
            writeNull();
            return;
        }

        write(text, 0, text.length());
    }

    public void writeInt(int i) {
        if (i == Integer.MIN_VALUE) {
            write("-2147483648");
            return;
        }

        int size = i < 0 ? IOUtils.stringSize(-i) + 1 : IOUtils.stringSize(i);

        int newcount = count + size;
        if (newcount > buf.length) {
            if (writer != null) {
                char[] chars = new char[size];
                IOUtils.getChars(i, size, chars);
                write(chars, 0, chars.length);
                return;
            }
            expandCapacity(newcount);
        }

        IOUtils.getChars(i, newcount, buf);

        count = newcount;
    }

    public void writeByteArray(byte[] bytes) {
        if (isEnabled(SerializerFeature.WriteClassName.mask)) {
            writeHex(bytes);
            return;
        }

        int bytesLen = bytes.length;
        char quote = useSingleQuotes ? '\'' : '"';
        if (bytesLen == 0) {
            String emptyString = useSingleQuotes ? "''" : "\"\"";
            write(emptyString);
            return;
        }

        char[] CA = IOUtils.CA;

        // base64 algorithm author Mikael Grev
        int eLen = (bytesLen / 3) * 3; // Length of even 24-bits.
        int charsLen = ((bytesLen - 1) / 3 + 1) << 2; // base64 character count
        // char[] chars = new char[charsLen];
        int offset = count;
        int newcount = count + charsLen + 2;
        if (newcount > buf.length) {
            if (writer != null) {
                encodeAndWriteBytes(bytes, bytesLen, quote, CA, eLen);
                return;
            }
            expandCapacity(newcount);
        }
        count = newcount;
        buf[offset++] = quote;

        // Encode even 24-bits
        encodeBytesToChars_(bytes, CA, eLen, offset);

        // Pad and encode last bits if source isn't even 24 bits.
        int left = bytesLen - eLen; // 0 - 2.
        if (left > 0) {
            // Prepare the int
            encodeLastFourChars(bytes, bytesLen, CA, eLen, newcount, left);
        }
        buf[newcount - 1] = quote;
    }

    private void encodeAndWriteBytes(byte[] bytes, int bytesLen, char quote, char[] CA, int eLen) {
        write(quote);

        encodeBytesToChars(bytes, CA, eLen);

        // Pad and encode last bits if source isn't even 24 bits.
		int left = bytesLen - eLen; // 0 - 2.
		if (left > 0) {
            // Prepare the int
		    writeEncodedBytes(bytes, bytesLen, CA, eLen, left);
        }

        write(quote);
    }

    private void encodeLastFourChars(byte[] bytes, int bytesLen, char[] CA, int eLen, int newcount, int left) {
        int i = ((bytes[eLen] & 0xff) << 10) | (left == 2 ? ((bytes[bytesLen - 1] & 0xff) << 2) : 0);

        // Set last four chars
		buf[newcount - 5] = CA[i >> 12];
        buf[newcount - 4] = CA[(i >>> 6) & 0x3f];
        buf[newcount - 3] = left == 2 ? CA[i & 0x3f] : '=';
        buf[newcount - 2] = '=';
    }

    private void encodeBytesToChars_(byte[] bytes, char[] CA, int eLen, int offset) {
        for (int s = 0, d = offset;s < eLen;) {
            // Copy next three bytes into lower 24 bits of int, paying attension to sign.
            int i = (bytes[s++] & 0xff) << 16 | (bytes[s++] & 0xff) << 8 | (bytes[s++] & 0xff);

            // Encode the int into four chars
            buf[d++] = CA[(i >>> 18) & 0x3f];
            buf[d++] = CA[(i >>> 12) & 0x3f];
            buf[d++] = CA[(i >>> 6) & 0x3f];
            buf[d++] = CA[i & 0x3f];
        }
    }

    private void writeEncodedBytes(byte[] bytes, int bytesLen, char[] CA, int eLen, int left) {
        int i = ((bytes[eLen] & 0xff) << 10) | (left == 2 ? ((bytes[bytesLen - 1] & 0xff) << 2) : 0);

        // Set last four chars
		write(CA[i >> 12]);
        write(CA[(i >>> 6) & 0x3f]);
        write(left == 2 ? CA[i & 0x3f] : '=');
        write('=');
    }

    private void encodeBytesToChars(byte[] bytes, char[] CA, int eLen) {
        for (int s = 0;s < eLen;) {
            // Copy next three bytes into lower 24 bits of int, paying attension to sign.
		    int i = (bytes[s++] & 0xff) << 16 | (bytes[s++] & 0xff) << 8 | (bytes[s++] & 0xff);

            // Encode the int into four chars
		    write(CA[(i >>> 18) & 0x3f]);
            write(CA[(i >>> 12) & 0x3f]);
            write(CA[(i >>> 6) & 0x3f]);
            write(CA[i & 0x3f]);
        }
    }

    public void writeHex(byte[] bytes) {
        int newcount = count + bytes.length * 2 + 3;
        ensureCapacity(newcount);

        buf[count++] = 'x';
        buf[count++] = '\'';

        for (int i = 0;i < bytes.length;++i) {
            encodeByteToHexChars(bytes, i);
        }
        buf[count++] = '\'';
    }

    private void encodeByteToHexChars(byte[] bytes, int i) {
        byte b = bytes[i];

        int a = b & 0xFF;
        int b0 = a >> 4;
        int b1 = a & 0xf;

        buf[count++] = (char) (b0 + (b0 < 10 ? 48 : 55));
        buf[count++] = (char) (b1 + (b1 < 10 ? 48 : 55));
    }

    public void writeFloat(float value, boolean checkWriteClassName) {
        if (value != value || value == Float.POSITIVE_INFINITY || value == Float.NEGATIVE_INFINITY) {
            writeNull();
            return;
        }
        int newcount = count + 15;
        if (newcount > buf.length) {
            if (writer != null) {
                String str = RyuFloat.toString(value);
                write(str, 0, str.length());

                writeClassNameF(checkWriteClassName);
                return;
            }
            expandCapacity(newcount);
        }

        int len = RyuFloat.toString(value, buf, count);
        count += len;

        writeClassNameF(checkWriteClassName);
    }

    private void writeClassNameF(boolean checkWriteClassName) {
        if (checkWriteClassName && isEnabled(SerializerFeature.WriteClassName)) {
            write('F');
        }
    }

    public void writeDouble(double value, boolean checkWriteClassName) {
        if (Double.isNaN(value)
                || Double.isInfinite(value)) {
            writeNull();
            return;
        }

        int newcount = count + 24;
        if (newcount > buf.length) {
            if (writer != null) {
                String str = RyuDouble.toString(value);
                write(str, 0, str.length());

                writeClassNameD(checkWriteClassName);
                return;
            }
            expandCapacity(newcount);
        }

        int len = RyuDouble.toString(value, buf, count);
        count += len;

        writeClassNameD(checkWriteClassName);
    }

    private void writeClassNameD(boolean checkWriteClassName) {
        if (checkWriteClassName && isEnabled(SerializerFeature.WriteClassName)) {
            write('D');
        }
    }

    public void writeEnum(Enum<?> value) {
        if (value == null) {
            writeNull();
            return;
        }
        
        String strVal = null;
        if (writeEnumUsingName && !writeEnumUsingToString) {
            strVal = value.name();
        } else if (writeEnumUsingToString) {
            strVal = value.toString();
        }

        if (strVal != null) {
            writeStringWithQuotes(strVal);
        } else {
            writeInt(value.ordinal());
        }
    }

    private void writeStringWithQuotes(String strVal) {
        char quote = isEnabled(SerializerFeature.UseSingleQuotes) ? '\'' : '"';
        write(quote);
        write(strVal);
        write(quote);
    }

    /**
     * @deprecated
     */
    public void writeLongAndChar(long i, char c) throws IOException {
        writeLong(i);
        write(c);
    }

    public void writeLong(long i) {
        boolean needQuotationMark = isEnabled(SerializerFeature.BrowserCompatible) //
                && (!isEnabled(SerializerFeature.WriteClassName)) //
                && (i > 9007199254740991L || i < -9007199254740991L);

        if (i == Long.MIN_VALUE) {
            if (needQuotationMark) {
                write("\"-9223372036854775808\"");
            }
            else {
                write("-9223372036854775808");
            }
            return;
        }

        int size = i < 0 ? IOUtils.stringSize(-i) + 1 : IOUtils.stringSize(i);

        int newcount = count + size;
        if (needQuotationMark) {
            newcount += 2;
        }
        if (newcount > buf.length) {
            if (writer != null) {
                writeCharsWithOptionalQuotes(i, needQuotationMark, size);
                return;
            }
            expandCapacity(newcount);
        }

        if (needQuotationMark) {
            buf[count] = '"';
            IOUtils.getChars(i, newcount - 1, buf);
            buf[newcount - 1] = '"';
        }
        else {
            IOUtils.getChars(i, newcount, buf);
        }

        count = newcount;
    }

    private void writeCharsWithOptionalQuotes(long i, boolean needQuotationMark, int size) {
        char[] chars = new char[size];
        IOUtils.getChars(i, size, chars);
        if (needQuotationMark) {
            write('"');
            write(chars, 0, chars.length);
            write('"');
        } else {
            write(chars, 0, chars.length);
        }
    }

    public void writeNull() {
        write("null");
    }
    
    public void writeNull(SerializerFeature feature) {
        writeNull(0, feature.mask);
    }
    
    public void writeNull(int beanFeatures, int feature) {
        if ((beanFeatures & feature) == 0 //
            && (this.features & feature) == 0) {
            writeNull();
            return;
        }
        if ((beanFeatures & SerializerFeature.WriteMapNullValue.mask) != 0
                && (beanFeatures & ~SerializerFeature.WriteMapNullValue.mask
                & SerializerFeature.WRITE_MAP_NULL_FEATURES) == 0) {
            writeNull();
            return;
        }
        
        if (feature == SerializerFeature.WriteNullListAsEmpty.mask) {
            write("[]");
        } else if (feature == SerializerFeature.WriteNullStringAsEmpty.mask) {
            writeString("");
        } else if (feature == SerializerFeature.WriteNullBooleanAsFalse.mask) {
            write("false");
        } else if (feature == SerializerFeature.WriteNullNumberAsZero.mask) {
            write('0');
        } else {
            writeNull();
        }
    }
    
    public void writeStringWithDoubleQuote(String text, char seperator) {
        if (text == null) {
            writeNull();
            writeSeparator(seperator);
            return;
        }

        int len = text.length();
        int newcount = count + len + 2;
        if (seperator != 0) {
            newcount++;
        }

        if (newcount > buf.length) {
            if (writer != null) {
                writeSerializedTextWithSeparator(text, seperator);
                return;
            }
            expandCapacity(newcount);
        }

        int start = count + 1;
        int end = start + len;

        buf[count] = '\"';
        text.getChars(0, len, buf, start);

        count = newcount;

        if (isEnabled(SerializerFeature.BrowserCompatible)) {
            int lastSpecialIndex = -1;

            for (int i = start;i < end;++i) {
                char ch = buf[i];

                if (ch == '"' //
                    || ch == '/' //
                    || ch == '\\') {
                    lastSpecialIndex = i;
                    newcount += 1;
                    continue;
                }

                if (ch == '\b' //
                    || ch == '\f' //
                    || ch == '\n' //
                    || ch == '\r' //
                    || ch == '\t') {
                    lastSpecialIndex = i;
                    newcount += 1;
                    continue;
                }

                if (ch < 32) {
                    lastSpecialIndex = i;
                    newcount += 5;
                    continue;
                }

                if (ch >= 127) {
                    lastSpecialIndex = i;
                    newcount += 5;
                    continue;
                }
            }

            updateCount(newcount);

            end = escapeSpecialCharacters(start, end, lastSpecialIndex);

            putDoubleQuotes(seperator);

            return;
        }

        int specialCount = 0;
        int lastSpecialIndex = -1;
        int firstSpecialIndex = -1;
        char lastSpecial = '\0';

        for (int i = start;i < end;++i) {
            char ch = buf[i];

            if (ch >= ']') { // 93
                if (ch >= 0x7F //
                        && (ch == '\u2028' //
                        || ch == '\u2029' //
                        || ch < 0xA0)) {
                    firstSpecialIndex = setFirstSpecialIndex(firstSpecialIndex, i);

                    specialCount++;
                    lastSpecialIndex = i;
                    lastSpecial = ch;
                    newcount += 4;
                }
                continue;
            }

            boolean special = (ch < 64 && (sepcialBits & (1L << ch)) != 0) || ch == '\\';
            if (special) {
                specialCount++;
                lastSpecialIndex = i;
                lastSpecial = ch;

                if (ch == '('
                        || ch == ')'
                        || ch == '<'
                        || ch == '>'
                        || (ch < IOUtils.specicalFlags_doubleQuotes.length //
                    && IOUtils.specicalFlags_doubleQuotes[ch] == 4) //
                ) {
                    newcount += 4;
                }

                firstSpecialIndex = setFirstSpecialIndex(firstSpecialIndex, i);
            }
        }

        if (specialCount > 0) {
            updateAndEncodeSpecialCharacters(text, newcount, start, end, specialCount, lastSpecialIndex, firstSpecialIndex, lastSpecial);
        }

        putDoubleQuotes(seperator);
    }

	private void putDoubleQuotes(char seperator) {
		if (seperator != 0) {
		    buf[count - 2] = '\"';
		    buf[count - 1] = seperator;
		    return;
		}
		buf[count - 1] = '\"';
	}

    private void updateAndEncodeSpecialCharacters(String text, int newcount, int start, int end, int specialCount, int lastSpecialIndex,
            int firstSpecialIndex, char lastSpecial) {
        newcount += specialCount;
        updateCount(newcount);

        if (specialCount == 1) {
            handleUnicodeAndSpecialCharacters(end, lastSpecialIndex, lastSpecial);
        } else if (specialCount > 1) {
            encodeSpecialCharacters(text, start, end, firstSpecialIndex);
        }
    }

    private void handleUnicodeAndSpecialCharacters(int end, int lastSpecialIndex, char lastSpecial) {
        if (lastSpecial == '\u2028') {
            shiftArrayElements(end, lastSpecialIndex);
            lastSpecialIndex = insertUnicodeEscapeSequence(lastSpecialIndex);
            buf[++lastSpecialIndex] = '8';
            return;

        }
        if (lastSpecial == '\u2029') {
            shiftArrayElements(end, lastSpecialIndex);
            lastSpecialIndex = insertUnicodeEscapeSequence(lastSpecialIndex);
            buf[++lastSpecialIndex] = '9';

        }
        else if (lastSpecial == '(' || lastSpecial == ')' || lastSpecial == '<' || lastSpecial == '>') {
            encodeSpecialCharToUnicode(end, lastSpecialIndex, lastSpecial);
        }
        else {
            handleSpecialCharacterEncoding(end, lastSpecialIndex, lastSpecial);
        }
    }

    private void writeSerializedTextWithSeparator(String text, char seperator) {
        write('"');

        for (int i = 0;i < text.length();++i)
            writeCharWithSerializationFeature(text, i);

        write('"');
        writeSeparator(seperator);
    }

    private void handleSpecialCharacterEncoding(int end, int lastSpecialIndex, char lastSpecial) {
        char ch = lastSpecial;
        if (ch < IOUtils.specicalFlags_doubleQuotes.length //
		    && IOUtils.specicalFlags_doubleQuotes[ch] == 4) {
            encodeAndShiftSpecialChar(end, lastSpecialIndex, ch);
        } else {
            shiftAndEncodeSpecialChar(end, lastSpecialIndex, ch);
        }
    }

    private int escapeSpecialCharacters(int start, int end, int lastSpecialIndex) {
        for (int i = lastSpecialIndex;i >= start;--i)
            end = encodeSpecialCharacters__(end, i);
        return end;
    }

    private int encodeSpecialCharacters__(int end, int i) {
        char ch = buf[i];
        if (ch == '\b' //
		    || ch == '\f'//
		    || ch == '\n' //
		    || ch == '\r' //
		    || ch == '\t'
        ) {
            System.arraycopy(buf, i + 1, buf, i + 2, end - i - 1);
            buf[i] = '\\';
            buf[i + 1] = replaceChars[(int) ch];
            end += 1;
            return end;
        }
        if (ch == '"' //
		    || ch == '/' //
		    || ch == '\\'
        ) {
            System.arraycopy(buf, i + 1, buf, i + 2, end - i - 1);
            buf[i] = '\\';
            buf[i + 1] = ch;
            end += 1;
            return end;
        }
        if (ch < 32) {
            System.arraycopy(buf, i + 1, buf, i + 6, end - i - 1);
            buf[i] = '\\';
            buf[i + 1] = 'u';
            buf[i + 2] = '0';
            buf[i + 3] = '0';
            buf[i + 4] = IOUtils.ASCII_CHARS[ch * 2];
            buf[i + 5] = IOUtils.ASCII_CHARS[ch * 2 + 1];
            end += 5;
            return end;
        }
        if (ch >= 127) {
            end = encodeCharToUnicode_(end, i, ch);
        }
        return end;
    }

    private void writeCharWithSerializationFeature(String text, int i) {
        char ch = text.charAt(i);
        if (isEnabled(SerializerFeature.BrowserSecure)) {
           if (ch == '(' || ch == ')' || ch == '<' || ch == '>') {
                writeUnicodeEscapeSequence();
                writeCharAsHexDigits(ch);
                return;
            }
        }
        if (isEnabled(SerializerFeature.BrowserCompatible)) {
            if (ch == '\b' //
		        || ch == '\f' //
		        || ch == '\n' //
		        || ch == '\r' //
		        || ch == '\t' //
		        || ch == '"' //
		        || ch == '/' //
		        || ch == '\\') {
                write('\\');
                write(replaceChars[(int) ch]);
                return;
            }

            if (ch < 32) {
                writeEncodedCharAsUnicode(ch);
                return;
            }

            if (ch >= 127) {
                writeUnicodeEscapeSequence();
                writeCharAsHexDigits(ch);
                return;
            }
        } else {
            if (ch < IOUtils.specicalFlags_doubleQuotes.length
                && IOUtils.specicalFlags_doubleQuotes[ch] != 0 //
		        || (ch == '/' && isEnabled(SerializerFeature.WriteSlashAsSpecial))) {
                writeEscapedCharacter(ch);
                return;
            }
        }
        write(ch);
    }

    private void writeEscapedCharacter(char ch) {
        write('\\');
        if (IOUtils.specicalFlags_doubleQuotes[ch] == 4) {
            writeUnicodeCharacter(ch);
        } else {
            write(IOUtils.replaceChars[ch]);
        }
    }

    private void writeEncodedCharAsUnicode(char ch) {
        writeUnicodeEscapeSequence();
        write('0');
        write('0');
        write(IOUtils.ASCII_CHARS[ch * 2]);
        write(IOUtils.ASCII_CHARS[ch * 2 + 1]);
    }

    private void encodeSpecialCharacters(String text, int start, int end, int firstSpecialIndex) {
        int textIndex = firstSpecialIndex - start;
        int bufIndex = firstSpecialIndex;
        for (int i = textIndex;i < text.length();++i) {
            char ch = text.charAt(i);

            if (browserSecure && (ch == '('
                    || ch == ')'
                    || ch == '<'
                    || ch == '>')) {
                buf[bufIndex++] = '\\';
                buf[bufIndex++] = 'u';
                buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 12) & 15];
                buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 8) & 15];
                buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 4) & 15];
                buf[bufIndex++] = IOUtils.DIGITS[ch & 15];
                end += 5;
            } else if (ch < IOUtils.specicalFlags_doubleQuotes.length //
		        && IOUtils.specicalFlags_doubleQuotes[ch] != 0 //
		        || (ch == '/' && isEnabled(SerializerFeature.WriteSlashAsSpecial))) {
                buf[bufIndex++] = '\\';
                if (IOUtils.specicalFlags_doubleQuotes[ch] == 4) {
                    buf[bufIndex++] = 'u';
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 12) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 8) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 4) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[ch & 15];
                    end += 5;
                } else {
                    buf[bufIndex++] = replaceChars[(int) ch];
                    end++;
                }
            } else {
                if (ch == '\u2028' || ch == '\u2029') {
                    buf[bufIndex++] = '\\';
                    buf[bufIndex++] = 'u';
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 12) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 8) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 4) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[ch & 15];
                    end += 5;
                } else {
                    buf[bufIndex++] = ch;
                }
            }
        }
    }

    private void shiftAndEncodeSpecialChar(int end, int lastSpecialIndex, char ch) {
        int srcPos = lastSpecialIndex + 1;
        int destPos = lastSpecialIndex + 2;
        int LengthOfCopy = end - lastSpecialIndex - 1;
        System.arraycopy(buf, srcPos, buf, destPos, LengthOfCopy);
        buf[lastSpecialIndex] = '\\';
        buf[++lastSpecialIndex] = replaceChars[(int) ch];
    }

    private void encodeAndShiftSpecialChar(int end, int lastSpecialIndex, char ch) {
        shiftArrayElements(end, lastSpecialIndex);

        int bufIndex = lastSpecialIndex;
        buf[bufIndex++] = '\\';
        encodeCharToUnicode(ch, bufIndex);
    }

    private void encodeSpecialCharToUnicode(int end, int lastSpecialIndex, char lastSpecial) {
        shiftArrayElements(end, lastSpecialIndex);
        buf[lastSpecialIndex] = '\\';
        buf[++lastSpecialIndex] = 'u';

        char ch = lastSpecial;
        buf[++lastSpecialIndex] = IOUtils.DIGITS[(ch >>> 12) & 15];
        buf[++lastSpecialIndex] = IOUtils.DIGITS[(ch >>> 8) & 15];
        buf[++lastSpecialIndex] = IOUtils.DIGITS[(ch >>> 4) & 15];
        buf[++lastSpecialIndex] = IOUtils.DIGITS[ch & 15];
    }

    private int encodeCharToUnicode_(int end, int i, char ch) {
        System.arraycopy(buf, i + 1, buf, i + 6, end - i - 1);
        buf[i] = '\\';
        buf[i + 1] = 'u';
        buf[i + 2] = IOUtils.DIGITS[(ch >>> 12) & 15];
        buf[i + 3] = IOUtils.DIGITS[(ch >>> 8) & 15];
        buf[i + 4] = IOUtils.DIGITS[(ch >>> 4) & 15];
        buf[i + 5] = IOUtils.DIGITS[ch & 15];
        end += 5;
        return end;
    }

    private void writeUnicodeCharacter(char ch) {
        write('u');
        write(IOUtils.DIGITS[ch >>> 12 & 15]);
        write(IOUtils.DIGITS[ch >>> 8 & 15]);
        write(IOUtils.DIGITS[ch >>> 4 & 15]);
        write(IOUtils.DIGITS[ch & 15]);
    }

    private void encodeCharToUnicode(char ch, int bufIndex) {
        buf[bufIndex++] = 'u';
        buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 12) & 15];
        buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 8) & 15];
        buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 4) & 15];
        buf[bufIndex++] = IOUtils.DIGITS[ch & 15];
    }

    private void writeCharAsHexDigits(char ch) {
        write(IOUtils.DIGITS[(ch >>> 12) & 15]);
        write(IOUtils.DIGITS[(ch >>> 8) & 15]);
        write(IOUtils.DIGITS[(ch >>> 4) & 15]);
        write(IOUtils.DIGITS[ch & 15]);
    }

    private void writeUnicodeEscapeSequence() {
        write('\\');
        write('u');
    }

    private void writeSeparator(char seperator) {
        if (seperator != 0) {
            write(seperator);
        }
    }

    public void writeStringWithDoubleQuote(char[] text, char seperator) {
        if (text == null) {
            writeNull();
            writeSeparator(seperator);
            return;
        }

        int len = text.length;
        int newcount = count + len + 2;
        if (seperator != 0) {
            newcount++;
        }

        if (newcount > buf.length) {
            if (writer != null) {
                writeEncodedTextWithSeparator(text, seperator);
                return;
            }
            expandCapacity(newcount);
        }

        int start = count + 1;
        int end = start + len;

        buf[count] = '\"';
//        text.getChars(0, len, buf, start);
        System.arraycopy(text, 0, buf, start, text.length);

        count = newcount;

        if (isEnabled(SerializerFeature.BrowserCompatible)) {
            int lastSpecialIndex = -1;

            for (int i = start;i < end;++i) {
                char ch = buf[i];

                if (ch == '"' //
                        || ch == '/' //
                        || ch == '\\') {
                    lastSpecialIndex = i;
                    newcount += 1;
                    continue;
                }

                if (ch == '\b' //
                        || ch == '\f' //
                        || ch == '\n' //
                        || ch == '\r' //
                        || ch == '\t') {
                    lastSpecialIndex = i;
                    newcount += 1;
                    continue;
                }

                if (ch < 32) {
                    lastSpecialIndex = i;
                    newcount += 5;
                    continue;
                }

                if (ch >= 127) {
                    lastSpecialIndex = i;
                    newcount += 5;
                    continue;
                }
            }

            updateCount(newcount);

            end = escapeSpecialCharacters(start, end, lastSpecialIndex);

            putDoubleQuotes(seperator);

            return;
        }

        int specialCount = 0;
        int lastSpecialIndex = -1;
        int firstSpecialIndex = -1;
        char lastSpecial = '\0';

        for (int i = start;i < end;++i) {
            char ch = buf[i];

            if (ch >= ']') { // 93
                if (ch >= 0x7F //
                        && (ch == '\u2028' //
                        || ch == '\u2029' //
                        || ch < 0xA0)) {
                    firstSpecialIndex = setFirstSpecialIndex(firstSpecialIndex, i);

                    specialCount++;
                    lastSpecialIndex = i;
                    lastSpecial = ch;
                    newcount += 4;
                }
                continue;
            }

            boolean special = (ch < 64 && (sepcialBits & (1L << ch)) != 0) || ch == '\\';
            if (special) {
                specialCount++;
                lastSpecialIndex = i;
                lastSpecial = ch;

                if (ch == '('
                        || ch == ')'
                        || ch == '<'
                        || ch == '>'
                        || (ch < IOUtils.specicalFlags_doubleQuotes.length //
                        && IOUtils.specicalFlags_doubleQuotes[ch] == 4) //
                        ) {
                    newcount += 4;
                }

                firstSpecialIndex = setFirstSpecialIndex(firstSpecialIndex, i);
            }
        }

        if (specialCount > 0) {
            handleSpecialCharactersEncoding(text, newcount, start, end, specialCount, lastSpecialIndex, firstSpecialIndex, lastSpecial);
        }

        putDoubleQuotes(seperator);
    }

    private void handleSpecialCharactersEncoding(char[] text, int newcount, int start, int end, int specialCount, int lastSpecialIndex,
            int firstSpecialIndex, char lastSpecial) {
        newcount += specialCount;
        updateCount(newcount);

        if (specialCount == 1) {
            handleUnicodeAndSpecialCharacters(end, lastSpecialIndex, lastSpecial);} else if (specialCount > 1) {
            encodeSpecialCharacters_(text, start, end, firstSpecialIndex);
        }
    }

    private void writeEncodedTextWithSeparator(char[] text, char seperator) {
        write('"');

        for (int i = 0;i < text.length;++i)
            handleCharacterEncoding(text, i);

        write('"');
        writeSeparator(seperator);
    }

    private void handleCharacterEncoding(char[] text, int i) {
        char ch = text[i];
        if (isEnabled(SerializerFeature.BrowserSecure)) {
            if (ch == '('
                    || ch == ')'
                    || ch == '<'
                    || ch == '>'
            ) {
                writeUnicodeEscapeSequence();
                writeCharAsHexDigits(ch);
                return;
            }
        }
        if (isEnabled(SerializerFeature.BrowserCompatible)) {
            if (ch == '\b' //
		            || ch == '\f' //
		            || ch == '\n' //
		            || ch == '\r' //
		            || ch == '\t' //
		            || ch == '"' //
		            || ch == '/' //
		            || ch == '\\') {
                write('\\');
                write(replaceChars[(int) ch]);
                return;
            }

            if (ch < 32) {
                writeEncodedCharAsUnicode(ch);
                return;
            }

            if (ch >= 127) {
                writeUnicodeEscapeSequence();
                writeCharAsHexDigits(ch);
                return;
            }
        } else {
            if (ch < IOUtils.specicalFlags_doubleQuotes.length
                    && IOUtils.specicalFlags_doubleQuotes[ch] != 0 //
		            || (ch == '/' && isEnabled(SerializerFeature.WriteSlashAsSpecial))) {
                writeEscapedCharacter(ch);
                return;
            }
        }
        write(ch);
    }

    private void encodeSpecialCharacters_(char[] text, int start, int end, int firstSpecialIndex) {
        int textIndex = firstSpecialIndex - start;
        int bufIndex = firstSpecialIndex;
        for (int i = textIndex;i < text.length;++i) {
            char ch = text[i];

            if (browserSecure && (ch == '('
                    || ch == ')'
                    || ch == '<'
                    || ch == '>')) {
                buf[bufIndex++] = '\\';
                buf[bufIndex++] = 'u';
                buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 12) & 15];
                buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 8) & 15];
                buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 4) & 15];
                buf[bufIndex++] = IOUtils.DIGITS[ch & 15];
                end += 5;
            } else if (ch < IOUtils.specicalFlags_doubleQuotes.length //
		            && IOUtils.specicalFlags_doubleQuotes[ch] != 0 //
		            || (ch == '/' && isEnabled(SerializerFeature.WriteSlashAsSpecial))) {
                buf[bufIndex++] = '\\';
                if (IOUtils.specicalFlags_doubleQuotes[ch] == 4) {
                    buf[bufIndex++] = 'u';
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 12) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 8) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 4) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[ch & 15];
                    end += 5;
                } else {
                    buf[bufIndex++] = replaceChars[(int) ch];
                    end++;
                }
            } else {
                if (ch == '\u2028' || ch == '\u2029') {
                    buf[bufIndex++] = '\\';
                    buf[bufIndex++] = 'u';
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 12) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 8) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[(ch >>> 4) & 15];
                    buf[bufIndex++] = IOUtils.DIGITS[ch & 15];
                    end += 5;
                } else {
                    buf[bufIndex++] = ch;
                }
            }
        }
    }

    private void updateCount(int newcount) {
        ensureCapacity(newcount);
        count = newcount;
    }
    
    public void writeFieldNameDirect(String text) {
        int len = text.length();
        int newcount = count + len + 3;

        ensureCapacity(newcount);

        int start = count + 1;

        buf[count] = '\"';
        text.getChars(0, len, buf, start);

        count = newcount;
        buf[count - 2] = '\"';
        buf[count - 1] = ':';
    }

    public void write(List<String> list) {
        if (list.isEmpty()) {
            write("[]");
            return;
        }

        int offset = count;
        int initOffset = offset;
        for (int i = 0, list_size = list.size();i < list_size;++i) {
            String text = list.get(i);

            boolean hasSpecial = false;
            if (text == null) {
                hasSpecial = true;
            } else {
                hasSpecial = hasSpecialCharacter(text, hasSpecial);
            }

            if (hasSpecial) {
                text = writeStringListWithBrackets(list, initOffset, text);
                return;
            }

            offset = calculateAndExpandBufferCapacity(list, offset, i, text);
        }
        buf[offset++] = ']';
        count = offset;
    }

    private int calculateAndExpandBufferCapacity(List<String> list, int offset, int i, String text) {
        int newcount = offset + text.length() + 3;
        if (i == list.size() - 1) {
            newcount++;
        }
        if (newcount > buf.length) {
            count = offset;
            expandCapacity(newcount);
        }

        if (i == 0) {
            buf[offset++] = '[';
        } else {
            buf[offset++] = ',';
        }
        buf[offset++] = '"';
        text.getChars(0, text.length(), buf, offset);
        offset += text.length();
        buf[offset++] = '"';
        return offset;
    }

    private String writeStringListWithBrackets(List<String> list, int initOffset, String text) {
        count = initOffset;
        write('[');
        for (int j = 0;j < list.size();++j) {
            text = writeStringAtIndex(list, j);
        }
        write(']');
        return text;
    }

    private String writeStringAtIndex(List<String> list, int j) {
        String text;
        text = list.get(j);
        if (j != 0) {
            write(',');
        }

        if (text == null) {
            write("null");
        } else {
            writeStringWithDoubleQuote(text, (char) 0);
        }
        return text;
    }

    private boolean hasSpecialCharacter(String text, boolean hasSpecial) {
        for (int j = 0, len = text.length();j < len;++j) {
            char ch = text.charAt(j);
            if (hasSpecial = ch < ' ' //
                              || ch > '~' //
                              || ch == '"' //
                              || ch == '\\') {
                break;
            }
        }
        return hasSpecial;
    }

    public void writeFieldValue(char seperator, String name, char value) {
        write(seperator);
        writeFieldName(name);
        if (value == 0) {
            writeString("\u0000");
        } else {
            writeString(Character.toString(value));
        }
    }

    public void writeFieldValue(char seperator, String name, boolean value) {
        if (!quoteFieldNames) {
            write(seperator);
            writeFieldName(name);
            write(value);
            return;
        }
        int intSize = value ? 4 : 5;

        int nameLen = name.length();
        int newcount = count + nameLen + 4 + intSize;
        if (newcount > buf.length) {
            if (writer != null) {
                writeNameValuePair(seperator, name, value);
                return;
            }
            expandCapacity(newcount);
        }

        int start = count;
        count = newcount;

        buf[start] = seperator;

        int nameEnd = start + nameLen + 1;

        buf[start + 1] = keySeperator;

        name.getChars(0, nameLen, buf, start + 2);

        buf[nameEnd + 1] = keySeperator;

        if (value) {
            System.arraycopy(VALUE_TRUE, 0, buf, nameEnd + 2, 5);
        } else {
            System.arraycopy(VALUE_FALSE, 0, buf, nameEnd + 2, 6);
        }
    }

    private void writeNameValuePair(char seperator, String name, boolean value) {
        write(seperator);
        writeString(name);
        write(':');
        write(value);
    }

    public void write(boolean value) {
        if (value) {
            write("true");
            return;
        }
        write("false");
    }

    public void writeFieldValue(char seperator, String name, int value) {
        if (value == Integer.MIN_VALUE || !quoteFieldNames) {
            writeSeparatedFieldWithIntValue(seperator, name, value);
            return;
        }

        int intSize = value < 0 ? IOUtils.stringSize(-value) + 1 : IOUtils.stringSize(value);

        int nameLen = name.length();
        int newcount = count + nameLen + 4 + intSize;
        if (newcount > buf.length) {
            if (writer != null) {
                writeSeparatedFieldWithIntValue(seperator, name, value);
                return;
            }
            expandCapacity(newcount);
        }

        int start = count;
        count = newcount;

        buf[start] = seperator;

        int nameEnd = start + nameLen + 1;

        buf[start + 1] = keySeperator;

        name.getChars(0, nameLen, buf, start + 2);

        buf[nameEnd + 1] = keySeperator;
        buf[nameEnd + 2] = ':';

        IOUtils.getChars(value, count, buf);
    }

    private void writeSeparatedFieldWithIntValue(char seperator, String name, int value) {
        write(seperator);
        writeFieldName(name);
        writeInt(value);
    }

    public void writeFieldValue(char seperator, String name, long value) {
        if (value == Long.MIN_VALUE
                || !quoteFieldNames
                || isEnabled(SerializerFeature.BrowserCompatible.mask)
        ) {
            writeSeparatedFieldNameWithValue(seperator, name, value);
            return;
        }

        int intSize = value < 0 ? IOUtils.stringSize(-value) + 1 : IOUtils.stringSize(value);

        int nameLen = name.length();
        int newcount = count + nameLen + 4 + intSize;
        if (newcount > buf.length) {
            if (writer != null) {
                writeSeparatedFieldNameWithValue(seperator, name, value);
                return;
            }
            expandCapacity(newcount);
        }

        int start = count;
        count = newcount;

        buf[start] = seperator;

        int nameEnd = start + nameLen + 1;

        buf[start + 1] = keySeperator;

        name.getChars(0, nameLen, buf, start + 2);

        buf[nameEnd + 1] = keySeperator;
        buf[nameEnd + 2] = ':';

        IOUtils.getChars(value, count, buf);
    }

    private void writeSeparatedFieldNameWithValue(char seperator, String name, long value) {
        write(seperator);
        writeFieldName(name);
        writeLong(value);
    }

    public void writeFieldValue(char seperator, String name, float value) {
        write(seperator);
        writeFieldName(name);
        writeFloat(value, false);
    }

    public void writeFieldValue(char seperator, String name, double value) {
        write(seperator);
        writeFieldName(name);
        writeDouble(value, false);
    }

    public void writeFieldValue(char seperator, String name, String value) {
        if (quoteFieldNames) {
            writeFieldValueBasedOnQuoteUsage(seperator, name, value);
            return;
        }
        writeSeparatedFieldNameAndValue(seperator, name, value);
    }

    private void writeFieldValueBasedOnQuoteUsage(char seperator, String name, String value) {
        if (useSingleQuotes){
            writeSeparatedFieldNameAndValue(seperator, name, value);
            return;
        }
        writeFieldValueWithBrowserCheck(seperator, name, value);
    }

    private void writeFieldValueWithBrowserCheck(char seperator, String name, String value) {
        if (!isEnabled(SerializerFeature.BrowserCompatible)) {
            writeFieldValueStringWithDoubleQuoteCheck(seperator, name, value);
            return;
        }
        write(seperator);
        writeStringWithDoubleQuote(name, ':');
        writeStringWithDoubleQuote(value, (char) 0);
    }

    private void writeSeparatedFieldNameAndValue(char seperator, String name, String value) {
        write(seperator);
        writeFieldName(name);
        if (value == null) {
            writeNull();
        } else {
            writeString(value);
        }
    }

    public void writeFieldValueStringWithDoubleQuoteCheck(char seperator, String name, String value) {
        int nameLen = name.length();
        int valueLen;

        int newcount = count;

        if (value == null) {
            valueLen = 4;
            newcount += nameLen + 8;
        } else {
            valueLen = value.length();
            newcount += nameLen + valueLen + 6;
        }

        if (newcount > buf.length) {
            if (writer != null) {
                write(seperator);
                writeStringWithDoubleQuote(name, ':');
                writeStringWithDoubleQuote(value, (char) 0);
                return;
            }
            expandCapacity(newcount);
        }

        buf[count] = seperator;

        int nameStart = count + 2;
        int nameEnd = nameStart + nameLen;

        buf[count + 1] = '\"';
        name.getChars(0, nameLen, buf, nameStart);

        count = newcount;

        buf[nameEnd] = '\"';

        int index = nameEnd + 1;
        buf[index++] = ':';

        if (value == null) {
            index = writeNullToBuffer(index);
            return;
        }

        buf[index++] = '"';

        int valueStart = index;
        int valueEnd = valueStart + valueLen;

        value.getChars(0, valueLen, buf, valueStart);

        int specialCount = 0;
        int lastSpecialIndex = -1;
        int firstSpecialIndex = -1;
        char lastSpecial = '\0';

        for (int i = valueStart;i < valueEnd;++i) {
            char ch = buf[i];

            if (ch >= ']') {
                if (ch >= 0x7F //
                    && (ch == '\u2028' //
                        || ch == '\u2029' //
                        || ch < 0xA0)) {
                    firstSpecialIndex = setFirstSpecialIndex(firstSpecialIndex, i);

                    specialCount++;
                    lastSpecialIndex = i;
                    lastSpecial = ch;
                    newcount += 4;
                }
                continue;
            }

            boolean special = (ch < 64 && (sepcialBits & (1L << ch)) != 0) || ch == '\\';
            if (special) {
                specialCount++;
                lastSpecialIndex = i;
                lastSpecial = ch;

                if (ch == '('
                        || ch == ')'
                        || ch == '<'
                        || ch == '>'
                        || (ch < IOUtils.specicalFlags_doubleQuotes.length //
                        && IOUtils.specicalFlags_doubleQuotes[ch] == 4) //
                        ) {
                    newcount += 4;
                }

                firstSpecialIndex = setFirstSpecialIndex(firstSpecialIndex, i);
            }
        }

        if (specialCount > 0) {
            updateCountAndEncodeSpecials(value, newcount, valueStart, valueEnd, specialCount, lastSpecialIndex, firstSpecialIndex,
                    lastSpecial);
        }
        

        buf[count - 1] = '\"';
    }

    private void updateCountAndEncodeSpecials(String value, int newcount, int valueStart, int valueEnd, int specialCount,
            int lastSpecialIndex, int firstSpecialIndex, char lastSpecial) {
        newcount += specialCount;
        updateCount(newcount);

        if (specialCount == 1) {
            encodeSpecialCharacters___(valueEnd, lastSpecialIndex, lastSpecial);
        } else if (specialCount > 1) {
            encodeSpecialCharacters(value, valueStart, valueEnd, firstSpecialIndex);}
    }

    private void encodeSpecialCharacters___(int valueEnd, int lastSpecialIndex, char lastSpecial) {
        if (lastSpecial == '\u2028') {
            shiftArrayElements(valueEnd, lastSpecialIndex);
            lastSpecialIndex = insertUnicodeEscapeSequence(lastSpecialIndex);
            buf[++lastSpecialIndex] = '8';
            return;
        }
        if (lastSpecial == '\u2029') {
            shiftArrayElements(valueEnd, lastSpecialIndex);
            lastSpecialIndex = insertUnicodeEscapeSequence(lastSpecialIndex);
            buf[++lastSpecialIndex] = '9';
        }
        else if (lastSpecial == '(' || lastSpecial == ')' || lastSpecial == '<' || lastSpecial == '>') {
            char ch = lastSpecial;
            encodeAndShiftSpecialChar(valueEnd, lastSpecialIndex, ch);
        }
        else {
            handleSpecialCharacterEncoding(valueEnd, lastSpecialIndex, lastSpecial);
        }
    }

    private int writeNullToBuffer(int index) {
        buf[index++] = 'n';
        buf[index++] = 'u';
        buf[index++] = 'l';
        buf[index++] = 'l';
        return index;
    }

    private int insertUnicodeEscapeSequence(int lastSpecialIndex) {
        buf[lastSpecialIndex] = '\\';
        buf[++lastSpecialIndex] = 'u';
        buf[++lastSpecialIndex] = '2';
        buf[++lastSpecialIndex] = '0';
        buf[++lastSpecialIndex] = '2';
        return lastSpecialIndex;
    }

    private void shiftArrayElements(int valueEnd, int lastSpecialIndex) {
        int srcPos = lastSpecialIndex + 1;
        int destPos = lastSpecialIndex + 6;
        int LengthOfCopy = valueEnd - lastSpecialIndex - 1;
        System.arraycopy(buf, srcPos, buf, destPos, LengthOfCopy);
    }

    private int setFirstSpecialIndex(int firstSpecialIndex, int i) {
        if (firstSpecialIndex == -1) {
            firstSpecialIndex = i;
        }
        return firstSpecialIndex;
    }

    public void writeFieldValueStringWithDoubleQuote(char seperator, String name, String value) {
        int nameLen = name.length();
        int valueLen;

        int newcount = count;

        valueLen = value.length();
        newcount += nameLen + valueLen + 6;

        if (newcount > buf.length) {
            if (writer != null) {
                write(seperator);
                writeStringWithDoubleQuote(name, ':');
                writeStringWithDoubleQuote(value, (char) 0);
                return;
            }
            expandCapacity(newcount);
        }

        buf[count] = seperator;

        int nameStart = count + 2;
        int nameEnd = nameStart + nameLen;

        buf[count + 1] = '\"';
        name.getChars(0, nameLen, buf, nameStart);

        count = newcount;

        buf[nameEnd] = '\"';

        int index = nameEnd + 1;
        buf[index++] = ':';
        buf[index++] = '"';

        int valueStart = index;
        value.getChars(0, valueLen, buf, valueStart);
        buf[count - 1] = '\"';
    }


    
    public void writeFieldValue(char seperator, String name, Enum<?> value) {
        if (value == null) {
            write(seperator);
            writeFieldName(name);
            writeNull();
            return;
        }

        if (writeEnumUsingName && !writeEnumUsingToString) {
            writeEnumFieldValue(seperator, name, value.name());
        } else if (writeEnumUsingToString) {
            writeEnumFieldValue(seperator, name, value.toString());
        } else {
            writeFieldValue(seperator, name, value.ordinal());
        }
    }

    private void writeEnumFieldValue(char seperator, String name, String value) {
        if (useSingleQuotes) {
            writeFieldValue(seperator, name, value);
            return;
        }
        writeFieldValueStringWithDoubleQuote(seperator, name, value);
    }

    public void writeFieldValue(char seperator, String name, BigDecimal value) {
        write(seperator);
        writeFieldName(name);
        if (value == null) {
            writeNull();
        } else {
            int scale = value.scale();
            write(isEnabled(SerializerFeature.WriteBigDecimalAsPlain) && scale >= -100 && scale < 100
                    ? value.toPlainString()
                    : value.toString()
            );
        }
    }

    public void writeString(String text, char seperator) {
        if (useSingleQuotes) {
            writeStringWithSingleQuote(text);
            write(seperator);
            return;
        }
        writeStringWithDoubleQuote(text, seperator);
    }

    public void writeString(String text) {
        if (useSingleQuotes) {
            writeStringWithSingleQuote(text);
            return;
        }
        writeStringWithDoubleQuote(text, (char) 0);
    }

    public void writeString(char[] chars) {
        if (useSingleQuotes) {
            writeStringWithSingleQuote(chars);
            return;
        }
        String text = new String(chars);
        writeStringWithDoubleQuote(text, (char) 0);
    }

    protected void writeStringWithSingleQuote(String text) {
        if (text == null) {
            writeNullString();
            return;
        }

        int len = text.length();
        int newcount = count + len + 2;
        if (newcount > buf.length) {
            if (writer != null) {
                writeEscapedText(text);
                return;
            }
            expandCapacity(newcount);
        }

        int start = count + 1;
        int end = start + len;

        buf[count] = '\'';
        text.getChars(0, len, buf, start);
        count = newcount;

        int specialCount = 0;
        int lastSpecialIndex = -1;
        char lastSpecial = '\0';
        for (int i = start;i < end;++i) {
            char ch = buf[i];
            if (ch <= 13 || ch == '\\' || ch == '\'' //
                || (ch == '/' && isEnabled(SerializerFeature.WriteSlashAsSpecial))) {
                specialCount++;
                lastSpecialIndex = i;
                lastSpecial = ch;
            }
        }

        newcount += specialCount;
        updateCount(newcount);

        if (specialCount == 1) {
            shiftAndReplaceSpecialCharacter(end, lastSpecialIndex, lastSpecial);
        } else if (specialCount > 1) {
            System.arraycopy(buf, lastSpecialIndex + 1, buf, lastSpecialIndex + 2, end - lastSpecialIndex - 1);
            buf[lastSpecialIndex] = '\\';
            buf[++lastSpecialIndex] = replaceChars[(int) lastSpecial];
            end++;
            for (int i = lastSpecialIndex - 2;i >= start;--i) {
                end = encodeSpecialCharacters____(end, i);
            }
        }

        buf[count - 1] = '\'';
    }

    private int encodeSpecialCharacters____(int end, int i) {
        char ch = buf[i];

        if (ch <= 13 || ch == '\\' || ch == '\'' //
		    || (ch == '/' && isEnabled(SerializerFeature.WriteSlashAsSpecial))) {
            end = shiftAndEncodeCharacter(end, i, ch);
        }
        return end;
    }

    private void writeEscapedText(String text) {
        write('\'');
        for (int i = 0;i < text.length();++i) {
            handleCharacterEscaping(text, i);
        }
        write('\'');
    }

    private int shiftAndEncodeCharacter(int end, int i, char ch) {
        System.arraycopy(buf, i + 1, buf, i + 2, end - i - 1);
        buf[i] = '\\';
        buf[i + 1] = replaceChars[(int) ch];
        end++;
        return end;
    }

    private void handleCharacterEscaping(String text, int i) {
        char ch = text.charAt(i);
        if (ch <= 13 || ch == '\\' || ch == '\'' //
		    || (ch == '/' && isEnabled(SerializerFeature.WriteSlashAsSpecial))) {
            write('\\');
            write(replaceChars[(int) ch]);
        } else {
            write(ch);
        }
    }

    private void writeNullString() {
        int newcount = count + 4;
        ensureCapacity(newcount);
        "null".getChars(0, 4, buf, count);
        count = newcount;
    }

    private void shiftAndReplaceSpecialCharacter(int end, int lastSpecialIndex, char lastSpecial) {
        System.arraycopy(buf, lastSpecialIndex + 1, buf, lastSpecialIndex + 2, end - lastSpecialIndex - 1);
        buf[lastSpecialIndex] = '\\';
        buf[++lastSpecialIndex] = replaceChars[(int) lastSpecial];
    }

    private void ensureCapacity(int newcount) {
        if (newcount > buf.length) {
            expandCapacity(newcount);
        }
    }

    protected void writeStringWithSingleQuote(char[] chars) {
        if (chars == null) {
            writeNullString();
            return;
        }

        int len = chars.length;
        int newcount = count + len + 2;
        if (newcount > buf.length) {
            if (writer != null) {
                writeEscapedCharsInQuotes(chars);
                return;
            }
            expandCapacity(newcount);
        }

        int start = count + 1;
        int end = start + len;

        buf[count] = '\'';
//        text.getChars(0, len, buf, start);
        System.arraycopy(chars, 0, buf, start, chars.length);
        count = newcount;

        int specialCount = 0;
        int lastSpecialIndex = -1;
        char lastSpecial = '\0';
        for (int i = start;i < end;++i) {
            char ch = buf[i];
            if (ch <= 13 || ch == '\\' || ch == '\'' //
                    || (ch == '/' && isEnabled(SerializerFeature.WriteSlashAsSpecial))) {
                specialCount++;
                lastSpecialIndex = i;
                lastSpecial = ch;
            }
        }

        newcount += specialCount;
        updateCount(newcount);

        if (specialCount == 1) {
            shiftAndReplaceSpecialCharacter(end, lastSpecialIndex, lastSpecial);} else if (specialCount > 1) {
            System.arraycopy(buf, lastSpecialIndex + 1, buf, lastSpecialIndex + 2, end - lastSpecialIndex - 1);
            buf[lastSpecialIndex] = '\\';
            buf[++lastSpecialIndex] = replaceChars[(int) lastSpecial];
            end++;
            for (int i = lastSpecialIndex - 2;i >= start;--i) {
                end = encodeSpecialCharacters____(end, i);}
        }

        buf[count - 1] = '\'';
    }

    private void writeEscapedCharsInQuotes(char[] chars) {
        write('\'');
        for (int i = 0;i < chars.length;++i) {
            handleCharacterEscaping_(chars, i);
        }
        write('\'');
    }

    private void handleCharacterEscaping_(char[] chars, int i) {
        char ch = chars[i];
        if (ch <= 13 || ch == '\\' || ch == '\'' //
		        || (ch == '/' && isEnabled(SerializerFeature.WriteSlashAsSpecial))) {
            write('\\');
            write(replaceChars[(int) ch]);
        } else {
            write(ch);
        }
    }

    public void writeFieldName(String key) {
        writeFieldName(key, false);
    }

    public void writeFieldName(String key, boolean checkSpecial) {
        if (key == null) {
            write("null:");
            return;
        }

        if (useSingleQuotes) {
            writeKeyWithOptionalQuote(key);
        } else {
            writeKeyWithAppropriateHandling(key);
        }
    }

    private void writeKeyWithAppropriateHandling(String key) {
        if (quoteFieldNames) {
            writeStringWithDoubleQuote(key, ':');
            return;
        }
        writeKeyWithSpecialHandling(key);
    }

    private void writeKeyWithSpecialHandling(String key) {
        boolean hashSpecial = key.length() == 0;
        hashSpecial = checkSpecialCharacterInKey(key, hashSpecial);
        if (hashSpecial) {
            writeStringWithDoubleQuote(key, ':');
        } else {
            write(key);
            write(':');
        }
    }

    private boolean checkSpecialCharacterInKey(String key, boolean hashSpecial) {
        for (int i = 0;i < key.length();++i) {
            char ch = key.charAt(i);
            boolean special = (ch < 64 && (sepcialBits & (1L << ch)) != 0) || ch == '\\';
            if (special) {
                hashSpecial = true;
                break;
            }
        }
        return hashSpecial;
    }

    private void writeKeyWithOptionalQuote(String key) {
        if (quoteFieldNames) {
            writeStringWithSingleQuote(key);
            write(':');
            return;
        }
        writeKeyWithSingleQuoteIfHasSpecial(key);
    }

    private void writeKeyWithSingleQuoteIfHasSpecial(String text) {
        byte[] specicalFlags_singleQuotes = IOUtils.specicalFlags_singleQuotes;

        int len = text.length();
        int newcount = count + len + 1;
        if (newcount > buf.length) {
            if (writer != null) {
                if (len == 0) {
                    write('\'');
                    write('\'');
                    write(':');
                    return;
                }

                encodeAndWriteTextWithSpecialHandling(text, specicalFlags_singleQuotes, len);
                return;
            }

            expandCapacity(newcount);
        }

        if (len == 0) {
            expandAndInsertSpecialCharacters();
            return;
        }

        int start = count;
        int end = start + len;

        text.getChars(0, len, buf, start);
        count = newcount;

        boolean hasSpecial = false;

        for (int i = start;i < end;++i) {
            char ch = buf[i];
            if (ch < specicalFlags_singleQuotes.length && specicalFlags_singleQuotes[ch] != 0) {
                if (!hasSpecial) {
                    newcount += 3;
                    updateCount(newcount);

                    System.arraycopy(buf, i + 1, buf, i + 3, end - i - 1);
                    System.arraycopy(buf, 0, buf, 1, i);
                    buf[start] = '\'';
                    buf[++i] = '\\';
                    buf[++i] = replaceChars[(int) ch];
                    end += 2;
                    buf[count - 2] = '\'';

                    hasSpecial = true;
                } else {
                    newcount++;
                    updateCount(newcount);

                    System.arraycopy(buf, i + 1, buf, i + 2, end - i);
                    buf[i] = '\\';
                    buf[++i] = replaceChars[(int) ch];
                    end++;
                }
            }
        }

        buf[newcount - 1] = ':';
    }

    private void encodeAndWriteTextWithSpecialHandling(String text, byte[] specicalFlags_singleQuotes, int len) {
        boolean hasSpecial = checkSpecialCharacters__(text, specicalFlags_singleQuotes, len);

        writeSingleQuoteIfSpecial(hasSpecial);
        for (int i = 0;i < len;++i) {
            handleCharacterEncoding_(text, specicalFlags_singleQuotes, i);
        }
        writeSingleQuoteIfSpecial(hasSpecial);
        write(':');
    }

    private void expandAndInsertSpecialCharacters() {
        int newCount = count + 3;
        if (newCount > buf.length) {
            expandCapacity(count + 3);
        }
        buf[count++] = '\'';
        buf[count++] = '\'';
        buf[count++] = ':';
    }

    private void handleCharacterEncoding_(String text, byte[] specicalFlags_singleQuotes, int i) {
        char ch = text.charAt(i);
        if (ch < specicalFlags_singleQuotes.length && specicalFlags_singleQuotes[ch] != 0) {
            write('\\');
            write(replaceChars[(int) ch]);
        } else {
            write(ch);
        }
    }

    private boolean checkSpecialCharacters__(String text, byte[] specicalFlags_singleQuotes, int len) {
        boolean hasSpecial = false;
        for (int i = 0;i < len;++i) {
            char ch = text.charAt(i);
            if (ch < specicalFlags_singleQuotes.length && specicalFlags_singleQuotes[ch] != 0) {
                hasSpecial = true;
                break;
            }
        }
        return hasSpecial;
    }

    private void writeSingleQuoteIfSpecial(boolean hasSpecial) {
        if (hasSpecial) {
            write('\'');
        }
    }

    public void flush() {
        if (writer == null) {
            return;
        }

        try {
            writer.write(buf, 0, count);
            writer.flush();
        } catch (IOException e) {
            throw new JSONException(e.getMessage(), e);
        }
        count = 0;
    }

    /**
     * @deprecated
     */
    public void reset() {
        count = 0;
    }
}
