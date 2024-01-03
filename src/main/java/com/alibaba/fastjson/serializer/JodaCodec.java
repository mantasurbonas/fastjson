package com.alibaba.fastjson.serializer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.TimeZone;

import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.alibaba.fastjson.util.TypeUtils;
import org.joda.time.*;
import org.joda.time.format.*;

public class JodaCodec implements ObjectSerializer, ContextObjectSerializer, ObjectDeserializer {
    public final static JodaCodec instance = new JodaCodec();

    private final static String            defaultPatttern = "yyyy-MM-dd HH:mm:ss";
    private final static DateTimeFormatter defaultFormatter = DateTimeFormat.forPattern(defaultPatttern);
    private final static DateTimeFormatter defaultFormatter_23 = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final static DateTimeFormatter formatter_dt19_tw = DateTimeFormat.forPattern("yyyy/MM/dd HH:mm:ss");
    private final static DateTimeFormatter formatter_dt19_cn = DateTimeFormat.forPattern("yyyy年M月d日 HH:mm:ss");
    private final static DateTimeFormatter formatter_dt19_cn_1 = DateTimeFormat.forPattern("yyyy年M月d日 H时m分s秒");
    private final static DateTimeFormatter formatter_dt19_kr = DateTimeFormat.forPattern("yyyy년M월d일 HH:mm:ss");
    private final static DateTimeFormatter formatter_dt19_us = DateTimeFormat.forPattern("MM/dd/yyyy HH:mm:ss");
    private final static DateTimeFormatter formatter_dt19_eur = DateTimeFormat.forPattern("dd/MM/yyyy HH:mm:ss");
    private final static DateTimeFormatter formatter_dt19_de = DateTimeFormat.forPattern("dd.MM.yyyy HH:mm:ss");
    private final static DateTimeFormatter formatter_dt19_in = DateTimeFormat.forPattern("dd-MM-yyyy HH:mm:ss");

    private final static DateTimeFormatter formatter_d8 = DateTimeFormat.forPattern("yyyyMMdd");
    private final static DateTimeFormatter formatter_d10_tw = DateTimeFormat.forPattern("yyyy/MM/dd");
    private final static DateTimeFormatter formatter_d10_cn = DateTimeFormat.forPattern("yyyy年M月d日");
    private final static DateTimeFormatter formatter_d10_kr = DateTimeFormat.forPattern("yyyy년M월d일");
    private final static DateTimeFormatter formatter_d10_us = DateTimeFormat.forPattern("MM/dd/yyyy");
    private final static DateTimeFormatter formatter_d10_eur = DateTimeFormat.forPattern("dd/MM/yyyy");
    private final static DateTimeFormatter formatter_d10_de = DateTimeFormat.forPattern("dd.MM.yyyy");
    private final static DateTimeFormatter formatter_d10_in = DateTimeFormat.forPattern("dd-MM-yyyy");

    private final static DateTimeFormatter ISO_FIXED_FORMAT =
            DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withZone(DateTimeZone.getDefault());

