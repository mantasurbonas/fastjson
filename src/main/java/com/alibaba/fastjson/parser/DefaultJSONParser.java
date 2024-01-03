/*
 * Copyright 1999-2019 Alibaba Group.
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
package com.alibaba.fastjson.parser;

import com.alibaba.fastjson.*;
import com.alibaba.fastjson.parser.deserializer.*;
import com.alibaba.fastjson.serializer.*;
import com.alibaba.fastjson.util.TypeUtils;

import java.io.Closeable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static com.alibaba.fastjson.parser.JSONLexer.EOI;
import static com.alibaba.fastjson.parser.JSONToken.*;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class DefaultJSONParser implements Closeable {

    public final Object                input;
    public final SymbolTable           symbolTable;
    protected ParserConfig             config;

    private final static Set<Class<?>> primitiveClasses = new HashSet<Class<?>>();

    private String                     dateFormatPattern = JSON.DEFFAULT_DATE_FORMAT;
    private DateFormat                 dateFormat;

    public final JSONLexer             lexer;

    protected ParseContext             context;

    private ParseContext[]             contextArray;
    private int                        contextArrayIndex = 0;

    private List<ResolveTask>          resolveTaskList;

    public final static int            NONE = 0;
    public final static int            NeedToResolve = 1;
    public final static int            TypeNameRedirect = 2;

    public int                         resolveStatus = NONE;

    private List<ExtraTypeProvider>    extraTypeProviders = null;
    private List<ExtraProcessor>       extraProcessors = null;
    protected FieldTypeResolver        fieldTypeResolver = null;

    private int                        objectKeyLevel = 0;

    private boolean                    autoTypeEnable;
    private String[]                   autoTypeAccept = null;

    protected transient BeanContext    lastBeanContext;

    static {
        Class<?>[] classes = new Class[]{
                boolean.class,
                byte.class,
                short.class,
                int.class,
                long.class,
                float.class,
                double.class,

                Boolean.class,
                Byte.class,
                Short.class,
                Integer.class,
                Long.class,
                Float.class,
                Double.class,

                BigInteger.class,
                BigDecimal.class,
                String.class
        };

        primitiveClasses.addAll(Arrays.asList(classes));
    }

    public String getDateFomartPattern() {
        return dateFormatPattern;
    }

    public DateFormat getDateFormat() {
        if (dateFormat == null) {
            dateFormat = new SimpleDateFormat(dateFormatPattern, lexer.getLocale());
            dateFormat.setTimeZone(lexer.getTimeZone());
        }
        return dateFormat;
    }

    public void setDateFormat(String dateFormat) {
        this.dateFormatPattern = dateFormat;
        this.dateFormat = null;
    }

    /**
     * @deprecated
     * @see setDateFormat
     */
    public void setDateFomrat(DateFormat dateFormat) {
        this.setDateFormat(dateFormat);
    }

    public void setDateFormat(DateFormat dateFormat) {
        this.dateFormat = dateFormat;
    }

    public DefaultJSONParser(String input) {
        this(input, ParserConfig.getGlobalInstance(), JSON.DEFAULT_PARSER_FEATURE);
    }

    public DefaultJSONParser(String input, ParserConfig config) {
        this(input, new JSONScanner(input, JSON.DEFAULT_PARSER_FEATURE), config);
    }

    public DefaultJSONParser(String input, ParserConfig config, int features) {
        this(input, new JSONScanner(input, features), config);
    }

    public DefaultJSONParser(char[] input, int length, ParserConfig config, int features) {
        this(input, new JSONScanner(input, length, features), config);
    }

    public DefaultJSONParser(JSONLexer lexer) {
        this(lexer, ParserConfig.getGlobalInstance());
    }

    public DefaultJSONParser(JSONLexer lexer, ParserConfig config) {
        this(null, lexer, config);
    }

    public DefaultJSONParser(Object input, JSONLexer lexer, ParserConfig config) {
        this.lexer = lexer;
        this.input = input;
        this.config = config;
        this.symbolTable = config.symbolTable;

        int ch = lexer.getCurrent();
        if (ch == '{') {
            lexer.next();
            ((JSONLexerBase) lexer).token = JSONToken.LBRACE;
        } else if (ch == '[') {
            lexer.next();
            ((JSONLexerBase) lexer).token = JSONToken.LBRACKET;
        } else {
            lexer.nextToken(); // prime the pump
        }
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    public String getInput() {
        if (input instanceof char[]) {
            return new String((char[]) input);
        }
        return input.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public final Object parseObject(Map object, Object fieldName) {
        JSONLexer lexer = this.lexer;

        if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken();
            return null;
        }

        if (lexer.token() == JSONToken.RBRACE) {
            lexer.nextToken();
            return object;
        }

        if (lexer.token() == JSONToken.LITERAL_STRING && lexer.stringVal().length() == 0) {
            lexer.nextToken();
            return object;
        }

        if (lexer.token() != JSONToken.LBRACE && lexer.token() != JSONToken.COMMA) {
            throw new JSONException("syntax error, expect {, actual " + lexer.tokenName() + ", " + lexer.info());
        }

        ParseContext context = this.context;
        try {
            boolean isJsonObjectMap = object instanceof JSONObject;
            Map map = isJsonObjectMap ? ((JSONObject) object).getInnerMap() : object;

            boolean setContextFlag = false;
            for (;;) {
                lexer.skipWhitespace();
                char ch = lexer.getCurrent();
                if (lexer.isEnabled(Feature.AllowArbitraryCommas)) {
                    ch = skipCommas(lexer, ch);
                }

                boolean isObjectKey = false;
                Object key;
                if (ch == '"') {
                    key = lexer.scanSymbol(symbolTable, '"');
                    ch = getNonWhitespaceChar(lexer);
                    if (ch != ':') {
                        throw new JSONException("expect ':' at " + lexer.pos() + ", name " + key);
                    }
                }
                else{
                    if (ch == '}') {
                        resetLexer(lexer);

                        if (!setContextFlag) {
                                if (this.context != null && fieldName == this.context.fieldName && object == this.context.object) {
                                    context = this.context;
                                }
                                else {
                                    ParseContext contextR = setContext(object, fieldName);
                                    if (context == null) {
                                        context = contextR;
                                    }
                                    setContextFlag = true;
                                }
                            }

                        return object;
                    }
                    if (ch == '\'') {
                        if (!lexer.isEnabled(Feature.AllowSingleQuotes)) {
                            throw new JSONException("syntax error");
                        }

                        key = lexer.scanSymbol(symbolTable, '\'');
                        ch = getNonWhitespaceChar(lexer);
                        if (ch != ':') {
                            throw new JSONException("expect ':' at " + lexer.pos());
                        }
                    }
                    else{
                        if (ch == EOI)
                            throw new JSONException("syntax error");
                        if (ch == ',')
                            throw new JSONException("syntax error");
                        if ((ch >= '0' && ch <= '9') || ch == '-') {
                            lexer.resetStringPosition();
                            lexer.scanNumber();
                            try {
                                key = parseJsonKey(lexer, isJsonObjectMap);
                            } catch (NumberFormatException e) {
                                throw new JSONException("parse number key error" + lexer.info());
                            }
                            ch = lexer.getCurrent();
                            if (ch != ':') {
                                throw new JSONException("parse number key error" + lexer.info());
                            }
                        }
                        else if (ch == '{' || ch == '[') {
                            if (objectKeyLevel++ > 512) {
                                throw new JSONException("object key level > 512");
                            }
                            lexer.nextToken();
                            key = parse();
                            isObjectKey = true;
                        }
                        else {
                            if (!lexer.isEnabled(Feature.AllowUnQuotedFieldNames)) {
                                throw new JSONException("syntax error");
                            }

                            key = lexer.scanSymbolUnQuoted(symbolTable);
                            ch = getNonWhitespaceChar(lexer);
                            if (ch != ':') {
                                throw new JSONException("expect ':' at " + lexer.pos() + ", actual " + ch);
                            }
                        }
                    }
                }

                if (!isObjectKey) {
                    lexer.next();
                    lexer.skipWhitespace();
                }

                ch = lexer.getCurrent();

                lexer.resetStringPosition();

                if (key == JSON.DEFAULT_TYPE_KEY
                        && !lexer.isEnabled(Feature.DisableSpecialKeyDetect)) {
                    String typeName = lexer.scanSymbol(symbolTable, '"');

                    if (lexer.isEnabled(Feature.IgnoreAutoType)) {
                        continue;
                    }

                    Class<?> clazz = null;
                    if (object != null
                            && object.getClass().getName().equals(typeName)) {
                        clazz = object.getClass();
                    }
                    else if ("java.util.HashMap".equals(typeName)) {
                        clazz = java.util.HashMap.class;
                    }
                    else if ("java.util.LinkedHashMap".equals(typeName)) {
                        clazz = java.util.LinkedHashMap.class;
                    }
                    else {

                        clazz = checkTypeAutoType(lexer, typeName, clazz);
                    }

                    if (clazz == null) {
                        map.put(JSON.DEFAULT_TYPE_KEY, typeName);
                        continue;
                    }

                    lexer.nextToken(JSONToken.COMMA);
                    if (lexer.token() == JSONToken.RBRACE) {
                        lexer.nextToken(JSONToken.COMMA);
                        try {
                            return deserializeObject(object, typeName, clazz);
                        } catch (Exception e) {
                            throw new JSONException("create instance error", e);
                        }
                    }

                    this.setResolveStatus(TypeNameRedirect);

                    if (this.context != null
                            && fieldName != null
                            && !(fieldName instanceof Integer)
                            && !(this.context.fieldName instanceof Integer)) {
                        this.popContext();
                    }

                    if (object.size() > 0) {
                        return castAndParseObject(object, clazz);
                    }

                    ObjectDeserializer deserializer = config.getDeserializer(clazz);
                    Class deserClass = deserializer.getClass();
                    if (JavaBeanDeserializer.class.isAssignableFrom(deserClass)
                            && deserClass != JavaBeanDeserializer.class
                            && deserClass != ThrowableDeserializer.class) {
                        this.setResolveStatus(NONE);
                    }
                    else if (deserializer instanceof MapDeserializer) {
                        this.setResolveStatus(NONE);
                    }
                    return deserializer.deserialze(this, clazz, fieldName);
                }

                if (key == "$ref"
                        && context != null
                        && (object == null || object.size() == 0)
                        && !lexer.isEnabled(Feature.DisableSpecialKeyDetect)) {
                    lexer.nextToken(JSONToken.LITERAL_STRING);
                    if (lexer.token() == JSONToken.LITERAL_STRING) {
                        String ref = lexer.stringVal();
                        lexer.nextToken(JSONToken.RBRACE);

                        if (lexer.token() == JSONToken.COMMA) {
                            map.put(key, ref);
                            continue;
                        }

                        Object refValue = null;
                        if ("@".equals(ref)) {
                            if (this.context != null) {
                                refValue = getReferenceValue(refValue);
                            }
                        }
                        else if ("..".equals(ref)) {
                            refValue = resolveReferenceValue(context, ref, refValue);
                        }
                        else if ("$".equals(ref)) {
                            refValue = resolveRootReferenceValue(context, ref, refValue);
                        }
                        else {
                            refValue = compileJSONPathRef(context, ref, refValue);
                        }

                        if (lexer.token() != JSONToken.RBRACE) {
                            throw new JSONException("syntax error, " + lexer.info());
                        }
                        lexer.nextToken(JSONToken.COMMA);

                        return refValue;
                    }
                    throw new JSONException("illegal ref, " + JSONToken.name(lexer.token()));
                }

                if (!setContextFlag) {
                    if (this.context != null && fieldName == this.context.fieldName && object == this.context.object) {
                        context = this.context;
                    }
                    else {
                        ParseContext contextR = setContext(object, fieldName);
                        if (context == null) {
                            context = contextR;
                        }
                        setContextFlag = true;
                    }
                }

                if (object.getClass() == JSONObject.class) {
                    if (key == null) {
                        key = "null";
                    }
                }

                Object value;
                if (ch == '"') {
                    value = parseAndStoreValue(lexer, map, key);
                }
                else if (ch >= '0' && ch <= '9' || ch == '-') {
                    value = parseAndStoreNumber(lexer, map, key);
                }
                else if (ch == '[') { // 减少嵌套，兼容android
                    value = parseAndStoreJsonValue(fieldName, lexer, context, map, key);

                    if (lexer.token() == JSONToken.RBRACE) {
                        lexer.nextToken();
                        return object;
                    }
                    if (lexer.token() != JSONToken.COMMA)
                        throw new JSONException("syntax error");
                    continue;
                }
                else if (ch == '{') { // 减少嵌套，兼容 Android
                    boolean parentIsArray = parseMapField(object, fieldName, lexer, map, key);

                    if (lexer.token() == JSONToken.RBRACE) {
                        lexer.nextToken();

                        setContext(context);
                        return object;
                    }
                    if (lexer.token() != JSONToken.COMMA)
                        throw new JSONException("syntax error, " + lexer.tokenName());
                    if (parentIsArray) {
                        this.popContext();
                    }
                    else {
                        this.setContext(context);
                    }
                    continue;
                }
                else {
                    lexer.nextToken();
                    value = parse();

                    map.put(key, value);

                    if (lexer.token() == JSONToken.RBRACE) {
                        lexer.nextToken();
                        return object;
                    }
                    if (lexer.token() != JSONToken.COMMA)
                        throw new JSONException("syntax error, position at " + lexer.pos() + ", name " + key);
                    continue;
                }

                ch = getNonWhitespaceChar(lexer);
                if (ch != ','){
                    if (ch == '}') {
                        resetLexer(lexer);

                        // this.setContext(object, fieldName);
                        this.setContext(value, key);

                        return object;
                    }
                    throw new JSONException("syntax error, position at " + lexer.pos() + ", name " + key);
                }
                lexer.next();
                continue;

            }
        } finally {
            this.setContext(context);
        }

    }

    private Object parseAndStoreValue(JSONLexer lexer, Map map, Object key) {
        Object value;
        lexer.scanString();
        String strValue = lexer.stringVal();
        value = strValue;

        if (lexer.isEnabled(Feature.AllowISO8601DateFormat)) {
            value = parseISO8601Date(value, strValue);
        }

        map.put(key, value);
        return value;
    }

    private boolean parseMapField(Map object, Object fieldName, JSONLexer lexer, Map map, Object key) {
        lexer.nextToken();

        boolean parentIsArray = fieldName != null && fieldName.getClass() == Integer.class;

        Map input;
        if (lexer.isEnabled(Feature.CustomMapDeserializer)) {
            MapDeserializer mapDeserializer = (MapDeserializer) config.getDeserializer(Map.class);


            input = (lexer.getFeatures() & Feature.OrderedField.mask) != 0
                    ? mapDeserializer.createMap(Map.class, lexer.getFeatures())
                    : mapDeserializer.createMap(Map.class);
        } else {
            input = new JSONObject(lexer.isEnabled(Feature.OrderedField));
        }
        ParseContext ctxLocal = null;

        if (!parentIsArray) {
            ctxLocal = setContext(this.context, input, key);
        }

        Object obj = null;
        boolean objParsed = false;
        if (fieldTypeResolver != null) {
            String resolveFieldName = key != null ? key.toString() : null;
            Type fieldType = fieldTypeResolver.resolve(object, resolveFieldName);
            if (fieldType != null) {
                ObjectDeserializer fieldDeser = config.getDeserializer(fieldType);
                obj = fieldDeser.deserialze(this, fieldType, key);
                objParsed = true;
            }
        }
        if (!objParsed) {
            obj = this.parseObject(input, key);
        }

        if (ctxLocal != null && input != obj) {
            ctxLocal.object = object;
        }

        if (key != null) {
            checkMapResolve(object, key.toString());
        }

        map.put(key, obj);

        if (parentIsArray) {
            //setContext(context, obj, key);
		    setContext(obj, key);
        }
        return parentIsArray;
    }

    private Object parseAndStoreJsonValue(Object fieldName, JSONLexer lexer, ParseContext context, Map map, Object key) {
        Object value;
        lexer.nextToken();

        JSONArray list = new JSONArray();

        boolean parentIsArray = fieldName != null && fieldName.getClass() == Integer.class;
//                    if (!parentIsArray) {
//                        this.setContext(context);
//                    }
		if (fieldName == null) {
            this.setContext(context);
        }

        this.parseArray(list, key);

        if (lexer.isEnabled(Feature.UseObjectArray)) {
            value = list.toArray();
        } else {
            value = list;
        }
        map.put(key, value);
        return value;
    }

    private Object parseAndStoreNumber(JSONLexer lexer, Map map, Object key) {
        Object value;
        lexer.scanNumber();
        if (lexer.token() == JSONToken.LITERAL_INT) {
            value = lexer.integerValue();
        } else {
            value = lexer.decimalValue(lexer.isEnabled(Feature.UseBigDecimal));
        }

        map.put(key, value);
        return value;
    }

    private Object parseISO8601Date(Object value, String strValue) {
        JSONScanner iso8601Lexer = new JSONScanner(strValue);
        if (iso8601Lexer.scanISO8601DateIfMatch()) {
            value = iso8601Lexer.getCalendar().getTime();
        }
        iso8601Lexer.close();
        return value;
    }

    private Object compileJSONPathRef(ParseContext context, String ref, Object refValue) {
        JSONPath jsonpath = JSONPath.compile(ref);
        if (jsonpath.isRef()) {
            addResolveTaskAndSetStatus(context, ref);
        } else {
            refValue = new JSONObject()
                    .fluentPut("$ref", ref);
        }
        return refValue;
    }

    private Object resolveRootReferenceValue(ParseContext context, String ref, Object refValue) {
        ParseContext rootContext = context;
        while (rootContext.parent != null) {
            rootContext = rootContext.parent;
        }

        refValue = resolveReferenceValue(rootContext, ref, refValue);
        return refValue;
    }

    private Object deserializeObject(Map object, String typeName, Class<?> clazz)
            throws InstantiationException, IllegalAccessException {
        Object instance = null;
        ObjectDeserializer deserializer = this.config.getDeserializer(clazz);
        if (deserializer instanceof JavaBeanDeserializer) {
            instance = TypeUtils.cast(object, clazz, this.config);
        }

        if (instance == null) {
            instance = createInstance(typeName, clazz);
        }

        return instance;
    }

    private Class<?> checkTypeAutoType(JSONLexer lexer, String typeName, Class<?> clazz) {
        boolean allDigits =  isAllDigits(typeName, true);

        if (!allDigits) {
            clazz = config.checkAutoType(typeName, null, lexer.getFeatures());
        }
        return clazz;
    }

    private Object resolveReferenceValue(ParseContext context, String ref, Object refValue) {
        if (context.object != null) {
            refValue = context.object;
        } else {
            addResolveTaskAndSetStatus(context, ref);
        }
        return refValue;
    }

    private Object getReferenceValue(Object refValue) {
        ParseContext thisContext = this.context;
        Object thisObj = thisContext.object;
        if (thisObj instanceof Object[] || thisObj instanceof Collection<?>) {
            refValue = thisObj;
        } else if (thisContext.parent != null) {
            refValue = thisContext.parent.object;
        }
        return refValue;
    }

    private Object castAndParseObject(Map object, Class<?> clazz) {
        Object newObj = TypeUtils.cast(object, clazz, this.config);
        this.setResolveStatus(NONE);
        this.parseObject(newObj);
        return newObj;
    }

    private Object createInstance(String typeName, Class<?> clazz) throws InstantiationException, IllegalAccessException {
        Object instance;
        if (clazz == Cloneable.class) {
            instance = new HashMap();
        } else if ("java.util.Collections$EmptyMap".equals(typeName)) {
            instance = Collections.emptyMap();
        } else if ("java.util.Collections$UnmodifiableMap".equals(typeName)) {
            instance = Collections.unmodifiableMap(new HashMap());
        } else {
            instance = clazz.newInstance();
        }
        return instance;
    }

    private boolean isAllDigits(String typeName, boolean allDigits) {
        for (int i = 0;i < typeName.length();++i) {
            char c = typeName.charAt(i);
            if (c < '0' || c > '9') {
                allDigits = false;
                break;
            }
        }
        return allDigits;
    }

    private Object parseJsonKey(JSONLexer lexer, boolean isJsonObjectMap) {
        Object key;
        if (lexer.token() == JSONToken.LITERAL_INT) {
            key = lexer.integerValue();
        } else {
            key = lexer.decimalValue(true);
        }
        if (lexer.isEnabled(Feature.NonStringKeyAsString) || isJsonObjectMap) {
            key = key.toString();
        }
        return key;
    }

    private char skipCommas(JSONLexer lexer, char ch) {
        while (ch == ',') {
            lexer.next();
            ch = getNonWhitespaceChar(lexer);
        }
        return ch;
    }

    private void addResolveTaskAndSetStatus(ParseContext context, String ref) {
        addResolveTask(new ResolveTask(context, ref));
        setResolveStatus(DefaultJSONParser.NeedToResolve);
    }

    private void resetLexer(JSONLexer lexer) {
        lexer.next();
        lexer.resetStringPosition();
        lexer.nextToken();
    }

    private char getNonWhitespaceChar(JSONLexer lexer) {
        lexer.skipWhitespace();
        return lexer.getCurrent();
    }

    public ParserConfig getConfig() {
        return config;
    }

    public void setConfig(ParserConfig config) {
        this.config = config;
    }

    // compatible
    @SuppressWarnings("unchecked")
    public <T> T parseObject(Class<T> clazz) {
        return (T) parseObject(clazz, null);
    }

    public <T> T parseObject(Type type) {
        return parseObject(type, null);
    }

    @SuppressWarnings("unchecked")
    public <T> T parseObject(Type type, Object fieldName) {
        int token = lexer.token();
        if (token == JSONToken.NULL) {
            lexer.nextToken();

            return (T) TypeUtils.optionalEmpty(type);
        }

        if (token == JSONToken.LITERAL_STRING) {
            if (type == byte[].class) {
                byte[] bytes = lexer.bytesValue();
                lexer.nextToken();
                return (T) bytes;
            }

            if (type == char[].class) {
                String strVal = lexer.stringVal();
                lexer.nextToken();
                return (T) strVal.toCharArray();
            }
        }

        ObjectDeserializer deserializer = config.getDeserializer(type);

        try {
            return deserializeObject_(type, fieldName, deserializer);
        } catch (JSONException e) {
            throw e;
        } catch (Throwable e) {
            throw new JSONException(e.getMessage(), e);
        }
    }

    private <T> T deserializeObject_(Type type, Object fieldName, ObjectDeserializer deserializer) {
        if (deserializer.getClass() == JavaBeanDeserializer.class) {
            return deserializeJsonToObject(type, fieldName, deserializer);
        }
        return (T) deserializer.deserialze(this, type, fieldName);
    }

    private <T> T deserializeJsonToObject(Type type, Object fieldName, ObjectDeserializer deserializer) {
        if (lexer.token() != JSONToken.LBRACE && lexer.token() != JSONToken.LBRACKET) {
        throw new JSONException("syntax error,expect start with { or [,but actually start with " + lexer.tokenName());
         }
        return (T) ((JavaBeanDeserializer) deserializer).deserialze(this, type, fieldName, 0);
    }

    public <T> List<T> parseArray(Class<T> clazz) {
        List<T> array = new ArrayList<T>();
        parseArray(clazz, array);
        return array;
    }

    public void parseArray(Class<?> clazz, @SuppressWarnings("rawtypes") Collection array) {
        parseArray((Type) clazz, array);
    }

    @SuppressWarnings("rawtypes")
    public void parseArray(Type type, Collection array) {
        parseArray(type, array, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void parseArray(Type type, Collection array, Object fieldName) {
        int token = lexer.token();
        if (token == JSONToken.SET || token == JSONToken.TREE_SET) {
            lexer.nextToken();
            token = lexer.token();
        }

        if (token != JSONToken.LBRACKET) {
            throw new JSONException("field " + fieldName + " expect '[', but " + JSONToken.name(token) + ", " + lexer.info());
        }

        ObjectDeserializer deserializer = null;
        if (int.class == type) {
            deserializer = IntegerCodec.instance;
            lexer.nextToken(JSONToken.LITERAL_INT);
        } else if (String.class == type) {
            deserializer = StringCodec.instance;
            lexer.nextToken(JSONToken.LITERAL_STRING);
        } else {
            deserializer = config.getDeserializer(type);
            lexer.nextToken(deserializer.getFastMatchToken());
        }

        ParseContext context = this.context;
        this.setContext(array, fieldName);
        try {
            parseCollection(type, array, deserializer);
        } finally {
            this.setContext(context);
        }

        lexer.nextToken(JSONToken.COMMA);
    }

    private void parseCollection(Type type, Collection array, ObjectDeserializer deserializer) {
        for (int i = 0;;++i) {
            if (lexer.isEnabled(Feature.AllowArbitraryCommas)) {
                while (lexer.token() == JSONToken.COMMA) {
                    lexer.nextToken();
                    continue;
                }
            }

            if (lexer.token() == JSONToken.RBRACKET) {
                break;
            }

            if (int.class == type) {
                Object val = IntegerCodec.instance.deserialze(this, null, null);
                array.add(val);
            } else if (String.class == type) {
                parseAndAddValue(array);
            } else {
                deserializeAndAddToArray(type, array, deserializer, i);
            }

            if (lexer.token() == JSONToken.COMMA) {
                lexer.nextToken(deserializer.getFastMatchToken());
                continue;
            }
        }
    }

    private void parseAndAddValue(Collection array) {
        String value;
        if (lexer.token() == JSONToken.LITERAL_STRING) {
            value = lexer.stringVal();
            lexer.nextToken(JSONToken.COMMA);
        } else {
            value = parseToString();
        }

        array.add(value);
    }

    private void deserializeAndAddToArray(Type type, Collection array, ObjectDeserializer deserializer, int i) {
        Object val;
        if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken();
            val = null;
        } else {
            val = deserializer.deserialze(this, type, i);
        }
        array.add(val);
        checkListResolve(array);
    }

    private String parseToString() {
        String value;
        Object obj = this.parse();
        if (obj == null) {
            value = null;
        } else {
            value = obj.toString();
        }
        return value;
    }

    public Object[] parseArray(Type[] types) {
        if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken(JSONToken.COMMA);
            return null;
        }

        if (lexer.token() != JSONToken.LBRACKET) {
            throw new JSONException("syntax error : " + lexer.tokenName());
        }

        Object[] list = new Object[types.length];
        if (types.length == 0) {
            lexer.nextToken(JSONToken.RBRACKET);

            checkBracketSyntax();
            return new Object[0];
        }

        lexer.nextToken(JSONToken.LITERAL_INT);

        for (int i = 0;i < types.length;++i) {
            Object value;

            if (lexer.token() == JSONToken.NULL) {
                value = null;
                lexer.nextToken(JSONToken.COMMA);
            } else {
                Type type = types[i];
                if (type == int.class || type == Integer.class) {
                    value = parseIntegerOrCastValue(type);
                } else if (type == String.class) {
                    value = parseStringValue(type);
                } else {
                    value = deserializeValue(types, i, type);
                }
            }
            list[i] = value;

            if (lexer.token() == JSONToken.RBRACKET) {
                break;
            }

            if (lexer.token() != JSONToken.COMMA) {
                throw new JSONException("syntax error :" + JSONToken.name(lexer.token()));
            }

            if (i == types.length - 1) {
                lexer.nextToken(JSONToken.RBRACKET);
            } else {
                lexer.nextToken(JSONToken.LITERAL_INT);
            }
        }

        checkBracketSyntax();

        return list;
    }

    private Object deserializeValue(Type[] types, int i, Type type) {
        Object value;
        boolean isArray = false;
        Class<?> componentType = null;
        if (i == types.length - 1) {
            if (type instanceof Class) {
                Class<?> clazz = (Class<?>) type;
                //如果最后一个type是字节数组，且当前token为字符串类型，不应该当作可变长参数进行处理
		        //而是作为一个整体的Base64字符串进行反序列化
		        if (!((clazz == byte[].class || clazz == char[].class) && lexer.token() == LITERAL_STRING)) {
                    isArray = clazz.isArray();
                    componentType = clazz.getComponentType();
                }
            }
        }

        // support varArgs
		if (isArray && lexer.token() != JSONToken.LBRACKET) {
            value = deserializeToList(type, componentType);
        } else {
            ObjectDeserializer deserializer = config.getDeserializer(type);
            value = deserializer.deserialze(this, type, i);
        }
        return value;
    }

    private Object deserializeToList(Type type, Class<?> componentType) {
        List<Object> varList = new ArrayList<Object>();

        ObjectDeserializer deserializer = config.getDeserializer(componentType);
        int fastMatch = deserializer.getFastMatchToken();

        if (lexer.token() != JSONToken.RBRACKET) {
            deserializeAndAddItems(type, varList, deserializer, fastMatch);
        }

        return TypeUtils.cast(varList, type, config);
    }

    private void deserializeAndAddItems(Type type, List<Object> varList, ObjectDeserializer deserializer, int fastMatch) {
        for (;;) {
            Object item = deserializer.deserialze(this, type, null);
            varList.add(item);

            if (lexer.token() == JSONToken.COMMA) {
                lexer.nextToken(fastMatch);
            }
            else{
                if (lexer.token() != JSONToken.RBRACKET)
                    throw new JSONException("syntax error :" + JSONToken.name(lexer.token()));
                break;
            }
        }
    }

    private Object parseStringValue(Type type) {
        Object value;
        if (lexer.token() == JSONToken.LITERAL_STRING) {
            value = lexer.stringVal();
            lexer.nextToken(JSONToken.COMMA);
        } else {
            value = parseAndCastValue(type);
        }
        return value;
    }

    private Object parseIntegerOrCastValue(Type type) {
        Object value;
        if (lexer.token() == JSONToken.LITERAL_INT) {
            value = Integer.valueOf(lexer.intValue());
            lexer.nextToken(JSONToken.COMMA);
        } else {
            value = parseAndCastValue(type);
        }
        return value;
    }

    private Object parseAndCastValue(Type type) {
        Object value;
        value = this.parse();
        return TypeUtils.cast(value, type, config);
    }

    private void checkBracketSyntax() {
        if (lexer.token() != JSONToken.RBRACKET) {
            throw new JSONException("syntax error");
        }

        lexer.nextToken(JSONToken.COMMA);
    }

    public void parseObject(Object object) {
        Class<?> clazz = object.getClass();
        JavaBeanDeserializer beanDeser = null;
        ObjectDeserializer deserializer = config.getDeserializer(clazz);
        if (deserializer instanceof JavaBeanDeserializer) {
            beanDeser = (JavaBeanDeserializer) deserializer;
        }

        if (lexer.token() != JSONToken.LBRACE && lexer.token() != JSONToken.COMMA) {
            throw new JSONException("syntax error, expect {, actual " + lexer.tokenName());
        }

        for (;;) {
            // lexer.scanSymbol
            String key = lexer.scanSymbol(symbolTable);

            if (key == null) {
                if (lexer.token() == JSONToken.RBRACE) {
                    lexer.nextToken(JSONToken.COMMA);
                    break;
                }
                if (lexer.token() == JSONToken.COMMA) {
                    if (lexer.isEnabled(Feature.AllowArbitraryCommas)) {
                        continue;
                    }
                }
            }

            FieldDeserializer fieldDeser = null;
            if (beanDeser != null) {
                fieldDeser = beanDeser.getFieldDeserializer(key);
            }

            if (fieldDeser == null) {
                checkLexerAndParse(clazz, key); // skip

                if (lexer.token() == JSONToken.RBRACE) {
                    lexer.nextToken();
                    return;
                }

                continue;
            } else {
                deserializeAndSetValue(object, fieldDeser);
            }

            if (lexer.token() == JSONToken.COMMA) {
                continue;
            }

            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken(JSONToken.COMMA);
                return;
            }
        }
    }

    private void deserializeAndSetValue(Object object, FieldDeserializer fieldDeser) {
        Class<?> fieldClass = fieldDeser.fieldInfo.fieldClass;
        Type fieldType = fieldDeser.fieldInfo.fieldType;
        Object fieldValue;
        if (fieldClass == int.class) {
            lexer.nextTokenWithColon(JSONToken.LITERAL_INT);
            fieldValue = IntegerCodec.instance.deserialze(this, fieldType, null);
        } else if (fieldClass == String.class) {
            lexer.nextTokenWithColon(JSONToken.LITERAL_STRING);
            fieldValue = StringCodec.deserialze(this);
        } else if (fieldClass == long.class) {
            lexer.nextTokenWithColon(JSONToken.LITERAL_INT);
            fieldValue = LongCodec.instance.deserialze(this, fieldType, null);
        } else {
            ObjectDeserializer fieldValueDeserializer = config.getDeserializer(fieldClass, fieldType);

            lexer.nextTokenWithColon(fieldValueDeserializer.getFastMatchToken());
            fieldValue = fieldValueDeserializer.deserialze(this, fieldType, null);
        }

        fieldDeser.setValue(object, fieldValue);
    }

    private void checkLexerAndParse(Class<?> clazz, String key) {
        if (!lexer.isEnabled(Feature.IgnoreNotMatch)) {
            throw new JSONException("setter not found, class " + clazz.getName() + ", property " + key);
        }

        lexer.nextTokenWithColon();
        parse();
    }

    public Object parseArrayWithType(Type collectionType) {
        if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken();
            return null;
        }

        Type[] actualTypes = ((ParameterizedType) collectionType).getActualTypeArguments();

        if (actualTypes.length != 1) {
            throw new JSONException("not support type " + collectionType);
        }

        Type actualTypeArgument = actualTypes[0];

        if (actualTypeArgument instanceof Class) {
            List<Object> array = new ArrayList<Object>();
            this.parseArray((Class<?>) actualTypeArgument, array);
            return array;
        }

        if (actualTypeArgument instanceof WildcardType) {
            return parseWildcardOrArray(collectionType, actualTypeArgument);
        }

        if (actualTypeArgument instanceof TypeVariable) {
            Type boundType = getBoundType(actualTypeArgument);
            if (boundType instanceof Class) {
                List<Object> array = new ArrayList<Object>();
                this.parseArray((Class<?>) boundType, array);
                return array;
            }
        }

        if (actualTypeArgument instanceof ParameterizedType) {
            return parseParameterizedType(actualTypeArgument);
        }

        throw new JSONException("TODO : " + collectionType);
    }

    private Object parseWildcardOrArray(Type collectionType, Type actualTypeArgument) {
        WildcardType wildcardType = (WildcardType) actualTypeArgument;

        // assert wildcardType.getUpperBounds().length == 1;
		Type upperBoundType = wildcardType.getUpperBounds()[0];

        // assert upperBoundType instanceof Class;
		if (Object.class.equals(upperBoundType)) {
            return parseWildcardType(collectionType, wildcardType);
        }

        List<Object> array = new ArrayList<Object>();
        this.parseArray((Class<?>) upperBoundType, array);
        return array;

        // throw new JSONException("not support type : " +
		// collectionType);return parse();
	}

    private Object parseParameterizedType(Type actualTypeArgument) {
        ParameterizedType parameterizedType = (ParameterizedType) actualTypeArgument;

        List<Object> array = new ArrayList<Object>();
        this.parseArray(parameterizedType, array);
        return array;
    }

    private Type getBoundType(Type actualTypeArgument) {
        TypeVariable<?> typeVariable = (TypeVariable<?>) actualTypeArgument;
        Type[] bounds = typeVariable.getBounds();

        if (bounds.length != 1) {
            throw new JSONException("not support : " + typeVariable);
        }

        return bounds[0];
    }

    private Object parseWildcardType(Type collectionType, WildcardType wildcardType) {
        if (wildcardType.getLowerBounds().length != 0) {
            throw new JSONException("not support type : " + collectionType);
        }
        // Collection<?>
		return parse();
    }

    public void acceptType(String typeName) {
        JSONLexer lexer = this.lexer;

        lexer.nextTokenWithColon();

        if (lexer.token() != JSONToken.LITERAL_STRING) {
            throw new JSONException("type not match error");
        }

        if (!typeName.equals(lexer.stringVal()))
            throw new JSONException("type not match error");
        parseNextToken(lexer);
    }

    private void parseNextToken(JSONLexer lexer) {
        lexer.nextToken();
        if (lexer.token() == JSONToken.COMMA) {
            lexer.nextToken();
        }
    }

    public int getResolveStatus() {
        return resolveStatus;
    }

    public void setResolveStatus(int resolveStatus) {
        this.resolveStatus = resolveStatus;
    }

    public Object getObject(String path) {
        for (int i = 0;i < contextArrayIndex;++i) {
            if (path.equals(contextArray[i].toString())) {
                return contextArray[i].object;
            }
        }

        return null;
    }

    @SuppressWarnings("rawtypes")
    public void checkListResolve(Collection array) {
        if (resolveStatus == NeedToResolve) {
            setResolveTaskDeserializerBasedOnType(array);
        }
    }

    private void setResolveTaskDeserializerBasedOnType(Collection array) {
        if (array instanceof List) {
            setLastResolveTaskDeserializer(array);
            return;
        }
        setResolveTaskDeserializer(array);
    }

    private void setResolveTaskDeserializer(Collection array) {
        ResolveTask task = getLastResolveTask();
        task.fieldDeserializer = new ResolveFieldDeserializer(array);
        task.ownerContext = context;
        setResolveStatus(DefaultJSONParser.NONE);
    }

    private void setLastResolveTaskDeserializer(Collection array) {
        int index = array.size() - 1;
        List list = (List) array;
        ResolveTask task = getLastResolveTask();
        task.fieldDeserializer = new ResolveFieldDeserializer(this, list, index);
        task.ownerContext = context;
        setResolveStatus(DefaultJSONParser.NONE);
    }

    @SuppressWarnings("rawtypes")
    public void checkMapResolve(Map object, Object fieldName) {
        if (resolveStatus == NeedToResolve) {
            setFieldDeserializer(object, fieldName);
        }
    }

    private void setFieldDeserializer(Map object, Object fieldName) {
        ResolveFieldDeserializer fieldResolver = new ResolveFieldDeserializer(object, fieldName);
        ResolveTask task = getLastResolveTask();
        task.fieldDeserializer = fieldResolver;
        task.ownerContext = context;
        setResolveStatus(DefaultJSONParser.NONE);
    }

    @SuppressWarnings("rawtypes")
    public Object parseObject(Map object) {
        return parseObject(object, null);
    }

    public JSONObject parseObject() {
        JSONObject object = new JSONObject(lexer.isEnabled(Feature.OrderedField));
        Object parsedObject = parseObject(object);

        if (parsedObject instanceof JSONObject) {
            return (JSONObject) parsedObject;
        }

        if (parsedObject == null) {
            return null;
        }

        return new JSONObject((Map) parsedObject);
    }

    @SuppressWarnings("rawtypes")
    public final void parseArray(Collection array) {
        parseArray(array, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public final void parseArray(Collection array, Object fieldName) {
        JSONLexer lexer = this.lexer;

        if (lexer.token() == JSONToken.SET || lexer.token() == JSONToken.TREE_SET) {
            lexer.nextToken();
        }

        if (lexer.token() != JSONToken.LBRACKET) {
            throw new JSONException("syntax error, expect [, actual " + JSONToken.name(lexer.token()) + ", pos "
                                    + lexer.pos() + ", fieldName " + fieldName);
        }

        lexer.nextToken(JSONToken.LITERAL_STRING);

        if (this.context != null && this.context.level > 512) {
            throw new JSONException("array level > 512");
        }

        ParseContext context = this.context;
        this.setContext(array, fieldName);
        try {
            for (int i = 0;;++i) {
                if (lexer.isEnabled(Feature.AllowArbitraryCommas)) {
                    while (lexer.token() == JSONToken.COMMA) {
                        lexer.nextToken();
                        continue;
                    }
                }

                Object value;
                switch (lexer.token()) {
                    case LITERAL_INT:
                        value = lexer.integerValue();
                        lexer.nextToken(JSONToken.COMMA);
                        break;
                    case LITERAL_FLOAT:
                    value = parseDecimalValue(lexer);
                        break;
                    case LITERAL_STRING:
                    value = parseDateOrString(lexer);

                        break;
                    case TRUE:
                        value = Boolean.TRUE;
                        lexer.nextToken(JSONToken.COMMA);
                        break;
                    case FALSE:
                        value = Boolean.FALSE;
                        lexer.nextToken(JSONToken.COMMA);
                        break;
                    case LBRACE:
                        JSONObject object = new JSONObject(lexer.isEnabled(Feature.OrderedField));
                        value = parseObject(object, i);
                        break;
                    case LBRACKET:
                    value = parseJSONArray(lexer, i);
                        break;
                    case NULL:
                    value = parseNextStringToken(lexer);
                        break;
                    case UNDEFINED:
                    value = parseNextStringToken(lexer);
                        break;
                    case RBRACKET:
                        lexer.nextToken(JSONToken.COMMA);
                        return;
                    case EOF:
                        throw new JSONException("unclosed jsonArray");
                    default:
                        value = parse();
                        break;
                }

                array.add(value);
                checkListResolve(array);

                if (lexer.token() == JSONToken.COMMA) {
                    lexer.nextToken(JSONToken.LITERAL_STRING);
                    continue;
                }
            }
        } catch (ClassCastException e) {
            throw new JSONException("unkown error", e);
        } finally {
            this.setContext(context);
        }
    }

    private Object parseDateOrString(JSONLexer lexer) {
        Object value;
        String stringLiteral = lexer.stringVal();
        lexer.nextToken(JSONToken.COMMA);

        if (lexer.isEnabled(Feature.AllowISO8601DateFormat)) {
            value = parseISO8601Date_(stringLiteral);
        } else {
            value = stringLiteral;
        }
        return value;
    }

    private Object parseJSONArray(JSONLexer lexer, int i) {
        Object value;
        Collection items = new JSONArray();
        parseArray(items, i);
        if (lexer.isEnabled(Feature.UseObjectArray)) {
            value = items.toArray();
        } else {
            value = items;
        }
        return value;
    }

    private Object parseISO8601Date_(String stringLiteral) {
        Object value;
        JSONScanner iso8601Lexer = new JSONScanner(stringLiteral);
        if (iso8601Lexer.scanISO8601DateIfMatch()) {
            value = iso8601Lexer.getCalendar().getTime();
        } else {
            value = stringLiteral;
        }
        iso8601Lexer.close();
        return value;
    }

    private Object parseDecimalValue(JSONLexer lexer) {
        Object value;
        if (lexer.isEnabled(Feature.UseBigDecimal)) {
            value = lexer.decimalValue(true);
        } else {
            value = lexer.decimalValue(false);
        }
        lexer.nextToken(JSONToken.COMMA);
        return value;
    }

    private Object parseNextStringToken(JSONLexer lexer) {
        Object value;
        value = null;
        lexer.nextToken(JSONToken.LITERAL_STRING);
        return value;
    }

    public ParseContext getContext() {
        return context;
    }

    public ParseContext getOwnerContext() {
        return context.parent;
    }

    public List<ResolveTask> getResolveTaskList() {
        if (resolveTaskList == null) {
            resolveTaskList = new ArrayList<ResolveTask>(2);
        }
        return resolveTaskList;
    }

    public void addResolveTask(ResolveTask task) {
        if (resolveTaskList == null) {
            resolveTaskList = new ArrayList<ResolveTask>(2);
        }
        resolveTaskList.add(task);
    }

    public ResolveTask getLastResolveTask() {
        return resolveTaskList.get(resolveTaskList.size() - 1);
    }

    public List<ExtraProcessor> getExtraProcessors() {
        if (extraProcessors == null) {
            extraProcessors = new ArrayList<ExtraProcessor>(2);
        }
        return extraProcessors;
    }

    public List<ExtraTypeProvider> getExtraTypeProviders() {
        if (extraTypeProviders == null) {
            extraTypeProviders = new ArrayList<ExtraTypeProvider>(2);
        }
        return extraTypeProviders;
    }

    public FieldTypeResolver getFieldTypeResolver() {
        return fieldTypeResolver;
    }

    public void setFieldTypeResolver(FieldTypeResolver fieldTypeResolver) {
        this.fieldTypeResolver = fieldTypeResolver;
    }

    public void setContext(ParseContext context) {
        if (lexer.isEnabled(Feature.DisableCircularReferenceDetect)) {
            return;
        }
        this.context = context;
    }

    public void popContext() {
        if (lexer.isEnabled(Feature.DisableCircularReferenceDetect)) {
            return;
        }

        this.context = this.context.parent;

        if (contextArrayIndex <= 0) {
            return;
        }

        contextArrayIndex--;
        contextArray[contextArrayIndex] = null;
    }

    public ParseContext setContext(Object object, Object fieldName) {
        if (lexer.isEnabled(Feature.DisableCircularReferenceDetect)) {
            return null;
        }

        return setContext(this.context, object, fieldName);
    }

    public ParseContext setContext(ParseContext parent, Object object, Object fieldName) {
        if (lexer.isEnabled(Feature.DisableCircularReferenceDetect)) {
            return null;
        }

        this.context = new ParseContext(parent, object, fieldName);
        addContext(this.context);

        return this.context;
    }

    private void addContext(ParseContext context) {
        int i = contextArrayIndex++;
        if (contextArray == null) {
            contextArray = new ParseContext[8];
        } else if (i >= contextArray.length) {
            expandContextArray();
        }
        contextArray[i] = context;
    }

    private void expandContextArray() {
        int newLen = (contextArray.length * 3) / 2;
        ParseContext[] newArray = new ParseContext[newLen];
        System.arraycopy(contextArray, 0, newArray, 0, contextArray.length);
        contextArray = newArray;
    }

    public Object parse() {
        return parse(null);
    }

    public Object parseKey() {
        if (lexer.token() == JSONToken.IDENTIFIER) {
            String value = lexer.stringVal();
            lexer.nextToken(JSONToken.COMMA);
            return value;
        }
        return parse(null);
    }

    public Object parse(Object fieldName) {
        JSONLexer lexer = this.lexer;
        switch (lexer.token()) {
            case SET:
                lexer.nextToken();
                HashSet<Object> set = new HashSet<Object>();
                parseArray(set, fieldName);
                return set;
            case TREE_SET:
                lexer.nextToken();
                TreeSet<Object> treeSet = new TreeSet<Object>();
                parseArray(treeSet, fieldName);
                return treeSet;
            case LBRACKET:
                Collection array = isEnabled(Feature.UseNativeJavaObject)
                        ? new ArrayList()
                        : new JSONArray();
                parseArray(array, fieldName);
                if (lexer.isEnabled(Feature.UseObjectArray)) {
                    return array.toArray();
                }
                return array;
            case LBRACE:
                Map object = isEnabled(Feature.UseNativeJavaObject)
                    ? lexer.isEnabled(Feature.OrderedField)
                    ? new HashMap()
                    : new LinkedHashMap()
                    : new JSONObject(lexer.isEnabled(Feature.OrderedField));
                return parseObject(object, fieldName);
//            case LBRACE: {
//                Map<String, Object> map = lexer.isEnabled(Feature.OrderedField)
//                        ? new LinkedHashMap<String, Object>()
//                        : new HashMap<String, Object>();
//                Object obj = parseObject(map, fieldName);
//                if (obj != map) {
//                    return obj;
//                }
//                return new JSONObject(map);
//            }
            case LITERAL_INT:
                Number intValue = lexer.integerValue();
                lexer.nextToken();
                return intValue;
            case LITERAL_FLOAT:
                Object value = lexer.decimalValue(lexer.isEnabled(Feature.UseBigDecimal));
                lexer.nextToken();
                return value;
            case LITERAL_STRING:
                String stringLiteral = lexer.stringVal();
                lexer.nextToken(JSONToken.COMMA);

                if (lexer.isEnabled(Feature.AllowISO8601DateFormat)) {
                    JSONScanner iso8601Lexer = new JSONScanner(stringLiteral);
                    try {
                        if (iso8601Lexer.scanISO8601DateIfMatch()) {
                            return iso8601Lexer.getCalendar().getTime();
                        }
                    } finally {
                        iso8601Lexer.close();
                    }
                }

                return stringLiteral;
            case NULL:
                lexer.nextToken();
                return null;
            case UNDEFINED:
                lexer.nextToken();
                return null;
            case TRUE:
                lexer.nextToken();
                return Boolean.TRUE;
            case FALSE:
                lexer.nextToken();
                return Boolean.FALSE;
            case NEW:
            long time = parseIdentifierTime(lexer);

                return new Date(time);
            case EOF:
                if (lexer.isBlankInput()) {
                    return null;
                }
                throw new JSONException("unterminated json string, " + lexer.info());
            case HEX:
                byte[] bytes = lexer.bytesValue();
                lexer.nextToken();
                return bytes;
            case IDENTIFIER:
                String identifier = lexer.stringVal();
                if ("NaN".equals(identifier)) {
                    lexer.nextToken();
                    return null;
                }
                throw new JSONException("syntax error, " + lexer.info());
            case ERROR:
            default:
                throw new JSONException("syntax error, " + lexer.info());
        }
    }

    private long parseIdentifierTime(JSONLexer lexer) {
        lexer.nextToken(JSONToken.IDENTIFIER);

        if (lexer.token() != JSONToken.IDENTIFIER) {
            throw new JSONException("syntax error");
        }
        lexer.nextToken(JSONToken.LPAREN);

        accept(JSONToken.LPAREN);
        long time = lexer.integerValue().longValue();
        accept(JSONToken.LITERAL_INT);

        accept(JSONToken.RPAREN);
        return time;
    }

    public void config(Feature feature, boolean state) {
        this.lexer.config(feature, state);
    }

    public boolean isEnabled(Feature feature) {
        return lexer.isEnabled(feature);
    }

    public JSONLexer getLexer() {
        return lexer;
    }

    public final void accept(int token) {
        JSONLexer lexer = this.lexer;
        if (lexer.token() != token)
            throw new JSONException("syntax error, expect " + JSONToken.name(token) + ", actual "
                    + JSONToken.name(lexer.token()));
        lexer.nextToken();
    }

    public final void accept(int token, int nextExpectToken) {
        JSONLexer lexer = this.lexer;
        if (lexer.token() == token) {
            lexer.nextToken(nextExpectToken);
        } else {
            throwException(token);
        }
    }

    public void throwException(int token) {
        throw new JSONException("syntax error, expect " + JSONToken.name(token) + ", actual "
                                + JSONToken.name(lexer.token()));
    }

    public void close() {
        JSONLexer lexer = this.lexer;

        try {
            validateJsonClosure(lexer);
        } finally {
            lexer.close();
        }
    }

    private void validateJsonClosure(JSONLexer lexer) {
        if (lexer.isEnabled(Feature.AutoCloseSource)) {
            if (lexer.token() != JSONToken.EOF) {
                throw new JSONException("not close json text, token : " + JSONToken.name(lexer.token()));
            }
        }
    }

    public Object resolveReference(String ref) {
        if (contextArray == null) {
            return null;
        }
        for (int i = 0;i < contextArray.length && i < contextArrayIndex;i++) {
            ParseContext context = contextArray[i];
            if (context.toString().equals(ref)) {
                return context.object;
            }
        }
        return null;
    }

    public void handleResovleTask(Object value) {
        if (resolveTaskList == null) {
            return;
        }

        for (int i = 0, size = resolveTaskList.size();i < size;++i) {
            resolveReferenceValue_(value, i);
        }
    }

    private void resolveReferenceValue_(Object value, int i) {
        ResolveTask task = resolveTaskList.get(i);
        String ref = task.referenceValue;

        Object object = null;
        if (task.ownerContext != null) {
            object = task.ownerContext.object;
        }

        Object refValue;

        if (ref.startsWith("$")) {
            refValue = getRefValueFromObject(value, ref);
        } else {
            refValue = task.context.object;
        }

        FieldDeserializer fieldDeser = task.fieldDeserializer;

        if (fieldDeser != null) {
            if (refValue != null
                    && refValue.getClass() == JSONObject.class
                    && fieldDeser.fieldInfo != null
                    && !Map.class.isAssignableFrom(fieldDeser.fieldInfo.fieldClass)) {
                refValue = evaluateRefValue(ref, refValue);
            }

            // workaround for bug
		    if (fieldDeser.getOwnerClass() != null
                    && (!fieldDeser.getOwnerClass().isInstance(object))
                    && task.ownerContext.parent != null
            ) {
                object = resolveObjectInstance(task, object, fieldDeser);
            }

            fieldDeser.setValue(object, refValue);
        }
    }

    private Object getRefValueFromObject(Object value, String ref) {
        Object refValue;
        refValue = getObject(ref);
        if (refValue == null) {
            try {
                refValue = evaluateJsonPathRefValue(value, ref, refValue);
            } catch (JSONPathException ex) {
                // skip
		    }
        }
        return refValue;
    }

    private Object resolveObjectInstance(ResolveTask task, Object object, FieldDeserializer fieldDeser) {
        object = findParentInstance(task, object, fieldDeser);
        return object;
    }

    private Object findParentInstance(ResolveTask task, Object object, FieldDeserializer fieldDeser) {
        object = resolveObjectInstance_(task, object, fieldDeser);
        return object;
    }

    private Object resolveObjectInstance_(ResolveTask task, Object object, FieldDeserializer fieldDeser) {
        for (ParseContext ctx = task.ownerContext.parent;ctx != null;ctx = ctx.parent) {
            if (fieldDeser.getOwnerClass().isInstance(ctx.object)) {
                object = ctx.object;
                break;
            }
        }
        return object;
    }

    private Object evaluateRefValue(String ref, Object refValue) {
        Object root = this.contextArray[0].object;
        JSONPath jsonpath = JSONPath.compile(ref);
        if (jsonpath.isRef()) {
            refValue = jsonpath.eval(root);
        }
        return refValue;
    }

    private Object evaluateJsonPathRefValue(Object value, String ref, Object refValue) {
        JSONPath jsonpath = new JSONPath(ref, SerializeConfig.getGlobalInstance(), config, true);
        if (jsonpath.isRef()) {
            refValue = jsonpath.eval(value);
        }
        return refValue;
    }

    public static class ResolveTask {

        public final ParseContext context;
        public final String       referenceValue;
        public FieldDeserializer  fieldDeserializer;
        public ParseContext       ownerContext;

        public ResolveTask(ParseContext context, String referenceValue) {
            this.context = context;
            this.referenceValue = referenceValue;
        }
    }

    public void parseExtra(Object object, String key) {
        JSONLexer lexer = this.lexer; // xxx
        lexer.nextTokenWithColon();
        Type type = null;

        if (extraTypeProviders != null) {
            for (ExtraTypeProvider extraProvider : extraTypeProviders) {
                type = extraProvider.getExtraType(object, key);
            }
        }
        Object value = type == null //
            ? parse() // skip
            : parseObject(type);

        if (object instanceof ExtraProcessable) {
            ExtraProcessable extraProcessable = (ExtraProcessable) object;
            extraProcessable.processExtra(key, value);
            return;
        }

        if (extraProcessors != null) {
            for (ExtraProcessor process : extraProcessors) {
                process.processExtra(object, key, value);
            }
        }

        if (resolveStatus == NeedToResolve) {
            resolveStatus = NONE;
        }
    }

    public Object parse(PropertyProcessable object, Object fieldName) {
        if (lexer.token() != JSONToken.LBRACE) {
            String msg = "syntax error, expect {, actual " + lexer.tokenName();
            if (fieldName instanceof String) {
                msg += ", fieldName ";
                msg += fieldName;
            }
            msg += ", ";
            msg += lexer.info();

            JSONArray array = new JSONArray();
            parseArray(array, fieldName);

            if (array.size() == 1) {
                Object first = array.get(0);
                if (first instanceof JSONObject) {
                    return (JSONObject) first;
                }
            }

            throw new JSONException(msg);
        }

        ParseContext context = this.context;
        try {
            for (int i = 0;;++i) {
                lexer.skipWhitespace();
                char ch = lexer.getCurrent();
                if (lexer.isEnabled(Feature.AllowArbitraryCommas)) {
                    while (ch == ',') {
                        lexer.next();
                        lexer.skipWhitespace();
                        ch = lexer.getCurrent();
                    }
                }

                String key;
                if (ch == '"') {
                    key = lexer.scanSymbol(symbolTable, '"');
                    lexer.skipWhitespace();
                    ch = lexer.getCurrent();
                    if (ch != ':') {
                        throw new JSONException("expect ':' at " + lexer.pos());
                    }
                }
                else{
                    if (ch == '}') {
                        lexer.next();
                        lexer.resetStringPosition();
                        lexer.nextToken(JSONToken.COMMA);
                        return object;
                    }
                    if (ch == '\'') {
                        if (!lexer.isEnabled(Feature.AllowSingleQuotes)) {
                            throw new JSONException("syntax error");
                        }

                        key = lexer.scanSymbol(symbolTable, '\'');
                        lexer.skipWhitespace();
                        ch = lexer.getCurrent();
                        if (ch != ':') {
                            throw new JSONException("expect ':' at " + lexer.pos());
                        }
                    }
                    else {
                        if (!lexer.isEnabled(Feature.AllowUnQuotedFieldNames)) {
                            throw new JSONException("syntax error");
                        }

                        key = lexer.scanSymbolUnQuoted(symbolTable);
                        lexer.skipWhitespace();
                        ch = lexer.getCurrent();
                        if (ch != ':') {
                            throw new JSONException("expect ':' at " + lexer.pos() + ", actual " + ch);
                        }
                    }
                }

                lexer.next();
                lexer.skipWhitespace();
                ch = lexer.getCurrent();

                lexer.resetStringPosition();

                if (key == JSON.DEFAULT_TYPE_KEY && !lexer.isEnabled(Feature.DisableSpecialKeyDetect)) {
                    String typeName = lexer.scanSymbol(symbolTable, '"');

                    Class<?> clazz = config.checkAutoType(typeName, null, lexer.getFeatures());

                    if (Map.class.isAssignableFrom(clazz)) {
                        lexer.nextToken(JSONToken.COMMA);
                        if (lexer.token() == JSONToken.RBRACE) {
                            lexer.nextToken(JSONToken.COMMA);
                            return object;
                        }
                        continue;
                    }

                    ObjectDeserializer deserializer = config.getDeserializer(clazz);

                    lexer.nextToken(JSONToken.COMMA);

                    setResolveStatus(DefaultJSONParser.TypeNameRedirect);

                    if (context != null && !(fieldName instanceof Integer)) {
                        popContext();
                    }

                    return (Map) deserializer.deserialze(this, clazz, fieldName);
                }

                Object value;
                lexer.nextToken();

                if (i != 0) {
                    setContext(context);
                }

                Type valueType = object.getType(key);

                if (lexer.token() == JSONToken.NULL) {
                    value = null;
                    lexer.nextToken();
                }
                else {
                    value = parseObject(valueType, key);
                }

                object.apply(key, value);

                setContext(context, value, key);
                setContext(context);

                int tok = lexer.token();
                if (tok == JSONToken.EOF || tok == JSONToken.RBRACKET) {
                    return object;
                }

                if (tok == JSONToken.RBRACE) {
                    lexer.nextToken();
                    return object;
                }
            }
        } finally {
            setContext(context);
        }
    }

}
