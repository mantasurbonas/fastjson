package com.alibaba.fastjson.parser;

import java.lang.reflect.Type;

public class ParseContext {

    public Object             object;
    public final ParseContext parent;
    public final Object       fieldName;
    public final int          level;
    public Type               type;
    private transient String  path;

    public ParseContext(ParseContext parent, Object object, Object fieldName) {
        this.parent = parent;
        this.object = object;
        this.fieldName = fieldName;
        this.level = parent == null ? 0 : parent.level + 1;
    }

    public String toString() {
        if (path == null) {
            setPath();
        }

        return path;
    }

    private void setPath() {
        if (parent == null) {
            path = "$";
            return;
        }
        generatePath();
    }

    private void generatePath() {
        if (fieldName instanceof Integer) {
            path = parent.toString() + "[" + fieldName + "]";
            return;
        }
        path = parent.toString() + "." + fieldName;
    }
}