    private final static String formatter_iso8601_pattern = "yyyy-MM-dd'T'HH:mm:ss";
    private final static String formatter_iso8601_pattern_23 = "yyyy-MM-dd'T'HH:mm:ss.SSS";
    private final static String formatter_iso8601_pattern_29 = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS";
    private final static DateTimeFormatter formatter_iso8601 = DateTimeFormat.forPattern(formatter_iso8601_pattern);


    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        return deserialze(parser, type, fieldName, null, 0);
    }

    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName, String format, int feature) {
        JSONLexer lexer = parser.lexer;
        if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken();
            return null;
        }

        if (lexer.token() == JSONToken.LITERAL_STRING) {
            String text = lexer.stringVal();
            lexer.nextToken();

            DateTimeFormatter formatter = null;
            if (format != null) {
                formatter = getDateTimeFormatter(format);
            }

            if ("".equals(text)) {
                return null;
            }

            if (type == LocalDateTime.class)
                return parseToDateTime(format, text, formatter);
            if (type == LocalDate.class)
                return parseStringToLocalDate(format, text, formatter);
            if (type == LocalTime.class)
                return parseToLocalTime(text);
            if (type == DateTime.class)
                return parseToZonedDateTime(text, formatter);
            if (type == DateTimeZone.class)
                return (T) DateTimeZone.forID(text);
            if (type == Period.class)
                return (T) Period.parse(text);
            if (type == Duration.class)
                return (T) Duration.parse(text);
            if (type == Instant.class)
                return parseToInstant(text);
            if (type == DateTimeFormatter.class) {
                return (T) DateTimeFormat.forPattern(text);
            }
        }
        else{
            if (lexer.token() == JSONToken.LITERAL_INT)
                return parseToSpecificDateTimeType(type, lexer);
            if (lexer.token() != JSONToken.LBRACE)
                throw new UnsupportedOperationException();
            JSONObject object = parser.parseObject();

            if (type == Instant.class) {
                Object epochSecond = object.get("epochSecond");

                if (epochSecond instanceof Number) {
                    return (T) Instant.ofEpochSecond(
                            TypeUtils.longExtractValue((Number) epochSecond));
                }

                Object millis = object.get("millis");
                if (millis instanceof Number) {
                    return (T) Instant.ofEpochMilli(
                            TypeUtils.longExtractValue((Number) millis));
                }
            }
        }
        return null;
    }

    private <T> T parseToInstant(String text) {
        boolean digit = true;
        digit = isNumeric(text, digit);
        if (digit && text.length() > 8 && text.length() < 19) {
            long epochMillis = Long.parseLong(text);
            return (T) new Instant(epochMillis);
        }

        return (T) Instant.parse(text);
    }

    private <T> T parseToSpecificDateTimeType(Type type, JSONLexer lexer) {
        long millis = lexer.longValue();
        lexer.nextToken();

        TimeZone timeZone = JSON.defaultTimeZone;
        if (timeZone == null) {
            timeZone = TimeZone.getDefault();
        }

        if (type == DateTime.class) {
            return (T) new DateTime(millis, DateTimeZone.forTimeZone(timeZone));
        }

        LocalDateTime localDateTime = new LocalDateTime(millis, DateTimeZone.forTimeZone(timeZone));
        if (type == LocalDateTime.class) {
            return (T) localDateTime;
        }

        if (type == LocalDate.class) {
            return (T) localDateTime.toLocalDate();
        }

        if (type == LocalTime.class) {
            return (T) localDateTime.toLocalTime();
        }

        if (type == Instant.class) {
            return (T) new Instant(millis);
        }

        throw new UnsupportedOperationException();
    }

    private <T> boolean isNumeric(String text, boolean digit) {
        digit = isNumeric_(text, digit);
        return digit;
    }

    private <T> boolean isNumeric_(String text, boolean digit) {
        digit = isDigitString(text, digit);
        return digit;
    }

    private <T> boolean isDigitString(String text, boolean digit) {
        digit = isDigitString_(text, digit);
        return digit;
    }

    private <T> boolean isDigitString_(String text, boolean digit) {
        for (int i = 0;i < text.length();++i) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                digit = false;
                break;
            }
        }
        return digit;
    }

    private <T> T parseToZonedDateTime(String text, DateTimeFormatter formatter) {
        if (formatter == defaultFormatter) {
            formatter = ISO_FIXED_FORMAT;
        }

        return (T) parseZonedDateTime(text, formatter);
    }

    private <T> T parseToLocalTime(String text) {
        LocalTime localDate;
        if (text.length() == 23) {
            LocalDateTime localDateTime = LocalDateTime.parse(text);
            localDate = localDateTime.toLocalTime();
        } else {
            localDate = LocalTime.parse(text);
        }
        return (T) localDate;
    }

    private <T> T parseStringToLocalDate(String format, String text, DateTimeFormatter formatter) {
        LocalDate localDate;
        if (text.length() == 23) {
            LocalDateTime localDateTime = LocalDateTime.parse(text);
            localDate = localDateTime.toLocalDate();
        } else {
            localDate = parseLocalDate(text, format, formatter);
        }

        return (T) localDate;
    }

    private <T> T parseToDateTime(String format, String text, DateTimeFormatter formatter) {
        LocalDateTime localDateTime;
        if (text.length() == 10 || text.length() == 8) {
            LocalDate localDate = parseLocalDate(text, format, formatter);
            localDateTime = localDate.toLocalDateTime(LocalTime.MIDNIGHT);
        } else {
            localDateTime = parseDateTime(text, formatter);
        }
        return (T) localDateTime;
    }

    private <T> DateTimeFormatter getDateTimeFormatter(String format) {
        DateTimeFormatter formatter;
        if (defaultPatttern.equals(format)) {
            formatter = defaultFormatter;
        } else {
            formatter = DateTimeFormat.forPattern(format);
        }
        return formatter;
    }

    protected LocalDateTime parseDateTime(String text, DateTimeFormatter formatter) {
        if (formatter == null) {
            if (text.length() == 19) {
                char c4 = text.charAt(4);
                char c7 = text.charAt(7);
                char c10 = text.charAt(10);
                char c13 = text.charAt(13);
                char c16 = text.charAt(16);
                if (c13 == ':' && c16 == ':') {
                    formatter = selectDateTimeFormatter(text, formatter, c4, c7, c10);
                }
            } else if (text.length() == 23) {
                formatter = adjustFormatterBasedOnPattern(text, formatter);
            }

            if (text.length() >= 17) {
                formatter = getFormatterBasedOnYearSymbol_(text, formatter);
            }

            boolean digit = true;
            digit = isNumeric(text, digit);
            if (digit && text.length() > 8 && text.length() < 19) {
                long epochMillis = Long.parseLong(text);
                return new LocalDateTime(epochMillis, DateTimeZone.forTimeZone(JSON.defaultTimeZone));
            }
        }

        return formatter == null ? //
                LocalDateTime.parse(text) //
                : LocalDateTime.parse(text, formatter);
    }

    private DateTimeFormatter selectDateTimeFormatter(String text, DateTimeFormatter formatter, char c4, char c7, char c10) {
        if (c4 == '-' && c7 == '-') { // yyyy-MM-dd  or  yyyy-MM-dd'T'
		    formatter = selectFormatter(formatter, c10);
        } else if (c4 == '/' && c7 == '/') { // tw yyyy/mm/dd
		    formatter = formatter_dt19_tw;
        } else {
            formatter = determineDateFormat(text, formatter, c4);
        }
        return formatter;
    }

    private DateTimeFormatter determineDateFormat(String text, DateTimeFormatter formatter, char c4) {
        char c0 = text.charAt(0);
        char c1 = text.charAt(1);
        char c2 = text.charAt(2);
        char c3 = text.charAt(3);
        char c5 = text.charAt(5);
        if (c2 == '/' && c5 == '/') { // mm/dd/yyyy or mm/dd/yyyy
		    formatter = determineDateTimeFormatter(formatter, c4, c0, c1, c3);
        } else if (c2 == '.' && c5 == '.') { // dd.mm.yyyy
		    formatter = formatter_dt19_de;
        } else if (c2 == '-' && c5 == '-') { // dd-mm-yyyy
		    formatter = formatter_dt19_in;
        }
        return formatter;
    }

    private DateTimeFormatter getFormatterBasedOnYearSymbol_(String text, DateTimeFormatter formatter) {
        char c4 = text.charAt(4);
        if (c4 == '年') {
            formatter = getChineseDateTimeFormatter(text);
        } else if (c4 == '년') {
            formatter = formatter_dt19_kr;
        }
        return formatter;
    }

    private DateTimeFormatter determineDateTimeFormatter(DateTimeFormatter formatter, char c4, char c0, char c1, char c3) {
        int v0 = (c0 - '0') * 10 + (c1 - '0');
        int v1 = (c3 - '0') * 10 + (c4 - '0');
        if (v0 > 12) {
            formatter = formatter_dt19_eur;
        } else if (v1 > 12) {
            formatter = formatter_dt19_us;
        } else {
            formatter = getCountrySpecificFormatter(formatter);
        }
        return formatter;
    }

    private DateTimeFormatter getChineseDateTimeFormatter(String text) {
        DateTimeFormatter formatter;
        if (text.charAt(text.length() - 1) == '秒') {
            formatter = formatter_dt19_cn_1;
        } else {
            formatter = formatter_dt19_cn;
        }
        return formatter;
    }

    private DateTimeFormatter adjustFormatterBasedOnPattern(String text, DateTimeFormatter formatter) {
        char c4 = text.charAt(4);
        char c7 = text.charAt(7);
        char c10 = text.charAt(10);
        char c13 = text.charAt(13);
        char c16 = text.charAt(16);
        char c19 = text.charAt(19);

        if (c13 == ':'
                && c16 == ':'
                && c4 == '-'
                && c7 == '-'
                && c10 == ' '
                && c19 == '.'
        ) {
            formatter = defaultFormatter_23;
        }
        return formatter;
    }

    private DateTimeFormatter getCountrySpecificFormatter(DateTimeFormatter formatter) {
        String country = Locale.getDefault().getCountry();

        if (country.equals("US")) {
            formatter = formatter_dt19_us;
        } else if (country.equals("BR") //
		        || country.equals("AU")) {
            formatter = formatter_dt19_eur;
        }
        return formatter;
    }

    private DateTimeFormatter selectFormatter(DateTimeFormatter formatter, char c10) {
        if (c10 == 'T') {
            formatter = formatter_iso8601;
        } else if (c10 == ' ') {
            formatter = defaultFormatter;
        }
        return formatter;
    }

    protected LocalDate parseLocalDate(String text, String format, DateTimeFormatter formatter) {
        if (formatter == null) {
            if (text.length() == 8) {
                formatter = formatter_d8;
            }

            if (text.length() == 10) {
                formatter = determineDateTimeFormatter_(text, formatter);
            }

            if (text.length() >= 9) {
                formatter = getFormatterBasedOnYearSymbol(text, formatter);
            }

            boolean digit = true;
            digit = isNumeric(text, digit);
            if (digit && text.length() > 8 && text.length() < 19) {
                long epochMillis = Long.parseLong(text);
                return new LocalDateTime(epochMillis, DateTimeZone.forTimeZone(JSON.defaultTimeZone))
                        .toLocalDate();
            }
        }

        return formatter == null ? //
                LocalDate.parse(text) //
                : LocalDate.parse(text, formatter);
    }

    private DateTimeFormatter determineDateTimeFormatter_(String text, DateTimeFormatter formatter) {
        char c4 = text.charAt(4);
        char c7 = text.charAt(7);
        if (c4 == '/' && c7 == '/') { // tw yyyy/mm/dd
		    formatter = formatter_d10_tw;
        }

        char c0 = text.charAt(0);
        char c1 = text.charAt(1);
        char c2 = text.charAt(2);
        char c3 = text.charAt(3);
        char c5 = text.charAt(5);
        if (c2 == '/' && c5 == '/') { // mm/dd/yyyy or mm/dd/yyyy
		    formatter = adjustDateTimeFormatter(formatter, c4, c0, c1, c3);
        } else if (c2 == '.' && c5 == '.') { // dd.mm.yyyy
		    formatter = formatter_d10_de;
        } else if (c2 == '-' && c5 == '-') { // dd-mm-yyyy
		    formatter = formatter_d10_in;
        }
        return formatter;
    }

    private DateTimeFormatter adjustDateTimeFormatter(DateTimeFormatter formatter, char c4, char c0, char c1, char c3) {
        int v0 = (c0 - '0') * 10 + (c1 - '0');
        int v1 = (c3 - '0') * 10 + (c4 - '0');
        if (v0 > 12) {
            formatter = formatter_d10_eur;
        } else if (v1 > 12) {
            formatter = formatter_d10_us;
        } else {
            formatter = adjustFormatterBasedOnCountry(formatter);
        }
        return formatter;
    }

    private DateTimeFormatter getFormatterBasedOnYearSymbol(String text, DateTimeFormatter formatter) {
        char c4 = text.charAt(4);
        if (c4 == '年') {
            formatter = formatter_d10_cn;
        } else if (c4 == '년') {
            formatter = formatter_d10_kr;
        }
        return formatter;
    }

    private DateTimeFormatter adjustFormatterBasedOnCountry(DateTimeFormatter formatter) {
        String country = Locale.getDefault().getCountry();

        if (country.equals("US")) {
            formatter = formatter_d10_us;
        } else if (country.equals("BR") //
		        || country.equals("AU")) {
            formatter = formatter_d10_eur;
        }
        return formatter;
    }

    protected DateTime parseZonedDateTime(String text, DateTimeFormatter formatter) {
        if (formatter == null) {
            if (text.length() == 19) {
                char c4 = text.charAt(4);
                char c7 = text.charAt(7);
                char c10 = text.charAt(10);
                char c13 = text.charAt(13);
                char c16 = text.charAt(16);
                if (c13 == ':' && c16 == ':') {
                    formatter = selectDateTimeFormatter(text, formatter, c4, c7, c10);}
            }

            if (text.length() >= 17) {
                formatter = getFormatterBasedOnYearSymbol_(text, formatter);}
        }

        return formatter == null ? //
                DateTime.parse(text) //
                : DateTime.parse(text, formatter);
    }

    public int getFastMatchToken() {
        return JSONToken.LITERAL_STRING;
    }

    public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType,
                      int features) throws IOException {
        SerializeWriter out = serializer.out;
        if (object == null) {
            out.writeNull();
        } else {
            serializeObject(serializer, object, fieldType, features, out);
        }
    }

    private void serializeObject(JSONSerializer serializer, Object object, Type fieldType, int features, SerializeWriter out) {
        if (fieldType == null) {
            fieldType = object.getClass();
        }

        if (fieldType == LocalDateTime.class) {
            serializeDateTime(serializer, object, features, out);
        } else {
            out.writeString(object.toString());
        }
    }

    private void serializeDateTime(JSONSerializer serializer, Object object, int features, SerializeWriter out) {
        int mask = SerializerFeature.UseISO8601DateFormat.getMask();
        LocalDateTime dateTime = (LocalDateTime) object;
        String format = serializer.getDateFormatPattern();

        if (format == null) {
            format = getDateFormat(serializer, features, mask, dateTime);
        }

        if (format != null) {
            write(out, dateTime, format);
        } else {
            out.writeLong(dateTime.toDateTime(DateTimeZone.forTimeZone(JSON.defaultTimeZone)).toInstant().getMillis());
        }
    }

    private String getDateFormat(JSONSerializer serializer, int features, int mask, LocalDateTime dateTime) {
        String format;
        if ((features & mask) != 0 || serializer.isEnabled(SerializerFeature.UseISO8601DateFormat)) {
            format = formatter_iso8601_pattern;
        } else if (serializer.isEnabled(SerializerFeature.WriteDateUseDateFormat)) {
            format = JSON.DEFFAULT_DATE_FORMAT;
        } else {
            format = getISO8601PatternBasedOnMillis(dateTime);
        }
        return format;
    }

    private String getISO8601PatternBasedOnMillis(LocalDateTime dateTime) {
        String format;
        int millis = dateTime.getMillisOfSecond();
        if (millis == 0) {
            format = formatter_iso8601_pattern_23;
        } else {
            format = formatter_iso8601_pattern_29;
        }
        return format;
    }

    public void write(JSONSerializer serializer, Object object, BeanContext context) throws IOException {
        SerializeWriter out = serializer.out;
        String format = context.getFormat();
        write(out, (ReadablePartial) object, format);
    }

    private void write(SerializeWriter out, ReadablePartial object, String format) {
        DateTimeFormatter formatter;
        if (format.equals(formatter_iso8601_pattern)) {
            formatter = formatter_iso8601;
        } else {
            formatter = DateTimeFormat.forPattern(format);
        }

        String text = formatter.print(object);
        out.writeString(text);
    }
}
