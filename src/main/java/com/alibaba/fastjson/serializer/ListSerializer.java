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

import com.alibaba.fastjson.util.TypeUtils;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public final class ListSerializer implements ObjectSerializer {

    public static final ListSerializer instance = new ListSerializer();

    public final void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType, int features)
                                                                                                       throws IOException {

        boolean writeClassName = serializer.out.isEnabled(SerializerFeature.WriteClassName)
                || SerializerFeature.isEnabled(features, SerializerFeature.WriteClassName);

        SerializeWriter out = serializer.out;

        Type elementType = null;
        if (writeClassName) {
            elementType = TypeUtils.getCollectionItemType(fieldType);
        }

        if (object == null) {
            out.writeNull(SerializerFeature.WriteNullListAsEmpty);
            return;
        }

        List<?> list = (List<?>) object;

        if (list.size() == 0) {
            out.append("[]");
            return;
        }

        SerialContext context = serializer.context;
        serializer.setContext(context, object, fieldName, 0);

        ObjectSerializer itemSerializer = null;
        try {
            if (out.isEnabled(SerializerFeature.PrettyFormat)) {
                serializeListItems(serializer, object, fieldName, features, out, elementType, list, context);
                return;
            }

            out.append('[');
            for (int i = 0, size = list.size();i < size;++i) {
                Object item = list.get(i);
                appendComma(out, i);
                
                if (item == null) {
                    out.append("null");
                } else {
                    Class<?> clazz = item.getClass();

                    if (clazz == Integer.class) {
                        out.writeInt(((Integer) item).intValue());
                    } else if (clazz == Long.class) {
                        writeLongValue(writeClassName, out, item);
                    } else {
                        if ((SerializerFeature.DisableCircularReferenceDetect.mask & features) != 0) {
                            itemSerializer = serializer.getObjectWriter(item.getClass());
                            itemSerializer.write(serializer, item, i, elementType, features);
                        } else {
                            if (!out.disableCircularReferenceDetect) {
                                setSerializationContext(serializer, object, fieldName, context);
                            }

                            if (serializer.containsReference(item)) {
                                serializer.writeReference(item);
                            } else {
                                serializeObject(serializer, features, elementType, i, item);
                            }
                        }
                    }
                }
            }
            out.append(']');
        } finally {
            serializer.context = context;
        }
    }

    private void serializeListItems(JSONSerializer serializer, Object object, Object fieldName, int features, SerializeWriter out,
            Type elementType, List<?> list, SerialContext context) throws IOException {
        out.append('[');
        serializer.incrementIndent();

        int i = 0;
        for (Object item : list) {
            appendComma(out, i);

            serializer.println();
            if (item != null) {
                serializeItem(serializer, object, fieldName, features, elementType, context, i, item);
            } else {
                serializer.out.writeNull();
            }
            i++;
        }

        serializer.decrementIdent();
        serializer.println();
        out.append(']');
    }

    private void serializeObject(JSONSerializer serializer, int features, Type elementType, int i, Object item)
            throws IOException {
        ObjectSerializer itemSerializer;
        itemSerializer = serializer.getObjectWriter(item.getClass());
        if ((SerializerFeature.WriteClassName.mask & features) != 0
                && itemSerializer instanceof JavaBeanSerializer)
        {
            JavaBeanSerializer javaBeanSerializer = (JavaBeanSerializer) itemSerializer;
            javaBeanSerializer.writeNoneASM(serializer, item, i, elementType, features);
        } else {
            itemSerializer.write(serializer, item, i, elementType, features);
        }
    }

    private void writeLongValue(boolean writeClassName, SerializeWriter out, Object item) {
        long val = ((Long) item).longValue();
        if (writeClassName) {
            out.writeLong(val);
            out.write('L');
        } else {
            out.writeLong(val);
        }
    }

    private void serializeItem(JSONSerializer serializer, Object object, Object fieldName, int features, Type elementType,
            SerialContext context, int i, Object item) throws IOException {
        ObjectSerializer itemSerializer;
        if (serializer.containsReference(item)) {
            serializer.writeReference(item);
        } else {
            itemSerializer = serializer.getObjectWriter(item.getClass());
            setSerializationContext(serializer, object, fieldName, context);
            itemSerializer.write(serializer, item, i, elementType, features);
        }
    }

    private void setSerializationContext(JSONSerializer serializer, Object object, Object fieldName, SerialContext context) {
        SerialContext itemContext = new SerialContext(context, object, fieldName, 0, 0);
        serializer.context = itemContext;
    }

    private void appendComma(SerializeWriter out, int i) {
        if (i != 0) {
            out.append(',');
        }
    }

}
