package com.alibaba.fastjson.support.hsf;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.*;
import com.alibaba.fastjson.util.TypeUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import static com.alibaba.fastjson.parser.JSONLexer.NOT_MATCH_NAME;

public class HSFJSONUtils {
    final static SymbolTable typeSymbolTable = new SymbolTable(1024);
    final static char[]      fieldName_argsTypes = "\"argsTypes\"".toCharArray();
    final static char[]      fieldName_argsObjs = "\"argsObjs\"".toCharArray();

    final static char[]      fieldName_type = "\"@type\":".toCharArray();

    public static Object[] parseInvocationArguments(String json, MethodLocator methodLocator) {
        DefaultJSONParser parser = new DefaultJSONParser(json);

        JSONLexerBase lexer = (JSONLexerBase) parser.getLexer();

        ParseContext rootContext = parser.setContext(null, null);

        Object[] values;
        int token = lexer.token();
        if (token == JSONToken.LBRACE) {
            values = parseJsonMethodArguments(json, methodLocator, parser, lexer, rootContext);
        } else if (token == JSONToken.LBRACKET) {
            String[] typeNames = lexer.scanFieldStringArray(null, -1, typeSymbolTable);

            lexer.skipWhitespace();

            char ch = lexer.getCurrent();

            if (ch == ']') {
                return parseMethodParameters(methodLocator, parser, typeNames);
            }
            values = parseMethodArgumentsWithLexer(methodLocator, parser, lexer, typeNames, ch);
        } else {
            values = null;
        }

        return values;
    }

    private static Object[] parseMethodArgumentsWithLexer(MethodLocator methodLocator, DefaultJSONParser parser, JSONLexerBase lexer,
            String[] typeNames, char ch) {
        Object[] values;
        if (ch == ',') {
            lexer.next();
            lexer.skipWhitespace();
        }
        lexer.nextToken(JSONToken.LBRACKET);

        Method method = methodLocator.findMethod(typeNames);
        Type[] argTypes = method.getGenericParameterTypes();
        values = parser.parseArray(argTypes);
        lexer.close();
        return values;
    }

    private static Object[] parseJsonMethodArguments(String json, MethodLocator methodLocator, DefaultJSONParser parser,
            JSONLexerBase lexer, ParseContext rootContext) {
        Object[] values;
        String[] typeNames = lexer.scanFieldStringArray(fieldName_argsTypes, -1, typeSymbolTable);
        if (typeNames == null && lexer.matchStat == NOT_MATCH_NAME) {
            typeNames = scanFieldTypeNames(lexer, typeNames);
        }
        Method method = methodLocator.findMethod(typeNames);

        if (method == null) {
            values = parseJsonToMethodArguments(json, methodLocator, lexer);
        } else {
            values = parseMethodArguments(parser, lexer, rootContext, method);
        }
        return values;
    }

    private static Object[] parseMethodParameters(MethodLocator methodLocator, DefaultJSONParser parser, String[] typeNames) {
        Object[] values;
        Method method = methodLocator.findMethod(null);
        Type[] argTypes = method.getGenericParameterTypes();
        values = new Object[typeNames.length];
        for (int i = 0;i < typeNames.length;++i) {
            castArgumentType(parser, values, typeNames, argTypes, i);
        }
        return values;
    }

    private static Object[] parseMethodArguments(DefaultJSONParser parser, JSONLexerBase lexer, ParseContext rootContext,
            Method method) {
        Object[] values;
        Type[] argTypes = method.getGenericParameterTypes();

        lexer.skipWhitespace();
        if (lexer.getCurrent() == ',') {
            lexer.next();
        }

        if (lexer.matchField2(fieldName_argsObjs)) {
            values = parseJsonArrayArguments(parser, lexer, rootContext, argTypes);
        } else {
            values = null;
        }

        parser.close();
        return values;
    }

    private static Object[] parseJsonToMethodArguments(String json, MethodLocator methodLocator, JSONLexerBase lexer) {
        Object[] values;
        String[] typeNames;
        Method method;
        lexer.close();

        JSONObject jsonObject = JSON.parseObject(json);
        typeNames = jsonObject.getObject("argsTypes", String[].class);
        method = methodLocator.findMethod(typeNames);

        JSONArray argsObjs = jsonObject.getJSONArray("argsObjs");
        if (argsObjs == null) {
            values = null;
        } else {
            values = extractMethodArguments(method, argsObjs);
        }
        return values;
    }

    private static void castArgumentType(DefaultJSONParser parser, Object[] values, String[] typeNames, Type[] argTypes,
            int i) {
        Type argType = argTypes[i];
        String typeName = typeNames[i];
        if (argType != String.class) {
            values[i] = TypeUtils.cast(typeName, argType, parser.getConfig());
        } else {
            values[i] = typeName;
        }
    }

    private static Object[] parseJsonArrayArguments(DefaultJSONParser parser, JSONLexerBase lexer, ParseContext rootContext,
            Type[] argTypes) {
        Object[] values;
        lexer.nextToken();

        ParseContext context = parser.setContext(rootContext, null, "argsObjs");
        values = parser.parseArray(argTypes);
        context.object = values;

        parser.accept(JSONToken.RBRACE);

        parser.handleResovleTask(null);
        return values;
    }

    private static Object[] extractMethodArguments(Method method, JSONArray argsObjs) {
        Object[] values;
        Type[] argTypes = method.getGenericParameterTypes();
        values = new Object[argTypes.length];
        for (int i = 0;i < argTypes.length;i++) {
            Type type = argTypes[i];
            values[i] = argsObjs.getObject(i, type);
        }
        return values;
    }

    private static String[] scanFieldTypeNames(JSONLexerBase lexer, String[] typeNames) {
        String type = lexer.scanFieldString(fieldName_type);
        if ("com.alibaba.fastjson.JSONObject".equals(type)) {
            typeNames = lexer.scanFieldStringArray(fieldName_argsTypes, -1, typeSymbolTable);
        }
        return typeNames;
    }
}
