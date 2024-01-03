package com.alibaba.fastjson.parser.deserializer;

import java.lang.reflect.Type;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONToken;

public class StackTraceElementDeserializer implements ObjectDeserializer {

    public final static StackTraceElementDeserializer instance = new StackTraceElementDeserializer();

    @SuppressWarnings({"unchecked", "unused"})
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        JSONLexer lexer = parser.lexer;
        if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken();
            return null;
        }

        if (lexer.token() != JSONToken.LBRACE && lexer.token() != JSONToken.COMMA) {
            throw new JSONException("syntax error: " + JSONToken.name(lexer.token()));
        }

        String declaringClass = null;
        String methodName = null;
        String fileName = null;
        int lineNumber = 0;
        String moduleName = null;
        String moduleVersion = null;
        String classLoaderName = null;

        for (;;) {
            // lexer.scanSymbol
            String key = lexer.scanSymbol(parser.getSymbolTable());

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

            lexer.nextTokenWithColon(JSONToken.LITERAL_STRING);
            if ("className".equals(key)) {
                declaringClass = parseJsonToken(lexer, declaringClass);
            }
            else if ("methodName".equals(key)) {
                methodName = parseJsonToken(lexer, methodName);
            }
            else if ("fileName".equals(key)) {
                fileName = parseJsonToken(lexer, fileName);
            }
            else if ("lineNumber".equals(key)) {
                lineNumber = parseLineNumber(lexer, lineNumber);
            }
            else if ("nativeMethod".equals(key)) {
                parseBooleanToken(lexer);
            }
            else if (key == JSON.DEFAULT_TYPE_KEY) {
                validateJsonLexer(lexer);
            }
            else if ("moduleName".equals(key)) {
                moduleName = parseJsonToken(lexer, moduleName);
            }
            else if ("moduleVersion".equals(key)) {
                moduleVersion = parseJsonToken(lexer, moduleVersion);
            }
            else{
                if (!"classLoaderName".equals(key))
                    throw new JSONException("syntax error : " + key);
                classLoaderName = parseJsonToken(lexer, classLoaderName);
            }

            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken(JSONToken.COMMA);
                break;
            }
        }
        return (T) new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
    }

    private <T> void validateJsonLexer(JSONLexer lexer) {
        if (lexer.token() == JSONToken.LITERAL_STRING) {
            validateStackTraceElement(lexer);
            return;
        }
        if (lexer.token() != JSONToken.NULL) {
            throw new JSONException("syntax error");
        }
    }

    private <T> void validateStackTraceElement(JSONLexer lexer) {
        String elementType = lexer.stringVal();
        if (!elementType.equals("java.lang.StackTraceElement")) {
            throw new JSONException("syntax error : " + elementType);    
        }
    }

    private <T> void parseBooleanToken(JSONLexer lexer) {
        if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken(JSONToken.COMMA);
            return;
        }
        if (lexer.token() == JSONToken.TRUE) {
            lexer.nextToken(JSONToken.COMMA);
        }
        else{
            if (lexer.token() != JSONToken.FALSE)
                throw new JSONException("syntax error");
            lexer.nextToken(JSONToken.COMMA);
        }
    }

    private <T> int parseLineNumber(JSONLexer lexer, int lineNumber) {
        if (lexer.token() == JSONToken.NULL) {
            lineNumber = 0;
        }
        else{
            if (lexer.token() != JSONToken.LITERAL_INT)
                throw new JSONException("syntax error");
            lineNumber = lexer.intValue();
        }
        return lineNumber;
    }

    private <T> String parseJsonToken(JSONLexer lexer, String declaringClass) {
        if (lexer.token() == JSONToken.NULL) {
            declaringClass = null;
        }
        else{
            if (lexer.token() != JSONToken.LITERAL_STRING)
                throw new JSONException("syntax error");
            declaringClass = lexer.stringVal();
        }
        return declaringClass;
    }

    public int getFastMatchToken() {
        return JSONToken.LBRACE;
    }
}
