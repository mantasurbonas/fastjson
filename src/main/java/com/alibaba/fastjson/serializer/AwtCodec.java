package com.alibaba.fastjson.serializer;

import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.lang.reflect.Type;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.ParseContext;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;

public class AwtCodec implements ObjectSerializer, ObjectDeserializer {

    public final static AwtCodec instance = new AwtCodec();
    
    public static boolean support(Class<?> clazz) {
        return clazz == Point.class //
               || clazz == Rectangle.class //
               || clazz == Font.class //
               || clazz == Color.class //
        ;
    }

    public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType,
                         int features) throws IOException {
        SerializeWriter out = serializer.out;

        if (object == null) {
            out.writeNull();
            return;
        }

        char sep = '{';

        if (object instanceof Point) {
            writePoint(object, out, sep);
        }
        else if (object instanceof Font) {
            writeFontDetails(object, out, sep);
        }
        else if (object instanceof Rectangle) {
            writeRectangleDetails(object, out, sep);
        }
        else{
            if (!(object instanceof Color))
                throw new JSONException("not support awt class : " + object.getClass().getName());
            writeColorDetails(object, out, sep);
        }

        out.write('}');

    }

    private void writeColorDetails(Object object, SerializeWriter out, char sep) {
        Color color = (Color) object;
        
        sep = writeClassName(out, Color.class, sep);
        
        out.writeFieldValue(sep, "r", color.getRed());
        out.writeFieldValue(',', "g", color.getGreen());
        out.writeFieldValue(',', "b", color.getBlue());
        if (color.getAlpha() > 0) {
            out.writeFieldValue(',', "alpha", color.getAlpha());
        }
    }

    private void writeRectangleDetails(Object object, SerializeWriter out, char sep) {
        Rectangle rectangle = (Rectangle) object;
        
        sep = writeClassName(out, Rectangle.class, sep);
        
        out.writeFieldValue(sep, "x", rectangle.x);
        out.writeFieldValue(',', "y", rectangle.y);
        out.writeFieldValue(',', "width", rectangle.width);
        out.writeFieldValue(',', "height", rectangle.height);
    }

    private void writeFontDetails(Object object, SerializeWriter out, char sep) {
        Font font = (Font) object;
        
        sep = writeClassName(out, Font.class, sep);
        
        out.writeFieldValue(sep, "name", font.getName());
        out.writeFieldValue(',', "style", font.getStyle());
        out.writeFieldValue(',', "size", font.getSize());
    }

    private void writePoint(Object object, SerializeWriter out, char sep) {
        Point font = (Point) object;
        
        sep = writeClassName(out, Point.class, sep);
        
        out.writeFieldValue(sep, "x", font.x);
        out.writeFieldValue(',', "y", font.y);
    }

    protected char writeClassName(SerializeWriter out, Class<?> clazz, char sep) {
        if (out.isEnabled(SerializerFeature.WriteClassName)) {
            sep = writeClassDetails(out, clazz);
        }
        return sep;
    }

    private char writeClassDetails(SerializeWriter out, Class<?> clazz) {
        out.write('{');
        out.writeFieldName(JSON.DEFAULT_TYPE_KEY);
        out.writeString(clazz.getName());
        return ',';
    }

    @SuppressWarnings("unchecked")

    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        JSONLexer lexer = parser.lexer;

        if (lexer.token() == JSONToken.NULL) {
            lexer.nextToken(JSONToken.COMMA);
            return null;
        }

        if (lexer.token() != JSONToken.LBRACE && lexer.token() != JSONToken.COMMA) {
            throw new JSONException("syntax error");
        }
        lexer.nextToken();

        T obj;
        if (type == Point.class) {
            obj = (T) parsePoint(parser, fieldName);
        }
        else if (type == Rectangle.class) {
            obj = (T) parseRectangle(parser);
        }
        else if (type == Color.class) {
            obj = (T) parseColor(parser);
        }
        else{
            if (type != Font.class)
                throw new JSONException("not support awt class : " + type);
            
            obj = (T) parseFont(parser);
        }

        ParseContext context = parser.getContext();
        parser.setContext(obj, fieldName);
        parser.setContext(context);

        return obj;
    }
    
    protected Font parseFont(DefaultJSONParser parser) {
        JSONLexer lexer = parser.lexer;

        int size = 0;
        int style = 0;
        String name = null;
        for (;;) {
            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken();
                break;
            }

            String key;
            if (lexer.token() != JSONToken.LITERAL_STRING)
                throw new JSONException("syntax error");
            
            key = lexer.stringVal();
            lexer.nextTokenWithColon(JSONToken.LITERAL_INT);


            if (key.equalsIgnoreCase("name")) {
                name = parseStringVal(lexer, name);
            }
            else if (key.equalsIgnoreCase("style")) {
                if (lexer.token() != JSONToken.LITERAL_INT)
                    throw new JSONException("syntax error");
                
                style = lexer.intValue();
                lexer.nextToken();
            }
            else{
                if (!key.equalsIgnoreCase("size"))
                    throw new JSONException("syntax error, " + key);
                
                if (lexer.token() != JSONToken.LITERAL_INT)
                    throw new JSONException("syntax error");
                
                size = lexer.intValue();
                lexer.nextToken();
            }

            if (lexer.token() == JSONToken.COMMA) {
                lexer.nextToken(JSONToken.LITERAL_STRING);
            }
        }

        return new Font(name, style, size);
    }

    private String parseStringVal(JSONLexer lexer, String name) {
        if (lexer.token() != JSONToken.LITERAL_STRING)
            throw new JSONException("syntax error");
        
        name = lexer.stringVal();
        lexer.nextToken();
        return name;
    }
    
    protected Color parseColor(DefaultJSONParser parser) {
        JSONLexer lexer = parser.lexer;

        int r = 0;
        int g = 0;
        int b = 0;
        int alpha = 0;
        for (;;) {
            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken();
                break;
            }

            String key;
            if (lexer.token() != JSONToken.LITERAL_STRING)
                throw new JSONException("syntax error");
            
            key = lexer.stringVal();
            lexer.nextTokenWithColon(JSONToken.LITERAL_INT);

            int val;
            if (lexer.token() != JSONToken.LITERAL_INT)
                throw new JSONException("syntax error");
            
            val = lexer.intValue();
            lexer.nextToken();

            if (key.equalsIgnoreCase("r")) {
                r = val;
            }
            else if (key.equalsIgnoreCase("g")) {
                g = val;
            }
            else if (key.equalsIgnoreCase("b")) {
                b = val;
            }
            else{
                if (!key.equalsIgnoreCase("alpha"))
                    throw new JSONException("syntax error, " + key);
                alpha = val;
            }

            if (lexer.token() == JSONToken.COMMA) {
                lexer.nextToken(JSONToken.LITERAL_STRING);
            }
        }

        return new Color(r, g, b, alpha);
    }
    
    protected Rectangle parseRectangle(DefaultJSONParser parser) {
        JSONLexer lexer = parser.lexer;

        int x = 0;
        int y = 0;
        int width = 0;
        int height = 0;
        for (;;) {
            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken();
                break;
            }

            String key;
            if (lexer.token() != JSONToken.LITERAL_STRING)
                throw new JSONException("syntax error");
            
            key = lexer.stringVal();
            lexer.nextTokenWithColon(JSONToken.LITERAL_INT);

            int val;
            int token = lexer.token();
            if (token == JSONToken.LITERAL_INT) {
                val = lexer.intValue();
                lexer.nextToken();
            }
            else{
                if (token != JSONToken.LITERAL_FLOAT)
                    throw new JSONException("syntax error");
                val = (int) lexer.floatValue();
                lexer.nextToken();
            }

            if (key.equalsIgnoreCase("x")) {
                x = val;
            }
            else if (key.equalsIgnoreCase("y")) {
                y = val;
            }
            else if (key.equalsIgnoreCase("width")) {
                width = val;
            }
            else{
                if (!key.equalsIgnoreCase("height"))
                    throw new JSONException("syntax error, " + key);
                height = val;
            }

            if (lexer.token() == JSONToken.COMMA) {
                lexer.nextToken(JSONToken.LITERAL_STRING);
            }
        }

        return new Rectangle(x, y, width, height);
    }

    protected Point parsePoint(DefaultJSONParser parser, Object fieldName) {
        JSONLexer lexer = parser.lexer;

        int x = 0;
        int y = 0;
        for (;;) {
            if (lexer.token() == JSONToken.RBRACE) {
                lexer.nextToken();
                break;
            }

            String key;
            if (lexer.token() != JSONToken.LITERAL_STRING)
                throw new JSONException("syntax error");
            key = lexer.stringVal();

            if (JSON.DEFAULT_TYPE_KEY.equals(key)) {
                parser.acceptType("java.awt.Point");
                continue;
            }

            if ("$ref".equals(key)) {
                return (Point) parseRef(parser, fieldName);
            }

            lexer.nextTokenWithColon(JSONToken.LITERAL_INT);

            int token = lexer.token();
            int val;
            if (token == JSONToken.LITERAL_INT) {
                val = lexer.intValue();
                lexer.nextToken();
            }
            else{
                if (token != JSONToken.LITERAL_FLOAT)
                    throw new JSONException("syntax error : " + lexer.tokenName());
                val = (int) lexer.floatValue();
                lexer.nextToken();
            }

            if (key.equalsIgnoreCase("x")) {
                x = val;
            }
            else{
                if (!key.equalsIgnoreCase("y"))
                    throw new JSONException("syntax error, " + key);
                y = val;
            }

            if (lexer.token() == JSONToken.COMMA) {
                lexer.nextToken(JSONToken.LITERAL_STRING);
            }
        }

        return new Point(x, y);
    }

    private Object parseRef(DefaultJSONParser parser, Object fieldName) {
        JSONLexer lexer = parser.getLexer();
        lexer.nextTokenWithColon(JSONToken.LITERAL_STRING);
        String ref = lexer.stringVal();
        parser.setContext(parser.getContext(), fieldName);
        parser.addResolveTask(new DefaultJSONParser.ResolveTask(parser.getContext(), ref));
        parser.popContext();
        parser.setResolveStatus(DefaultJSONParser.NeedToResolve);
        lexer.nextToken(JSONToken.RBRACE);
        parser.accept(JSONToken.RBRACE);
        return null;
    }

    public int getFastMatchToken() {
        return JSONToken.LBRACE;
    }
}
