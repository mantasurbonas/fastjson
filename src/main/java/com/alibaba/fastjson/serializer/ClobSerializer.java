package com.alibaba.fastjson.serializer;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.sql.Clob;
import java.sql.SQLException;

import com.alibaba.fastjson.JSONException;

public class ClobSerializer implements ObjectSerializer {

    public final static ClobSerializer instance = new ClobSerializer();

    public void write(JSONSerializer serializer, Object object, Object fieldName, Type fieldType, int features) throws IOException {
        try {
            if (object == null) {
                serializer.writeNull();
                return;
            }
            
            serializeClob(serializer, object);
        } catch (SQLException e) {
            throw new IOException("write clob error", e);
        }
    }

    private void serializeClob(JSONSerializer serializer, Object object) throws SQLException, IOException {
        Clob clob = (Clob) object;
        Reader reader = clob.getCharacterStream();

        StringBuilder buf = new StringBuilder();
        
        try {
            char[] chars = new char[2048];
            readToBuffer(reader, buf, chars);
        } catch (Exception ex) {
            throw new JSONException("read string from reader error", ex);
        }
        
        String text = buf.toString();
        reader.close();
        serializer.write(text);
    }

    private void readToBuffer(Reader reader, StringBuilder buf, char[] chars) throws IOException {
        for (;;) {
            int len = reader.read(chars, 0, chars.length);
            if (len < 0) {
                break;
            }
            buf.append(chars, 0, len);
        }
    }

}
