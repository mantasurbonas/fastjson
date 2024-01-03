package com.alibaba.fastjson.parser.deserializer;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.ParseContext;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.util.FieldInfo;
import com.alibaba.fastjson.util.ParameterizedTypeImpl;

public class ArrayListTypeFieldDeserializer extends FieldDeserializer {

    private final Type         itemType;
    private int                itemFastMatchToken;
    private ObjectDeserializer deserializer;

    public ArrayListTypeFieldDeserializer(ParserConfig mapping, Class<?> clazz, FieldInfo fieldInfo) {
        super(clazz, fieldInfo);

        Type fieldType = fieldInfo.fieldType;
        if (fieldType instanceof ParameterizedType) {
            Type argType = ((ParameterizedType) fieldInfo.fieldType).getActualTypeArguments()[0];
            if (argType instanceof WildcardType) {
                argType = getWildcardUpperBound(argType);
            }
            this.itemType = argType;
        } else {
            this.itemType = Object.class;
        }
    }

    private Type getWildcardUpperBound(Type argType) {
        WildcardType wildcardType = (WildcardType) argType;
        Type[] upperBounds = wildcardType.getUpperBounds();
        if (upperBounds.length == 1) {
            argType = upperBounds[0];
        }
        return argType;
    }

    public int getFastMatchToken() {
        return JSONToken.LBRACKET;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void parseField(DefaultJSONParser parser, Object object, Type objectType, Map<String, Object> fieldValues) {
        JSONLexer lexer = parser.lexer;
        int token = lexer.token();
        if (token == JSONToken.NULL
                || (token == JSONToken.LITERAL_STRING && lexer.stringVal().length() == 0)) {
            if (object == null) {
                fieldValues.put(fieldInfo.name, null);
            } else {
                setValue(object, null);
            }
            return;
        }

        ArrayList list = new ArrayList();

        ParseContext context = parser.getContext();

        parser.setContext(context, object, fieldInfo.name);
        parseArray(parser, objectType, list);
        parser.setContext(context);

        if (object == null) {
            fieldValues.put(fieldInfo.name, list);
        } else {
            setValue(object, list);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public final void parseArray(DefaultJSONParser parser, Type objectType, Collection array) {
        Type itemType = this.itemType;
        ObjectDeserializer itemTypeDeser = this.deserializer;

        if (objectType instanceof ParameterizedType) {
            if (itemType instanceof TypeVariable) {
                TypeVariable typeVar = (TypeVariable) itemType;
                ParameterizedType paramType = (ParameterizedType) objectType;

                Class<?> objectClass = null;
                if (paramType.getRawType() instanceof Class) {
                    objectClass = (Class<?>) paramType.getRawType();
                }

                int paramIndex = -1;
                if (objectClass != null) {
                    paramIndex = findTypeParameterIndex_(typeVar, objectClass, paramIndex);
                }

                if (paramIndex != -1) {
                    itemType = paramType.getActualTypeArguments()[paramIndex];
                    if (!itemType.equals(this.itemType)) {
                        itemTypeDeser = parser.getConfig().getDeserializer(itemType);
                    }
                }
            } else if (itemType instanceof ParameterizedType) {
                itemType = resolveParameterizedItemType(objectType, itemType);
            }
        } else if (itemType instanceof TypeVariable && objectType instanceof Class) {
            itemType = resolveObjectType(objectType, itemType);
        }

        JSONLexer lexer = parser.lexer;

        int token = lexer.token();
        if (token == JSONToken.LBRACKET) {
            parseJSONCollection(parser, array, itemType, itemTypeDeser, lexer);
        } else if (token == JSONToken.LITERAL_STRING && fieldInfo.unwrapped) {
            parseJsonArray(array, lexer);
        } else {
            deserializeAndAddValue(parser, array, itemType, itemTypeDeser);
        }
    }

    private Type resolveParameterizedItemType(Type objectType, Type itemType) {
        ParameterizedType parameterizedItemType = (ParameterizedType) itemType;
        Type[] itemActualTypeArgs = parameterizedItemType.getActualTypeArguments();
        if (itemActualTypeArgs.length == 1 && itemActualTypeArgs[0] instanceof TypeVariable) {
            itemType = resolveItemType(objectType, itemType, parameterizedItemType, itemActualTypeArgs);
        }
        return itemType;
    }

    private void parseJSONCollection(DefaultJSONParser parser, Collection array, Type itemType, ObjectDeserializer itemTypeDeser,
            JSONLexer lexer) {
        if (itemTypeDeser == null) {
            itemTypeDeser = deserializer = parser.getConfig().getDeserializer(itemType);
            itemFastMatchToken = deserializer.getFastMatchToken();
        }

        lexer.nextToken(itemFastMatchToken);

        parseCollection(parser, array, itemType, itemTypeDeser, lexer);

        lexer.nextToken(JSONToken.COMMA);
    }

    private Type resolveObjectType(Type objectType, Type itemType) {
        Class objectClass = (Class) objectType;
        TypeVariable typeVar = (TypeVariable) itemType;
        objectClass.getTypeParameters();

        itemType = resolveTypeVariable(itemType, objectClass, typeVar);
        return itemType;
    }

    private Type resolveItemType(Type objectType, Type itemType, ParameterizedType parameterizedItemType,
            Type[] itemActualTypeArgs) {
        TypeVariable typeVar = (TypeVariable) itemActualTypeArgs[0];
        ParameterizedType paramType = (ParameterizedType) objectType;

        Class<?> objectClass = null;
        if (paramType.getRawType() instanceof Class) {
            objectClass = (Class<?>) paramType.getRawType();
        }

        int paramIndex = -1;
        if (objectClass != null) {
            paramIndex = findTypeParameterIndex_(typeVar, objectClass, paramIndex);

        }

        if (paramIndex != -1) {
            itemActualTypeArgs[0] = paramType.getActualTypeArguments()[paramIndex];
            itemType = TypeReference.intern(
                    new ParameterizedTypeImpl(itemActualTypeArgs, parameterizedItemType.getOwnerType(), parameterizedItemType.getRawType())
            );
        }
        return itemType;
    }

    private void deserializeAndAddValue(DefaultJSONParser parser, Collection array, Type itemType,
            ObjectDeserializer itemTypeDeser) {
        if (itemTypeDeser == null) {
            itemTypeDeser = deserializer = parser.getConfig().getDeserializer(itemType);
        }
        Object val = itemTypeDeser.deserialze(parser, itemType, 0);
        addValueAndResolve(parser, array, val);
    }

    private void parseJsonArray(Collection array, JSONLexer lexer) {
        String str = lexer.stringVal();
        lexer.nextToken();
        DefaultJSONParser valueParser = new DefaultJSONParser(str);
        valueParser.parseArray(array);
    }

    private void parseCollection(DefaultJSONParser parser, Collection array, Type itemType, ObjectDeserializer itemTypeDeser,
            JSONLexer lexer) {
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

            Object val = itemTypeDeser.deserialze(parser, itemType, i);
            addValueAndResolve(parser, array, val);

            if (lexer.token() == JSONToken.COMMA) {
                lexer.nextToken(itemFastMatchToken);
                continue;
            }
        }
    }

    private Type resolveTypeVariable(Type itemType, Class objectClass, TypeVariable typeVar) {
        for (int i = 0, size = objectClass.getTypeParameters().length;i < size;++i) {
            TypeVariable item = objectClass.getTypeParameters()[i];
            if (item.getName().equals(typeVar.getName())) {
                Type[] bounds = item.getBounds();
                if (bounds.length == 1) {
                    itemType = bounds[0];
                }
                break;
            }
        }
        return itemType;
    }

    private int findTypeParameterIndex_(TypeVariable typeVar, Class<?> objectClass, int paramIndex) {
        for (int i = 0, size = objectClass.getTypeParameters().length;i < size;++i) {
            TypeVariable item = objectClass.getTypeParameters()[i];
            if (item.getName().equals(typeVar.getName())) {
                paramIndex = i;
                break;
            }
        }
        return paramIndex;
    }

    private void addValueAndResolve(DefaultJSONParser parser, Collection array, Object val) {
        array.add(val);

        parser.checkListResolve(array);
    }
}
