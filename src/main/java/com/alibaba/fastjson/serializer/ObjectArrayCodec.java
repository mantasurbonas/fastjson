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
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.alibaba.fastjson.util.TypeUtils;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class ObjectArrayCodec implements ObjectSerializer, ObjectDeserializer {

    public static final ObjectArrayCodec instance = new ObjectArrayCodec();

    public ObjectArrayCodec() {
    }

    public final void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType, int features)
                                                                                                       throws IOException {
        SerializeWriter out = serializer.out;

        Object[] array = (Object[]) object;

        if (object == null) {
            out.writeNull(SerializerFeature.WriteNullListAsEmpty);
            return;
        }

        int size = array.length;

        int end = size - 1;

        if (end == -1) {
            out.append("[]");
            return;
        }

        SerialContext context = serializer.context;
        serializer.setContext(context, object, fieldName, 0);

        try {
            Class<?> preClazz = null;
            ObjectSerializer preWriter = null;
            out.append('[');

            if (out.isEnabled(SerializerFeature.PrettyFormat)) {
                writeArrayElements(serializer, out, array, size);
                return;
            }

            for (int i = 0;i < end;++i) {
                Object item = array[i];

                if (item == null) {
                    out.append("null,");
                } else {
                    if (serializer.containsReference(item)) {
                        serializer.writeReference(item);
                    } else {
                        Class<?> clazz = item.getClass();

                        if (clazz == preClazz) {
                            preWriter.write(serializer, item, i, null, 0);
                        } else {
                            preClazz = clazz;
                            preWriter = serializer.getObjectWriter(clazz);

                            preWriter.write(serializer, item, i, null, 0);
                        }
                    }
                    out.append(',');
                }
            }

            Object item = array[end];

            if (item == null) {
                out.append("null]");
            } else {
                writeSerializedItem(serializer, out, end, item);
            }
        } finally {
            serializer.context = context;
        }
    }

    private void writeArrayElements(JSONSerializer serializer, SerializeWriter out, Object[] array, int size) {
        serializer.incrementIndent();
        serializer.println();
        for (int i = 0;i < size;++i) {
            writeArrayElement(serializer, out, array, i);
        }
        serializer.decrementIdent();
        serializer.println();
        out.write(']');
    }

    private void writeSerializedItem(JSONSerializer serializer, SerializeWriter out, int end, Object item) {
        if (serializer.containsReference(item)) {
            serializer.writeReference(item);
        } else {
            serializer.writeWithFieldName(item, end);
        }
        out.append(']');
    }

    private void writeArrayElement(JSONSerializer serializer, SerializeWriter out, Object[] array, int i) {
        if (i != 0) {
            out.write(',');
            serializer.println();
        }
        serializer.writeWithFieldName(array[i], Integer.valueOf(i));
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        JSONLexer lexer = parser.lexer;
        int token = lexer.token();
        if (token == JSONToken.NULL) {
            lexer.nextToken(JSONToken.COMMA);
            return null;
        }

        if (token == JSONToken.LITERAL_STRING || token == JSONToken.HEX) {
            return deserializeBytes(type, lexer);
        }

        Class componentClass;
        Type componentType;
        if (type instanceof GenericArrayType) {
            GenericArrayType clazz = (GenericArrayType) type;
            componentType = clazz.getGenericComponentType();
            if (componentType instanceof TypeVariable) {
                componentClass = getComponentTypeClass(parser, componentType);
            } else {
                componentClass = TypeUtils.getClass(componentType);
            }
        } else {
            Class clazz = (Class) type;
            componentType = componentClass = clazz.getComponentType();
        }
        JSONArray array = new JSONArray();
        parser.parseArray(componentType, array, fieldName);

        return (T) toObjectArray(parser, componentClass, array);
    }

    private <T> Class getComponentTypeClass(DefaultJSONParser parser, Type componentType) {
        Class componentClass;
        TypeVariable typeVar = (TypeVariable) componentType;
        Type objType = parser.getContext().type;
        if (objType instanceof ParameterizedType) {
            componentClass = getComponentClass(typeVar, objType);
        } else {
            componentClass = TypeUtils.getClass(typeVar.getBounds()[0]);
        }
        return componentClass;
    }

    private <T> Class getComponentClass(TypeVariable typeVar, Type objType) {
        Class componentClass;
        ParameterizedType objParamType = (ParameterizedType) objType;
        Type objRawType = objParamType.getRawType();
        Type actualType = null;
        if (objRawType instanceof Class) {
            actualType = getActualTypeArgument(typeVar, objParamType, objRawType, actualType);
        }
        if (actualType instanceof Class) {
            componentClass = (Class) actualType;
        } else {
            componentClass = Object.class;
        }
        return componentClass;
    }

    private <T> Type getActualTypeArgument(TypeVariable typeVar, ParameterizedType objParamType, Type objRawType, Type actualType) {
        TypeVariable[] objTypeParams = ((Class) objRawType).getTypeParameters();
        for (int i = 0;i < objTypeParams.length;++i) {
            if (objTypeParams[i].getName().equals(typeVar.getName())) {
                actualType = objParamType.getActualTypeArguments()[i];
            }
        }
        return actualType;
    }

    private <T> T deserializeBytes(Type type, JSONLexer lexer) {
        byte[] bytes = lexer.bytesValue();
        lexer.nextToken(JSONToken.COMMA);

        if (bytes.length == 0 && type != byte[].class) {
            return null;
        }

        return (T) bytes;
    }

    @SuppressWarnings("unchecked")
    private <T> T toObjectArray(DefaultJSONParser parser, Class<?> componentType, JSONArray array) {
        if (array == null) {
            return null;
        }

        int size = array.size();

        Object objArray = Array.newInstance(componentType, size);
        for (int i = 0;i < size;++i)
            parseArrayElement(parser, componentType, array, objArray, i);

        array.setRelatedArray(objArray);
        array.setComponentType(componentType);
        return (T) objArray; // TODO
    }

    private <T> void parseArrayElement(DefaultJSONParser parser, Class<?> componentType, JSONArray array, Object objArray,
            int i) {
        Object value = array.get(i);
        if (value == array) {
            Array.set(objArray, i, objArray);
            return;
        }
        if (componentType.isArray()) {
            setArrayElement(parser, componentType, objArray, i, value);
        } else {
            updateArrayElement(parser, componentType, array, objArray, i, value);

        }
    }

    private <T> void updateArrayElement(DefaultJSONParser parser, Class<?> componentType, JSONArray array, Object objArray,
            int i, Object value) {
        Object element = null;
        if (value instanceof JSONArray) {
            element = updateElementIfValueMatch(array, objArray, i, value, element);
        }

        if (element == null) {
            element = TypeUtils.cast(value, componentType, parser.getConfig());
        }
        Array.set(objArray, i, element);
    }

    private <T> Object updateElementIfValueMatch(JSONArray array, Object objArray, int i, Object value, Object element) {
        boolean contains = false;
        JSONArray valueArray = (JSONArray) value;
        int valueArraySize = valueArray.size();
        for (int y = 0;y < valueArraySize;++y) {
            contains = setArrayValueIfMatch(array, objArray, i, contains, valueArray, y);
        }
        if (contains) {
            element = valueArray.toArray();
        }
        return element;
    }

    private <T> boolean setArrayValueIfMatch(JSONArray array, Object objArray, int i, boolean contains, JSONArray valueArray,
            int y) {
        Object valueItem = valueArray.get(y);
        if (valueItem == array) {
            valueArray.set(i, objArray);
            contains = true;
        }
        return contains;
    }

    private <T> void setArrayElement(DefaultJSONParser parser, Class<?> componentType, Object objArray, int i, Object value) {
        Object element;
        if (componentType.isInstance(value)) {
            element = value;
        } else {
            element = toObjectArray(parser, componentType, (JSONArray) value);
        }

        Array.set(objArray, i, element);
    }

    public int getFastMatchToken() {
        return JSONToken.LBRACKET;
    }
}
