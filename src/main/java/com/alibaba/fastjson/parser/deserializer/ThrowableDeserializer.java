package com.alibaba.fastjson.parser.deserializer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.util.FieldInfo;
import com.alibaba.fastjson.util.TypeUtils;

public class ThrowableDeserializer extends JavaBeanDeserializer {

    public ThrowableDeserializer(ParserConfig mapping, Class<?> clazz) {
        super(mapping, clazz, clazz);
    }

    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        JSONLexer lexer = parser.lexer;
        
        if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken();
            return null;
        }

        if (parser.getResolveStatus() == DefaultJSONParser.TypeNameRedirect) {
            parser.setResolveStatus(DefaultJSONParser.NONE);
        } else {
            if (lexer.token() != JSONToken.LBRACE) {
                throw new JSONException("syntax error");
            }
        }

        Throwable cause = null;
        Class<?> exClass = null;
        
        if (type != null && type instanceof Class) {
            exClass = assignExceptionClass(type, exClass);
        }
        
        String message = null;
        StackTraceElement[] stackTrace = null;
        Map<String, Object> otherValues = null;


        for (;;) {
            // lexer.scanSymbol
            String key = lexer.scanSymbol(parser.getSymbolTable());

            if (key == null) {
                if (lexer.token() == JSONToken.RBRACE) {
                    lexer.nextToken(JSONToken.COMMA);
                    break;
                }
                if (lexer.token() == JSONToken.COMMA) {
                    if (lexer.isEnabled(Feature.AllowArbitraryCommas)) {
                        continue;
                    }
                }
            }

            lexer.nextTokenWithColon(JSONToken.LITERAL_STRING);

            if (JSON.DEFAULT_TYPE_KEY.equals(key)) {
                exClass = parseExceptionClass(parser, lexer, exClass);
            } else if ("message".equals(key)) {
                message = parseMessage(lexer, message);
            } else if ("cause".equals(key)) {
                cause = deserialze(parser, null, "cause");
            } else if ("stackTrace".equals(key)) {
                stackTrace = parser.parseObject(StackTraceElement[].class);
            } else {
                otherValues = parseAndAddToMap(parser, otherValues, key);
            }

            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken(JSONToken.COMMA);
                break;
            }
        }

        Throwable ex = null;
        if (exClass == null) {
            ex = new Exception(message, cause);
        } else {
            ex = createThrowableInstance(cause, exClass, message, ex);
        }

        if (stackTrace != null) {
            ex.setStackTrace(stackTrace);
        }

        if (otherValues != null) {
            deserializeExceptionFields(parser, exClass, otherValues, ex);
        }

        return (T) ex;
    }

    private <T> void deserializeExceptionFields(DefaultJSONParser parser, Class<?> exClass, Map<String, Object> otherValues,
            Throwable ex) {
        JavaBeanDeserializer exBeanDeser = null;

        if (exClass != null) {
            exBeanDeser = getJavaBeanDeserializer(parser, exClass, exBeanDeser);
        }

        if (exBeanDeser != null) {
            setExceptionFieldValues(parser, otherValues, ex, exBeanDeser);
        }
    }

    private <T> void setExceptionFieldValues(DefaultJSONParser parser, Map<String, Object> otherValues, Throwable ex,
            JavaBeanDeserializer exBeanDeser) {
        for (Map.Entry<String, Object> entry : otherValues.entrySet()) {
            setExceptionFieldValue(parser, ex, exBeanDeser, entry);
        }
    }

    private <T> void setExceptionFieldValue(DefaultJSONParser parser, Throwable ex, JavaBeanDeserializer exBeanDeser,
            Map.Entry<String, Object> entry) {
        String key = entry.getKey();
        Object value = entry.getValue();

        FieldDeserializer fieldDeserializer = exBeanDeser.getFieldDeserializer(key);
        if (fieldDeserializer != null) {
            setFieldValue(parser, ex, value, fieldDeserializer);
        }
    }

    private <T> JavaBeanDeserializer getJavaBeanDeserializer(DefaultJSONParser parser, Class<?> exClass,
            JavaBeanDeserializer exBeanDeser) {
        if (exClass == clazz) {
            exBeanDeser = this;
        } else {
            exBeanDeser = getDeserializerInstance(parser, exClass, exBeanDeser);
        }
        return exBeanDeser;
    }

    private <T> Throwable createThrowableInstance(Throwable cause, Class<?> exClass, String message, Throwable ex) {
        if (!Throwable.class.isAssignableFrom(exClass)) {
            throw new JSONException("type not match, not Throwable. " + exClass.getName());
        }

        try {
            ex = createOrAssignException(cause, exClass, message);
        } catch (Exception e) {
            throw new JSONException("create instance error", e);
        }
        return ex;
    }

    private <T> void setFieldValue(DefaultJSONParser parser, Throwable ex, Object value,
            FieldDeserializer fieldDeserializer) {
        FieldInfo fieldInfo = fieldDeserializer.fieldInfo;
        if (!fieldInfo.fieldClass.isInstance(value)) {
            value = TypeUtils.cast(value, fieldInfo.fieldType, parser.getConfig());
        }
        fieldDeserializer.setValue(ex, value);
    }

    private <T> JavaBeanDeserializer getDeserializerInstance(DefaultJSONParser parser, Class<?> exClass,
            JavaBeanDeserializer exBeanDeser) {
        ObjectDeserializer exDeser = parser.getConfig().getDeserializer(exClass);
        if (exDeser instanceof JavaBeanDeserializer) {
            exBeanDeser = (JavaBeanDeserializer) exDeser;
        }
        return exBeanDeser;
    }

    private <T> Throwable createOrAssignException(Throwable cause, Class<?> exClass, String message) throws Exception {
        Throwable ex;
        ex = createException(message, cause, exClass);
        if (ex == null) {
            ex = new Exception(message, cause);
        }
        return ex;
    }

    private <T> Map<String, Object> parseAndAddToMap(DefaultJSONParser parser, Map<String, Object> otherValues, String key) {
        if (otherValues == null) {
            otherValues = new HashMap<String, Object>();
        }
        otherValues.put(key, parser.parse());
        return otherValues;
    }

    private <T> String parseMessage(JSONLexer lexer, String message) {
        if (lexer.token() == JSONToken.NULL) {
            message = null;
        }
        else{
            if (lexer.token() != JSONToken.LITERAL_STRING)
                throw new JSONException("syntax error");
            message = lexer.stringVal();
        }
        lexer.nextToken();
        return message;
    }

    private <T> Class<?> parseExceptionClass(DefaultJSONParser parser, JSONLexer lexer, Class<?> exClass) {
        if (lexer.token() != JSONToken.LITERAL_STRING)
            throw new JSONException("syntax error");
        String exClassName = lexer.stringVal();
        exClass = parser.getConfig().checkAutoType(exClassName, Throwable.class, lexer.getFeatures());
        lexer.nextToken(JSONToken.COMMA);
        return exClass;
    }

    private <T> Class<?> assignExceptionClass(Type type, Class<?> exClass) {
        Class<?> clazz = (Class<?>) type;
        if (Throwable.class.isAssignableFrom(clazz)) {
            exClass = clazz;
        }
        return exClass;
    }

    private Throwable createException(String message, Throwable cause, Class<?> exClass) throws Exception {
        Constructor<?> defaultConstructor = null;
        Constructor<?> messageConstructor = null;
        Constructor<?> causeConstructor = null;
        for (Constructor<?> constructor : exClass.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 0) {
                defaultConstructor = constructor;
                continue;
            }

            if (types.length == 1 && types[0] == String.class) {
                messageConstructor = constructor;
                continue;
            }

            if (types.length == 2 && types[0] == String.class && types[1] == Throwable.class) {
                causeConstructor = constructor;
                continue;
            }
        }

        if (causeConstructor != null) {
            return (Throwable) causeConstructor.newInstance(message, cause);
        }

        if (messageConstructor != null) {
            return (Throwable) messageConstructor.newInstance(message);
        }

        if (defaultConstructor != null) {
            return (Throwable) defaultConstructor.newInstance();
        }

        return null;
    }

    public int getFastMatchToken() {
        return JSONToken.LBRACE;
    }
}
