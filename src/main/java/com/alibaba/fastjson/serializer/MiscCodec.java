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

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

import com.alibaba.fastjson.*;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.alibaba.fastjson.util.IOUtils;
import com.alibaba.fastjson.util.TypeUtils;
import org.w3c.dom.Node;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class MiscCodec implements ObjectSerializer, ObjectDeserializer {
    private static      boolean   FILE_RELATIVE_PATH_SUPPORT = false;
    public final static MiscCodec instance = new MiscCodec();
    private static      Method    method_paths_get;
    private static      boolean   method_paths_get_error = false;

    static {
        FILE_RELATIVE_PATH_SUPPORT = "true".equals(IOUtils.getStringProperty("fastjson.deserializer.fileRelativePathSupport"));
    }

    public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType,
                         int features) throws IOException {
        SerializeWriter out = serializer.out;

        if (object == null) {
            out.writeNull();
            return;
        }

        Class<?> objClass = object.getClass();

        String strVal;
        if (objClass == SimpleDateFormat.class) {
            String pattern = ((SimpleDateFormat) object).toPattern();

            if (out.isEnabled(SerializerFeature.WriteClassName)) {
                if (object.getClass() != fieldType) {
                    writeObjectWithPattern(serializer, object, out, pattern);
                    return;
                }
            }

            strVal = pattern;
        }
        else if (objClass == Class.class) {
            Class<?> clazz = (Class<?>) object;
            strVal = clazz.getName();
        }
        else{
            if (objClass == InetSocketAddress.class) {
                writeInetSocketAddress(serializer, object, out);
                return;
            }
            if (object instanceof File) {
                strVal = ((File) object).getPath();
            }
            else if (object instanceof InetAddress) {
                strVal = ((InetAddress) object).getHostAddress();
            }
            else if (object instanceof TimeZone) {
                TimeZone timeZone = (TimeZone) object;
                strVal = timeZone.getID();
            }
            else if (object instanceof Currency) {
                Currency currency = (Currency) object;
                strVal = currency.getCurrencyCode();
            }
            else{
                if (object instanceof JSONStreamAware) {
                    JSONStreamAware aware = (JSONStreamAware) object;
                    aware.writeJSONString(out);
                    return;
                }
                if (object instanceof Iterator) {
                    Iterator<?> it = (Iterator<?>) object;
                    writeIterator(serializer, out, it);
                    return;
                }
                if (object instanceof Iterable) {
                    Iterator<?> it = ((Iterable<?>) object).iterator();
                    writeIterator(serializer, out, it);
                    return;
                }
                if (object instanceof Map.Entry) {
                    serializeMapEntry(serializer, object, out);
                    return;
                }
                if (object.getClass().getName().equals("net.sf.json.JSONNull")) {
                    out.writeNull();
                    return;
                }
                if (!(object instanceof org.w3c.dom.Node))
                    throw new JSONException("not support class : " + objClass);
                strVal = toString((Node) object);
            }
        }

        out.writeString(strVal);
    }

    private void serializeMapEntry(JSONSerializer serializer, Object object, SerializeWriter out) {
        Map.Entry entry = (Map.Entry) object;
        Object objKey = entry.getKey();
        Object objVal = entry.getValue();

        if (objKey instanceof String) {
            writeObjectField(serializer, out, objKey, objVal);
        } else {
            writeKeyValuePair(serializer, out, objKey, objVal);
        }
        out.write('}');
    }

    private void writeKeyValuePair(JSONSerializer serializer, SerializeWriter out, Object objKey, Object objVal) {
        out.write('{');
        serializer.write(objKey);
        out.write(':');
        serializer.write(objVal);
    }

    private void writeObjectField(JSONSerializer serializer, SerializeWriter out, Object objKey, Object objVal) {
        String key = (String) objKey;

        if (objVal instanceof String) {
            String value = (String) objVal;
            out.writeFieldValueStringWithDoubleQuoteCheck('{', key, value);
        } else {
            out.write('{');
            out.writeFieldName(key);
            serializer.write(objVal);
        }
    }

    private void writeInetSocketAddress(JSONSerializer serializer, Object object, SerializeWriter out) {
        InetSocketAddress address = (InetSocketAddress) object;

        InetAddress inetAddress = address.getAddress();

        out.write('{');
        if (inetAddress != null) {
            out.writeFieldName("address");
            serializer.write(inetAddress);
            out.write(',');
        }
        out.writeFieldName("port");
        out.writeInt(address.getPort());
        out.write('}');
    }

    private void writeObjectWithPattern(JSONSerializer serializer, Object object, SerializeWriter out, String pattern) {
        out.write('{');
        out.writeFieldName(JSON.DEFAULT_TYPE_KEY);
        serializer.write(object.getClass().getName());
        out.writeFieldValue(',', "val", pattern);
        out.write('}');
    }

    private static String toString(org.w3c.dom.Node node) {
        try {
            return transformNodeToString(node);
        } catch (TransformerException e) {
            throw new JSONException("xml node to string error", e);
        }
    }

    private static String transformNodeToString(org.w3c.dom.Node node)
            throws TransformerFactoryConfigurationError, TransformerConfigurationException, TransformerException {
        TransformerFactory transFactory = TransformerFactory.newInstance();
        Transformer transformer = transFactory.newTransformer();
        DOMSource domSource = new DOMSource(node);

        StringWriter out = new StringWriter();
        transformer.transform(domSource, new StreamResult(out));
        return out.toString();
    }

    protected void writeIterator(JSONSerializer serializer, SerializeWriter out, Iterator<?> it) {
        int i = 0;
        out.write('[');
        while (it.hasNext()) {
            i = writeNextItem(serializer, out, it, i);
        }
        out.write(']');
        return;
    }

    private int writeNextItem(JSONSerializer serializer, SerializeWriter out, Iterator<?> it, int i) {
        if (i != 0) {
            out.write(',');
        }
        Object item = it.next();
        serializer.write(item);
        ++i;
        return i;
    }

    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type clazz, Object fieldName) {
        JSONLexer lexer = parser.lexer;

        if (clazz == InetSocketAddress.class) {
            if (lexer.token() == JSONToken.NULL) {
                lexer.nextToken();
                return null;
            }

            parser.accept(JSONToken.LBRACE);

            InetAddress address = null;
            int port = 0;
            for (;;) {
                String key = lexer.stringVal();
                lexer.nextToken(JSONToken.COLON);

                if (key.equals("address")) {
                    parser.accept(JSONToken.COLON);
                    address = parser.parseObject(InetAddress.class);
                }
                else if (key.equals("port")) {
                    port = parsePortNumber(parser, lexer);
                }
                else {
                    parser.accept(JSONToken.COLON);
                    parser.parse();
                }

                if (lexer.token() == JSONToken.COMMA) {
                    lexer.nextToken();
                    continue;
                }

                break;
            }

            parser.accept(JSONToken.RBRACE);

            return (T) new InetSocketAddress(address, port);
        }

        Object objVal;

        if (parser.resolveStatus == DefaultJSONParser.TypeNameRedirect) {
            objVal = parseJsonObjectValue(parser, lexer);
        }
        else {
            objVal = parser.parse();
        }

        String strVal;

        if (objVal == null) {
            strVal = null;
        }
        else{
            if (!(objVal instanceof String))
                return deserializeObject(clazz, objVal);
            strVal = (String) objVal;
        }

        if (strVal == null || strVal.length() == 0) {
            return null;
        }

        if (clazz == UUID.class) {
            return (T) UUID.fromString(strVal);
        }

        if (clazz == URI.class) {
            return (T) URI.create(strVal);
        }

        if (clazz == URL.class) {
            try {
                return (T) new URL(strVal);
            } catch (MalformedURLException e) {
                throw new JSONException("create url error", e);
            }
        }

        if (clazz == Pattern.class) {
            return (T) Pattern.compile(strVal);
        }

        if (clazz == Locale.class) {
            return (T) TypeUtils.toLocale(strVal);
        }

        if (clazz == SimpleDateFormat.class) {
            SimpleDateFormat dateFormat = new SimpleDateFormat(strVal, lexer.getLocale());
            dateFormat.setTimeZone(lexer.getTimeZone());
            return (T) dateFormat;
        }

        if (clazz == InetAddress.class || clazz == Inet4Address.class || clazz == Inet6Address.class) {
            try {
                return (T) InetAddress.getByName(strVal);
            } catch (UnknownHostException e) {
                throw new JSONException("deserialize inet adress error", e);
            }
        }

        if (clazz == File.class) {
            return createFileFromPath(strVal);
        }

        if (clazz == TimeZone.class) {
            return (T) TimeZone.getTimeZone(strVal);
        }

        if (clazz instanceof ParameterizedType) {
            ParameterizedType parmeterizedType = (ParameterizedType) clazz;
            clazz = parmeterizedType.getRawType();
        }

        if (clazz == Class.class) {
            return (T) TypeUtils.loadClass(strVal, parser.getConfig().getDefaultClassLoader(), false);
        }

        if (clazz == Charset.class) {
            return (T) Charset.forName(strVal);
        }

        if (clazz == Currency.class) {
            return (T) Currency.getInstance(strVal);
        }

        if (clazz == JSONPath.class) {
            return (T) new JSONPath(strVal);
        }



        if (clazz instanceof Class) {
            return deserializePath(clazz, strVal);
        }

        throw new JSONException("MiscCodec not support " + clazz.toString());
    }

    private <T> T deserializePath(Type clazz, String strVal) {
        String className = ((Class) clazz).getName();

        if (className.equals("java.nio.file.Path")) {
            try {
                return getDeserializedPath(strVal);
            } catch (NoSuchMethodException ex) {
                method_paths_get_error = true;
            } catch (IllegalAccessException ex) {
                throw new JSONException("Path deserialize erorr", ex);
            } catch (InvocationTargetException ex) {
                throw new JSONException("Path deserialize erorr", ex);
            }
        }

        throw new JSONException("MiscCodec not support " + className);
    }

    private <T> T deserializeObject(Type clazz, Object objVal) {
        if (objVal instanceof JSONObject) {
            return deserializeJsonObject(clazz, objVal);
        }
        throw new JSONException("expect string");
    }

    private <T> Object parseJsonObjectValue(DefaultJSONParser parser, JSONLexer lexer) {
        Object objVal;
        parser.resolveStatus = DefaultJSONParser.NONE;
        parser.accept(JSONToken.COMMA);

        if (lexer.token() != JSONToken.LITERAL_STRING)
            throw new JSONException("syntax error");
        validateLexerValue(lexer);

        parser.accept(JSONToken.COLON);

        objVal = parser.parse();

        parser.accept(JSONToken.RBRACE);
        return objVal;
    }

    private <T> T getDeserializedPath(String strVal)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        if (method_paths_get == null && !method_paths_get_error) {
            Class<?> paths = TypeUtils.loadClass("java.nio.file.Paths");
            method_paths_get = paths.getMethod("get", String.class, String[].class);
        }
        if (method_paths_get != null) {
            return (T) method_paths_get.invoke(null, strVal, new String[0]);
        }

        throw new JSONException("Path deserialize erorr");
    }

    private <T> T createFileFromPath(String strVal) {
        if (strVal.indexOf("..") >= 0 && !FILE_RELATIVE_PATH_SUPPORT) {
            throw new JSONException("file relative path not support.");
        }

        return (T) new File(strVal);
    }

    private <T> T deserializeJsonObject(Type clazz, Object objVal) {
        JSONObject jsonObject = (JSONObject) objVal;

        if (clazz == Currency.class) {
            String currency = jsonObject.getString("currency");
            if (currency != null) {
                return (T) Currency.getInstance(currency);
            }

            String symbol = jsonObject.getString("currencyCode");
            if (symbol != null) {
                return (T) Currency.getInstance(symbol);
            }
        }

        if (clazz == Map.Entry.class) {
           return (T) jsonObject.entrySet().iterator().next();
        }

        return jsonObject.toJavaObject(clazz);
    }

    private <T> void validateLexerValue(JSONLexer lexer) {
        if (!"val".equals(lexer.stringVal())) {
            throw new JSONException("syntax error");
        }
        lexer.nextToken();
    }

    private <T> int parsePortNumber(DefaultJSONParser parser, JSONLexer lexer) {
        int port;
        parser.accept(JSONToken.COLON);
        if (lexer.token() != JSONToken.LITERAL_INT) {
            throw new JSONException("port is not int");
        }
        port = lexer.intValue();
        lexer.nextToken();
        return port;
    }

    public int getFastMatchToken() {
        return JSONToken.LITERAL_STRING;
    }
}
