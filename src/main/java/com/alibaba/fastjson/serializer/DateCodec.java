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

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONScanner;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.deserializer.AbstractDateDeserializer;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.alibaba.fastjson.util.IOUtils;
import com.alibaba.fastjson.util.TypeUtils;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class DateCodec extends AbstractDateDeserializer implements ObjectSerializer, ObjectDeserializer {

    public final static DateCodec instance = new DateCodec();
    
    public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType, int features) throws IOException {
        SerializeWriter out = serializer.out;

        if (object == null) {
            out.writeNull();
            return;
        }

        Class<?> clazz = object.getClass();
        if (clazz == java.sql.Date.class && !out.isEnabled(SerializerFeature.WriteDateUseDateFormat)) {
            long millis = ((java.sql.Date) object).getTime();
            TimeZone timeZone = serializer.timeZone;
            int offset = timeZone.getOffset(millis);
            //
            if ((millis + offset) % (24 * 1000 * 3600) == 0
                    && !SerializerFeature.isEnabled(out.features, features, SerializerFeature.WriteClassName)) {
                out.writeString(object.toString());
                return;
            }
        }

        if (clazz == java.sql.Time.class) {
            long millis = ((java.sql.Time) object).getTime();
            if ("unixtime".equals(serializer.getDateFormatPattern())) {
                long seconds = millis / 1000;
                out.writeLong(seconds);
                return;
            }

            if ("millis".equals(serializer.getDateFormatPattern())) {
                out.writeLong(millis);
                return;
            }

            if (millis < 24L * 60L * 60L * 1000L) {
                out.writeString(object.toString());
                return;
            }
        }

        int nanos = 0;
        if (clazz == java.sql.Timestamp.class) {
            java.sql.Timestamp ts = (java.sql.Timestamp) object;
            nanos = ts.getNanos();
        }
        
        Date date;
        if (object instanceof Date) {
            date = (Date) object;
        } else {
            date = TypeUtils.castToDate(object);
        }

        if ("unixtime".equals(serializer.getDateFormatPattern())) {
            long seconds = date.getTime() / 1000;
            out.writeLong(seconds);
            return;
        }

        if ("millis".equals(serializer.getDateFormatPattern())) {
            long millis = date.getTime();
            out.writeLong(millis);
            return;
        }

        if (out.isEnabled(SerializerFeature.WriteDateUseDateFormat)) {
            serializeDate(serializer, out, date);
            return;
        }
        
        if (out.isEnabled(SerializerFeature.WriteClassName)) {
            if (clazz != fieldType) {
                if (clazz == java.util.Date.class) {
                    out.write("new Date(");
                    out.writeLong(((Date) object).getTime());
                    out.write(')');
                } else {
                    writeDateObject(serializer, object, out, clazz);
                }
                return;
            }
        }

        long time = date.getTime();
        if (out.isEnabled(SerializerFeature.UseISO8601DateFormat)) {
            char quote = out.isEnabled(SerializerFeature.UseSingleQuotes) ? '\'' : '\"'; 
            out.write(quote);

            Calendar calendar = Calendar.getInstance(serializer.timeZone, serializer.locale);
            calendar.setTimeInMillis(time);

            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH) + 1;
            int day = calendar.get(Calendar.DAY_OF_MONTH);
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            int minute = calendar.get(Calendar.MINUTE);
            int second = calendar.get(Calendar.SECOND);
            int millis = calendar.get(Calendar.MILLISECOND);

            char[] buf;
            if (nanos > 0) {
                buf = formatDateTime(nanos, year, month, day, hour, minute, second);
            } else if (millis != 0) {
                buf = formatDateTimeArray(year, month, day, hour, minute, second, millis);

            } else {
                buf = formatDateTimeArray__(year, month, day, hour, minute, second);
            }


            if (nanos > 0) { // java.sql.Timestamp
                writeTrimmedBufferWithQuote(out, quote, buf);
                return;
            }

            out.write(buf);

            float timeZoneF = calendar.getTimeZone().getOffset(calendar.getTimeInMillis()) / (3600.0f * 1000);
            int timeZone = (int) timeZoneF;
            if (timeZone == 0.0) {
                out.write('Z');
            } else {
                writeTimeZone(out, timeZoneF, timeZone);
            }

            out.write(quote);
        } else {
            out.writeLong(time);
        }
    }

    private void writeTrimmedBufferWithQuote(SerializeWriter out, char quote, char[] buf) {
        int i = 0;
        i = findNonZeroCharacterIndex(buf, i);
        out.write(buf, 0, buf.length - i);
        out.write(quote);
    }

    private char[] formatDateTimeArray__(int year, int month, int day, int hour, int minute, int second) {
        char[] buf;
        if (second == 0 && minute == 0 && hour == 0) {
            buf = "0000-00-00".toCharArray();
            formatDate(year, month, day, buf);
        } else {
            buf = formatDateTimeArray_(year, month, day, hour, minute, second);
        }
        return buf;
    }

    private void serializeDate(JSONSerializer serializer, SerializeWriter out, Date date) {
        DateFormat format = serializer.getDateFormat();
        if (format == null) {
            // 如果是通过FastJsonConfig进行设置，优先从FastJsonConfig获取
		    format = createDateFormat(serializer);
        }
        String text = format.format(date);
        out.writeString(text);
    }

    private void writeTimeZone(SerializeWriter out, float timeZoneF, int timeZone) {
        if (timeZone > 9) {
            out.write('+');
            out.writeInt(timeZone);
        } else if (timeZone > 0) {
            out.write('+');
            out.write('0');
            out.writeInt(timeZone);
        } else if (timeZone < -9) {
            out.write('-');
            out.writeInt(-timeZone);
        } else if (timeZone < 0) {
            out.write('-');
            out.write('0');
            out.writeInt(-timeZone);
        }
        out.write(':');
        // handles uneven timeZones 30 mins, 45 mins
		// this would always be less than 60
		int offSet = (int) (Math.abs(timeZoneF - timeZone) * 60);
        out.append(String.format("%02d", offSet));
    }

    private int findNonZeroCharacterIndex(char[] buf, int i) {
        i = findNonZeroCharIndexFromEnd(buf, i);
        return i;
    }

    private int findNonZeroCharIndexFromEnd(char[] buf, int i) {
        i = findTrailingZeroIndex(buf, i);
        return i;
    }

    private int findTrailingZeroIndex(char[] buf, int i) {
        i = findNonZeroCharIndexFromEnd_(buf, i);
        return i;
    }

    private int findNonZeroCharIndexFromEnd_(char[] buf, int i) {
        for (;i < 9;++i) {
            int off = buf.length - i - 1;
            if (buf[off] != '0') {
                break;
            }
        }
        return i;
    }

    private char[] formatDateTimeArray_(int year, int month, int day, int hour, int minute, int second) {
        char[] buf;
        buf = "0000-00-00T00:00:00".toCharArray();
        IOUtils.getChars(second, 19, buf);
        IOUtils.getChars(minute, 16, buf);
        IOUtils.getChars(hour, 13, buf);
        formatDate(year, month, day, buf);
        return buf;
    }

    private char[] formatDateTimeArray(int year, int month, int day, int hour, int minute, int second, int millis) {
        char[] buf;
        buf = "0000-00-00T00:00:00.000".toCharArray();
        IOUtils.getChars(millis, 23, buf);
        IOUtils.getChars(second, 19, buf);
        IOUtils.getChars(minute, 16, buf);
        IOUtils.getChars(hour, 13, buf);
        formatDate(year, month, day, buf);
        return buf;
    }

    private char[] formatDateTime(int nanos, int year, int month, int day, int hour, int minute, int second) {
        char[] buf;
        buf = "0000-00-00 00:00:00.000000000".toCharArray();
        IOUtils.getChars(nanos, 29, buf);
        IOUtils.getChars(second, 19, buf);
        IOUtils.getChars(minute, 16, buf);
        IOUtils.getChars(hour, 13, buf);
        formatDate(year, month, day, buf);
        return buf;
    }

    private void writeDateObject(JSONSerializer serializer, Object object, SerializeWriter out, Class<?> clazz) {
        out.write('{');
        out.writeFieldName(JSON.DEFAULT_TYPE_KEY);
        serializer.write(clazz.getName());
        out.writeFieldValue(',', "val", ((Date) object).getTime());
        out.write('}');
    }

    private DateFormat createDateFormat(JSONSerializer serializer) {
        DateFormat format;
        String dateFormatPattern = serializer.getFastJsonConfigDateFormatPattern();
        if (dateFormatPattern == null) {
            dateFormatPattern = JSON.DEFFAULT_DATE_FORMAT;
        }

        format = new SimpleDateFormat(dateFormatPattern, serializer.locale);
        format.setTimeZone(serializer.timeZone);
        return format;
    }

    private void formatDate(int year, int month, int day, char[] buf) {
        IOUtils.getChars(day, 10, buf);
        IOUtils.getChars(month, 7, buf);
        IOUtils.getChars(year, 4, buf);
    }
    
    @SuppressWarnings("unchecked")
    public <T> T cast(DefaultJSONParser parser, Type clazz, Object fieldName, Object val) {

        if (val == null) {
            return null;
        }

        if (val instanceof java.util.Date)
            return (T) val;
        if (val instanceof BigDecimal)
            return (T) new java.util.Date(TypeUtils.longValue((BigDecimal) val));
        if (val instanceof Number)
            return (T) new java.util.Date(((Number) val).longValue());
        if (val instanceof String) {
            return parseJsonDate(parser, clazz, val);
        }

        throw new JSONException("parse error");
    }

    private <T> T parseJsonDate(DefaultJSONParser parser, Type clazz, Object val) {
        String strVal = (String) val;
        if (strVal.length() == 0) {
            return null;
        }

        if (strVal.length() == 23 && strVal.endsWith(" 000")) {
            strVal = strVal.substring(0, 19);
        }

        {
            JSONScanner dateLexer = new JSONScanner(strVal);
            try {
                if (dateLexer.scanISO8601DateIfMatch(false)) {
                    return getCalendarOrTime(clazz, dateLexer);
                }
            } finally {
                dateLexer.close();
            }
        }

        String dateFomartPattern = parser.getDateFomartPattern();
        boolean formatMatch = strVal.length() == dateFomartPattern.length()
                || (strVal.length() == 22 && dateFomartPattern.equals("yyyyMMddHHmmssSSSZ"))
                || (strVal.indexOf('T') != -1 && dateFomartPattern.contains("'T'") && strVal.length() + 2 == dateFomartPattern.length())
                ;
        if (formatMatch) {
            DateFormat dateFormat = parser.getDateFormat();
            try {
                return (T) dateFormat.parse(strVal);
            } catch (ParseException e) {
                // skip
		    }
        }
        
        if (strVal.startsWith("/Date(") && strVal.endsWith(")/")) {
            String dotnetDateStr = strVal.substring(6, strVal.length() - 2);
            strVal = dotnetDateStr;
        }

        if ("0000-00-00".equals(strVal)
                || "0000-00-00T00:00:00".equalsIgnoreCase(strVal)
                || "0001-01-01T00:00:00+08:00".equalsIgnoreCase(strVal)) {
            return null;
        }

        int index = strVal.lastIndexOf('|');
        if (index > 20) {
            String tzStr = strVal.substring(index + 1);
            TimeZone timeZone = TimeZone.getTimeZone(tzStr);
            if (!"GMT".equals(timeZone.getID())) {
                String subStr = strVal.substring(0, index);
                JSONScanner dateLexer = new JSONScanner(subStr);
                try {
                    if (dateLexer.scanISO8601DateIfMatch(false)) {
                        return getCalendarOrTimeWithTimeZone(clazz, timeZone, dateLexer);
                    }
                } finally {
                    dateLexer.close();
                }
            }
        }

        // 2017-08-14 19:05:30.000|America/Los_Angeles
//            
		long longVal = Long.parseLong(strVal);
        return (T) new java.util.Date(longVal);
    }

    private <T> T getCalendarOrTimeWithTimeZone(Type clazz, TimeZone timeZone, JSONScanner dateLexer) {
        Calendar calendar = dateLexer.getCalendar();

        calendar.setTimeZone(timeZone);

        if (clazz == Calendar.class) {
            return (T) calendar;
        }

        return (T) calendar.getTime();
    }

    private <T> T getCalendarOrTime(Type clazz, JSONScanner dateLexer) {
        Calendar calendar = dateLexer.getCalendar();

        if (clazz == Calendar.class) {
            return (T) calendar;
        }

        return (T) calendar.getTime();
    }

    public int getFastMatchToken() {
        return JSONToken.LITERAL_INT;
    }

}
