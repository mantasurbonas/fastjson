package com.alibaba.fastjson.parser.deserializer;

import java.lang.reflect.Type;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.*;
import com.alibaba.fastjson.util.TypeUtils;

public abstract class AbstractDateDeserializer extends ContextObjectDeserializer implements ObjectDeserializer {

    public <T> T deserialze(DefaultJSONParser parser, Type clazz, Object fieldName) {
        return deserialze(parser, clazz, fieldName, null, 0);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type clazz, Object fieldName, String format, int features) {
        JSONLexer lexer = parser.lexer;

        Object val;
        if (lexer.token() == JSONToken.LITERAL_INT) {
            val = parseUnixTimeMillis(format, lexer);
        }
        else if (lexer.token() == JSONToken.LITERAL_STRING) {
            String strVal = lexer.stringVal();
            
            if (format != null) {
                if ("yyyy-MM-dd HH:mm:ss.SSSSSSSSS".equals(format)
                        && clazz instanceof Class
                        && ((Class) clazz).getName().equals("java.sql.Timestamp")) {
                    return (T) TypeUtils.castToTimestamp(strVal);
                }

                val = parseFormattedDate(parser, format, strVal);
            }
            else {
                val = null;
            }
            
            if (val == null) {
                val = parseJSONDate(lexer, strVal);
            }
        }
        else if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken();
            val = null;
        }
        else if (lexer.token() == JSONToken.LBRACE) {
            lexer.nextToken();
            
            String key;
            if (lexer.token() != JSONToken.LITERAL_STRING)
                throw new JSONException("syntax error");
            key = lexer.stringVal();
                
            if (JSON.DEFAULT_TYPE_KEY.equals(key)) {
                clazz = parseType(parser, clazz, lexer);
            }
                
            lexer.nextTokenWithColon(JSONToken.LITERAL_INT);
            
            long timeMillis;
            if (lexer.token() != JSONToken.LITERAL_INT)
                throw new JSONException("syntax error : " + lexer.tokenName());
            timeMillis = lexer.longValue();
            lexer.nextToken();
            
            val = timeMillis;
            
            parser.accept(JSONToken.RBRACE);
        }
        else if (parser.getResolveStatus() == DefaultJSONParser.TypeNameRedirect) {
            val = parseJSONValue(parser, lexer);
        }
        else {
            val = parser.parse();
        }

        return (T) cast(parser, clazz, fieldName, val);
    }

    private <T> Object parseJSONValue(DefaultJSONParser parser, JSONLexer lexer) {
        Object val;
        parser.setResolveStatus(DefaultJSONParser.NONE);
        parser.accept(JSONToken.COMMA);

        if (lexer.token() != JSONToken.LITERAL_STRING)
            throw new JSONException("syntax error");
        validateValue(lexer);

        parser.accept(JSONToken.COLON);

        val = parser.parse();

        parser.accept(JSONToken.RBRACE);
        return val;
    }

    private <T> Object parseJSONDate(JSONLexer lexer, String strVal) {
        Object val;
        val = strVal;
        lexer.nextToken(JSONToken.COMMA);
        
        if (lexer.isEnabled(Feature.AllowISO8601DateFormat)) {
            val = parseISO8601Date(val, strVal);
        }
        return val;
    }

    private <T> Object parseFormattedDate(DefaultJSONParser parser, String format, String strVal) {
        Object val;
        SimpleDateFormat simpleDateFormat = null;
        try {
            simpleDateFormat = new SimpleDateFormat(format, parser.lexer.getLocale());
        } catch (IllegalArgumentException ex) {
            simpleDateFormat = formatDate(parser, format, simpleDateFormat, ex);
        }

        if (JSON.defaultTimeZone != null) {
            simpleDateFormat.setTimeZone(parser.lexer.getTimeZone());
        }

        val = parseDate(strVal, simpleDateFormat);

        if (val == null && JSON.defaultLocale == Locale.CHINA) {
            try {
                simpleDateFormat = new SimpleDateFormat(format, Locale.US);
            } catch (IllegalArgumentException ex) {
                simpleDateFormat = formatDate(parser, format, simpleDateFormat, ex);
            }
            simpleDateFormat.setTimeZone(parser.lexer.getTimeZone());

            val = parseDate(strVal, simpleDateFormat);
        }

        if (val == null) {
            val = parseDateTime(format, strVal);
        }
        return val;
    }

    private <T> void validateValue(JSONLexer lexer) {
        if (!"val".equals(lexer.stringVal())) {
            throw new JSONException("syntax error");
        }
        lexer.nextToken();
    }

    private <T> Type parseType(DefaultJSONParser parser, Type clazz, JSONLexer lexer) {
        lexer.nextToken();
        parser.accept(JSONToken.COLON);
        
        String typeName = lexer.stringVal();
        Class<?> type = parser.getConfig().checkAutoType(typeName, null, lexer.getFeatures());
        if (type != null) {
            clazz = type;
        }
        
        parser.accept(JSONToken.LITERAL_STRING);
        parser.accept(JSONToken.COMMA);
        return clazz;
    }

    private <T> Object parseISO8601Date(Object val, String strVal) {
        JSONScanner iso8601Lexer = new JSONScanner(strVal);
        if (iso8601Lexer.scanISO8601DateIfMatch()) {
            val = iso8601Lexer.getCalendar().getTime();
        }
        iso8601Lexer.close();
        return val;
    }

    private <T> Object parseDateTime(String format, String strVal) {
        Object val;
        if (format.equals("yyyy-MM-dd'T'HH:mm:ss.SSS") //
		        && strVal.length() == 19) {
            try {
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", JSON.defaultLocale);
                df.setTimeZone(JSON.defaultTimeZone);
                val = df.parse(strVal);
            } catch (ParseException ex2) {
                // skip
		        val = null;
            }
        } else {
            // skip
		    val = null;
        }
        return val;
    }

    private <T> Object parseUnixTimeMillis(String format, JSONLexer lexer) {
        long millis = lexer.longValue();
        lexer.nextToken(JSONToken.COMMA);
        if ("unixtime".equals(format)) {
            millis *= 1000;
        }
        return millis;
    }

    private <T> Object parseDate(String strVal, SimpleDateFormat simpleDateFormat) {
        Object val;
        try {
            val = simpleDateFormat.parse(strVal);
        } catch (ParseException ex) {
            val = null;
            // skip
		}
        return val;
    }

    private <T> SimpleDateFormat formatDate(DefaultJSONParser parser, String format, SimpleDateFormat simpleDateFormat,
            IllegalArgumentException ex) {
        if (format.contains("T")) {
            String fromat2 = format.replaceAll("T", "'T'");
            try {
            simpleDateFormat = new SimpleDateFormat(fromat2, parser.lexer.getLocale());
            } catch (IllegalArgumentException e2) {
                throw ex;
            }
        }
        return simpleDateFormat;
    }

    protected abstract <T> T cast(DefaultJSONParser parser, Type clazz, Object fieldName, Object value);
}
