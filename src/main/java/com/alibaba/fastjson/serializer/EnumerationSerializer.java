package com.alibaba.fastjson.serializer;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Enumeration;


public class EnumerationSerializer implements ObjectSerializer {
    public static EnumerationSerializer instance = new EnumerationSerializer();
    
    public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType, int features) throws IOException {
        SerializeWriter out = serializer.out;

        if (object == null) {
            out.writeNull(SerializerFeature.WriteNullListAsEmpty);
            return;
        }
        
        Type elementType = null;
        if (out.isEnabled(SerializerFeature.WriteClassName)) {
            elementType = getElementType(fieldType, elementType);
        }
        
        Enumeration<?> e = (Enumeration<?>) object;
        
        SerialContext context = serializer.context;
        serializer.setContext(context, object, fieldName, 0);

        try {
            serializeEnumerationWrapper(serializer, out, elementType, e);
        } finally {
            serializer.context = context;
        }
    }

    private void serializeEnumerationWrapper(JSONSerializer serializer, SerializeWriter out, Type elementType, Enumeration<?> e)
            throws IOException {
        int i = 0;
        out.append('[');
        serializeEnumeration(serializer, out, elementType, e, i);
        out.append(']');
    }

    private void serializeEnumeration(JSONSerializer serializer, SerializeWriter out, Type elementType, Enumeration<?> e, int i)
            throws IOException {
        while (e.hasMoreElements())
            i = serializeNextElement(serializer, out, elementType, e, i);
    }

    private int serializeNextElement(JSONSerializer serializer, SerializeWriter out, Type elementType, Enumeration<?> e, int i)
            throws IOException {
        Object item = e.nextElement();
        if (i++ != 0) {
            out.append(',');
        }
        if (item == null) {
            out.writeNull();
            return i;
        }
        ObjectSerializer itemSerializer = serializer.getObjectWriter(item.getClass());
        itemSerializer.write(serializer, item, i - 1, elementType, 0);
        return i;
    }

    private Type getElementType(Type fieldType, Type elementType) {
        if (fieldType instanceof ParameterizedType) {
            ParameterizedType param = (ParameterizedType) fieldType;
            elementType = param.getActualTypeArguments()[0];
        }
        return elementType;
    }
}
