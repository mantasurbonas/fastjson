/*
 * Copyright 1999-2017 Alibaba Group.
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
package com.alibaba.fastjson;

import static com.alibaba.fastjson.util.TypeUtils.castToBigDecimal;
import static com.alibaba.fastjson.util.TypeUtils.castToBigInteger;
import static com.alibaba.fastjson.util.TypeUtils.castToBoolean;
import static com.alibaba.fastjson.util.TypeUtils.castToByte;
import static com.alibaba.fastjson.util.TypeUtils.castToBytes;
import static com.alibaba.fastjson.util.TypeUtils.castToDate;
import static com.alibaba.fastjson.util.TypeUtils.castToDouble;
import static com.alibaba.fastjson.util.TypeUtils.castToFloat;
import static com.alibaba.fastjson.util.TypeUtils.castToInt;
import static com.alibaba.fastjson.util.TypeUtils.castToLong;
import static com.alibaba.fastjson.util.TypeUtils.castToShort;
import static com.alibaba.fastjson.util.TypeUtils.castToSqlDate;
import static com.alibaba.fastjson.util.TypeUtils.castToTimestamp;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.util.TypeUtils;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class JSONObject extends JSON implements Map<String, Object>, Cloneable, Serializable, InvocationHandler {

    private static final long         serialVersionUID = 1L;
    private static final int          DEFAULT_INITIAL_CAPACITY = 16;

    private final Map<String, Object> map;

    public JSONObject() {
        this(DEFAULT_INITIAL_CAPACITY, false);
    }

    public JSONObject(Map<String, Object> map) {
        if (map == null) {
            throw new IllegalArgumentException("map is null.");
        }
        this.map = map;
    }

    public JSONObject(boolean ordered) {
        this(DEFAULT_INITIAL_CAPACITY, ordered);
    }

    public JSONObject(int initialCapacity) {
        this(initialCapacity, false);
    }

    public JSONObject(int initialCapacity, boolean ordered) {
        if (ordered) {
            map = new LinkedHashMap<String, Object>(initialCapacity);
            return;
        }
        map = new HashMap<String, Object>(initialCapacity);
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public boolean containsKey(Object key) {
        boolean result = map.containsKey(key);
        if (!result) {
            if (key instanceof Number
                    || key instanceof Character
                    || key instanceof Boolean
                    || key instanceof UUID
            ) {
                result = map.containsKey(key.toString());
            }
        }
        return result;
    }

    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    public Object get(Object key) {
        Object val = map.get(key);

        if (val == null) {
            if (key instanceof Number
                    || key instanceof Character
                    || key instanceof Boolean
                    || key instanceof UUID
            ) {
                val = map.get(key.toString());
            }
        }

        return val;
    }

    public Object getOrDefault(Object key, Object defaultValue) {
        Object v;
        return (v = get(key)) != null ? v : defaultValue;
    }

    public JSONObject getJSONObject(String key) {
        Object value = map.get(key);

        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }

        if (value instanceof Map) {
            return new JSONObject((Map) value);
        }

        if (value instanceof String) {
            return JSON.parseObject((String) value);
        }

        return (JSONObject) toJSON(value);
    }

    public JSONArray getJSONArray(String key) {
        Object value = map.get(key);

        if (value instanceof JSONArray) {
            return (JSONArray) value;
        }

        if (value instanceof List) {
            return new JSONArray((List) value);
        }

        if (value instanceof String) {
            return (JSONArray) JSON.parse((String) value);
        }

        return (JSONArray) toJSON(value);
    }

    public <T> T getObject(String key, Class<T> clazz) {
        Object obj = map.get(key);
        return TypeUtils.castToJavaBean(obj, clazz);
    }

    public <T> T getObject(String key, Type type) {
        Object obj = map.get(key);
        return TypeUtils.cast(obj, type, ParserConfig.getGlobalInstance());
    }

    public <T> T getObject(String key, TypeReference typeReference) {
        Object obj = map.get(key);
        if (typeReference == null) {
            return (T) obj;
        }
        return TypeUtils.cast(obj, typeReference.getType(), ParserConfig.getGlobalInstance());
    }

    public Boolean getBoolean(String key) {
        Object value = get(key);

        if (value == null) {
            return null;
        }

        return castToBoolean(value);
    }

    public byte[] getBytes(String key) {
        Object value = get(key);

        if (value == null) {
            return null;
        }

        return castToBytes(value);
    }

    public boolean getBooleanValue(String key) {
        Object value = get(key);

        Boolean booleanVal = castToBoolean(value);
        if (booleanVal == null) {
            return false;
        }

        return booleanVal.booleanValue();
    }

    public Byte getByte(String key) {
        Object value = get(key);

        return castToByte(value);
    }

    public byte getByteValue(String key) {
        Object value = get(key);

        Byte byteVal = castToByte(value);
        if (byteVal == null) {
            return 0;
        }

        return byteVal.byteValue();
    }

    public Short getShort(String key) {
        Object value = get(key);

        return castToShort(value);
    }

    public short getShortValue(String key) {
        Object value = get(key);

        Short shortVal = castToShort(value);
        if (shortVal == null) {
            return 0;
        }

        return shortVal.shortValue();
    }

    public Integer getInteger(String key) {
        Object value = get(key);

        return castToInt(value);
    }

    public int getIntValue(String key) {
        Object value = get(key);

        Integer intVal = castToInt(value);
        if (intVal == null) {
            return 0;
        }

        return intVal.intValue();
    }

    public Long getLong(String key) {
        Object value = get(key);

        return castToLong(value);
    }

    public long getLongValue(String key) {
        Object value = get(key);

        Long longVal = castToLong(value);
        if (longVal == null) {
            return 0L;
        }

        return longVal.longValue();
    }

    public Float getFloat(String key) {
        Object value = get(key);

        return castToFloat(value);
    }

    public float getFloatValue(String key) {
        Object value = get(key);

        Float floatValue = castToFloat(value);
        if (floatValue == null) {
            return 0F;
        }

        return floatValue.floatValue();
    }

    public Double getDouble(String key) {
        Object value = get(key);

        return castToDouble(value);
    }

    public double getDoubleValue(String key) {
        Object value = get(key);

        Double doubleValue = castToDouble(value);
        if (doubleValue == null) {
            return 0D;
        }

        return doubleValue.doubleValue();
    }

    public BigDecimal getBigDecimal(String key) {
        Object value = get(key);

        return castToBigDecimal(value);
    }

    public BigInteger getBigInteger(String key) {
        Object value = get(key);

        return castToBigInteger(value);
    }

    public String getString(String key) {
        Object value = get(key);

        if (value == null) {
            return null;
        }

        return value.toString();
    }

    public Date getDate(String key) {
        Object value = get(key);

        return castToDate(value);
    }

    public Object getSqlDate(String key) {
        Object value = get(key);

        return castToSqlDate(value);
    }

    public Object getTimestamp(String key) {
        Object value = get(key);

        return castToTimestamp(value);
    }

    public Object put(String key, Object value) {
        return map.put(key, value);
    }

    public JSONObject fluentPut(String key, Object value) {
        map.put(key, value);
        return this;
    }

    public void putAll(Map<? extends String, ?> m) {
        map.putAll(m);
    }

    public JSONObject fluentPutAll(Map<? extends String, ?> m) {
        map.putAll(m);
        return this;
    }

    public void clear() {
        map.clear();
    }

    public JSONObject fluentClear() {
        map.clear();
        return this;
    }

    public Object remove(Object key) {
        return map.remove(key);
    }

    public JSONObject fluentRemove(Object key) {
        map.remove(key);
        return this;
    }

    public Set<String> keySet() {
        return map.keySet();
    }

    public Collection<Object> values() {
        return map.values();
    }

    public Set<Map.Entry<String, Object>> entrySet() {
        return map.entrySet();
    }

    @Override
    public JSONObject clone() {
        return new JSONObject(map instanceof LinkedHashMap //
                ? new LinkedHashMap<String, Object>(map) //
                : new HashMap<String, Object>(map)
        );
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof JSONObject) {
            return this.map.equals(((JSONObject) obj).map);
        }

        return this.map.equals(obj);
    }

    @Override
    public int hashCode() {
        return this.map.hashCode();
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 1) {
            return handleMethodInvocation(method, args);
        }

        if (parameterTypes.length == 0) {
            return getAnnotatedValue(method);
        }

        throw new UnsupportedOperationException(method.toGenericString());
    }

    private Object handleMethodInvocation(Method method, Object[] args) {
        if (method.getName().equals("equals")) {
            return this.equals(args[0]);
        }

        Class<?> returnType = method.getReturnType();
        if (returnType != void.class) {
            throw new JSONException("illegal setter");
        }

        String name = getJsonFieldName(method);

        if (name == null) {
            name = extractSetterName(method);
        }

        map.put(name, args[0]);
        return null;
    }

    private Object getAnnotatedValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class) {
            throw new JSONException("illegal getter");
        }

        String name = null;
        JSONField annotation = TypeUtils.getAnnotation(method, JSONField.class);
        if (annotation != null) {
            if (annotation.name().length() != 0) {
                name = annotation.name();
            }
        }

        if (name == null) {
            name = method.getName();
            if (name.startsWith("get")) {
                name = name.substring(3);
                name = formatName(name);
            }
            else{
                if (!name.startsWith("is")){
                    if (name.startsWith("hashCode"))
                        return this.hashCode();
                    if (name.startsWith("toString"))
                        return this.toString();
                    throw new JSONException("illegal getter");
                }
                name = name.substring(2);
                name = formatName(name);
            }
        }

        Object value = map.get(name);
        return TypeUtils.cast(value, method.getGenericReturnType(), ParserConfig.getGlobalInstance());
    }

    private String extractSetterName(Method method) {
        String name;
        name = method.getName();

        if (!name.startsWith("set")) {
            throw new JSONException("illegal setter");
        }

        name = name.substring(3);
        if (name.length() == 0) {
            throw new JSONException("illegal setter");
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private String formatName(String name) {
        if (name.length() == 0) {
            throw new JSONException("illegal getter");
        }
        name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        return name;
    }

    private String getJsonFieldName(Method method) {
        String name = null;
        JSONField annotation = TypeUtils.getAnnotation(method, JSONField.class);
        if (annotation != null) {
            if (annotation.name().length() != 0) {
                name = annotation.name();
            }
        }
        return name;
    }

    public Map<String, Object> getInnerMap() {
        return this.map;
    }

    public <T> T toJavaObject(Class<T> clazz) {
        if (clazz == Map.class || clazz == JSONObject.class || clazz == JSON.class) {
            return (T) this;
        }

        if (clazz == Object.class && !containsKey(JSON.DEFAULT_TYPE_KEY)) {
            return (T) this;
        }

        return TypeUtils.castToJavaBean(this, clazz, ParserConfig.getGlobalInstance());
    }

    public <T> T toJavaObject(Class<T> clazz, ParserConfig config, int features) {
        if (clazz == Map.class) {
            return (T) this;
        }

        if (clazz == Object.class && !containsKey(JSON.DEFAULT_TYPE_KEY)) {
            return (T) this;
        }

        return TypeUtils.castToJavaBean(this, clazz, config);
    }
}
