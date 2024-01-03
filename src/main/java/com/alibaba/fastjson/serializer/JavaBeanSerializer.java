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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.PropertyNamingStrategy;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.annotation.JSONType;
import com.alibaba.fastjson.util.FieldInfo;
import com.alibaba.fastjson.util.TypeUtils;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class JavaBeanSerializer extends SerializeFilterable implements ObjectSerializer {
    // serializers
    protected final FieldSerializer[] getters;
    protected final FieldSerializer[] sortedGetters;
    
    protected final SerializeBeanInfo  beanInfo;

    private transient volatile long[] hashArray;
    private transient volatile short[] hashArrayMapping;
    
    public JavaBeanSerializer(Class<?> beanType) {
        this(beanType, (Map<String, String>) null);
    }

    public JavaBeanSerializer(Class<?> beanType, String... aliasList) {
        this(beanType, createAliasMap(aliasList));
    }

    static Map<String, String> createAliasMap(String... aliasList) {
        Map<String, String> aliasMap = new HashMap<String, String>();
        for (String alias : aliasList) {
            aliasMap.put(alias, alias);
        }

        return aliasMap;
    }

    public JSONType getJSONType() {
        return beanInfo.jsonType;
    }

    /**
     * @since 1.2.42
     */
    public Class<?> getType() {
        return beanInfo.beanType;
    }

    public JavaBeanSerializer(Class<?> beanType, Map<String, String> aliasMap) {
        this(TypeUtils.buildBeanInfo(beanType, aliasMap, null));
    }
    
    public JavaBeanSerializer(SerializeBeanInfo beanInfo) {
        this.beanInfo = beanInfo;
        
        sortedGetters = new FieldSerializer[beanInfo.sortedFields.length];
        for (int i = 0;i < sortedGetters.length;++i) {
            sortedGetters[i] = new FieldSerializer(beanInfo.beanType, beanInfo.sortedFields[i]);
        }
        
        if (beanInfo.fields == beanInfo.sortedFields) {
            getters = sortedGetters;
        } else {
            getters = new FieldSerializer[beanInfo.fields.length];
            boolean hashNotMatch = false;
            hashNotMatch = updateFieldSerializers(beanInfo, hashNotMatch);
            if (hashNotMatch) {
                System.arraycopy(sortedGetters, 0, getters, 0, sortedGetters.length);
            }
        }

        if (beanInfo.jsonType != null) {
            addSerializeFilters(beanInfo);
        }
    }

    private void addSerializeFilters(SerializeBeanInfo beanInfo) {
        for (Class<? extends SerializeFilter> filterClass : beanInfo.jsonType.serialzeFilters()) {
            try {
                SerializeFilter filter = filterClass.getConstructor().newInstance();
                this.addFilter(filter);
            } catch (Exception e) {
                // skip
		    }
        }
    }

    private boolean updateFieldSerializers(SerializeBeanInfo beanInfo, boolean hashNotMatch) {
        for (int i = 0;i < getters.length;++i) {
            FieldSerializer fieldSerializer = getFieldSerializer(beanInfo.fields[i].name);
            if (fieldSerializer == null) {
                hashNotMatch = true;
                break;
            }
            getters[i] = fieldSerializer;
        }
        return hashNotMatch;
    }

    public void writeDirectNonContext(JSONSerializer serializer, //
                      Object object, //
                      Object fieldName, //
                      Type fieldType, //
                      int features) throws IOException {
        write(serializer, object, fieldName, fieldType, features);
    }
    
    public void writeAsArray(JSONSerializer serializer, //
                                       Object object, //
                                       Object fieldName, //
                                       Type fieldType, //
                                       int features) throws IOException {
        write(serializer, object, fieldName, fieldType, features);
    }
    
    public void writeAsArrayNonContext(JSONSerializer serializer, //
                                       Object object, //
                                       Object fieldName, //
                                       Type fieldType, //
                                       int features) throws IOException {
        write(serializer, object, fieldName, fieldType, features);
    }

    public void write(JSONSerializer serializer, //
                      Object object, //
                      Object fieldName, //
                      Type fieldType, //
                      int features) throws IOException {
        write(serializer, object, fieldName, fieldType, features, false);
    }

    public void writeNoneASM(JSONSerializer serializer, //
                      Object object, //
                      Object fieldName, //
                      Type fieldType, //
                      int features) throws IOException {
        write(serializer, object, fieldName, fieldType, features, false);
    }

    protected void write(JSONSerializer serializer, //
                            Object object, //
                            Object fieldName, //
                            Type fieldType, //
                            int features,
                            boolean unwrapped
    ) throws IOException {
        SerializeWriter out = serializer.out;

        if (object == null) {
            out.writeNull();
            return;
        }

        if (writeReference(serializer, object, features)) {
            return;
        }

        FieldSerializer[] getters;

        if (out.sortField) {
            getters = this.sortedGetters;
        }
        else {
            getters = this.getters;
        }

        SerialContext parent = serializer.context;
        if (!this.beanInfo.beanType.isEnum()) {
            serializer.setContext(parent, object, fieldName, this.beanInfo.features, features);
        }

        boolean writeAsArray = isWriteAsArray(serializer, features);

        FieldSerializer errorFieldSerializer = null;
        try {
            char startSeperator = writeAsArray ? '[' : '{';
            char endSeperator = writeAsArray ? ']' : '}';
            if (!unwrapped) {
                out.append(startSeperator);
            }

            if (getters.length > 0 && out.isEnabled(SerializerFeature.PrettyFormat)) {
                serializer.incrementIndent();
                serializer.println();
            }

            boolean commaFlag = false;

            if ((this.beanInfo.features & SerializerFeature.WriteClassName.mask) != 0
                    || (features & SerializerFeature.WriteClassName.mask) != 0
                    || serializer.isWriteClassName(fieldType, object)) {
                commaFlag = serializeObjectWithWildcardType(serializer, object, fieldType, commaFlag);
            }

            char seperator = commaFlag ? ',' : '\0';

            boolean writeClassName = out.isEnabled(SerializerFeature.WriteClassName);
            char newSeperator = this.writeBefore(serializer, object, seperator);
            commaFlag = newSeperator == ',';

            boolean skipTransient = out.isEnabled(SerializerFeature.SkipTransientField);
            boolean ignoreNonFieldGetter = out.isEnabled(SerializerFeature.IgnoreNonFieldGetter);

            for (int i = 0;i < getters.length;++i) {
                    FieldSerializer fieldSerializer = getters[i];

                    Field field = fieldSerializer.fieldInfo.field;
                    FieldInfo fieldInfo = fieldSerializer.fieldInfo;
                    String fieldInfoName = fieldInfo.name;
                    Class<?> fieldClass = fieldInfo.fieldClass;

                    boolean fieldUseSingleQuotes = SerializerFeature.isEnabled(out.features, fieldInfo.serialzeFeatures, SerializerFeature.UseSingleQuotes);
                    boolean directWritePrefix = out.quoteFieldNames && !fieldUseSingleQuotes;

                    if (skipTransient) {
                        if (fieldInfo.fieldTransient) {
                            continue;
                        }
                    }

                    if (ignoreNonFieldGetter) {
                        if (field == null) {
                            continue;
                        }
                    }

                    boolean notApply = false;
                    if ((!this.applyName(serializer, object, fieldInfoName)) //
                            || !this.applyLabel(serializer, fieldInfo.label)) {
                        if (writeAsArray) {
                            notApply = true;
                        }
                        else {
                            continue;
                        }
                    }

                    if (fieldInfoName.equals(beanInfo.typeKey)
                            && serializer.isWriteClassName(fieldType, object)) {
                        continue;
                    }

                    Object propertyValue;

                    if (notApply) {
                        propertyValue = null;
                    } else
                        try {
                            propertyValue = fieldSerializer.getPropertyValueDirect(object);
                        } catch (InvocationTargetException ex) {
                            errorFieldSerializer = fieldSerializer;
                            if (!out.isEnabled(SerializerFeature.IgnoreErrorGetter))
                                throw ex;
                            propertyValue = null;
                        }

                    if (!this.apply(serializer, object, fieldInfoName, propertyValue)) {
                        continue;
                    }

                    if (fieldClass == String.class && "trim".equals(fieldInfo.format)) {
                        if (propertyValue != null) {
                            propertyValue = ((String) propertyValue).trim();
                        }
                    }

                    String key = fieldInfoName;
                    key = this.processKey(serializer, object, key, propertyValue);

                    Object originalValue = propertyValue;
                    propertyValue = this.processValue(serializer, fieldSerializer.fieldContext, object, fieldInfoName,
                            propertyValue, features);

                    if (propertyValue == null) {
                        int serialzeFeatures = fieldInfo.serialzeFeatures;
                        JSONField jsonField = fieldInfo.getAnnotation();
                        serialzeFeatures = updateSerializeFeatures(serialzeFeatures);
                        // beanInfo.jsonType
                        if (jsonField != null && !"".equals(jsonField.defaultValue())) {
                            propertyValue = jsonField.defaultValue();
                        }
                        else if (fieldClass == Boolean.class) {
                            int defaultMask = SerializerFeature.WriteNullBooleanAsFalse.mask;
                            int mask = defaultMask | SerializerFeature.WriteMapNullValue.mask;
                            if ((!writeAsArray) && (serialzeFeatures & mask) == 0 && (out.features & mask) == 0) {
                                continue;
                            }
                            else if ((serialzeFeatures & defaultMask) != 0) {
                                propertyValue = false;
                            }
                            else if ((out.features & defaultMask) != 0
                                    && (serialzeFeatures & SerializerFeature.WriteMapNullValue.mask) == 0) {
                                propertyValue = false;
                            }
                        }
                        else if (fieldClass == String.class) {
                            int defaultMask = SerializerFeature.WriteNullStringAsEmpty.mask;
                            int mask = defaultMask | SerializerFeature.WriteMapNullValue.mask;
                            if ((!writeAsArray) && (serialzeFeatures & mask) == 0 && (out.features & mask) == 0) {
                                continue;
                            }
                            else if ((serialzeFeatures & defaultMask) != 0) {
                                propertyValue = "";
                            }
                            else if ((out.features & defaultMask) != 0
                                    && (serialzeFeatures & SerializerFeature.WriteMapNullValue.mask) == 0) {
                                propertyValue = "";
                            }
                        }
                        else if (Number.class.isAssignableFrom(fieldClass)) {
                            int defaultMask = SerializerFeature.WriteNullNumberAsZero.mask;
                            int mask = defaultMask | SerializerFeature.WriteMapNullValue.mask;
                            if ((!writeAsArray) && (serialzeFeatures & mask) == 0 && (out.features & mask) == 0) {
                                continue;
                            }
                            else if ((serialzeFeatures & defaultMask) != 0) {
                                propertyValue = 0;
                            }
                            else if ((out.features & defaultMask) != 0
                                    && (serialzeFeatures & SerializerFeature.WriteMapNullValue.mask) == 0) {
                                propertyValue = 0;
                            }
                        }
                        else if (Collection.class.isAssignableFrom(fieldClass)) {
                            int defaultMask = SerializerFeature.WriteNullListAsEmpty.mask;
                            int mask = defaultMask | SerializerFeature.WriteMapNullValue.mask;
                            if ((!writeAsArray) && (serialzeFeatures & mask) == 0 && (out.features & mask) == 0) {
                                continue;
                            }
                            else if ((serialzeFeatures & defaultMask) != 0) {
                                propertyValue = Collections.emptyList();
                            }
                            else if ((out.features & defaultMask) != 0
                                    && (serialzeFeatures & SerializerFeature.WriteMapNullValue.mask) == 0) {
                                propertyValue = Collections.emptyList();
                            }
                        }
                        else if ((!writeAsArray) && (!fieldSerializer.writeNull)
                                && !out.isEnabled(SerializerFeature.WriteMapNullValue.mask)
                                && (serialzeFeatures & SerializerFeature.WriteMapNullValue.mask) == 0) { 
                            continue;
                        }
                    }

                    if (propertyValue != null  //
                            && (out.notWriteDefaultValue //
                            || (fieldInfo.serialzeFeatures & SerializerFeature.NotWriteDefaultValue.mask) != 0 //
                            || (beanInfo.features & SerializerFeature.NotWriteDefaultValue.mask) != 0 //
                    )) {
                        Class<?> fieldCLass = fieldInfo.fieldClass;
                        if (fieldCLass == byte.class && propertyValue instanceof Byte
                                && ((Byte) propertyValue).byteValue() == 0) {
                            continue;
                        }
                        else if (fieldCLass == short.class && propertyValue instanceof Short
                                && ((Short) propertyValue).shortValue() == 0) {
                            continue;
                        }
                        else if (fieldCLass == int.class && propertyValue instanceof Integer
                                && ((Integer) propertyValue).intValue() == 0) {
                            continue;
                        }
                        else if (fieldCLass == long.class && propertyValue instanceof Long
                                && ((Long) propertyValue).longValue() == 0L) {
                            continue;
                        }
                        else if (fieldCLass == float.class && propertyValue instanceof Float
                                && ((Float) propertyValue).floatValue() == 0F) {
                            continue;
                        }
                        else if (fieldCLass == double.class && propertyValue instanceof Double
                                && ((Double) propertyValue).doubleValue() == 0D) {
                            continue;
                        }
                        else if (fieldCLass == boolean.class && propertyValue instanceof Boolean
                                && !((Boolean) propertyValue).booleanValue()) {
                            continue;
                        }
                    }

                    if (commaFlag) {
                        if (fieldInfo.unwrapped
                                && propertyValue instanceof Map
                                && ((Map) propertyValue).size() == 0) {
                            continue;
                        }

                        out.write(',');
                        if (out.isEnabled(SerializerFeature.PrettyFormat)) {
                            serializer.println();
                        }
                    }

                    if (key != fieldInfoName) {
                        writePropertyValue(serializer, out, writeAsArray, propertyValue, key);
                    }
                    else if (originalValue != propertyValue) {
                        serializePropertyValue(serializer, writeAsArray, fieldSerializer, propertyValue);
                    }
                    else {
                        if (!writeAsArray) {
                            writeFieldPrefixConditionally(serializer, out, writeClassName, fieldSerializer, fieldInfo, fieldClass,
                                    directWritePrefix);
                        }

                        if (!writeAsArray) {
                            JSONField fieldAnnotation = fieldInfo.getAnnotation();
                            if (fieldClass == String.class && (fieldAnnotation == null || fieldAnnotation.serializeUsing() == Void.class)) {
                                serializeOrWriteNullProperty(out, fieldSerializer, fieldUseSingleQuotes, propertyValue);
                            }
                            else {
                                if (fieldInfo.unwrapped
                                        && propertyValue instanceof Map
                                        && ((Map) propertyValue).size() == 0) {
                                    commaFlag = false;
                                    continue;
                                }

                                fieldSerializer.writeValue(serializer, propertyValue);
                            }
                        }
                        else {
                            fieldSerializer.writeValue(serializer, propertyValue);
                        }
                    }

                    boolean fieldUnwrappedNull = false;
                    if (fieldInfo.unwrapped
                            && propertyValue instanceof Map) {
                        fieldUnwrappedNull = updateFieldUnwrappedNullStatus_(serializer, propertyValue, fieldUnwrappedNull);
                    }

                    if (!fieldUnwrappedNull) {
                        commaFlag = true;
                    }
                }

            this.writeAfter(serializer, object, commaFlag ? ',' : '\0');

            if (getters.length > 0 && out.isEnabled(SerializerFeature.PrettyFormat)) {
                serializer.decrementIdent();
                serializer.println();
            }

            if (!unwrapped) {
                out.append(endSeperator);
            }
        } catch (Exception e) {
            generateAndThrowJSONException(object, fieldName, errorFieldSerializer, e);
        } finally {
            serializer.context = parent;
        }
    }

    private boolean updateFieldUnwrappedNullStatus_(JSONSerializer serializer, Object propertyValue, boolean fieldUnwrappedNull) {
        Map map = (Map) propertyValue;
        if (map.size() == 0) {
            fieldUnwrappedNull = true;
        } else if (!serializer.isEnabled(SerializerFeature.WriteMapNullValue)) {
            fieldUnwrappedNull = updateFieldUnwrappedNullStatus(fieldUnwrappedNull, map);
        }
        return fieldUnwrappedNull;
    }

    private void generateAndThrowJSONException(Object object, Object fieldName, FieldSerializer errorFieldSerializer, Exception e) {
        String errorMessage = "write javaBean error, fastjson version " + JSON.VERSION;
        if (object != null) {
            errorMessage += ", class " + object.getClass().getName();
        }
        if (fieldName != null) {
            errorMessage += ", fieldName : " + fieldName;
        } else if (errorFieldSerializer != null && errorFieldSerializer.fieldInfo != null) {
            errorMessage = appendFieldInfoToErrorMessage(errorFieldSerializer, errorMessage);
        }
        if (e.getMessage() != null) {
            errorMessage += ", " + e.getMessage();
        }

        Throwable cause = null;
        if (e instanceof InvocationTargetException) {
            cause = e.getCause();
        }
        if (cause == null) {
            cause = e;
        }

        throw new JSONException(errorMessage, cause);
    }

    private boolean updateFieldUnwrappedNullStatus(boolean fieldUnwrappedNull, Map map) {
        boolean hasNotNull = false;
        hasNotNull = checkMapForNonNullValues(map, hasNotNull);
        if (!hasNotNull) {
            fieldUnwrappedNull = true;
        }
        return fieldUnwrappedNull;
    }

    private void serializeOrWriteNullProperty(SerializeWriter out, FieldSerializer fieldSerializer, boolean fieldUseSingleQuotes,
                                                 Object propertyValue) {
        if (propertyValue == null) {
            writeNullOrEmptyString(out, fieldSerializer);
            return;
        }
        writePropertyValue_(out, fieldUseSingleQuotes, propertyValue);
    }

    private void writeFieldPrefixConditionally(JSONSerializer serializer, SerializeWriter out, boolean writeClassName,
            FieldSerializer fieldSerializer, FieldInfo fieldInfo, Class<?> fieldClass, boolean directWritePrefix)
            throws IOException {
        boolean isMap = Map.class.isAssignableFrom(fieldClass);
        boolean isJavaBean = !fieldClass.isPrimitive() && !fieldClass.getName().startsWith("java.") || fieldClass == Object.class;
        if (writeClassName || !fieldInfo.unwrapped || !(isMap || isJavaBean)) {
            writeFieldPrefix(serializer, out, fieldSerializer, fieldInfo, directWritePrefix);
        }
    }

    private String appendFieldInfoToErrorMessage(FieldSerializer errorFieldSerializer, String errorMessage) {
        FieldInfo fieldInfo = errorFieldSerializer.fieldInfo;
        if (fieldInfo.method != null) {
            errorMessage += ", method : " + fieldInfo.method.getName();
        } else {
            errorMessage += ", fieldName : " + errorFieldSerializer.fieldInfo.name;
        }
        return errorMessage;
    }

    private boolean checkMapForNonNullValues(Map map, boolean hasNotNull) {
        hasNotNull = checkMapForNonNullValues_(map, hasNotNull);
        return hasNotNull;
    }

    private boolean checkMapForNonNullValues_(Map map, boolean hasNotNull) {
        hasNotNull = checkMapValuesNotNull(map, hasNotNull);
        return hasNotNull;
    }

    private boolean checkMapValuesNotNull(Map map, boolean hasNotNull) {
        hasNotNull = checkNotNullInMapValues(map, hasNotNull);
        return hasNotNull;
    }

    private boolean checkNotNullInMapValues(Map map, boolean hasNotNull) {
        for (Object value : map.values()) {
            if (value != null) {
                hasNotNull = true;
                break;
            }
        }
        return hasNotNull;
    }

    private void writePropertyValue_(SerializeWriter out, boolean fieldUseSingleQuotes, Object propertyValue) {
        String propertyValueString = (String) propertyValue;

        if (fieldUseSingleQuotes) {
            out.writeStringWithSingleQuote(propertyValueString);
        } else {
            out.writeStringWithDoubleQuote(propertyValueString, (char) 0);
        }
    }

    private void writeNullOrEmptyString(SerializeWriter out, FieldSerializer fieldSerializer) {
        int serialzeFeatures = fieldSerializer.features;
        serialzeFeatures = updateSerializeFeatures(serialzeFeatures);
        if ((out.features & SerializerFeature.WriteNullStringAsEmpty.mask) != 0
                && (serialzeFeatures & SerializerFeature.WriteMapNullValue.mask) == 0) {
            out.writeString("");
        } else if ((serialzeFeatures & SerializerFeature.WriteNullStringAsEmpty.mask) != 0) {
            out.writeString("");
        } else {
            out.writeNull();
        }
    }

    private void writeFieldPrefix(JSONSerializer serializer, SerializeWriter out, FieldSerializer fieldSerializer,
                                     FieldInfo fieldInfo, boolean directWritePrefix) throws IOException {
        if (directWritePrefix) {
            out.write(fieldInfo.name_chars, 0, fieldInfo.name_chars.length);
            return;
        }
        fieldSerializer.writePrefix(serializer);
    }

    private void serializePropertyValue(JSONSerializer serializer, boolean writeAsArray, FieldSerializer fieldSerializer,
            Object propertyValue) throws IOException {
        if (!writeAsArray) {
            fieldSerializer.writePrefix(serializer);
        }
        serializer.write(propertyValue);
    }

    private void writePropertyValue(JSONSerializer serializer, SerializeWriter out, boolean writeAsArray, Object propertyValue,
            String key) {
        if (!writeAsArray) {
            out.writeFieldName(key, true);
        }

        serializer.write(propertyValue);
    }

    private boolean serializeObjectWithWildcardType(JSONSerializer serializer, Object object, Type fieldType, boolean commaFlag) {
        Class<?> objClass = object.getClass();

        Type type;
        if (objClass != fieldType && fieldType instanceof WildcardType) {
            type = TypeUtils.getClass(fieldType);
        } else {
            type = fieldType;
        }

        if (objClass != type) {
            writeClassName(serializer, beanInfo.typeKey, object);
            commaFlag = true;
        }
        return commaFlag;
    }

    private int updateSerializeFeatures(int serialzeFeatures) {
        if (beanInfo.jsonType != null) {
            serialzeFeatures |= SerializerFeature.of(beanInfo.jsonType.serialzeFeatures());
        }
        return serialzeFeatures;
    }

    protected void writeClassName(JSONSerializer serializer, String typeKey, Object object) {
        if (typeKey == null) {
            typeKey = serializer.config.typeKey;
        }
        serializer.out.writeFieldName(typeKey, false);
        String typeName = this.beanInfo.typeName;
        if (typeName == null) {
            typeName = getClassName(object);
        }
        serializer.write(typeName);
    }

    private String getClassName(Object object) {
        Class<?> clazz = object.getClass();

        if (TypeUtils.isProxy(clazz)) {
            clazz = clazz.getSuperclass();
        }

        return clazz.getName();
    }

    public boolean writeReference(JSONSerializer serializer, Object object, int fieldFeatures) {
        SerialContext context = serializer.context;
        int mask = SerializerFeature.DisableCircularReferenceDetect.mask;
        if (context == null || (context.features & mask) != 0 || (fieldFeatures & mask) != 0) {
            return false;
        }

        if (serializer.references != null && serializer.references.containsKey(object)) {
            serializer.writeReference(object);
            return true;
        }
        return false;
    }
    
    protected boolean isWriteAsArray(JSONSerializer serializer) {
        return isWriteAsArray(serializer, 0);   
    }

    protected boolean isWriteAsArray(JSONSerializer serializer, int fieldFeatrues) {
        int mask = SerializerFeature.BeanToArray.mask;
        return (beanInfo.features & mask) != 0 //
                || serializer.out.beanToArray //
                || (fieldFeatrues & mask) != 0;
    }
    
    public Object getFieldValue(Object object, String key) {
        FieldSerializer fieldDeser = getFieldSerializer(key);
        if (fieldDeser == null) {
            throw new JSONException("field not found. " + key);
        }
        
        try {
            return fieldDeser.getPropertyValue(object);
        } catch (InvocationTargetException ex) {
            throw new JSONException("getFieldValue error." + key, ex);
        } catch (IllegalAccessException ex) {
            throw new JSONException("getFieldValue error." + key, ex);
        }
    }

    public Object getFieldValue(Object object, String key, long keyHash, boolean throwFieldNotFoundException) {
        FieldSerializer fieldDeser = getFieldSerializer(keyHash);
        if (fieldDeser == null) {
            return throwJSONExceptionIfNotFound(key, throwFieldNotFoundException);
        }

        try {
            return fieldDeser.getPropertyValue(object);
        } catch (InvocationTargetException ex) {
            throw new JSONException("getFieldValue error." + key, ex);
        } catch (IllegalAccessException ex) {
            throw new JSONException("getFieldValue error." + key, ex);
        }
    }

    private Object throwJSONExceptionIfNotFound(String key, boolean throwFieldNotFoundException) {
        if (throwFieldNotFoundException) {
            throw new JSONException("field not found. " + key);
        }
        return null;
    }

    public FieldSerializer getFieldSerializer(String key) {
        if (key == null) {
            return null;
        }

        int low = 0;
        int high = sortedGetters.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;

            String fieldName = sortedGetters[mid].fieldInfo.name;

            int cmp = fieldName.compareTo(key);

            if (cmp < 0) {
                low = mid + 1;
            }
            else{
                if (cmp <= 0)
                    return sortedGetters[mid];
                high = mid - 1;
            }
        }

        return null; // key not found.
    }

    public FieldSerializer getFieldSerializer(long hash) {
        PropertyNamingStrategy[] namingStrategies = null;
        if (this.hashArray == null) {
            namingStrategies = generateNamingStrategiesHashArray();
        }

        int pos = Arrays.binarySearch(hashArray, hash);
        if (pos < 0) {
            return null;
        }

        if (hashArrayMapping == null) {
            initializePropertyMapping(namingStrategies);
        }

        int getterIndex = hashArrayMapping[pos];
        if (getterIndex != -1) {
            return sortedGetters[getterIndex];
        }

        return null; // key not found.
    }

    private void initializePropertyMapping(PropertyNamingStrategy[] namingStrategies) {
        if (namingStrategies == null) {
            namingStrategies = PropertyNamingStrategy.values();
        }

        short[] mapping = new short[hashArray.length];
        Arrays.fill(mapping, (short) -1);
        for (int i = 0;i < sortedGetters.length;i++) {
            updatePropertyMapping(namingStrategies, mapping, i);
        }
        hashArrayMapping = mapping;
    }

    private void updatePropertyMapping(PropertyNamingStrategy[] namingStrategies, short[] mapping, int i) {
        String name = sortedGetters[i].fieldInfo.name;

        int p = Arrays.binarySearch(hashArray
                , TypeUtils.fnv1a_64(name));
        if (p >= 0) {
            mapping[p] = (short) i;
        }

        for (int j = 0;j < namingStrategies.length;j++)
            updateNameMapping(namingStrategies, mapping, i, name, j);
    }

    private PropertyNamingStrategy[] generateNamingStrategiesHashArray() {
        PropertyNamingStrategy[] namingStrategies;
        namingStrategies = PropertyNamingStrategy.values();

        long[] hashArray = new long[sortedGetters.length * namingStrategies.length];
        int index = 0;
        for (int i = 0;i < sortedGetters.length;i++) {
            String name = sortedGetters[i].fieldInfo.name;
            hashArray[index++] = TypeUtils.fnv1a_64(name);

            index = updateHashArrayWithNamingStrategies(namingStrategies, hashArray, index, name);
        }
        Arrays.sort(hashArray, 0, index);

        this.hashArray = new long[index];
        System.arraycopy(hashArray, 0, this.hashArray, 0, index);
        return namingStrategies;
    }

    private void updateNameMapping(PropertyNamingStrategy[] namingStrategies, short[] mapping, int i, String name, int j) {
        String name_t = namingStrategies[j].translate(name);
        if (name.equals(name_t)) {
            return;
        }
        int p_t = Arrays.binarySearch(hashArray
                , TypeUtils.fnv1a_64(name_t));
        if (p_t >= 0) {
            mapping[p_t] = (short) i;
        }
    }

    private int updateHashArrayWithNamingStrategies(PropertyNamingStrategy[] namingStrategies, long[] hashArray, int index, String name) {
        for (int j = 0;j < namingStrategies.length;j++)
            index = translateAndHashName(namingStrategies, hashArray, index, name, j);
        return index;
    }

    private int translateAndHashName(PropertyNamingStrategy[] namingStrategies, long[] hashArray, int index, String name, int j) {
        String name_t = namingStrategies[j].translate(name);
        if (name.equals(name_t)) {
            return index;
        }
        hashArray[index++] = TypeUtils.fnv1a_64(name_t);
        return index;
    }

    public List<Object> getFieldValues(Object object) throws Exception {
        List<Object> fieldValues = new ArrayList<Object>(sortedGetters.length);
        for (FieldSerializer getter : sortedGetters) {
            fieldValues.add(getter.getPropertyValue(object));
        }

        return fieldValues;
    }

    // for jsonpath deepSet
    public List<Object> getObjectFieldValues(Object object) throws Exception {
        List<Object> fieldValues = new ArrayList<Object>(sortedGetters.length);
        for (FieldSerializer getter : sortedGetters)
            addNonPrimitiveFieldValue(object, fieldValues, getter);

        return fieldValues;
    }

    private void addNonPrimitiveFieldValue(Object object, List<Object> fieldValues, FieldSerializer getter)
            throws InvocationTargetException, IllegalAccessException {
        Class fieldClass = getter.fieldInfo.fieldClass;
        if (fieldClass.isPrimitive()) {
            return;
        }
        if (fieldClass.getName().startsWith("java.lang.")) {
            return;
        }
        fieldValues.add(getter.getPropertyValue(object));
    }
    
    public int getSize(Object object) throws Exception {
        int size = 0;
        for (FieldSerializer getter : sortedGetters) {
            size = incrementSizeIfNotNull(object, size, getter);
        }
        return size;
    }

    private int incrementSizeIfNotNull(Object object, int size, FieldSerializer getter)
            throws InvocationTargetException, IllegalAccessException {
        Object value = getter.getPropertyValueDirect(object);
        if (value != null) {
            size++;
        }
        return size;
    }
    
    /**
     * Get field names of not null fields. Keep the same logic as getSize.
     * 
     * @param object the object to be checked
     * @return field name set
     * @throws Exception
     * @see #getSize(Object)
     */
    public Set<String> getFieldNames(Object object) throws Exception {
        Set<String> fieldNames = new HashSet<String>();
        for (FieldSerializer getter : sortedGetters) {
            addFieldNameIfNotNull(object, fieldNames, getter);
        }
        return fieldNames;
    }

    private void addFieldNameIfNotNull(Object object, Set<String> fieldNames, FieldSerializer getter)
            throws InvocationTargetException, IllegalAccessException {
        Object value = getter.getPropertyValueDirect(object);
        if (value != null) {
            fieldNames.add(getter.fieldInfo.name);
        }
    }

    public Map<String, Object> getFieldValuesMap(Object object) throws Exception {
        Map<String, Object> map = new LinkedHashMap<String, Object>(sortedGetters.length);
        serializeAndSkipTransientFields(object, map);

        return map;
    }

    private void serializeAndSkipTransientFields(Object object, Map<String, Object> map)
            throws InvocationTargetException, IllegalAccessException {
        boolean skipTransient = true;
        FieldInfo fieldInfo = null;

        for (FieldSerializer getter : sortedGetters) {
            skipTransient = SerializerFeature.isEnabled(getter.features, SerializerFeature.SkipTransientField);
            fieldInfo = getter.fieldInfo;

            if (skipTransient && fieldInfo != null && fieldInfo.fieldTransient) {
                continue;
            }

            if (getter.fieldInfo.unwrapped) {
                serializeAndAddToMap(object, map, getter);
            } else {
                map.put(getter.fieldInfo.name, getter.getPropertyValue(object));
            }
        }
    }

    private void serializeAndAddToMap(Object object, Map<String, Object> map, FieldSerializer getter)
            throws InvocationTargetException, IllegalAccessException {
        Object unwrappedValue = getter.getPropertyValue(object);
        Object map1 = JSON.toJSON(unwrappedValue);
        if (map1 instanceof Map) {
            map.putAll((Map) map1);
        } else {
            map.put(getter.fieldInfo.name, getter.getPropertyValue(object));
        }
    }

    protected BeanContext getBeanContext(int orinal) {
        return sortedGetters[orinal].fieldContext;
    }
    
    protected Type getFieldType(int ordinal) {
        return sortedGetters[ordinal].fieldInfo.fieldType;
    }
    
    protected char writeBefore(JSONSerializer jsonBeanDeser, //
                            Object object, char seperator) {
        
        if (jsonBeanDeser.beforeFilters != null) {
            seperator = applyBeforeFilters(jsonBeanDeser, object, seperator);
        }
        
        if (this.beforeFilters != null) {
            seperator = applyBeforeFilters_(jsonBeanDeser, object, seperator);
        }
        
        return seperator;
    }

    private char applyBeforeFilters_(JSONSerializer jsonBeanDeser, Object object, char seperator) {
        for (BeforeFilter beforeFilter : this.beforeFilters) {
            seperator = beforeFilter.writeBefore(jsonBeanDeser, object, seperator);
        }
        return seperator;
    }

    private char applyBeforeFilters(JSONSerializer jsonBeanDeser, Object object, char seperator) {
        for (BeforeFilter beforeFilter : jsonBeanDeser.beforeFilters) {
            seperator = beforeFilter.writeBefore(jsonBeanDeser, object, seperator);
        }
        return seperator;
    }
    
    protected char writeAfter(JSONSerializer jsonBeanDeser, // 
                           Object object, char seperator) {
        if (jsonBeanDeser.afterFilters != null) {
            seperator = applyAfterFilters(jsonBeanDeser, object, seperator);
        }
        
        if (this.afterFilters != null) {
            seperator = applyAfterFilters_(jsonBeanDeser, object, seperator);
        }
        
        return seperator;
    }

    private char applyAfterFilters_(JSONSerializer jsonBeanDeser, Object object, char seperator) {
        for (AfterFilter afterFilter : this.afterFilters) {
            seperator = afterFilter.writeAfter(jsonBeanDeser, object, seperator);
        }
        return seperator;
    }

    private char applyAfterFilters(JSONSerializer jsonBeanDeser, Object object, char seperator) {
        for (AfterFilter afterFilter : jsonBeanDeser.afterFilters) {
            seperator = afterFilter.writeAfter(jsonBeanDeser, object, seperator);
        }
        return seperator;
    }
    
    protected boolean applyLabel(JSONSerializer jsonBeanDeser, String label) {
        if (jsonBeanDeser.labelFilters != null) {
            for (LabelFilter propertyFilter : jsonBeanDeser.labelFilters) {
                if (!propertyFilter.apply(label)) {
                    return false;
                }
            }
        }
        
        if (this.labelFilters != null) {
            for (LabelFilter propertyFilter : this.labelFilters) {
                if (!propertyFilter.apply(label)) {
                    return false;
                }
            }
        }
        
        return true;
    }
}
