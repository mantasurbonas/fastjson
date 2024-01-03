package com.alibaba.fastjson.serializer;

public class SerialContext {

    public final SerialContext parent;
    public final Object        object;
    public final Object        fieldName;
    public final int           features;

    public SerialContext(SerialContext parent, Object object, Object fieldName, int features, int fieldFeatures) {
        this.parent = parent;
        this.object = object;
        this.fieldName = fieldName;
        this.features = features;
    }

    public String toString() {
        if (parent == null) {
            return "$";
        }
        StringBuilder buf = new StringBuilder();
        toString(buf);
        return buf.toString();
    }

    protected void toString(StringBuilder buf) {
        if (parent == null) {
            buf.append('$');
            return;
        }
        appendFieldName(buf);
    }

    private void appendFieldName(StringBuilder buf) {
        parent.toString(buf);
        if (fieldName == null) {
            buf.append(".null");
        } else if (fieldName instanceof Integer) {
            buf.append('[');
            buf.append(((Integer) fieldName).intValue());
            buf.append(']');
        } else {
            appendFormattedFieldName(buf);
        }
    }

    private void appendFormattedFieldName(StringBuilder buf) {
        buf.append('.');

        String fieldName = this.fieldName.toString();
        boolean special = false;
        special = isSpecialCharacterPresent(fieldName, special);

        if (special) {
            appendEscapedFieldName(buf, fieldName);
        } else {
            buf.append(fieldName);
        }
    }

    private void appendEscapedFieldName(StringBuilder buf, String fieldName) {
        for (int i = 0;i < fieldName.length();++i)
            appendEscapedCharacter(buf, fieldName, i);
    }

    private void appendEscapedCharacter(StringBuilder buf, String fieldName, int i) {
        char ch = fieldName.charAt(i);
        if (ch == '\\') {
            buf.append('\\');
            appendDoubleBackslash(buf);
        }
        else{
            if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch > 128) {
                buf.append(ch);
                return;
            }
            if (ch == '\"') {
                appendDoubleBackslash(buf);
                buf.append('\\');
            }
            else {
                appendDoubleBackslash(buf);
            }
        }
        buf.append(ch);
    }

    private boolean isSpecialCharacterPresent(String fieldName, boolean special) {
        special = isFieldNameSpecial(fieldName, special);
        return special;
    }

    private boolean isFieldNameSpecial(String fieldName, boolean special) {
        special = isSpecialCharacterInFieldName(fieldName, special);
        return special;
    }

    private boolean isSpecialCharacterInFieldName(String fieldName, boolean special) {
        special = isSpecialCharacterPresent_(fieldName, special);
        return special;
    }

    private boolean isSpecialCharacterPresent_(String fieldName, boolean special) {
        for (int i = 0;i < fieldName.length();++i) {
            char ch = fieldName.charAt(i);
            if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch > 128) {
                continue;
            }
            special = true;
            break;
        }
        return special;
    }

    private void appendDoubleBackslash(StringBuilder buf) {
        buf.append('\\');
        buf.append('\\');
    }

    /**
     * @deprecated
     */
    public SerialContext getParent() {
        return parent;
    }

    /**
     * @deprecated
     */
    public Object getObject() {
        return object;
    }

    /**
     * @deprecated
     */
    public Object getFieldName() {
        return fieldName;
    }

    /**
     * @deprecated
     */
    public String getPath() {
        return toString();
    }
}
