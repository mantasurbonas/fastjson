package com.alibaba.fastjson.parser.deserializer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONValidator;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.parser.*;
import com.alibaba.fastjson.parser.DefaultJSONParser.ResolveTask;
import com.alibaba.fastjson.util.FieldInfo;
import com.alibaba.fastjson.util.JavaBeanInfo;
import com.alibaba.fastjson.util.TypeUtils;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.alibaba.fastjson.util.TypeUtils.fnv1a_64_magic_hashcode;

public class JavaBeanDeserializer implements ObjectDeserializer {

    private final FieldDeserializer[]   fieldDeserializers;
    protected final FieldDeserializer[] sortedFieldDeserializers;
    protected final Class<?>            clazz;
    public final JavaBeanInfo           beanInfo;
    private ConcurrentMap<String, Object> extraFieldDeserializers;

    private final Map<String, FieldDeserializer> alterNameFieldDeserializers;
    private Map<String, FieldDeserializer> fieldDeserializerMap;

    private transient long[] smartMatchHashArray;
    private transient short[] smartMatchHashArrayMapping;

    private transient long[] hashArray;
    private transient short[] hashArrayMapping;

    private final ParserConfig.AutoTypeCheckHandler autoTypeCheckHandler;
    
    public JavaBeanDeserializer(ParserConfig config, Class<?> clazz) {
        this(config, clazz, clazz);
    }

    public JavaBeanDeserializer(ParserConfig config, Class<?> clazz, Type type) {
        this(config //
                , JavaBeanInfo.build(clazz, type, config.propertyNamingStrategy, config.fieldBased, config.compatibleWithJavaBean, config.isJacksonCompatible())
        );
    }
    
    public JavaBeanDeserializer(ParserConfig config, JavaBeanInfo beanInfo) {
        this.clazz = beanInfo.clazz;
        this.beanInfo = beanInfo;

        ParserConfig.AutoTypeCheckHandler autoTypeCheckHandler = null;
        if (beanInfo.jsonType != null && beanInfo.jsonType.autoTypeCheckHandler() != ParserConfig.AutoTypeCheckHandler.class) {
            try {
                autoTypeCheckHandler = beanInfo.jsonType.autoTypeCheckHandler().newInstance();
            } catch (Exception e) {
                //
            }
        }
        this.autoTypeCheckHandler = autoTypeCheckHandler;

        Map<String, FieldDeserializer> alterNameFieldDeserializers = null;
        sortedFieldDeserializers = new FieldDeserializer[beanInfo.sortedFields.length];
        for (int i = 0, size = beanInfo.sortedFields.length;i < size;++i) {
            alterNameFieldDeserializers = createFieldDeserializers(config, beanInfo, alterNameFieldDeserializers, i, size);
        }
        this.alterNameFieldDeserializers = alterNameFieldDeserializers;

        fieldDeserializers = new FieldDeserializer[beanInfo.fields.length];
        for (int i = 0, size = beanInfo.fields.length;i < size;++i) {
            FieldInfo fieldInfo = beanInfo.fields[i];
            FieldDeserializer fieldDeserializer = getFieldDeserializer(fieldInfo.name);
            fieldDeserializers[i] = fieldDeserializer;
        }
    }

    private Map<String, FieldDeserializer> createFieldDeserializers(ParserConfig config, JavaBeanInfo beanInfo,
            Map<String, FieldDeserializer> alterNameFieldDeserializers, int i, int size) {
        FieldInfo fieldInfo = beanInfo.sortedFields[i];
        FieldDeserializer fieldDeserializer = config.createFieldDeserializer(config, beanInfo, fieldInfo);

        sortedFieldDeserializers[i] = fieldDeserializer;

        if (size > 128) {
            initializeFieldDeserializerMap(fieldInfo, fieldDeserializer);
        }

        for (String name : fieldInfo.alternateNames) {
            alterNameFieldDeserializers = addDeserializerToMap(alterNameFieldDeserializers, fieldDeserializer, name);
        }
        return alterNameFieldDeserializers;
    }

    private Map<String, FieldDeserializer> addDeserializerToMap(Map<String, FieldDeserializer> alterNameFieldDeserializers,
            FieldDeserializer fieldDeserializer, String name) {
        if (alterNameFieldDeserializers == null) {
            alterNameFieldDeserializers = new HashMap<String, FieldDeserializer>();
        }
        alterNameFieldDeserializers.put(name, fieldDeserializer);
        return alterNameFieldDeserializers;
    }

    private void initializeFieldDeserializerMap(FieldInfo fieldInfo, FieldDeserializer fieldDeserializer) {
        if (fieldDeserializerMap == null) {
            fieldDeserializerMap = new HashMap<String, FieldDeserializer>();
        }
        fieldDeserializerMap.put(fieldInfo.name, fieldDeserializer);
    }

    public FieldDeserializer getFieldDeserializer(String key) {
        return getFieldDeserializer(key, null);
    }

    public FieldDeserializer getFieldDeserializer(String key, int[] setFlags) {
        if (key == null) {
            return null;
        }

        if (fieldDeserializerMap != null) {
            FieldDeserializer fieldDeserializer = fieldDeserializerMap.get(key);
            if (fieldDeserializer != null) {
                return fieldDeserializer;
            }
        }
        
        int low = 0;
        int high = sortedFieldDeserializers.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            
            String fieldName = sortedFieldDeserializers[mid].fieldInfo.name;
            
            int cmp = fieldName.compareTo(key);

            if (cmp < 0) {
                low = mid + 1;
            }
            else{
                if (cmp <= 0)
                    return getFieldDeserializer_(setFlags, mid);
                high = mid - 1;
            }
        }

        if (this.alterNameFieldDeserializers != null) {
            return this.alterNameFieldDeserializers.get(key);
        }
        
