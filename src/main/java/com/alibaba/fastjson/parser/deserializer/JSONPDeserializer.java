package com.alibaba.fastjson.parser.deserializer;

import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONPObject;
import com.alibaba.fastjson.parser.*;

import java.lang.reflect.Type;

/**
 * Created by wenshao on 21/02/2017.
 */
public class JSONPDeserializer implements ObjectDeserializer {
    public static final JSONPDeserializer instance = new JSONPDeserializer();

    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        JSONLexerBase lexer = (JSONLexerBase) parser.getLexer();

        SymbolTable symbolTable = parser.getSymbolTable();

        String funcName = lexer.scanSymbolUnQuoted(symbolTable);
        lexer.nextToken();

        int tok = lexer.token();

        if (tok == JSONToken.DOT) {
            String name = lexer.scanSymbolUnQuoted(parser.getSymbolTable());
            funcName += ".";
            funcName += name;
            lexer.nextToken();
            tok = lexer.token();
        }

        JSONPObject jsonp = new JSONPObject(funcName);

        if (tok != JSONToken.LPAREN) {
            throw new JSONException("illegal jsonp : " + lexer.info());
        }
        lexer.nextToken();
        parseJsonpParameters(parser, lexer, jsonp);
        tok = lexer.token();
        if (tok == JSONToken.SEMI) {
            lexer.nextToken();
        }

        return (T) jsonp;
    }

    private <T> void parseJsonpParameters(DefaultJSONParser parser, JSONLexerBase lexer, JSONPObject jsonp) {
        int tok;
        for (;;) {
            Object arg = parser.parse();
            jsonp.addParameter(arg);

            tok = lexer.token();
            if (tok == JSONToken.COMMA) {
                lexer.nextToken();
            }
            else{
                if (tok != JSONToken.RPAREN)
                    throw new JSONException("illegal jsonp : " + lexer.info());
                lexer.nextToken();
                break;
            }
        }
    }

    public int getFastMatchToken() {
        return 0;
    }
}
