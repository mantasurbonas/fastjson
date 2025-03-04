package com.alibaba.fastjson.serializer;

import java.lang.reflect.Type;
import java.util.Collection;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;


public class CharArrayCodec implements ObjectDeserializer {

    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type clazz, Object fieldName) {
        return (T) deserialze(parser);
    }
    
    @SuppressWarnings("unchecked")
    public static <T> T deserialze(DefaultJSONParser parser) {
        JSONLexer lexer = parser.lexer;
        if (lexer.token() == JSONToken.LITERAL_STRING) {
            String val = lexer.stringVal();
            lexer.nextToken(JSONToken.COMMA);
            return (T) val.toCharArray();
        }
        
        if (lexer.token() == JSONToken.LITERAL_INT) {
            Number val = lexer.integerValue();
            lexer.nextToken(JSONToken.COMMA);
            return (T) val.toString().toCharArray();
        }

        Object value = parser.parse();

        if (value instanceof  String) {
            return (T) ((String) value).toCharArray();
        }

        if (value instanceof Collection) {
            return castToCharArray(value);
        }

        return value == null //
            ? null //
            : (T) JSON.toJSONString(value).toCharArray();
    }

    private static <T> T castToCharArray(Object value) {
        Collection<?> collection = (Collection) value;

        boolean accept = true;
        accept = checkSingleCharacterStrings(collection, accept);

        if (!accept) {
            throw new JSONException("can not cast to char[]");
        }

        char[] chars = new char[collection.size()];
        int pos = 0;
        for (Object item : collection) {
            chars[pos++] = ((String) item).charAt(0);
        }
        return (T) chars;
    }

    private static <T> boolean checkSingleCharacterStrings(Collection<?> collection, boolean accept) {
        accept = validateSingleCharacterStrings(collection, accept);
        return accept;
    }

    private static <T> boolean validateSingleCharacterStrings(Collection<?> collection, boolean accept) {
        accept = checkStringElementsLength(collection, accept);
        return accept;
    }

    private static <T> boolean checkStringElementsLength(Collection<?> collection, boolean accept) {
        accept = checkSingleCharacterInCollection(collection, accept);
        return accept;
    }

    private static <T> boolean checkSingleCharacterInCollection(Collection<?> collection, boolean accept) {
        for (Object item : collection) {
            if (item instanceof String) {
                int itemLength = ((String) item).length();
                if (itemLength != 1) {
                    accept = false;
                    break;
                }
            }
        }
        return accept;
    }

    public int getFastMatchToken() {
        return JSONToken.LITERAL_STRING;
    }
}