        return null;  // key not found.
    }

    private FieldDeserializer getFieldDeserializer_(int[] setFlags, int mid) {
        if (isSetFlag(mid, setFlags)) {
            return null;
        }

        return sortedFieldDeserializers[mid]; // key found
	}

    public FieldDeserializer getFieldDeserializer(long hash) {
        if (this.hashArray == null) {
            generateSortedHashArray();
        }

        int pos = Arrays.binarySearch(hashArray, hash);
        if (pos < 0) {
            return null;
        }

        if (hashArrayMapping == null) {
            mapFieldIndices();
        }

        int setterIndex = hashArrayMapping[pos];
        if (setterIndex != -1) {
            return sortedFieldDeserializers[setterIndex];
        }

        return null; // key not found.
    }

    private void mapFieldIndices() {
        short[] mapping = new short[hashArray.length];
        Arrays.fill(mapping, (short) -1);
        for (int i = 0;i < sortedFieldDeserializers.length;i++) {
            mapFieldIndex(mapping, i);
        }
        hashArrayMapping = mapping;
    }

    private void mapFieldIndex(short[] mapping, int i) {
        int p = Arrays.binarySearch(hashArray
                , TypeUtils.fnv1a_64(sortedFieldDeserializers[i].fieldInfo.name));
        if (p >= 0) {
            mapping[p] = (short) i;
        }
    }

    private void generateSortedHashArray() {
        long[] hashArray = new long[sortedFieldDeserializers.length];
        for (int i = 0;i < sortedFieldDeserializers.length;i++) {
            hashArray[i] = TypeUtils.fnv1a_64(sortedFieldDeserializers[i].fieldInfo.name);
        }
        Arrays.sort(hashArray);
        this.hashArray = hashArray;
    }

    static boolean isSetFlag(int i, int[] setFlags) {
        if (setFlags == null) {
            return false;
        }

        int flagIndex = i / 32;
        return flagIndex < setFlags.length
                && (setFlags[flagIndex] & (1 << i % 32)) != 0;
    }
    
    public Object createInstance(DefaultJSONParser parser, Type type) {
        if (type instanceof Class) {
            if (clazz.isInterface()) {
                return createProxyInstance(type);
            }
        }

        if (beanInfo.defaultConstructor == null && beanInfo.factoryMethod == null) {
            return null;
        }

        if (beanInfo.factoryMethod != null && beanInfo.defaultConstructorParameterSize > 0) {
            return null;
        }

        Object object;
        try {
            object = createObjectInstance_(parser, type);
        } catch (JSONException e) {
            throw e;
        } catch (Exception e) {
            throw new JSONException("create instance error, class " + clazz.getName(), e);
        }

        if (parser != null // 
                && parser.lexer.isEnabled(Feature.InitStringFieldAsEmpty)) {
            clearStringFields(object);
        }

        return object;
    }

    private Object createObjectInstance_(DefaultJSONParser parser, Type type)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        Object object;
        Constructor<?> constructor = beanInfo.defaultConstructor;
        if (beanInfo.defaultConstructorParameterSize == 0) {
            object = createObjectInstance(constructor);
        } else {
            object = createInnerClassInstance(parser, type, constructor);
        }
        return object;
    }

    private void clearStringFields(Object object) {
        for (FieldInfo fieldInfo : beanInfo.fields) {
            if (fieldInfo.fieldClass == String.class) {
                try {
                    fieldInfo.set(object, "");
                } catch (Exception e) {
                    throw new JSONException("create instance error, class " + clazz.getName(), e);
                }
            }
        }
    }

    private Object createInnerClassInstance(DefaultJSONParser parser, Type type, Constructor<?> constructor)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        Object object;
        ParseContext context = parser.getContext();
        if (context == null || context.object == null) {
            throw new JSONException("can't create non-static inner class instance.");
        }

        String typeName;
        if (!(type instanceof Class))
            throw new JSONException("can't create non-static inner class instance.");
        typeName = ((Class<?>) type).getName();

        int lastIndex = typeName.lastIndexOf('$');
        String parentClassName = typeName.substring(0, lastIndex);

        Object ctxObj = context.object;
        String parentName = ctxObj.getClass().getName();

        Object param = null;
        if (!parentName.equals(parentClassName)) {
            ParseContext parentContext = context.parent;
            if (parentContext != null
                    && parentContext.object != null
                    && ("java.util.ArrayList".equals(parentName)
                    || "java.util.List".equals(parentName)
                    || "java.util.Collection".equals(parentName)
                    || "java.util.Map".equals(parentName)
                    || "java.util.HashMap".equals(parentName))) {
                parentName = parentContext.object.getClass().getName();
                if (parentName.equals(parentClassName)) {
                    param = parentContext.object;
                }
            }
            else {
                param = ctxObj;
            }
        }
        else {
            param = ctxObj;
        }

        if (param == null || param instanceof Collection && ((Collection) param).isEmpty()) {
            throw new JSONException("can't create non-static inner class instance.");
        }

        return constructor.newInstance(param);
    }

    private Object createObjectInstance(Constructor<?> constructor)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        Object object;
        if (constructor != null) {
            object = constructor.newInstance();
        } else {
            object = beanInfo.factoryMethod.invoke(null);
        }
        return object;
    }

    private Object createProxyInstance(Type type) {
        Class<?> clazz = (Class<?>) type;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        JSONObject obj = new JSONObject();
        return Proxy.newProxyInstance(loader, new Class<?>[]{clazz}, obj);
    }
    
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        return deserialze(parser, type, fieldName, 0);
    }

    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName, int features) {
        return deserialze(parser, type, fieldName, null, features, null);
    }

    @SuppressWarnings({"unchecked"})
    public <T> T deserialzeArrayMapping(DefaultJSONParser parser, Type type, Object fieldName, Object object) {
        JSONLexer lexer = parser.lexer; // xxx
        if (lexer.token() != JSONToken.LBRACKET) {
            throw new JSONException("error");
        }

        String typeName = null;
        if ((typeName = lexer.scanTypeName(parser.symbolTable)) != null) {
            ObjectDeserializer deserializer = getDeserializer(parser, type, lexer, typeName);

            if (deserializer instanceof JavaBeanDeserializer) {
                return ((JavaBeanDeserializer) deserializer).deserialzeArrayMapping(parser, type, fieldName, object);
            }
        }

        object = createInstance(parser, type);

        deserializeFields(parser, object, lexer);
        lexer.nextToken(JSONToken.COMMA);

        return (T) object;
    }

    private <T> void deserializeFields(DefaultJSONParser parser, Object object, JSONLexer lexer) {
        for (int i = 0, size = sortedFieldDeserializers.length;i < size;++i) {
            char seperator = i == size - 1 ? ']' : ',';
            FieldDeserializer fieldDeser = sortedFieldDeserializers[i];
            Class<?> fieldClass = fieldDeser.fieldInfo.fieldClass;
            if (fieldClass == int.class) {
                int value = lexer.scanInt(seperator);
                fieldDeser.setValue(object, value);
            } else if (fieldClass == String.class) {
                String value = lexer.scanString(seperator);
                fieldDeser.setValue(object, value);
            } else if (fieldClass == long.class) {
                long value = lexer.scanLong(seperator);
                fieldDeser.setValue(object, value);
            } else if (fieldClass.isEnum()) {
                parseEnumValue(parser, object, lexer, seperator, fieldDeser, fieldClass);
            } else if (fieldClass == boolean.class) {
                boolean value = lexer.scanBoolean(seperator);
                fieldDeser.setValue(object, value);
            } else if (fieldClass == float.class) {
                float value = lexer.scanFloat(seperator);
                fieldDeser.setValue(object, value);
            } else if (fieldClass == double.class) {
                double value = lexer.scanDouble(seperator);
                fieldDeser.setValue(object, value);
            } else if (fieldClass == java.util.Date.class && lexer.getCurrent() == '1') {
                long longValue = lexer.scanLong(seperator);
                fieldDeser.setValue(object, new java.util.Date(longValue));
            } else if (fieldClass == BigDecimal.class) {
                BigDecimal value = lexer.scanDecimal(seperator);
                fieldDeser.setValue(object, value);
            } else {
                lexer.nextToken(JSONToken.LBRACKET);
                Object value = parser.parseObject(fieldDeser.fieldInfo.fieldType, fieldDeser.fieldInfo.name);
                fieldDeser.setValue(object, value);

                if (lexer.token() == JSONToken.RBRACKET) {
                    break;
                }

                check(lexer, seperator == ']' ? JSONToken.RBRACKET : JSONToken.COMMA);
                // parser.accept(seperator == ']' ? JSONToken.RBRACKET : JSONToken.COMMA);
            }
        }
    }

    private <T> void parseEnumValue(DefaultJSONParser parser, Object object, JSONLexer lexer, char seperator,
            FieldDeserializer fieldDeser, Class<?> fieldClass) {
        char ch = lexer.getCurrent();
        
        Object value;
        if (ch == '\"' || ch == 'n') {
            value = lexer.scanEnum(fieldClass, parser.getSymbolTable(), seperator);
        } else if (ch >= '0' && ch <= '9') {
            int ordinal = lexer.scanInt(seperator);
            
            EnumDeserializer enumDeser = (EnumDeserializer) ((DefaultFieldDeserializer) fieldDeser).getFieldValueDeserilizer(parser.getConfig());
            value = enumDeser.valueOf(ordinal);
        } else {
            value = scanEnum(lexer, seperator);
        }
        
        fieldDeser.setValue(object, value);
    }

    private <T> ObjectDeserializer getDeserializer(DefaultJSONParser parser, Type type, JSONLexer lexer, String typeName) {
        ObjectDeserializer deserializer = getSeeAlso(parser.getConfig(), this.beanInfo, typeName);
        Class<?> userType = null;

        if (deserializer == null) {
            Class<?> expectClass = TypeUtils.getClass(type);
            userType = parser.getConfig().checkAutoType(typeName, expectClass, lexer.getFeatures());
            deserializer = parser.getConfig().getDeserializer(userType);
        }
        return deserializer;
    }

    protected void check(JSONLexer lexer, int token) {
        if (lexer.token() != token) {
            throw new JSONException("syntax error");
        }
    }
    
    protected Enum<?> scanEnum(JSONLexer lexer, char seperator) {
        throw new JSONException("illegal enum. " + lexer.info());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected <T> T deserialze(DefaultJSONParser parser, // 
                                                                                    Type type, // 
                                                                                    Object fieldName, // 
                                                                                    Object object, //
                                                                                    int features, //
                                                                                    int[] setFlags) {
        if (type == JSON.class || type == JSONObject.class) {
            return (T) parser.parse();
        }

        JSONLexerBase lexer = (JSONLexerBase) parser.lexer; // xxx
        ParserConfig config = parser.getConfig();

        int token = lexer.token();
        if (token == JSONToken.NULL) {
            lexer.nextToken(JSONToken.COMMA);
            return null;
        }

        ParseContext context = parser.getContext();
        if (object != null && context != null) {
            context = context.parent;
        }
        ParseContext childContext = null;

        try {
            Map<String, Object> fieldValues = null;

            if (token == JSONToken.RBRACE) {
                lexer.nextToken(JSONToken.COMMA);
                if (object == null) {
                    object = createInstance(parser, type);
                }
                return (T) object;
            }

            if (token == JSONToken.LBRACKET) {
                int mask = Feature.SupportArrayToBean.mask;
                boolean isSupportArrayToBean = (beanInfo.parserFeatures & mask) != 0 //
                        || lexer.isEnabled(Feature.SupportArrayToBean) //
                        || (features & mask) != 0
                ;
                if (isSupportArrayToBean) {
                    return deserialzeArrayMapping(parser, type, fieldName, object);
                }
            }

            if (token != JSONToken.LBRACE && token != JSONToken.COMMA) {
                return parseJsonValue(fieldName, lexer, config, token);
            }

            if (parser.resolveStatus == DefaultJSONParser.TypeNameRedirect) {
                parser.resolveStatus = DefaultJSONParser.NONE;
            }

            String typeKey = beanInfo.typeKey;
            for (int fieldIndex = 0, notMatchCount = 0;;fieldIndex++) {
                String key = null;
                FieldDeserializer fieldDeserializer = null;
                FieldInfo fieldInfo = null;
                Class<?> fieldClass = null;
                JSONField fieldAnnotation = null;
                boolean customDeserializer = false;
                if (fieldIndex < sortedFieldDeserializers.length && notMatchCount < 16) {
                    fieldDeserializer = sortedFieldDeserializers[fieldIndex];
                    fieldInfo = fieldDeserializer.fieldInfo;
                    fieldClass = fieldInfo.fieldClass;
                    fieldAnnotation = fieldInfo.getAnnotation();
                    if (fieldAnnotation != null && fieldDeserializer instanceof DefaultFieldDeserializer) {
                        customDeserializer = ((DefaultFieldDeserializer) fieldDeserializer).customDeserilizer;
                    }
                }

                boolean matchField = false;
                boolean valueParsed = false;
                
                Object fieldValue = null;
                if (fieldDeserializer != null) {
                    char[] name_chars = fieldInfo.name_chars;
                    if (customDeserializer && lexer.matchField(name_chars)) {
                        matchField = true;
                    }
                    else if (fieldClass == int.class || fieldClass == Integer.class) {
                        int intVal = lexer.scanFieldInt(name_chars);
                        if (intVal == 0 && lexer.matchStat == JSONLexer.VALUE_NULL) {
                            fieldValue = null;
                        }
                        else {
                            fieldValue = intVal;
                        }

                        if (lexer.matchStat > 0) {
                                matchField = true;
                                valueParsed = true;
                            }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == long.class || fieldClass == Long.class) {
                        long longVal = lexer.scanFieldLong(name_chars);
                        if (longVal == 0 && lexer.matchStat == JSONLexer.VALUE_NULL) {
                            fieldValue = null;
                        }
                        else {
                            fieldValue = longVal;
                        }

                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == String.class) {
                        fieldValue = lexer.scanFieldString(name_chars);
                        
                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == java.util.Date.class && fieldInfo.format == null) {
                        fieldValue = lexer.scanFieldDate(name_chars);

                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == BigDecimal.class) {
                        fieldValue = lexer.scanFieldDecimal(name_chars);

                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == BigInteger.class) {
                        fieldValue = lexer.scanFieldBigInteger(name_chars);

                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == boolean.class || fieldClass == Boolean.class) {
                        boolean booleanVal = lexer.scanFieldBoolean(name_chars);

                        if (lexer.matchStat == JSONLexer.VALUE_NULL) {
                            fieldValue = null;
                        }
                        else {
                            fieldValue = booleanVal;
                        }
                        
                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == float.class || fieldClass == Float.class) {
                        float floatVal = lexer.scanFieldFloat(name_chars);
                        if (floatVal == 0 && lexer.matchStat == JSONLexer.VALUE_NULL) {
                            fieldValue = null;
                        }
                        else {
                            fieldValue = floatVal;
                        }

                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == double.class || fieldClass == Double.class) {
                        double doubleVal = lexer.scanFieldDouble(name_chars);
                        if (doubleVal == 0 && lexer.matchStat == JSONLexer.VALUE_NULL) {
                            fieldValue = null;
                        }
                        else {
                            fieldValue = doubleVal;
                        }

                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass.isEnum() // 
                            && parser.getConfig().getDeserializer(fieldClass) instanceof EnumDeserializer
                            && (fieldAnnotation == null || fieldAnnotation.deserializeUsing() == Void.class)
                    ) {
                        if (fieldDeserializer instanceof DefaultFieldDeserializer) {
                            ObjectDeserializer fieldValueDeserilizer = ((DefaultFieldDeserializer) fieldDeserializer).fieldValueDeserilizer;
                            fieldValue = this.scanEnum(lexer, name_chars, fieldValueDeserilizer);

                            if (lexer.matchStat > 0) {
                                matchField = true;
                                valueParsed = true;
                            }
                            else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                                notMatchCount++;
                                continue;
                            }
                        }
                    }
                    else if (fieldClass == int[].class) {
                        fieldValue = lexer.scanFieldIntArray(name_chars);

                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == float[].class) {
                        fieldValue = lexer.scanFieldFloatArray(name_chars);

                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (fieldClass == float[][].class) {
                        fieldValue = lexer.scanFieldFloatArray2(name_chars);

                        if (lexer.matchStat > 0) {
                            matchField = true;
                            valueParsed = true;
                        }
                        else if (lexer.matchStat == JSONLexer.NOT_MATCH_NAME) {
                            notMatchCount++;
                            continue;
                        }
                    }
                    else if (lexer.matchField(name_chars)) {
                        matchField = true;
                    }
                    else {
                        continue;
                    }
                }
                
                if (!matchField) {
                    key = lexer.scanSymbol(parser.symbolTable);

                    if (key == null) {
                        token = lexer.token();
                        if (token == JSONToken.RBRACE) {
                            lexer.nextToken(JSONToken.COMMA);
                            break;
                        }
                        if (token == JSONToken.COMMA) {
                            if (lexer.isEnabled(Feature.AllowArbitraryCommas)) {
                                continue;
                            }
                        }
                    }

                    if ("$ref" == key && context != null) {
                        lexer.nextTokenWithColon(JSONToken.LITERAL_STRING);
                        token = lexer.token();
                        if (token != JSONToken.LITERAL_STRING)
                            throw new JSONException("illegal ref, " + JSONToken.name(token));
                        object = resolveObjectReference(parser, object, lexer, context);

                        lexer.nextToken(JSONToken.RBRACE);
                        if (lexer.token() != JSONToken.RBRACE) {
                            throw new JSONException("illegal ref");
                        }
                        lexer.nextToken(JSONToken.COMMA);

                        parser.setContext(context, object, fieldName);

                        return (T) object;
                    }

                    if ((typeKey != null && typeKey.equals(key))
                            || JSON.DEFAULT_TYPE_KEY == key) {
                        lexer.nextTokenWithColon(JSONToken.LITERAL_STRING);
                        if (lexer.token() == JSONToken.LITERAL_STRING) {
                            String typeName = lexer.stringVal();
                            lexer.nextToken(JSONToken.COMMA);

                            if (typeName.equals(beanInfo.typeName) || parser.isEnabled(Feature.IgnoreAutoType)) {
                                if (lexer.token() == JSONToken.RBRACE) {
                                    lexer.nextToken();
                                    break;
                                }
                                continue;
                            }
                            

                            ObjectDeserializer deserializer = getSeeAlso(config, this.beanInfo, typeName);
                            Class<?> userType = null;

                            if (deserializer == null) {
                                Class<?> expectClass = TypeUtils.getClass(type);

                                if (autoTypeCheckHandler != null) {
                                    userType = autoTypeCheckHandler.handler(typeName, expectClass, lexer.getFeatures());
                                }

                                if (userType == null) {
                                    if (typeName.equals("java.util.HashMap") || typeName.equals("java.util.LinkedHashMap")) {
                                        if (lexer.token() == JSONToken.RBRACE) {
                                            lexer.nextToken();
                                            break;
                                        }
                                        continue;
                                    }
                                }

                                if (userType == null) {
                                    userType = config.checkAutoType(typeName, expectClass, lexer.getFeatures());
                                }
                                deserializer = parser.getConfig().getDeserializer(userType);
                            }

                            Object typedObject = deserializer.deserialze(parser, userType, fieldName);
                            if (deserializer instanceof JavaBeanDeserializer) {
                                deserializeJavaBean(typeKey, typeName, deserializer, typedObject);
                            }
                            return (T) typedObject;
                        }
                        throw new JSONException("syntax error");
                    }
                }

                if (object == null && fieldValues == null) {
                    object = createInstance(parser, type);
                    if (object == null) {
                        fieldValues = new HashMap<String, Object>(this.fieldDeserializers.length);
                    }
                    childContext = parser.setContext(context, object, fieldName);
                    if (setFlags == null) {
                        setFlags = new int[(this.fieldDeserializers.length / 32) + 1];
                    }
                }

                if (matchField) {
                    if (!valueParsed) {
                        fieldDeserializer.parseField(parser, object, type, fieldValues);
                    }
                    else {
                        if (object == null) {
                            fieldValues.put(fieldInfo.name, fieldValue);
                        }
                        else if (fieldValue == null) {
                            if (fieldClass != int.class //
                                    && fieldClass != long.class //
                                    && fieldClass != float.class //
                                    && fieldClass != double.class //
                                    && fieldClass != boolean.class //
                            ) {
                                fieldDeserializer.setValue(object, fieldValue);
                            }
                        }
                        else {
                            trimAndSetValue(object, features, fieldDeserializer, fieldInfo, fieldClass, fieldValue);
                        }

                        setFlagAtIndex(setFlags, fieldIndex);

                        if (lexer.matchStat == JSONLexer.END) {
                            break;
                        }
                    }
                }
                else {
                    boolean match = parseField(parser, key, object, type,
                            fieldValues == null ? new HashMap<String, Object>(this.fieldDeserializers.length) : fieldValues, setFlags);

                    if (!match) {
                        if (lexer.token() == JSONToken.RBRACE) {
                            lexer.nextToken();
                            break;
                        }

                        continue;
                    }
                    else if (lexer.token() == JSONToken.COLON) {
                        throw new JSONException("syntax error, unexpect token ':'");
                    }
                }

                if (lexer.token() == JSONToken.COMMA) {
                    continue;
                }

                if (lexer.token() == JSONToken.RBRACE) {
                    lexer.nextToken(JSONToken.COMMA);
                    break;
                }

                if (lexer.token() == JSONToken.IDENTIFIER || lexer.token() == JSONToken.ERROR) {
                    throw new JSONException("syntax error, unexpect token " + JSONToken.name(lexer.token()));
                }
            }

            if (object == null) {
                if (fieldValues == null) {
                    object = createInstance(parser, type);
                    if (childContext == null) {
                        childContext = parser.setContext(context, object, fieldName);
                    }
                    return (T) object;
                }

                String[] paramNames = beanInfo.creatorConstructorParameters;
                Object[] params;
                if (paramNames != null) {
                    params = new Object[paramNames.length];
                    for (int i = 0;i < paramNames.length;i++) {
                        String paramName = paramNames[i];

                        Object param = fieldValues.remove(paramName);
                        if (param == null) {
                            Type fieldType = beanInfo.creatorConstructorParameterTypes[i];
                            FieldInfo fieldInfo = beanInfo.fields[i];
                            param = resetFieldValue(param, fieldType, fieldInfo);
                        }
                        else {
                            if (beanInfo.creatorConstructorParameterTypes != null && i < beanInfo.creatorConstructorParameterTypes.length) {
                                param = castParamToObjectInstance(i, param);
                            }
                        }
                        params[i] = param;
                    }
                }
                else {
                    params = generateParamsFromFieldValues(fieldValues);
                }

                if (beanInfo.creatorConstructor != null) {
                    object = createObjectAndSetFields(object, fieldValues, paramNames, params);
                }
                else if (beanInfo.factoryMethod != null) {
                    try {
                        object = beanInfo.factoryMethod.invoke(null, params);
                    } catch (Exception e) {
                        throw new JSONException("create factory method error, " + beanInfo.factoryMethod.toString(), e);
                    }
                }

                setChildContextObject(object, childContext);
            }
            
            Method buildMethod = beanInfo.buildMethod;
            if (buildMethod == null) {
                return (T) object;
            }
            
            
            Object builtObj;
            try {
                builtObj = buildMethod.invoke(object);
            } catch (Exception e) {
                throw new JSONException("build object error", e);
            }
            
            return (T) builtObj;
        } finally {
            setChildContextObject(object, childContext);
            parser.setContext(context);
        }
    }

    private <T> Object createObjectAndSetFields(Object object, Map<String, Object> fieldValues, String[] paramNames, Object[] params) {
        boolean hasNull = false;
        if (beanInfo.kotlin) {
            hasNull = checkNullInParams(params, hasNull);
        }

        try {
            object = createObjectInstance__(params, hasNull);
        } catch (Exception e) {
            throw new JSONException("create instance error, " + paramNames + ", "
                                    + beanInfo.creatorConstructor.toGenericString(), e);
        }

        if (paramNames != null) {
            setFieldValues(object, fieldValues);
        }
        return object;
    }

    private <T> Object castParamToObjectInstance(int i, Object param) {
        Type paramType = beanInfo.creatorConstructorParameterTypes[i];
        if (paramType instanceof Class) {
            param = castObjectToListInstance(param, paramType);
        }
        return param;
    }

    private <T> Object createObjectInstance__(Object[] params, boolean hasNull)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        Object object;
        if (hasNull && beanInfo.kotlinDefaultConstructor != null) {
            object = createObjectFromParams(params);
        } else {
            object = beanInfo.creatorConstructor.newInstance(params);
        }
        return object;
    }

    private <T> Object castObjectToListInstance(Object param, Type paramType) {
        Class paramClass = (Class) paramType;
        if (!paramClass.isInstance(param)) {
            if (param instanceof List) {
                param = getSingleInstanceOrList(param, paramClass);
            }
        }
        return param;
    }

    private <T> void setFieldValues(Object object, Map<String, Object> fieldValues) {
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            setFieldValue(object, entry);
        }
    }

    private <T> Object createObjectFromParams(Object[] params)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        Object object;
        object = beanInfo.kotlinDefaultConstructor.newInstance(new Object[0]);

        for (int i = 0;i < params.length;i++) {
            setFieldFromParams(object, params, i);
        }
        return object;
    }

    private <T> Object[] generateParamsFromFieldValues(Map<String, Object> fieldValues) {
        Object[] params;
        FieldInfo[] fieldInfoList = beanInfo.fields;
        int size = fieldInfoList.length;
        params = new Object[size];
        for (int i = 0;i < size;++i) {
            setParamFromFieldValues(fieldValues, params, fieldInfoList, i);
        }
        return params;
    }

    private <T> Object getSingleInstanceOrList(Object param, Class paramClass) {
        List list = (List) param;
        if (list.size() == 1) {
            param = getFirstInstanceOfClass(param, paramClass, list);
        }
        return param;
    }

    private <T> void deserializeJavaBean(String typeKey, String typeName, ObjectDeserializer deserializer, Object typedObject) {
        JavaBeanDeserializer javaBeanDeserializer = (JavaBeanDeserializer) deserializer;
        if (typeKey != null) {
            FieldDeserializer typeKeyFieldDeser = javaBeanDeserializer.getFieldDeserializer(typeKey);
            if (typeKeyFieldDeser != null) {
                typeKeyFieldDeser.setValue(typedObject, typeName);
            }
        }
    }

    private <T> Object resolveObjectReference(DefaultJSONParser parser, Object object, JSONLexerBase lexer, ParseContext context) {
        String ref = lexer.stringVal();
        if ("@".equals(ref)) {
            object = context.object;
        } else if ("..".equals(ref)) {
            object = resolveObjectContext(parser, object, context, ref);
        } else if ("$".equals(ref)) {
            object = resolveRootContext(parser, object, context, ref);
        } else {
            if (ref.indexOf('\\') > 0) {
                StringBuilder buf = new StringBuilder();
                unescapeCharacters(ref, buf);
                ref = buf.toString();
            }
            Object refObj = parser.resolveReference(ref);
            if (refObj != null) {
                object = refObj;
            } else {
                parser.addResolveTask(new ResolveTask(context, ref));
                parser.resolveStatus = DefaultJSONParser.NeedToResolve;
            }
        }
        return object;
    }

    private <T> void setFieldValue(Object object, Map.Entry<String, Object> entry) {
        FieldDeserializer fieldDeserializer = getFieldDeserializer(entry.getKey());
        if (fieldDeserializer != null) {
            fieldDeserializer.setValue(object, entry.getValue());
        }
    }

    private <T> void setFieldFromParams(Object object, Object[] params, int i)
            throws IllegalAccessException, InvocationTargetException {
        Object param = params[i];
        if (param != null && beanInfo.fields != null && i < beanInfo.fields.length) {
            FieldInfo fieldInfo = beanInfo.fields[i];
            fieldInfo.set(object, param);
        }
    }

    private <T> boolean checkNullInParams(Object[] params, boolean hasNull) {
        hasNull = checkNullInParams_(params, hasNull);
        return hasNull;
    }

    private <T> boolean checkNullInParams_(Object[] params, boolean hasNull) {
        hasNull = checkNullFields(params, hasNull);
        return hasNull;
    }

    private <T> boolean checkNullFields(Object[] params, boolean hasNull) {
        hasNull = checkNullFieldsInParams(params, hasNull);
        return hasNull;
    }

    private <T> boolean checkNullFieldsInParams(Object[] params, boolean hasNull) {
        for (int i = 0;i < params.length;i++) {
            if (params[i] == null && beanInfo.fields != null && i < beanInfo.fields.length) {
                hasNull = checkStringFieldNullability(hasNull, i);
                break;
            }
        }
        return hasNull;
    }

    private <T> void setParamFromFieldValues(Map<String, Object> fieldValues, Object[] params, FieldInfo[] fieldInfoList, int i) {
        FieldInfo fieldInfo = fieldInfoList[i];
        Object param = fieldValues.get(fieldInfo.name);
        if (param == null) {
            Type fieldType = fieldInfo.fieldType;
            param = resetFieldValue(param, fieldType, fieldInfo);
        }
        params[i] = param;
    }

    private <T> Object getFirstInstanceOfClass(Object param, Class paramClass, List list) {
        Object first = list.get(0);
        if (paramClass.isInstance(first)) {
            param = list.get(0);
        }
        return param;
    }

    private <T> void trimAndSetValue(Object object, int features, FieldDeserializer fieldDeserializer, FieldInfo fieldInfo,
            Class<?> fieldClass, Object fieldValue) {
        if (fieldClass == String.class
                && ((features & Feature.TrimStringFieldValue.mask) != 0
                    || (beanInfo.parserFeatures & Feature.TrimStringFieldValue.mask) != 0
                    || (fieldInfo.parserFeatures & Feature.TrimStringFieldValue.mask) != 0)) {
            fieldValue = ((String) fieldValue).trim();
        }

        fieldDeserializer.setValue(object, fieldValue);
    }

    private <T> void unescapeCharacters(String ref, StringBuilder buf) {
        for (int i = 0;i < ref.length();++i) {
            char ch = ref.charAt(i);
            if (ch == '\\') {
                ch = ref.charAt(++i);
            }
            buf.append(ch);
        }
    }

    private <T> Object resolveRootContext(DefaultJSONParser parser, Object object, ParseContext context, String ref) {
        ParseContext rootContext = context;
        while (rootContext.parent != null) {
            rootContext = rootContext.parent;
        }

        if (rootContext.object != null) {
            object = rootContext.object;
        } else {
            parser.addResolveTask(new ResolveTask(rootContext, ref));
            parser.resolveStatus = DefaultJSONParser.NeedToResolve;
        }
        return object;
    }

    private <T> Object resolveObjectContext(DefaultJSONParser parser, Object object, ParseContext context, String ref) {
        ParseContext parentContext = context.parent;
        if (parentContext.object != null) {
            object = parentContext.object;
        } else {
            parser.addResolveTask(new ResolveTask(parentContext, ref));
            parser.resolveStatus = DefaultJSONParser.NeedToResolve;
        }
        return object;
    }

    private <T> T parseJsonValue(Object fieldName, JSONLexerBase lexer, ParserConfig config, int token) {
        if (lexer.isBlankInput()) {
            return null;
        }

        if (token == JSONToken.LITERAL_STRING) {
            String strVal = lexer.stringVal();
            if (strVal.length() == 0) {
                lexer.nextToken();
                return null;
            }

            if (beanInfo.jsonType != null) {
                for (Class<?> seeAlsoClass : beanInfo.jsonType.seeAlso()) {
                    if (Enum.class.isAssignableFrom(seeAlsoClass)) {
                        try {
                            return (T) Enum.valueOf((Class<Enum>) seeAlsoClass, strVal);
                        } catch (IllegalArgumentException e) {
                            // skip
		                }
                    }
                }
            }
        }

        if (token == JSONToken.LBRACKET && lexer.getCurrent() == ']') {
            lexer.next();
            lexer.nextToken();
            return null;
        }

        if (beanInfo.factoryMethod != null && beanInfo.fields.length == 1) {
            try {
                FieldInfo field = beanInfo.fields[0];
                if (field.fieldClass == Integer.class) {
                    if (token == JSONToken.LITERAL_INT) {
                        int intValue = lexer.intValue();
                        lexer.nextToken();
                        return (T) createFactoryInstance(config, intValue);
                    }
                } else if (field.fieldClass == String.class) {
                    if (token == JSONToken.LITERAL_STRING) {
                        String stringVal = lexer.stringVal();
                        lexer.nextToken();
                        return (T) createFactoryInstance(config, stringVal);
                    }
                }
            } catch (Exception ex) {
                throw new JSONException(ex.getMessage(), ex);
            }
        }
        
        StringBuilder buf = (new StringBuilder()) //
		                                        .append("syntax error, expect {, actual ") //
		                                        .append(lexer.tokenName()) //
		                                        .append(", pos ") //
		                                        .append(lexer.pos());

        if (fieldName instanceof String) {
            buf //
		        .append(", fieldName ") //
		        .append(fieldName);
        }

        buf.append(", fastjson-version ").append(JSON.VERSION);
        
        throw new JSONException(buf.toString());
    }

    private <T> void setChildContextObject(Object object, ParseContext childContext) {
        if (childContext != null) {
            childContext.object = object;
        }
    }

    private <T> Object resetFieldValue(Object param, Type fieldType, FieldInfo fieldInfo) {
        if (fieldType == byte.class) {
            param = (byte) 0;
        } else if (fieldType == short.class) {
            param = (short) 0;
        } else if (fieldType == int.class) {
            param = 0;
        } else if (fieldType == long.class) {
            param = 0L;
        } else if (fieldType == float.class) {
            param = 0F;
        } else if (fieldType == double.class) {
            param = 0D;
        } else if (fieldType == boolean.class) {
            param = Boolean.FALSE;
        } else if (fieldType == String.class
                && (fieldInfo.parserFeatures & Feature.InitStringFieldAsEmpty.mask) != 0) {
            param = "";
        }
        return param;
    }

    protected Enum scanEnum(JSONLexerBase lexer, char[] name_chars, ObjectDeserializer fieldValueDeserilizer) {
        EnumDeserializer enumDeserializer = null;
        if (fieldValueDeserilizer instanceof EnumDeserializer) {
            enumDeserializer = (EnumDeserializer) fieldValueDeserilizer;
        }

        if (enumDeserializer == null) {
            lexer.matchStat = JSONLexer.NOT_MATCH;
            return null;
        }

        long enumNameHashCode = lexer.scanEnumSymbol(name_chars);
        if (lexer.matchStat > 0)
            return getEnumByHashCode(lexer, enumDeserializer, enumNameHashCode);
        return null;
    }

    private Enum getEnumByHashCode(JSONLexerBase lexer, EnumDeserializer enumDeserializer, long enumNameHashCode) {
        Enum e = enumDeserializer.getEnumByHashCode(enumNameHashCode);
        if (e == null) {
            if (enumNameHashCode == fnv1a_64_magic_hashcode) {
                return null;
            }

            if (lexer.isEnabled(Feature.ErrorOnEnumNotMatch)) {
                throw new JSONException("not match enum value, " + enumDeserializer.enumClass);
            }
        }

        return e;
    }

    public boolean parseField(DefaultJSONParser parser, String key, Object object, Type objectType,
                              Map<String, Object> fieldValues) {
        return parseField(parser, key, object, objectType, fieldValues, null);
    }
    
    public boolean parseField(DefaultJSONParser parser, String key, Object object, Type objectType,
                              Map<String, Object> fieldValues, int[] setFlags) {
        JSONLexer lexer = parser.lexer; // xxx

        int disableFieldSmartMatchMask = Feature.DisableFieldSmartMatch.mask;
        int initStringFieldAsEmpty = Feature.InitStringFieldAsEmpty.mask;
        FieldDeserializer fieldDeserializer;
        if (lexer.isEnabled(disableFieldSmartMatchMask) || (this.beanInfo.parserFeatures & disableFieldSmartMatchMask) != 0) {
            fieldDeserializer = getFieldDeserializer(key);
        } else if (lexer.isEnabled(initStringFieldAsEmpty) || (this.beanInfo.parserFeatures & initStringFieldAsEmpty) != 0) {
            fieldDeserializer = smartMatch(key);
        } else {
            fieldDeserializer = smartMatch(key, setFlags);
        }

        int mask = Feature.SupportNonPublicField.mask;
        if (fieldDeserializer == null
                && (lexer.isEnabled(mask)
                    || (this.beanInfo.parserFeatures & mask) != 0)) {
            if (this.extraFieldDeserializers == null) {
                registerClassHierarchyFields();
            }

            Object deserOrField = extraFieldDeserializers.get(key);
            if (deserOrField != null) {
                fieldDeserializer = getFieldDeserializer__(parser, key, deserOrField);
            }
        }

        if (fieldDeserializer == null) {
            return parseAndSetField(parser, key, object, objectType, fieldValues, setFlags, lexer);
        }

        int fieldIndex = -1;
        fieldIndex = findFieldDeserializerIndex(fieldDeserializer, fieldIndex);
        if (fieldIndex != -1 && setFlags != null && key.startsWith("_")) {
            if (isSetFlag(fieldIndex, setFlags)) {
                parser.parseExtra(object, key);
                return false;
            }
        }

        lexer.nextTokenWithColon(fieldDeserializer.getFastMatchToken());

        fieldDeserializer.parseField(parser, object, objectType, fieldValues);

        setFlagAtIndex(setFlags, fieldIndex);

        return true;
    }

    private void registerClassHierarchyFields() {
        ConcurrentHashMap extraFieldDeserializers = new ConcurrentHashMap<String, Object>(1, 0.75f, 1);
        for (Class c = this.clazz;c != null && c != Object.class;c = c.getSuperclass()) {
            registerClassFields(extraFieldDeserializers, c);
        }
        this.extraFieldDeserializers = extraFieldDeserializers;
    }

    private void registerClassFields(ConcurrentHashMap extraFieldDeserializers, Class c) {
        Field[] fields = c.getDeclaredFields();
        for (Field field : fields)
            registerNonFinalField(extraFieldDeserializers, field);
    }

    private boolean parseAndSetField(DefaultJSONParser parser, String key, Object object, Type objectType,
            Map<String, Object> fieldValues, int[] setFlags, JSONLexer lexer) {
        if (!lexer.isEnabled(Feature.IgnoreNotMatch)) {
            throw new JSONException("setter not found, class " + clazz.getName() + ", property " + key);
        }

        int fieldIndex = parseUnwrappedField(parser, key, object, objectType, fieldValues, lexer);

        if (fieldIndex != -1) {
            setFlagAtIndex(setFlags, fieldIndex);
            return true;
        }
        
        parser.parseExtra(object, key);

        return false;
    }

    private FieldDeserializer getFieldDeserializer__(DefaultJSONParser parser, String key, Object deserOrField) {
        FieldDeserializer fieldDeserializer;
        if (deserOrField instanceof FieldDeserializer) {
            fieldDeserializer = (FieldDeserializer) deserOrField;
        } else {
            fieldDeserializer = createFieldDeserializer(parser, key, deserOrField);
        }
        return fieldDeserializer;
    }

    private void registerNonFinalField(ConcurrentHashMap extraFieldDeserializers, Field field) {
        String fieldName = field.getName();
        if (this.getFieldDeserializer(fieldName) != null) {
            return;
        }
        int fieldModifiers = field.getModifiers();
        if ((fieldModifiers & Modifier.FINAL) != 0 || (fieldModifiers & Modifier.STATIC) != 0) {
            return;
        }
        JSONField jsonField = TypeUtils.getAnnotation(field, JSONField.class);
        if (jsonField != null) {
            fieldName = getAlteredFieldName(fieldName, jsonField);
        }
        extraFieldDeserializers.put(fieldName, field);
    }

    private int findFieldDeserializerIndex(FieldDeserializer fieldDeserializer, int fieldIndex) {
        for (int i = 0;i < sortedFieldDeserializers.length;++i) {
            if (sortedFieldDeserializers[i] == fieldDeserializer) {
                fieldIndex = i;
                break;
            }
        }
        return fieldIndex;
    }

    private int parseUnwrappedField(DefaultJSONParser parser, String key, Object object, Type objectType,
            Map<String, Object> fieldValues, JSONLexer lexer) {
        int fieldIndex = -1;
        for (int i = 0;i < this.sortedFieldDeserializers.length;i++) {
            FieldDeserializer fieldDeser = this.sortedFieldDeserializers[i];
        
            FieldInfo fieldInfo = fieldDeser.fieldInfo;
            if (fieldInfo.unwrapped //
                    && fieldDeser instanceof DefaultFieldDeserializer) {
                if (fieldInfo.field != null) {
                    DefaultFieldDeserializer defaultFieldDeserializer = (DefaultFieldDeserializer) fieldDeser;
                    ObjectDeserializer fieldValueDeser = defaultFieldDeserializer.getFieldValueDeserilizer(parser.getConfig());
                    if (fieldValueDeser instanceof JavaBeanDeserializer) {
                        JavaBeanDeserializer javaBeanFieldValueDeserializer = (JavaBeanDeserializer) fieldValueDeser;
                        FieldDeserializer unwrappedFieldDeser = javaBeanFieldValueDeserializer.getFieldDeserializer(key);
                        if (unwrappedFieldDeser != null) {
                            Object fieldObject;
                            try {
                                fieldObject = fieldInfo.field.get(object);
                                if (fieldObject == null) {
                                    fieldObject = ((JavaBeanDeserializer) fieldValueDeser).createInstance(parser, fieldInfo.fieldType);
                                    fieldDeser.setValue(object, fieldObject);
                                }
                                lexer.nextTokenWithColon(defaultFieldDeserializer.getFastMatchToken());
                                unwrappedFieldDeser.parseField(parser, fieldObject, objectType, fieldValues);
                                fieldIndex = i;
                            } catch (Exception e) {
                                throw new JSONException("parse unwrapped field error.", e);
                            }
                        }
                    } else if (fieldValueDeser instanceof MapDeserializer) {
                        fieldIndex = parseAndSetFieldIndex(parser, key, object, lexer, i, fieldDeser, fieldInfo, fieldValueDeser);
                    }
                } else if (fieldInfo.method.getParameterTypes().length == 2) {
                    fieldIndex = parseAndInvokeField(parser, key, object, lexer, i, fieldInfo);
                }
            }
        }
        return fieldIndex;
    }

    private int parseAndInvokeField(DefaultJSONParser parser, String key, Object object, JSONLexer lexer, int i,
            FieldInfo fieldInfo) {
        lexer.nextTokenWithColon();
        Object fieldValue = parser.parse(key);
        try {
            fieldInfo.method.invoke(object, key, fieldValue);
        } catch (Exception e) {
            throw new JSONException("parse unwrapped field error.", e);
        }
        return i;
    }

    private int parseAndSetFieldIndex(DefaultJSONParser parser, String key, Object object, JSONLexer lexer, int i,
            FieldDeserializer fieldDeser, FieldInfo fieldInfo, ObjectDeserializer fieldValueDeser) {
        MapDeserializer javaBeanFieldValueDeserializer = (MapDeserializer) fieldValueDeser;
        try {
            parseAndSetFieldValue(parser, key, object, lexer, fieldDeser, fieldInfo,
                    javaBeanFieldValueDeserializer);
        } catch (Exception e) {
            throw new JSONException("parse unwrapped field error.", e);
        }
        return i;
    }

    private void parseAndSetFieldValue(DefaultJSONParser parser, String key, Object object, JSONLexer lexer,
            FieldDeserializer fieldDeser, FieldInfo fieldInfo, MapDeserializer javaBeanFieldValueDeserializer)
            throws IllegalAccessException {
        Map fieldObject;
        fieldObject = (Map) fieldInfo.field.get(object);
        if (fieldObject == null) {
            fieldObject = javaBeanFieldValueDeserializer.createMap(fieldInfo.fieldType);
            fieldDeser.setValue(object, fieldObject);
        }

        Object fieldValue = parseFieldValue(parser, key, lexer);
        fieldObject.put(key, fieldValue);
    }

    private FieldDeserializer createFieldDeserializer(DefaultJSONParser parser, String key, Object deserOrField) {
        FieldDeserializer fieldDeserializer;
        Field field = (Field) deserOrField;
        field.setAccessible(true);
        FieldInfo fieldInfo = new FieldInfo(key, field.getDeclaringClass(), field.getType(), field.getGenericType(), field, 0, 0, 0);
        fieldDeserializer = new DefaultFieldDeserializer(parser.getConfig(), clazz, fieldInfo);
        extraFieldDeserializers.put(key, fieldDeserializer);
        return fieldDeserializer;
    }

    private String getAlteredFieldName(String fieldName, JSONField jsonField) {
        String alteredFieldName = jsonField.name();
        if (!"".equals(alteredFieldName)) {
            fieldName = alteredFieldName;
        }
        return fieldName;
    }

    private void setFlagAtIndex(int[] setFlags, int fieldIndex) {
        if (setFlags == null) {
            return;
        }
        int flagIndex = fieldIndex / 32;
        int bitIndex = fieldIndex % 32;
        setFlags[flagIndex] |= 1 << bitIndex;
    }

    private Object parseFieldValue(DefaultJSONParser parser, String key, JSONLexer lexer) {
        lexer.nextTokenWithColon();
        return parser.parse(key);
    }

    public FieldDeserializer smartMatch(String key) {
        return smartMatch(key, null);
    }

    public FieldDeserializer smartMatch(String key, int[] setFlags) {
        if (key == null) {
            return null;
        }
        
        FieldDeserializer fieldDeserializer = getFieldDeserializer(key, setFlags);

        if (fieldDeserializer == null) {
            if (this.smartMatchHashArray == null) {
                sortFieldDeserializerHashCodes();
            }

            // smartMatchHashArrayMapping
            long smartKeyHash = TypeUtils.fnv1a_64_lower(key);
            int pos = Arrays.binarySearch(smartMatchHashArray, smartKeyHash);
            if (pos < 0) {
                long smartKeyHash1 = TypeUtils.fnv1a_64_extract(key);
                pos = Arrays.binarySearch(smartMatchHashArray, smartKeyHash1);
            }

            boolean is = false;
            if (pos < 0 && (is = key.startsWith("is"))) {
                smartKeyHash = TypeUtils.fnv1a_64_extract(key.substring(2));
                pos = Arrays.binarySearch(smartMatchHashArray, smartKeyHash);
            }

            if (pos >= 0) {
                fieldDeserializer = getUpdatedFieldDeserializer(setFlags, fieldDeserializer, pos);
            }

            if (fieldDeserializer != null) {
                FieldInfo fieldInfo = fieldDeserializer.fieldInfo;
                if ((fieldInfo.parserFeatures & Feature.DisableFieldSmartMatch.mask) != 0) {
                    return null;
                }

                fieldDeserializer = nullifyNonBooleanDeserializer(fieldDeserializer, is, fieldInfo);
            }
        }


        return fieldDeserializer;
    }

    private FieldDeserializer getUpdatedFieldDeserializer(int[] setFlags, FieldDeserializer fieldDeserializer, int pos) {
        if (smartMatchHashArrayMapping == null) {
            mapFieldIndices_();
        }

        int deserIndex = smartMatchHashArrayMapping[pos];
        if (deserIndex != -1) {
            if (!isSetFlag(deserIndex, setFlags)) {
                fieldDeserializer = sortedFieldDeserializers[deserIndex];
            }
        }
        return fieldDeserializer;
    }

    private void mapFieldIndices_() {
        short[] mapping = new short[smartMatchHashArray.length];
        Arrays.fill(mapping, (short) -1);
        for (int i = 0;i < sortedFieldDeserializers.length;i++) {
            mapFieldIndex_(mapping, i);
        }
        smartMatchHashArrayMapping = mapping;
    }

    private FieldDeserializer nullifyNonBooleanDeserializer(FieldDeserializer fieldDeserializer, boolean is, FieldInfo fieldInfo) {
        Class fieldClass = fieldInfo.fieldClass;
        if (is && (fieldClass != boolean.class && fieldClass != Boolean.class)) {
            fieldDeserializer = null;
        }
        return fieldDeserializer;
    }

    private void mapFieldIndex_(short[] mapping, int i) {
        int p = Arrays.binarySearch(smartMatchHashArray, sortedFieldDeserializers[i].fieldInfo.nameHashCode);
        if (p >= 0) {
            mapping[p] = (short) i;
        }
    }

    private void sortFieldDeserializerHashCodes() {
        long[] hashArray = new long[sortedFieldDeserializers.length];
        for (int i = 0;i < sortedFieldDeserializers.length;i++) {
            hashArray[i] = sortedFieldDeserializers[i].fieldInfo.nameHashCode;
        }
        Arrays.sort(hashArray);
        this.smartMatchHashArray = hashArray;
    }

    public int getFastMatchToken() {
        return JSONToken.LBRACE;
    }

    private Object createFactoryInstance(ParserConfig config, Object value) //
            throws IllegalArgumentException,
            IllegalAccessException,
            InvocationTargetException {
        return beanInfo.factoryMethod.invoke(null, value);
    }
    
    public Object createInstance(Map<String, Object> map, ParserConfig config) //
                                                                               throws IllegalArgumentException,
                                                                               IllegalAccessException,
                                                                               InvocationTargetException {
        Object object = null;
        
        if (beanInfo.creatorConstructor == null && beanInfo.factoryMethod == null) {
            object = createInstance(null, clazz);
            
            for (Map.Entry<String, Object> entry : map.entrySet())
                deserializeField(config, object, entry);

            if (beanInfo.buildMethod != null) {
                Object builtObj;
                try {
                    builtObj = beanInfo.buildMethod.invoke(object);
                } catch (Exception e) {
                    throw new JSONException("build object error", e);
                }

                return builtObj;
            }

            return object;
        }

        
        FieldInfo[] fieldInfoList = beanInfo.fields;
        int size = fieldInfoList.length;
        Object[] params = new Object[size];
        Map<String, Integer> missFields = getMissingFields(map, fieldInfoList, size, params);

        if (missFields != null) {
            assignValuesToMatchingFields(map, params, missFields);
        }

        if (beanInfo.creatorConstructor != null) {
            object = createObjectWithNullCheck(config, object, params);
        } else if (beanInfo.factoryMethod != null) {
            try {
                object = beanInfo.factoryMethod.invoke(null, params);
            } catch (Exception e) {
                throw new JSONException("create factory method error, " + beanInfo.factoryMethod.toString(), e);
            }
        }
        
        return object;
    }

    private Object createObjectWithNullCheck(ParserConfig config, Object object, Object[] params) {
        boolean hasNull = false;
        if (beanInfo.kotlin) {
            hasNull = updateParamsAndCheckNullability(config, params, hasNull);
        }

        if (hasNull && beanInfo.kotlinDefaultConstructor != null) {
            try {
                object = createInstanceFromParams(params);
            } catch (Exception e) {
                throw new JSONException("create instance error, "
                        + beanInfo.creatorConstructor.toGenericString(), e);
            }
        } else {
            try {
                object = beanInfo.creatorConstructor.newInstance(params);
            } catch (Exception e) {
                throw new JSONException("create instance error, "
                        + beanInfo.creatorConstructor.toGenericString(), e);
            }
        }
        return object;
    }

    private boolean updateParamsAndCheckNullability(ParserConfig config, Object[] params, boolean hasNull) {
        for (int i = 0;i < params.length;i++) {
            hasNull = updateParamAndCheckNullability(config, params, hasNull, i);
        }
        return hasNull;
    }

    private void assignValuesToMatchingFields(Map<String, Object> map, Object[] params, Map<String, Integer> missFields) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            assignValueIfFieldMatched(params, missFields, entry);
        }
    }

    private Object createInstanceFromParams(Object[] params)
            throws InstantiationException, IllegalAccessException, InvocationTargetException {
        Object object;
        object = beanInfo.kotlinDefaultConstructor.newInstance();

        for (int i = 0;i < params.length;i++) {
            setFieldFromParams(object, params, i);}
        return object;
    }

    private boolean updateParamAndCheckNullability(ParserConfig config, Object[] params, boolean hasNull, int i) {
        Object param = params[i];
        if (param == null) {
            if (beanInfo.fields != null && i < beanInfo.fields.length) {
                hasNull = checkStringFieldNullability(hasNull, i);
            }
        } else if (param.getClass() != beanInfo.fields[i].fieldClass) {
            params[i] = TypeUtils.cast(param, beanInfo.fields[i].fieldClass, config);
        }
        return hasNull;
    }

    private void assignValueIfFieldMatched(Object[] params, Map<String, Integer> missFields, Map.Entry<String, Object> entry) {
        String key = entry.getKey();
        Object value = entry.getValue();

        FieldDeserializer fieldDeser = smartMatch(key);
        if (fieldDeser != null) {
            assignValueToField(params, missFields, value, fieldDeser);
        }
    }

    private void deserializeField(ParserConfig config, Object object, Map.Entry<String, Object> entry)
            throws IllegalAccessException {
        String key = entry.getKey();
        Object value = entry.getValue();
        FieldDeserializer fieldDeser = smartMatch(key);
        if (fieldDeser == null) {
            return;
        }
        FieldInfo fieldInfo = fieldDeser.fieldInfo;
        Field field = fieldDeser.fieldInfo.field;
        Type paramType = fieldInfo.fieldType;
        Class<?> fieldClass = fieldInfo.fieldClass;
        JSONField fieldAnnation = fieldInfo.getAnnotation();
        if (fieldInfo.declaringClass != null
                && ((!fieldClass.isInstance(value))
                || (fieldAnnation != null && fieldAnnation.deserializeUsing() != Void.class))
        ) {
            parseObjectField(object, value, fieldDeser, paramType);
            return;
        }
        if (field != null && fieldInfo.method == null) {
            Class fieldType = field.getType();
            if (fieldType == boolean.class) {
                if (value == Boolean.FALSE) {
                    field.setBoolean(object, false);
                    return;
                }

                if (value == Boolean.TRUE) {
                    field.setBoolean(object, true);
                    return;
                }
            }
            else if (fieldType == int.class) {
                if (value instanceof Number) {
                    field.setInt(object, ((Number) value).intValue());
                    return;
                }
            }
            else if (fieldType == long.class) {
                if (value instanceof Number) {
                    field.setLong(object, ((Number) value).longValue());
                    return;
                }
            }
            else if (fieldType == float.class) {
                if (value instanceof Number) {
                    field.setFloat(object, ((Number) value).floatValue());
                    return;
                }
                if (value instanceof String) {
                    setFloatFieldFromObject(object, value, field);
                    return;
                }
            }
            else if (fieldType == double.class) {
                if (value instanceof Number) {
                    field.setDouble(object, ((Number) value).doubleValue());
                    return;
                }
                if (value instanceof String) {
                    setFieldDoubleValue(object, value, field);
                    return;
                }
            }
            else if (value != null && paramType == value.getClass()) {
                field.set(object, value);
                return;
            }
        }
        String format = fieldInfo.format;
        if (format != null && paramType == Date.class) {
            value = TypeUtils.castToDate(value, format);
        }
        else if (format != null && (paramType instanceof Class) && (((Class) paramType).getName().equals("java.time.LocalDateTime"))) {
            value = Jdk8DateCodec.castToLocalDateTime(value, format);
        }
        else {
            value = castValueType(config, value, paramType);
        }
        fieldDeser.setValue(object, value);
    }

    private void setFieldDoubleValue(Object object, Object value, Field field) throws IllegalAccessException {
        String strVal = (String) value;
        double doubleValue;
        if (strVal.length() <= 10) {
            doubleValue = TypeUtils.parseDouble(strVal);
        } else {
            doubleValue = Double.parseDouble(strVal);
        }

        field.setDouble(object, doubleValue);
    }

    private void setFloatFieldFromObject(Object object, Object value, Field field) throws IllegalAccessException {
        String strVal = (String) value;
        float floatValue;
        if (strVal.length() <= 10) {
            floatValue = TypeUtils.parseFloat(strVal);
        } else {
            floatValue = Float.parseFloat(strVal);
        }

        field.setFloat(object, floatValue);
    }

    private void parseObjectField(Object object, Object value, FieldDeserializer fieldDeser, Type paramType) {
        String input;
        if (value instanceof String
                && JSONValidator.from(((String) value))
                    .validate())
        {
            input = (String) value;
        } else {
            input = JSON.toJSONString(value);
        }

        DefaultJSONParser parser = new DefaultJSONParser(input);
        fieldDeser.parseField(parser, object, paramType, null);
    }

    private boolean checkStringFieldNullability(boolean hasNull, int i) {
        FieldInfo fieldInfo = beanInfo.fields[i];
        if (fieldInfo.fieldClass == String.class) {
            hasNull = true;
        }
        return hasNull;
    }

    private void assignValueToField(Object[] params, Map<String, Integer> missFields, Object value,
            FieldDeserializer fieldDeser) {
        Integer index = missFields.get(fieldDeser.fieldInfo.name);
        if (index != null) {
            params[index] = value;
        }
    }

    private Map<String, Integer> getMissingFields(Map<String, Object> map, FieldInfo[] fieldInfoList, int size,
            Object[] params) {
        Map<String, Integer> missFields = null;
        for (int i = 0;i < size;++i) {
            missFields = assignDefaultValueToMissingFields(map, fieldInfoList, params, missFields, i);
        }
        return missFields;
    }

    private Map<String, Integer> assignDefaultValueToMissingFields(Map<String, Object> map, FieldInfo[] fieldInfoList, Object[] params,
            Map<String, Integer> missFields, int i) {
        FieldInfo fieldInfo = fieldInfoList[i];
        Object param = map.get(fieldInfo.name);

        if (param == null) {
            Class<?> fieldClass = fieldInfo.fieldClass;
            if (fieldClass == int.class) {
                param = 0;
            } else if (fieldClass == long.class) {
                param = 0L;
            } else if (fieldClass == short.class) {
                param = Short.valueOf((short) 0);
            } else if (fieldClass == byte.class) {
                param = Byte.valueOf((byte) 0);
            } else if (fieldClass == float.class) {
                param = Float.valueOf(0);
            } else if (fieldClass == double.class) {
                param = Double.valueOf(0);
            } else if (fieldClass == char.class) {
                param = '0';
            } else if (fieldClass == boolean.class) {
                param = false;
            }
            if (missFields == null) {
                missFields = new HashMap<String, Integer>();
            }
            missFields.put(fieldInfo.name, i);
        }
        params[i] = param;
        return missFields;
    }

    private Object castValueType(ParserConfig config, Object value, Type paramType) {
        if (paramType instanceof ParameterizedType) {
            value = TypeUtils.cast(value, (ParameterizedType) paramType, config);
        } else {
            value = TypeUtils.cast(value, paramType, config);
        }
        return value;
    }
    
    public Type getFieldType(int ordinal) {
        return sortedFieldDeserializers[ordinal].fieldInfo.fieldType;
    }

    protected Object parseRest(DefaultJSONParser parser, Type type, Object fieldName, Object instance, int features) {
        return parseRest(parser, type, fieldName, instance, features, new int[0]);
    }

    protected Object parseRest(DefaultJSONParser parser
            , Type type
            , Object fieldName
            , Object instance
            , int features
            , int[] setFlags) {
        return deserialze(parser, type, fieldName, instance, features, setFlags);
    }
    
    protected static JavaBeanDeserializer getSeeAlso(ParserConfig config, JavaBeanInfo beanInfo, String typeName) {
        if (beanInfo.jsonType == null) {
            return null;
        }
        
        for (Class<?> seeAlsoClass : beanInfo.jsonType.seeAlso()) {
            ObjectDeserializer seeAlsoDeser = config.getDeserializer(seeAlsoClass);
            if (seeAlsoDeser instanceof JavaBeanDeserializer) {
                JavaBeanDeserializer seeAlsoJavaBeanDeser = (JavaBeanDeserializer) seeAlsoDeser;

                JavaBeanInfo subBeanInfo = seeAlsoJavaBeanDeser.beanInfo;
                if (subBeanInfo.typeName.equals(typeName)) {
                    return seeAlsoJavaBeanDeser;
                }
                
                JavaBeanDeserializer subSeeAlso = getSeeAlso(config, subBeanInfo, typeName);
                if (subSeeAlso != null) {
                    return subSeeAlso;
                }
            }
        }

        return null;
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected static void parseArray(Collection collection, //
                              ObjectDeserializer deser, //
                              DefaultJSONParser parser, //
                              Type type, //
                              Object fieldName) {

        JSONLexerBase lexer = (JSONLexerBase) parser.lexer;
        int token = lexer.token();
        if (token == JSONToken.NULL) {
            lexer.nextToken(JSONToken.COMMA);
            token = lexer.token();
            return;
        }

        if (token != JSONToken.LBRACKET) {
            parser.throwException(token);
        }
        char ch = lexer.getCurrent();
        processBracketToken(lexer, ch);
        
        if (lexer.token() == JSONToken.RBRACKET) {
            lexer.nextToken();
            return;
        }

        int index = 0;
        for (;;) {
            Object item = deser.deserialze(parser, type, index);
            collection.add(item);
            index++;
            if (lexer.token() == JSONToken.COMMA) {
                ch = lexer.getCurrent();
                processBracketToken(lexer, ch);
            } else {
                break;
            }
        }
        
        token = lexer.token();
        if (token != JSONToken.RBRACKET) {
            parser.throwException(token);
        }
        
        ch = lexer.getCurrent();
        if (ch == ',') {
            lexer.next();
            lexer.setToken(JSONToken.COMMA);
        } else {
            lexer.nextToken(JSONToken.COMMA);
        }
//        parser.accept(JSONToken.RBRACKET, JSONToken.COMMA);
    }

    private static void processBracketToken(JSONLexerBase lexer, char ch) {
        if (ch == '[') {
            lexer.next();
            lexer.setToken(JSONToken.LBRACKET);
            return;
        }
        lexer.nextToken(JSONToken.LBRACKET);
    }
    
}
