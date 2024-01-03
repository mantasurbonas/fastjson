package com.alibaba.fastjson.parser.deserializer;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Date;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONScanner;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.util.TypeUtils;

public class SqlDateDeserializer extends AbstractDateDeserializer implements ObjectDeserializer {

    public final static SqlDateDeserializer instance = new SqlDateDeserializer();
    public final static SqlDateDeserializer instance_timestamp = new SqlDateDeserializer(true);
    
    private boolean                           timestamp = false;
    
    public SqlDateDeserializer() {
        
    }
    
    public SqlDateDeserializer(boolean timestmap) {
        this.timestamp = true;
    }

    @SuppressWarnings("unchecked")
    protected <T> T cast(DefaultJSONParser parser, Type clazz, Object fieldName, Object val) {
        if (timestamp) {
            return castTimestamp(parser, clazz, fieldName, val);
        }
        
        if (val == null) {
            return null;
        }

        if (val instanceof java.util.Date) {
            val = new java.sql.Date(((Date) val).getTime());
        }
        else if (val instanceof BigDecimal) {
            val = (T) new java.sql.Date(TypeUtils.longValue((BigDecimal) val));
        }
        else{
            if (!(val instanceof Number)){
                if (val instanceof String)
                    return parseJsonToDate(parser, val);
                throw new JSONException("parse error : " + val);
            }
            val = (T) new java.sql.Date(((Number) val).longValue());
        }

        return (T) val;
    }

    private <T> T parseJsonToDate(DefaultJSONParser parser, Object val) {
        String strVal = (String) val;
        if (strVal.length() == 0) {
            return null;
        }

        long longVal;

        JSONScanner dateLexer = new JSONScanner(strVal);
        try {
            if (dateLexer.scanISO8601DateIfMatch()) {
                longVal = dateLexer.getCalendar().getTimeInMillis();
            } else {

                DateFormat dateFormat = parser.getDateFormat();
                try {
                    java.util.Date date = (java.util.Date) dateFormat.parse(strVal);
                    return (T) new java.sql.Date(date.getTime());
                } catch (ParseException e) {
                    // skip
		        }

                longVal = Long.parseLong(strVal);
            }
        } finally {
            dateLexer.close();
        }
        return (T) new java.sql.Date(longVal);
    }
    
    @SuppressWarnings("unchecked")
    protected <T> T castTimestamp(DefaultJSONParser parser, Type clazz, Object fieldName, Object val) {

        if (val == null) {
            return null;
        }

        if (val instanceof java.util.Date) {
            return (T) new java.sql.Timestamp(((Date) val).getTime());
        }

        if (val instanceof BigDecimal) {
            return (T) new java.sql.Timestamp(TypeUtils.longValue((BigDecimal) val));
        }

        if (val instanceof Number) {
            return (T) new java.sql.Timestamp(((Number) val).longValue());
        }

        if (val instanceof String) {
            return parseStringToTimestamp(parser, val);
        }

        throw new JSONException("parse error");
    }

    private <T> T parseStringToTimestamp(DefaultJSONParser parser, Object val) {
        String strVal = (String) val;
        if (strVal.length() == 0) {
            return null;
        }

        long longVal;
        JSONScanner dateLexer = new JSONScanner(strVal);
        try {
            if (strVal.length() > 19
                    && strVal.charAt(4) == '-'
                    && strVal.charAt(7) == '-'
                    && strVal.charAt(10) == ' '
                    && strVal.charAt(13) == ':'
                    && strVal.charAt(16) == ':'
                    && strVal.charAt(19) == '.') {
                String dateFomartPattern = parser.getDateFomartPattern();
                if (dateFomartPattern.length() != strVal.length() && dateFomartPattern == JSON.DEFFAULT_DATE_FORMAT) {
                    return (T) java.sql.Timestamp.valueOf(strVal);
                }
            }

            if (dateLexer.scanISO8601DateIfMatch(false)) {
                longVal = dateLexer.getCalendar().getTimeInMillis();
            } else {
                DateFormat dateFormat = parser.getDateFormat();
                try {
                    java.util.Date date = (java.util.Date) dateFormat.parse(strVal);
                    return (T) new java.sql.Timestamp(date.getTime());
                } catch (ParseException e) {
                    // skip
		        }

                longVal = Long.parseLong(strVal);
            }
        } finally {
            dateLexer.close();
        }

        return (T) new java.sql.Timestamp(longVal);
    }

    public int getFastMatchToken() {
        return JSONToken.LITERAL_INT;
    }
}
