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
import java.util.Collection;
import java.util.HashSet;
import java.util.TreeSet;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.alibaba.fastjson.util.TypeUtils;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class CollectionCodec implements ObjectSerializer, ObjectDeserializer {

    public final static CollectionCodec instance = new CollectionCodec();

    public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType, int features) throws IOException {
        SerializeWriter out = serializer.out;

        if (object == null) {
            out.writeNull(SerializerFeature.WriteNullListAsEmpty);
            return;
        }

        Type elementType = null;
        if (out.isEnabled(SerializerFeature.WriteClassName)
                || SerializerFeature.isEnabled(features, SerializerFeature.WriteClassName))
        {
            elementType = TypeUtils.getCollectionItemType(fieldType);
        }

        Collection<?> collection = (Collection<?>) object;

        SerialContext context = serializer.context;
        serializer.setContext(context, object, fieldName, 0);

        if (out.isEnabled(SerializerFeature.WriteClassName)) {
            appendCollectionType(out, collection);
        }

        try {
            serializeCollection(serializer, features, out, elementType, collection);
        } finally {
            serializer.context = context;
        }
    }

    private void serializeCollection(JSONSerializer serializer, int features, SerializeWriter out, Type elementType,
            Collection<?> collection) throws IOException {
        int i = 0;
        out.append('[');
        for (Object item : collection)
            i = serializeItem(serializer, features, out, elementType, i, item);
        out.append(']');
    }

    private int serializeItem(JSONSerializer serializer, int features, SerializeWriter out, Type elementType, int i,
            Object item) throws IOException {
        if (i++ != 0) {
            out.append(',');
        }
        if (item == null) {
            out.writeNull();
            return i;
        }
        Class<?> clazz = item.getClass();
        if (clazz == Integer.class) {
            out.writeInt(((Integer) item).intValue());
            return i;
        }
        if (clazz == Long.class) {
            return writeLongValue(out, i, item);
        }
        ObjectSerializer itemSerializer = serializer.getObjectWriter(clazz);
        if (SerializerFeature.isEnabled(features, SerializerFeature.WriteClassName)
                && itemSerializer instanceof JavaBeanSerializer) {
            JavaBeanSerializer javaBeanSerializer = (JavaBeanSerializer) itemSerializer;
            javaBeanSerializer.writeNoneASM(serializer, item, i - 1, elementType, features);
        } else {
            itemSerializer.write(serializer, item, i - 1, elementType, features);
        }
        return i;
    }

    private int writeLongValue(SerializeWriter out, int i, Object item) {
        out.writeLong(((Long) item).longValue());

        if (out.isEnabled(SerializerFeature.WriteClassName)) {
            out.write('L');
        }
        return i;
    }

    private void appendCollectionType(SerializeWriter out, Collection<?> collection) {
        if (HashSet.class.isAssignableFrom(collection.getClass())) {
            out.append("Set");
            return;
        }
        if (TreeSet.class == collection.getClass()) {
            out.append("TreeSet");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        if (parser.lexer.token() == JSONToken.NULL) {
            parser.lexer.nextToken(JSONToken.COMMA);
            return null;
        }
        
        if (type == JSONArray.class) {
            JSONArray array = new JSONArray();
            parser.parseArray(array);
            return (T) array;
        }

        Collection list;
        if (parser.lexer.token() == JSONToken.SET) {
            parser.lexer.nextToken();
            list = TypeUtils.createSet(type);
        } else {
            list = TypeUtils.createCollection(type);
        }

        Type itemType = TypeUtils.getCollectionItemType(type);
        parser.parseArray(itemType, list, fieldName);

        return (T) list;
    }

  

    public int getFastMatchToken() {
        return JSONToken.LBRACKET;
    }
}
