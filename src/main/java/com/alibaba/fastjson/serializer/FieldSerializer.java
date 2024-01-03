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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.annotation.JSONType;
import com.alibaba.fastjson.util.FieldInfo;
import com.alibaba.fastjson.util.TypeUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Collection;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class FieldSerializer implements Comparable<FieldSerializer> {

    public final FieldInfo        fieldInfo;
    protected final boolean       writeNull;
    protected int                 features;

    private final String          double_quoted_fieldPrefix;
    private String                single_quoted_fieldPrefix;
    private String                un_quoted_fieldPrefix;

    protected BeanContext         fieldContext;

    private String                format;
    protected boolean             writeEnumUsingToString = false;
    protected boolean             writeEnumUsingName = false;
    protected boolean             disableCircularReferenceDetect = false;

    protected boolean             serializeUsing = false;

    protected boolean             persistenceXToMany = false; // OneToMany or ManyToMany
    protected boolean             browserCompatible;

    private RuntimeSerializerInfo runtimeInfo;
    
    public FieldSerializer(Class<?> beanType, FieldInfo fieldInfo) {
        this.fieldInfo = fieldInfo;
        this.fieldContext = new BeanContext(beanType, fieldInfo);

        if (beanType != null) {
            setJSONTypeIfPresent(beanType);
        }
        
        fieldInfo.setAccessible();

        this.double_quoted_fieldPrefix = '"' + fieldInfo.name + "\":";

        boolean writeNull = false;
        JSONField annotation = fieldInfo.getAnnotation();
        if (annotation != null) {
            writeNull = setSerializationFeatures(writeNull, annotation);
        }
        
        this.writeNull = writeNull;

        persistenceXToMany = TypeUtils.isAnnotationPresentOneToMany(fieldInfo.method)
                || TypeUtils.isAnnotationPresentManyToMany(fieldInfo.method);
    }

    private void setJSONTypeIfPresent(Class<?> beanType) {
        JSONType jsonType = TypeUtils.getAnnotation(beanType, JSONType.class);
        if (jsonType != null) {
            setJSONTypeSerializerFeatures(jsonType);
        }
    }

    private boolean setSerializationFeatures(boolean writeNull, JSONField annotation) {
        writeNull = setWriteNullFeature(writeNull, annotation);

        format = annotation.format();

        if (format.trim().length() == 0) {
            format = null;
        }

        for (SerializerFeature feature : annotation.serialzeFeatures()) {
            setSerializerFeatureState(feature);
        }
        
        features |= SerializerFeature.of(annotation.serialzeFeatures());
        return writeNull;
    }

    private void setJSONTypeSerializerFeatures(JSONType jsonType) {
        for (SerializerFeature feature : jsonType.serialzeFeatures()) {
            setSerializerFeature(feature);
        }
    }

    private void setSerializerFeatureState(SerializerFeature feature) {
        if (feature == SerializerFeature.WriteEnumUsingToString) {
            writeEnumUsingToString = true;
            return;
        }
        if (feature == SerializerFeature.WriteEnumUsingName) {
            writeEnumUsingName = true;
        }
        else if (feature == SerializerFeature.DisableCircularReferenceDetect) {
            disableCircularReferenceDetect = true;
        }
        else if (feature == SerializerFeature.BrowserCompatible) {
            browserCompatible = true;
        }
    }

    private boolean setWriteNullFeature(boolean writeNull, JSONField annotation) {
        writeNull = setWriteNullFromAnnotationFeatures(writeNull, annotation);
        return writeNull;
    }

    private boolean setWriteNullFromAnnotationFeatures(boolean writeNull, JSONField annotation) {
        writeNull = setWriteNullFromAnnotationFeatures_(writeNull, annotation);
        return writeNull;
    }

    private boolean setWriteNullFromAnnotationFeatures_(boolean writeNull, JSONField annotation) {
        writeNull = updateWriteNullStatus(writeNull, annotation);
        return writeNull;
    }

    private boolean updateWriteNullStatus(boolean writeNull, JSONField annotation) {
        for (SerializerFeature feature : annotation.serialzeFeatures()) {
            if ((feature.getMask() & SerializerFeature.WRITE_MAP_NULL_FEATURES) != 0) {
                writeNull = true;
                break;
            }
        }
        return writeNull;
    }

    private void setSerializerFeature(SerializerFeature feature) {
        if (feature == SerializerFeature.WriteEnumUsingToString) {
            writeEnumUsingToString = true;
            return;
        }
        if (feature == SerializerFeature.WriteEnumUsingName) {
            writeEnumUsingName = true;
        }
        else if (feature == SerializerFeature.DisableCircularReferenceDetect) {
            disableCircularReferenceDetect = true;
        }
        else if (feature == SerializerFeature.BrowserCompatible) {
            features |= SerializerFeature.BrowserCompatible.mask;
            browserCompatible = true;
        }
        else if (feature == SerializerFeature.WriteMapNullValue) {
            features |= SerializerFeature.WriteMapNullValue.mask;
        }
    }

    public void writePrefix(JSONSerializer serializer) throws IOException {
        SerializeWriter out = serializer.out;

        if (out.quoteFieldNames) {
            writeFieldPrefixWithQuotes(out);
        } else {
            writeUnquotedFieldPrefix(out);
        }
    }

    private void writeFieldPrefixWithQuotes(SerializeWriter out) {
        boolean useSingleQuotes = SerializerFeature.isEnabled(out.features, fieldInfo.serialzeFeatures, SerializerFeature.UseSingleQuotes);
        if (useSingleQuotes) {
            writeFieldPrefix(out);
        } else {
            out.write(double_quoted_fieldPrefix);
        }
    }

    private void writeUnquotedFieldPrefix(SerializeWriter out) {
        if (un_quoted_fieldPrefix == null) {
            this.un_quoted_fieldPrefix = fieldInfo.name + ":";
        }
        out.write(un_quoted_fieldPrefix);
    }

    private void writeFieldPrefix(SerializeWriter out) {
        if (single_quoted_fieldPrefix == null) {
            single_quoted_fieldPrefix = '\'' + fieldInfo.name + "\':";
        }
        out.write(single_quoted_fieldPrefix);
    }

    public Object getPropertyValueDirect(Object object) throws InvocationTargetException, IllegalAccessException {
        Object fieldValue = fieldInfo.get(object);
        if (persistenceXToMany && !TypeUtils.isHibernateInitialized(fieldValue)) {
            return null;
        }
        return fieldValue;
    }

    public Object getPropertyValue(Object object) throws InvocationTargetException, IllegalAccessException {
        Object propertyValue = fieldInfo.get(object);
        if (format != null && propertyValue != null) {
            if (fieldInfo.fieldClass == java.util.Date.class || fieldInfo.fieldClass == java.sql.Date.class) {
                SimpleDateFormat dateFormat = new SimpleDateFormat(format, JSON.defaultLocale);
                dateFormat.setTimeZone(JSON.defaultTimeZone);
                return dateFormat.format(propertyValue);
            }
        }
        return propertyValue;
    }
    
    public int compareTo(FieldSerializer o) {
        return this.fieldInfo.compareTo(o.fieldInfo);
    }
    

    public void writeValue(JSONSerializer serializer, Object propertyValue) throws Exception {
        if (runtimeInfo == null) {

            serializePropertyValue(serializer, propertyValue);
        }
        
        RuntimeSerializerInfo runtimeInfo = this.runtimeInfo;
        
        int fieldFeatures
                = (disableCircularReferenceDetect
                ? (fieldInfo.serialzeFeatures | SerializerFeature.DisableCircularReferenceDetect.mask)
                : fieldInfo.serialzeFeatures) | features;

        if (propertyValue == null) {
            SerializeWriter out = serializer.out;

            if (fieldInfo.fieldClass == Object.class
                    && out.isEnabled(SerializerFeature.WRITE_MAP_NULL_FEATURES)) {
                out.writeNull();
                return;
            }

            Class<?> runtimeFieldClass = runtimeInfo.runtimeFieldClass;

            if (Number.class.isAssignableFrom(runtimeFieldClass)) {
                out.writeNull(features, SerializerFeature.WriteNullNumberAsZero.mask);
                return;
            }
            if (String.class == runtimeFieldClass) {
                out.writeNull(features, SerializerFeature.WriteNullStringAsEmpty.mask);
                return;
            }
            if (Boolean.class == runtimeFieldClass) {
                out.writeNull(features, SerializerFeature.WriteNullBooleanAsFalse.mask);
                return;
            }
            if (Collection.class.isAssignableFrom(runtimeFieldClass)
                    || runtimeFieldClass.isArray()) {
                out.writeNull(features, SerializerFeature.WriteNullListAsEmpty.mask);
                return;
            }

            ObjectSerializer fieldSerializer = runtimeInfo.fieldSerializer;

            if ((out.isEnabled(SerializerFeature.WRITE_MAP_NULL_FEATURES))
                    && fieldSerializer instanceof JavaBeanSerializer) {
                out.writeNull();
                return;
            }

            fieldSerializer.write(serializer, null, fieldInfo.name, fieldInfo.fieldType, fieldFeatures);
            return;
        }

        if (fieldInfo.isEnum) {
            if (writeEnumUsingName) {
                serializer.out.writeString(((Enum<?>) propertyValue).name());
                return;
            }

            if (writeEnumUsingToString) {
                serializer.out.writeString(((Enum<?>) propertyValue).toString());
                return;
            }
        }
        
        Class<?> valueClass = propertyValue.getClass();
        ObjectSerializer valueSerializer;
        if (valueClass == runtimeInfo.runtimeFieldClass || serializeUsing) {
            valueSerializer = runtimeInfo.fieldSerializer;
        }
        else {
            valueSerializer = serializer.getObjectWriter(valueClass);
        }
        
        if (format != null && !(valueSerializer instanceof DoubleSerializer || valueSerializer instanceof FloatCodec)) {
            if (valueSerializer instanceof ContextObjectSerializer) {
                ((ContextObjectSerializer) valueSerializer).write(serializer, propertyValue, this.fieldContext);    
            }
            else {
                serializer.writeWithFormat(propertyValue, format);
            }
            return;
        }

        if (fieldInfo.unwrapped) {
            if (valueSerializer instanceof JavaBeanSerializer) {
                JavaBeanSerializer javaBeanSerializer = (JavaBeanSerializer) valueSerializer;
                javaBeanSerializer.write(serializer, propertyValue, fieldInfo.name, fieldInfo.fieldType, fieldFeatures, true);
                return;
            }

            if (valueSerializer instanceof MapSerializer) {
                MapSerializer mapSerializer = (MapSerializer) valueSerializer;
                mapSerializer.write(serializer, propertyValue, fieldInfo.name, fieldInfo.fieldType, fieldFeatures, true);
                return;
            }
        }

        if ((features & SerializerFeature.WriteClassName.mask) != 0
                && valueClass != fieldInfo.fieldClass
                && valueSerializer instanceof JavaBeanSerializer) {
            ((JavaBeanSerializer) valueSerializer).write(serializer, propertyValue, fieldInfo.name, fieldInfo.fieldType, fieldFeatures, false);
            return;
        }

        if (browserCompatible && (fieldInfo.fieldClass == long.class || fieldInfo.fieldClass == Long.class)) {
            long value = (Long) propertyValue;
            if (value > 9007199254740991L || value < -9007199254740991L) {
                serializer.getWriter().writeString(Long.toString(value));
                return;
            }
        }

        valueSerializer.write(serializer, propertyValue, fieldInfo.name, fieldInfo.fieldType, fieldFeatures);
    }

    private void serializePropertyValue(JSONSerializer serializer, Object propertyValue)
            throws InstantiationException, IllegalAccessException {
        Class<?> runtimeFieldClass;
        if (propertyValue == null) {
            runtimeFieldClass = getWrapperClass();
        } else {
            runtimeFieldClass = propertyValue.getClass();
        }

        ObjectSerializer fieldSerializer = null;
        JSONField fieldAnnotation = fieldInfo.getAnnotation();

        if (fieldAnnotation != null && fieldAnnotation.serializeUsing() != Void.class) {
            fieldSerializer = (ObjectSerializer) fieldAnnotation.serializeUsing().newInstance();
            serializeUsing = true;
        } else {
            fieldSerializer = getUpdatedFieldSerializer(serializer, runtimeFieldClass, fieldSerializer);
        }

        runtimeInfo = new RuntimeSerializerInfo(fieldSerializer, runtimeFieldClass);
    }

    private ObjectSerializer getUpdatedFieldSerializer(JSONSerializer serializer, Class<?> runtimeFieldClass,
            ObjectSerializer fieldSerializer) {
        if (format != null) {
            fieldSerializer = getRuntimeFieldSerializer(runtimeFieldClass, fieldSerializer);
        }

        if (fieldSerializer == null) {
            fieldSerializer = serializer.getObjectWriter(runtimeFieldClass);
        }
        return fieldSerializer;
    }

    private ObjectSerializer getRuntimeFieldSerializer(Class<?> runtimeFieldClass, ObjectSerializer fieldSerializer) {
        if (runtimeFieldClass == double.class || runtimeFieldClass == Double.class) {
            fieldSerializer = new DoubleSerializer(format);
        } else if (runtimeFieldClass == float.class || runtimeFieldClass == Float.class) {
            fieldSerializer = new FloatCodec(format);
        }
        return fieldSerializer;
    }

    private Class<?> getWrapperClass() {
        Class<?> runtimeFieldClass;
        runtimeFieldClass = this.fieldInfo.fieldClass;
        if (runtimeFieldClass == byte.class) {
            runtimeFieldClass = Byte.class;
        } else if (runtimeFieldClass == short.class) {
            runtimeFieldClass = Short.class;
        } else if (runtimeFieldClass == int.class) {
            runtimeFieldClass = Integer.class;
        } else if (runtimeFieldClass == long.class) {
            runtimeFieldClass = Long.class;
        } else if (runtimeFieldClass == float.class) {
            runtimeFieldClass = Float.class;
        } else if (runtimeFieldClass == double.class) {
            runtimeFieldClass = Double.class;
        } else if (runtimeFieldClass == boolean.class) {
            runtimeFieldClass = Boolean.class;
        }
        return runtimeFieldClass;
    }

    static class RuntimeSerializerInfo {
        final ObjectSerializer fieldSerializer;
        final Class<?>         runtimeFieldClass;

        public RuntimeSerializerInfo(ObjectSerializer fieldSerializer, Class<?> runtimeFieldClass) {
            this.fieldSerializer = fieldSerializer;
            this.runtimeFieldClass = runtimeFieldClass;
        }
    }
}
