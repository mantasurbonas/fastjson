package com.alibaba.fastjson.asm;

import com.alibaba.fastjson.util.ASMUtils;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

public class TypeCollector {
    private static String JSONType = ASMUtils.desc(com.alibaba.fastjson.annotation.JSONType.class);

    private static final Map<String, String> primitives = new HashMap<String, String>() {
        {
            put("int", "I");
            put("boolean", "Z");
            put("byte", "B");
            put("char", "C");
            put("short", "S");
            put("float", "F");
            put("long", "J");
            put("double", "D");
        }
    };

    private final String methodName;

    private final Class<?>[] parameterTypes;

    protected MethodCollector collector;

    protected boolean jsonType;

    public TypeCollector(String methodName, Class<?>[] parameterTypes) {
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.collector = null;
    }

    protected MethodCollector visitMethod(int access, String name, String desc) {
        if (collector != null) {
            return null;
        }

        if (!name.equals(methodName)) {
            return null;
        }

        Type[] argTypes = Type.getArgumentTypes(desc);
        int longOrDoubleQuantity = 0;
        for (Type t : argTypes) {
            longOrDoubleQuantity = incrementIfLongOrDouble(longOrDoubleQuantity, t);
        }

        if (argTypes.length != this.parameterTypes.length) {
            return null;
        }
        for (int i = 0;i < argTypes.length;i++) {
            if (!correctTypeName(argTypes[i], this.parameterTypes[i].getName())) {
                return null;
            }
        }

        return collector = new MethodCollector(
                Modifier.isStatic(access) ? 0 : 1,
                argTypes.length + longOrDoubleQuantity);
    }

    private int incrementIfLongOrDouble(int longOrDoubleQuantity, Type t) {
        String className = t.getClassName();
        if (className.equals("long") || className.equals("double")) {
            longOrDoubleQuantity++;
        }
        return longOrDoubleQuantity;
    }

    public void visitAnnotation(String desc) {
        if (JSONType.equals(desc)) {
            jsonType = true;
        }
    }

    private boolean correctTypeName(Type type, String paramTypeName) {
        String s = type.getClassName();
        // array notation needs cleanup.
        StringBuilder braces = new StringBuilder();
        while (s.endsWith("[]")) {
            braces.append('[');
            s = s.substring(0, s.length() - 2);
        }
        if (braces.length() != 0) {
            s = appendPrimitiveOrReferenceType(s, braces);
        }
        return s.equals(paramTypeName);
    }

    private String appendPrimitiveOrReferenceType(String s, StringBuilder braces) {
        if (primitives.containsKey(s)) {
            s = braces.append(primitives.get(s)).toString();
        } else {
            s = braces.append('L').append(s).append(';').toString();
        }
        return s;
    }

    public String[] getParameterNamesForMethod() {
        if (collector == null || !collector.debugInfoPresent) {
            return new String[0];
        }
        return collector.getResult().split(",");
    }

    public boolean matched() {
        return collector != null;
    }

    public boolean hasJsonType() {
        return jsonType;
    }
}