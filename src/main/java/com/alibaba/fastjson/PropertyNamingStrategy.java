package com.alibaba.fastjson;

/**
 * @since 1.2.15
 */
public enum PropertyNamingStrategy {
                                    CamelCase, // camelCase
                                    PascalCase, // PascalCase
                                    SnakeCase, // snake_case
                                    KebabCase, // kebab-case
                                    NoChange,  //
                                    NeverUseThisValueExceptDefaultValue;

    public String translate(String propertyName) {
        switch (this) {
            case SnakeCase: {
                return formatPropertyName(propertyName);
            }
            case KebabCase: {
                return formatPropertyNameWithBuffer(propertyName);
            }
            case PascalCase: {
                return capitalizeFirstLetter(propertyName);
            }
            case CamelCase: {
                return lowercaseFirstLetter(propertyName);
            }
            case NoChange:
            case NeverUseThisValueExceptDefaultValue:
            default:
                return propertyName;
        }
    }

    private String formatPropertyNameWithBuffer(String propertyName) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0;i < propertyName.length();++i) {
            appendFormattedPropertyName(propertyName, buf, i);
        }
        return buf.toString();
    }

    private String formatPropertyName(String propertyName) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0;i < propertyName.length();++i) {
            appendFormattedCharacter(propertyName, buf, i);
        }
        return buf.toString();
    }

    private void appendFormattedPropertyName(String propertyName, StringBuilder buf, int i) {
        char ch = propertyName.charAt(i);
        if (ch >= 'A' && ch <= 'Z') {
            appendLowercaseWithDash(buf, i, ch);
        } else {
            buf.append(ch);
        }
    }

    private void appendFormattedCharacter(String propertyName, StringBuilder buf, int i) {
        char ch = propertyName.charAt(i);
        if (ch >= 'A' && ch <= 'Z') {
            appendCharacterWithUnderscore(buf, i, ch);
        } else {
            buf.append(ch);
        }
    }

    private String lowercaseFirstLetter(String propertyName) {
        char ch = propertyName.charAt(0);
        if (ch >= 'A' && ch <= 'Z') {
            char[] chars = propertyName.toCharArray();
            chars[0] += 32;
            return new String(chars);
        }

        return propertyName;
    }

    private String capitalizeFirstLetter(String propertyName) {
        char ch = propertyName.charAt(0);
        if (ch >= 'a' && ch <= 'z') {
            char[] chars = propertyName.toCharArray();
            chars[0] -= 32;
            return new String(chars);
        }

        return propertyName;
    }

    private void appendLowercaseWithDash(StringBuilder buf, int i, char ch) {
        char ch_ucase = (char) (ch + 32);
        if (i > 0) {
            buf.append('-');
        }
        buf.append(ch_ucase);
    }

    private void appendCharacterWithUnderscore(StringBuilder buf, int i, char ch) {
        char ch_ucase = (char) (ch + 32);
        if (i > 0) {
            buf.append('_');
        }
        buf.append(ch_ucase);
    }
}
