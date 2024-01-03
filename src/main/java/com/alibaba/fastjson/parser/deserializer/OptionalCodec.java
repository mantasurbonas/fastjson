package com.alibaba.fastjson.parser.deserializer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.serializer.JSONSerializer;
import com.alibaba.fastjson.serializer.ObjectSerializer;
import com.alibaba.fastjson.util.TypeUtils;

public class OptionalCodec implements ObjectSerializer, ObjectDeserializer {

    public static OptionalCodec instance = new OptionalCodec();

    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        
        if (type == OptionalInt.class) {
            return parseIntegerToObject(parser);
        }
        
        if (type == OptionalLong.class) {
            return parseLongToObject(parser);
        }
        
        if (type == OptionalDouble.class) {
            return parseDoubleToObject(parser);
        }
        
        type = TypeUtils.unwrapOptional(type);
        Object value = parser.parseObject(type);
        
        if (value == null) {
            return (T) Optional.empty();
        }
        
        return (T) Optional.of(value);
    }

    private <T> T parseDoubleToObject(DefaultJSONParser parser) {
        Object obj = parser.parseObject(Double.class);
        Double value = TypeUtils.castToDouble(obj);
        if (value == null)
            return (T) OptionalDouble.empty();
        return (T) OptionalDouble.of(value);
    }

    private <T> T parseLongToObject(DefaultJSONParser parser) {
        Object obj = parser.parseObject(Long.class);
        Long value = TypeUtils.castToLong(obj);
        if (value == null)
            return (T) OptionalLong.empty();
        return (T) OptionalLong.of(value);
    }

    private <T> T parseIntegerToObject(DefaultJSONParser parser) {
        Object obj = parser.parseObject(Integer.class);
        Integer value = TypeUtils.castToInt(obj);
        if (value == null)
            return (T) OptionalInt.empty();
        return (T) OptionalInt.of(value);
    }

    public int getFastMatchToken() {
        return JSONToken.LBRACE;
    }

    public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType,
                      int features) throws IOException {

        if (object == null) {
            serializer.writeNull();
            return;
        }

        if (object instanceof Optional) {
            Optional<?> optional = (Optional<?>) object;
            Object value = optional.isPresent() ? optional.get() : null;
            serializer.write(value);
            return;
        }

        if (object instanceof OptionalDouble) {
            serializeOptionalDouble(serializer, object);
            return;
        }
        
        if (object instanceof OptionalInt) {
            serializeOptionalInt(serializer, object);
            return;
        }
        
        if (object instanceof OptionalLong) {
            serializeOptionalLong(serializer, object);
            return;
        }
        
        throw new JSONException("not support optional : " + object.getClass());
    }

    private void serializeOptionalLong(JSONSerializer serializer, Object object) {
        OptionalLong optional = (OptionalLong) object;
        if (optional.isPresent()) {
            long value = optional.getAsLong();
            serializer.out.writeLong(value);
        } else {
            serializer.writeNull();
        }
    }

    private void serializeOptionalInt(JSONSerializer serializer, Object object) {
        OptionalInt optional = (OptionalInt) object;
        if (optional.isPresent()) {
            int value = optional.getAsInt();
            serializer.out.writeInt(value);
        } else {
            serializer.writeNull();
        }
    }

    private void serializeOptionalDouble(JSONSerializer serializer, Object object) {
        OptionalDouble optional = (OptionalDouble) object;
        if (optional.isPresent()) {
            double value = optional.getAsDouble();
            serializer.write(value);
        } else {
            serializer.writeNull();
        }
    }

}
