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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class MapSerializer extends SerializeFilterable implements ObjectSerializer {

    public static MapSerializer instance = new MapSerializer();

    private static final int NON_STRINGKEY_AS_STRING = SerializerFeature.of(
            new SerializerFeature[]{
                    SerializerFeature.BrowserCompatible,
                    SerializerFeature.WriteNonStringKeyAsString,
                    SerializerFeature.BrowserSecure});

    public void write(JSONSerializer serializer
            , Object object
            , Object fieldName
            , Type fieldType
            , int features) throws IOException {
        write(serializer, object, fieldName, fieldType, features, false);
    }

    @SuppressWarnings({"rawtypes"})
    public void write(JSONSerializer serializer
            , Object object
            , Object fieldName
            , Type fieldType
            , int features //
            , boolean unwrapped) throws IOException {
        SerializeWriter out = serializer.out;

        if (object == null) {
            out.writeNull();
            return;
        }

        Map<?, ?> map = (Map<?, ?>) object;
        int mapSortFieldMask = SerializerFeature.MapSortField.mask;
        if ((out.features & mapSortFieldMask) != 0 || (features & mapSortFieldMask) != 0) {
            map = normalizeMap(map);
        }

        if (serializer.containsReference(object)) {
            serializer.writeReference(object);
            return;
        }

        SerialContext parent = serializer.context;
        serializer.setContext(parent, object, fieldName, 0);
        try {
            if (!unwrapped) {
                out.write('{');
            }

            serializer.incrementIndent();

            Class<?> preClazz = null;
            ObjectSerializer preWriter = null;

            boolean first = true;

            if (out.isEnabled(SerializerFeature.WriteClassName)) {
                first = writeTypeKeyIfAbsent(serializer, object, out, map, first);
            }

            for (Map.Entry entry : map.entrySet()) {
                Object value = entry.getValue();

                Object entryKey = entry.getKey();

                {
                    List<PropertyPreFilter> preFilters = serializer.propertyPreFilters;
                    if (preFilters != null && preFilters.size() > 0) {
                        if (entryKey == null || entryKey instanceof String) {
                            if (!this.applyName(serializer, object, (String) entryKey)) {
                                continue;
                            }
                        } else if (entryKey.getClass().isPrimitive() || entryKey instanceof Number) {
                            String strKey = JSON.toJSONString(entryKey);
                            if (!this.applyName(serializer, object, strKey)) {
                                continue;
                            }
                        }
                    }
                }
                {
                    List<PropertyPreFilter> preFilters = this.propertyPreFilters;
                    if (preFilters != null && preFilters.size() > 0) {
                        if (entryKey == null || entryKey instanceof String) {
                            if (!this.applyName(serializer, object, (String) entryKey)) {
                                continue;
                            }
                        } else if (entryKey.getClass().isPrimitive() || entryKey instanceof Number) {
                            String strKey = JSON.toJSONString(entryKey);
                            if (!this.applyName(serializer, object, strKey)) {
                                continue;
                            }
                        }
                    }
                }
                
                {
                    List<PropertyFilter> propertyFilters = serializer.propertyFilters;
                    if (propertyFilters != null && propertyFilters.size() > 0) {
                        if (entryKey == null || entryKey instanceof String) {
                            if (!this.apply(serializer, object, (String) entryKey, value)) {
                                continue;
                            }
                        } else if (entryKey.getClass().isPrimitive() || entryKey instanceof Number) {
                            String strKey = JSON.toJSONString(entryKey);
                            if (!this.apply(serializer, object, strKey, value)) {
                                continue;
                            }
                        }
                    }
                }
                {
                    List<PropertyFilter> propertyFilters = this.propertyFilters;
                    if (propertyFilters != null && propertyFilters.size() > 0) {
                        if (entryKey == null || entryKey instanceof String) {
                            if (!this.apply(serializer, object, (String) entryKey, value)) {
                                continue;
                            }
                        } else if (entryKey.getClass().isPrimitive() || entryKey instanceof Number) {
                            String strKey = JSON.toJSONString(entryKey);
                            if (!this.apply(serializer, object, strKey, value)) {
                                continue;
                            }
                        }
                    }
                }
                
                {
                    List<NameFilter> nameFilters = serializer.nameFilters;
                    entryKey = processEntryKey(serializer, object, value, entryKey, nameFilters);
                }
                {
                    entryKey = processNameFilters(serializer, object, value, entryKey);
                }

                {
                    value = processValueOrObject(serializer, object, features, value, entryKey);
                }

                if (value == null) {
                    if (!SerializerFeature.isEnabled(out.features, features, SerializerFeature.WriteMapNullValue)) {
                        continue;
                    }
                }

                if (entryKey instanceof String) {
                    writeSerializedKey(serializer, out, first, entryKey);
                } else {
                    writeEntryKey(serializer, features, out, first, entryKey);
                }

                first = false;

                if (value == null) {
                    out.writeNull();
                    continue;
                }

                Class<?> clazz = value.getClass();

                if (clazz != preClazz) {
                    preClazz = clazz;
                    preWriter = serializer.getObjectWriter(clazz);
                }

                if (SerializerFeature.isEnabled(features, SerializerFeature.WriteClassName)
                        && preWriter instanceof JavaBeanSerializer) {
                    serializeJavaBean(serializer, fieldType, features, preWriter, value, entryKey);
                } else {
                    preWriter.write(serializer, value, entryKey, null, features);
                }
            }
        } finally {
            serializer.context = parent;
        }

        serializer.decrementIdent();
        if (out.isEnabled(SerializerFeature.PrettyFormat) && map.size() > 0) {
            serializer.println();
        }

        if (!unwrapped) {
            out.write('}');
        }
    }

    private void serializeJavaBean(JSONSerializer serializer, Type fieldType, int features, ObjectSerializer preWriter,
            Object value, Object entryKey) throws IOException {
        Type valueType = null;
        if (fieldType instanceof ParameterizedType) {
            valueType = getSecondTypeArgument(fieldType, valueType);
        }

        JavaBeanSerializer javaBeanSerializer = (JavaBeanSerializer) preWriter;
        javaBeanSerializer.writeNoneASM(serializer, value, entryKey, valueType, features);
    }

    private Object processValueOrObject(JSONSerializer serializer, Object object, int features, Object value, Object entryKey) {
        if (entryKey == null || entryKey instanceof String) {
            value = this.processValue(serializer, null, object, (String) entryKey, value, features);
        } else {
            value = processObjectValue(serializer, object, features, value, entryKey);
        }
        return value;
    }

    private Object processNameFilters(JSONSerializer serializer, Object object, Object value, Object entryKey) {
        List<NameFilter> nameFilters = this.nameFilters;
        if (nameFilters != null && nameFilters.size() > 0) {
            entryKey = processEntryKey_(serializer, object, value, entryKey);
        }
        return entryKey;
    }

    private Type getSecondTypeArgument(Type fieldType, Type valueType) {
        ParameterizedType parameterizedType = (ParameterizedType) fieldType;
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (actualTypeArguments.length == 2) {
            valueType = actualTypeArguments[1];
        }
        return valueType;
    }

    private void writeEntryKey(JSONSerializer serializer, int features, SerializeWriter out, boolean first,
            Object entryKey) {
        writeComma(out, first);

        if ((out.isEnabled(NON_STRINGKEY_AS_STRING) || SerializerFeature.isEnabled(features, SerializerFeature.WriteNonStringKeyAsString))
                && !(entryKey instanceof Enum)) {
            String strEntryKey = JSON.toJSONString(entryKey);
            serializer.write(strEntryKey);
        } else {
            serializer.write(entryKey);
        }

        out.write(':');
    }

    private void writeSerializedKey(JSONSerializer serializer, SerializeWriter out, boolean first, Object entryKey) {
        String key = (String) entryKey;

        writeComma(out, first);

        if (out.isEnabled(SerializerFeature.PrettyFormat)) {
            serializer.println();
        }
        out.writeFieldName(key, true);
    }

    private Object processObjectValue(JSONSerializer serializer, Object object, int features, Object value, Object entryKey) {
        boolean objectOrArray = entryKey instanceof Map || entryKey instanceof Collection;
        if (!objectOrArray) {
            String strKey = JSON.toJSONString(entryKey);
            value = this.processValue(serializer, null, object, strKey, value, features);
        }
        return value;
    }

    private Object processEntryKey_(JSONSerializer serializer, Object object, Object value, Object entryKey) {
        if (entryKey == null || entryKey instanceof String) {
            entryKey = this.processKey(serializer, object, (String) entryKey, value);
        } else if (entryKey.getClass().isPrimitive() || entryKey instanceof Number) {
            String strKey = JSON.toJSONString(entryKey);
            entryKey = this.processKey(serializer, object, strKey, value);
        }
        return entryKey;
    }

    private boolean writeTypeKeyIfAbsent(JSONSerializer serializer, Object object, SerializeWriter out, Map<?, ?> map,
            boolean first) {
        String typeKey = serializer.config.typeKey;
        Class<?> mapClass = map.getClass();
        boolean containsKey = (mapClass == JSONObject.class || mapClass == HashMap.class || mapClass == LinkedHashMap.class) 
                && map.containsKey(typeKey);
        if (!containsKey) {
            out.writeFieldName(typeKey);
            out.writeString(object.getClass().getName());
            first = false;
        }
        return first;
    }

    private Map<?, ?> normalizeMap(Map<?, ?> map) {
        if (map instanceof JSONObject) {
            map = ((JSONObject) map).getInnerMap();
        }

        if ((!(map instanceof SortedMap)) && !(map instanceof LinkedHashMap)) {
            try {
                map = new TreeMap(map);
            } catch (Exception ex) {
                // skip
		    }
        }
        return map;
    }

    private void writeComma(SerializeWriter out, boolean first) {
        if (!first) {
            out.write(',');
        }
    }

    private Object processEntryKey(JSONSerializer serializer, Object object, Object value, Object entryKey,
            List<NameFilter> nameFilters) {
        if (nameFilters != null && nameFilters.size() > 0) {
            entryKey = processEntryKeyWithSerializer(serializer, object, value, entryKey);
        }
        return entryKey;
    }

    private Object processEntryKeyWithSerializer(JSONSerializer serializer, Object object, Object value, Object entryKey) {
        if (entryKey == null || entryKey instanceof String) {
            entryKey = this.processKey(serializer, object, (String) entryKey, value);
        } else if (entryKey.getClass().isPrimitive() || entryKey instanceof Number) {
            String strKey = JSON.toJSONString(entryKey);
            entryKey = this.processKey(serializer, object, strKey, value);
        }
        return entryKey;
    }

}
