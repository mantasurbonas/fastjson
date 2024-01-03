package com.alibaba.fastjson.parser.deserializer;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.*;

import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.util.TypeUtils;

import static com.alibaba.fastjson.util.TypeUtils.fnv1a_64_magic_hashcode;
import static com.alibaba.fastjson.util.TypeUtils.fnv1a_64_magic_prime;

@SuppressWarnings("rawtypes")
public class EnumDeserializer implements ObjectDeserializer {

    protected final Class<?> enumClass;
    protected final Enum[]   enums;
    protected final Enum[]   ordinalEnums;
    protected       long[]   enumNameHashCodes;

    public EnumDeserializer(Class<?> enumClass) {
        this.enumClass = enumClass;

        ordinalEnums = (Enum[]) enumClass.getEnumConstants();

        Map<Long, Enum> enumMap = new HashMap<Long, Enum>();
        for (int i = 0;i < ordinalEnums.length;++i) {
            mapEnumHashCodes(enumClass, enumMap, i);
        }

        this.enumNameHashCodes = new long[enumMap.size()];
        {
            sortEnumNameHashCodes(enumMap);
        }

        this.enums = new Enum[enumNameHashCodes.length];
        for (int i = 0;i < this.enumNameHashCodes.length;++i) {
            long hash = enumNameHashCodes[i];
            Enum e = enumMap.get(hash);
            this.enums[i] = e;
        }
    }

    private void mapEnumHashCodes(Class<?> enumClass, Map<Long, Enum> enumMap, int i) {
        Enum e = ordinalEnums[i];
        String name = e.name();

        JSONField jsonField = null;
        try {
            Field field = enumClass.getField(name);
            jsonField = TypeUtils.getAnnotation(field, JSONField.class);
            if (jsonField != null) {
                name = getJsonFieldName(name, jsonField);
            }
        } catch (Exception ex) {
            // skip
		}

        long hash = fnv1a_64_magic_hashcode;
        long hash_lower = fnv1a_64_magic_hashcode;
        for (int j = 0;j < name.length();++j) {
            char ch = name.charAt(j);

            hash ^= ch;
            hash_lower ^= ch >= 'A' && ch <= 'Z' ? (ch + 32) : ch;

            hash *= fnv1a_64_magic_prime;
            hash_lower *= fnv1a_64_magic_prime;
        }

        enumMap.put(hash, e);
        if (hash != hash_lower) {
            enumMap.put(hash_lower, e);
        }

        if (jsonField != null) {
            addAlternateNamesToEnumMap(enumMap, e, jsonField, hash, hash_lower);
        }
    }

    private void addAlternateNamesToEnumMap(Map<Long, Enum> enumMap, Enum e, JSONField jsonField, long hash, long hash_lower) {
        for (String alterName : jsonField.alternateNames()) {
            putAlternateNameHashInEnumMap(enumMap, e, hash, hash_lower, alterName);
        }
    }

    private void sortEnumNameHashCodes(Map<Long, Enum> enumMap) {
        int i = 0;
        for (Long h : enumMap.keySet()) {
            enumNameHashCodes[i++] = h;
        }
        Arrays.sort(this.enumNameHashCodes);
    }

    private void putAlternateNameHashInEnumMap(Map<Long, Enum> enumMap, Enum e, long hash, long hash_lower, String alterName) {
        long alterNameHash = fnv1a_64_magic_hashcode;
        for (int j = 0;j < alterName.length();++j) {
            char ch = alterName.charAt(j);
            alterNameHash ^= ch;
            alterNameHash *= fnv1a_64_magic_prime;
        }
        if (alterNameHash != hash && alterNameHash != hash_lower) {
            enumMap.put(alterNameHash, e);
        }
    }

    private String getJsonFieldName(String name, JSONField jsonField) {
        String jsonFieldName = jsonField.name();
        if (jsonFieldName != null && jsonFieldName.length() > 0) {
            name = jsonFieldName;
        }
        return name;
    }

    public Enum getEnumByHashCode(long hashCode) {
        if (enums == null) {
            return null;
        }

        int enumIndex = Arrays.binarySearch(this.enumNameHashCodes, hashCode);

        if (enumIndex < 0) {
            return null;
        }

        return enums[enumIndex];
    }
    
    public Enum<?> valueOf(int ordinal) {
        return ordinalEnums[ordinal];
    }

    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        try {
            return parseEnum(parser);
        } catch (JSONException e) {
            throw e;
        } catch (Exception e) {
            throw new JSONException(e.getMessage(), e);
        }
    }

    private <T> T parseEnum(DefaultJSONParser parser) {
        Object value;
        JSONLexer lexer = parser.lexer;
        int token = lexer.token();
        if (token == JSONToken.LITERAL_INT)
            return parseEnumValue(lexer);
        if (token == JSONToken.LITERAL_STRING)
            return parseEnumFromLexer(lexer);
        if (token == JSONToken.NULL) {
            value = null;
            lexer.nextToken(JSONToken.COMMA);

            return null;
        }
        value = parser.parse();

        throw new JSONException("parse enum " + enumClass.getName() + " error, value : " + value);
    }

    private <T> T parseEnumFromLexer(JSONLexer lexer) {
        String name = lexer.stringVal();
        lexer.nextToken(JSONToken.COMMA);

        if (name.length() == 0) {
            return (T) null;
        }

        long hash = fnv1a_64_magic_hashcode;
        long hash_lower = fnv1a_64_magic_hashcode;
        for (int j = 0;j < name.length();++j) {
            char ch = name.charAt(j);

            hash ^= ch;
            hash_lower ^= ch >= 'A' && ch <= 'Z' ? (ch + 32) : ch;

            hash *= fnv1a_64_magic_prime;
            hash_lower *= fnv1a_64_magic_prime;
        }

        Enum e = getEnumByHashCode(hash);
        if (e == null && hash_lower != hash) {
            e = getEnumByHashCode(hash_lower);
        }

        if (e == null && lexer.isEnabled(Feature.ErrorOnEnumNotMatch)) {
            throw new JSONException("not match enum value, " + enumClass.getName() + " : " + name);
        }
        return (T) e;
    }

    private <T> T parseEnumValue(JSONLexer lexer) {
        int intValue = lexer.intValue();
        lexer.nextToken(JSONToken.COMMA);

        if (intValue < 0 || intValue >= ordinalEnums.length) {
            throw new JSONException("parse enum " + enumClass.getName() + " error, value : " + intValue);
        }

        return (T) ordinalEnums[intValue];
    }

    public int getFastMatchToken() {
        return JSONToken.LITERAL_INT;
    }
}
