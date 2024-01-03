package com.alibaba.fastjson.parser.deserializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.DefaultJSONParser.ResolveTask;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.ParseContext;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.util.FieldInfo;

public class DefaultFieldDeserializer extends FieldDeserializer {

    protected ObjectDeserializer fieldValueDeserilizer;
    protected boolean            customDeserilizer = false;

    public DefaultFieldDeserializer(ParserConfig config, Class<?> clazz, FieldInfo fieldInfo) {
        super(clazz, fieldInfo);
        JSONField annotation = fieldInfo.getAnnotation();
        if (annotation != null) {
            Class<?> deserializeUsing = annotation.deserializeUsing();
            customDeserilizer = deserializeUsing != null && deserializeUsing != Void.class;
        }
    }

    public ObjectDeserializer getFieldValueDeserilizer(ParserConfig config) {
        if (fieldValueDeserilizer == null) {
            initializeDeserializer(config);
        }

        return fieldValueDeserilizer;
    }

    private void initializeDeserializer(ParserConfig config) {
        JSONField annotation = fieldInfo.getAnnotation();
        if (annotation != null && annotation.deserializeUsing() != Void.class) {
            Class<?> deserializeUsing = annotation.deserializeUsing();
            try {
                fieldValueDeserilizer = (ObjectDeserializer) deserializeUsing.newInstance();
            } catch (Exception ex) {
                throw new JSONException("create deserializeUsing ObjectDeserializer error", ex);
            }
        } else {
            fieldValueDeserilizer = config.getDeserializer(fieldInfo.fieldClass, fieldInfo.fieldType);
        }
    }

    @Override
    public void parseField(DefaultJSONParser parser, Object object, Type objectType, Map<String, Object> fieldValues) {
        if (this.fieldValueDeserilizer == null) {
            getFieldValueDeserilizer(parser.getConfig());
        }

        ObjectDeserializer fieldValueDeserilizer = this.fieldValueDeserilizer;
        Type fieldType = fieldInfo.fieldType;
        if (objectType instanceof ParameterizedType) {
            ParseContext objContext = parser.getContext();
            if (objContext != null) {
                objContext.type = objectType;
            }
            if (fieldType != objectType) {
                fieldType = FieldInfo.getFieldType(this.clazz, objectType, fieldType);
                if (fieldValueDeserilizer instanceof JavaObjectDeserializer) {
                    fieldValueDeserilizer = parser.getConfig().getDeserializer(fieldType);
                }
            }
        }

        // ContextObjectDeserializer
        Object value;
        if (fieldValueDeserilizer instanceof JavaBeanDeserializer && fieldInfo.parserFeatures != 0) {
            JavaBeanDeserializer javaBeanDeser = (JavaBeanDeserializer) fieldValueDeserilizer;
            value = javaBeanDeser.deserialze(parser, fieldType, fieldInfo.name, fieldInfo.parserFeatures);
        } else {
            value = deserializeFieldValue(parser, fieldValueDeserilizer, fieldType);
        }

        if (value instanceof byte[]
                && ("gzip".equals(fieldInfo.format) || "gzip,base64".equals(fieldInfo.format))) {
            byte[] bytes = (byte[]) value;
            GZIPInputStream gzipIn = null;
            try {
                gzipIn = new GZIPInputStream(new ByteArrayInputStream(bytes));

                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                decompressGzipStream(gzipIn, byteOut);
                value = byteOut.toByteArray();

            } catch (IOException ex) {
                throw new JSONException("unzip bytes error.", ex);
            }
        }

        if (parser.getResolveStatus() == DefaultJSONParser.NeedToResolve) {
            setParserResolveTask(parser);
        } else {
            setFieldValue(object, fieldValues, value);
        }
    }

    private void setFieldValue(Object object, Map<String, Object> fieldValues, Object value) {
        if (object == null) {
            fieldValues.put(fieldInfo.name, value);
            return;
        }
        setValue(object, value);
    }

    private void setParserResolveTask(DefaultJSONParser parser) {
        ResolveTask task = parser.getLastResolveTask();
        task.fieldDeserializer = this;
        task.ownerContext = parser.getContext();
        parser.setResolveStatus(DefaultJSONParser.NONE);
    }

    private void decompressGzipStream(GZIPInputStream gzipIn, ByteArrayOutputStream byteOut) throws IOException {
        for (;;) {
            byte[] buf = new byte[1024];
            int len = gzipIn.read(buf);
            if (len == -1) {
                break;
            }
            if (len > 0) {
                byteOut.write(buf, 0, len);
            }
        }
    }

    private Object deserializeFieldValue(DefaultJSONParser parser, ObjectDeserializer fieldValueDeserilizer, Type fieldType) {
        Object value;
        if ((this.fieldInfo.format != null || this.fieldInfo.parserFeatures != 0)
                && fieldValueDeserilizer instanceof ContextObjectDeserializer) {
            value = ((ContextObjectDeserializer) fieldValueDeserilizer) //
		                            .deserialze(parser,
                                                fieldType,
                                                fieldInfo.name,
                                                fieldInfo.format,
                                                fieldInfo.parserFeatures);
        } else {
            value = fieldValueDeserilizer.deserialze(parser, fieldType, fieldInfo.name);
        }
        return value;
    }

    public int getFastMatchToken() {
        if (fieldValueDeserilizer != null) {
            return fieldValueDeserilizer.getFastMatchToken();
        }

        return JSONToken.LITERAL_INT;
    }

    public void parseFieldUnwrapped(DefaultJSONParser parser, Object object, Type objectType, Map<String, Object> fieldValues) {
        throw new JSONException("TODO");
    }
}
