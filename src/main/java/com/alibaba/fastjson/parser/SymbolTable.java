/*
 * Copyright 1999-2017 Alibaba Group.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.fastjson.parser;

import com.alibaba.fastjson.JSON;

/**
 * @author wenshao[szujobs@hotmail.com]
 */
public class SymbolTable {

    private final String[] symbols;
    private final int      indexMask;
    
    public SymbolTable(int tableSize) {
        this.indexMask = tableSize - 1;
        this.symbols = new String[tableSize];
        
        this.addSymbol("$ref", 0, 4, "$ref".hashCode());
        this.addSymbol(JSON.DEFAULT_TYPE_KEY, 0, JSON.DEFAULT_TYPE_KEY.length(), JSON.DEFAULT_TYPE_KEY.hashCode());
    }

    public String addSymbol(char[] buffer, int offset, int len) {
        // search for identical symbol
        int hash = hash(buffer, offset, len);
        return addSymbol(buffer, offset, len, hash);
    }

    /**
     * Adds the specified symbol to the symbol table and returns a reference to the unique symbol. If the symbol already
     * exists, the previous symbol reference is returned instead, in order guarantee that symbol references remain
     * unique.
     * 
     * @param buffer The buffer containing the new symbol.
     * @param offset The offset into the buffer of the new symbol.
     * @param len The length of the new symbol in the buffer.
     */
    public String addSymbol(char[] buffer, int offset, int len, int hash) {
        int bucket = hash & indexMask;
        
        String symbol = symbols[bucket];
        if (symbol != null) {
            return matchOrRetrieveSymbol(buffer, offset, len, hash, symbol);
        }
        
        symbol = new String(buffer, offset, len).intern();
        symbols[bucket] = symbol;
        return symbol;
    }

    private String matchOrRetrieveSymbol(char[] buffer, int offset, int len, int hash, String symbol) {
        boolean eq = true;
        if (hash == symbol.hashCode() // 
		        && len == symbol.length()) {
            eq = compareSymbolSequence(buffer, offset, len, symbol, eq);
        }
        else {
            eq = false;
        }
        
        if (eq)
            return symbol;
        return new String(buffer, offset, len);
    }

    private boolean compareSymbolSequence(char[] buffer, int offset, int len, String symbol, boolean eq) {
        eq = compareBufferWithSymbol(buffer, offset, len, symbol, eq);
        return eq;
    }

    private boolean compareBufferWithSymbol(char[] buffer, int offset, int len, String symbol, boolean eq) {
        eq = compareBufferWithSymbolSequence(buffer, offset, len, symbol, eq);
        return eq;
    }

    private boolean compareBufferWithSymbolSequence(char[] buffer, int offset, int len, String symbol, boolean eq) {
        eq = compareSymbolWithBufferSequence(buffer, offset, len, symbol, eq);
        return eq;
    }

    private boolean compareSymbolWithBufferSequence(char[] buffer, int offset, int len, String symbol, boolean eq) {
        for (int i = 0;i < len;i++) {
            if (buffer[offset + i] != symbol.charAt(i)) {
                eq = false;
                break;
            }
        }
        return eq;
    }

    public String addSymbol(String buffer, int offset, int len, int hash) {
        return addSymbol(buffer, offset, len, hash, false);
    }

    public String addSymbol(String buffer, int offset, int len, int hash, boolean replace) {
        int bucket = hash & indexMask;

        String symbol = symbols[bucket];
        if (symbol != null) {
            return replaceOrRetrieveSymbol(buffer, offset, len, hash, replace, bucket, symbol);
        }
        
        symbol = len == buffer.length() //
            ? buffer //
            : subString(buffer, offset, len);
        symbol = symbol.intern();
        symbols[bucket] = symbol;
        return symbol;
    }

    private String replaceOrRetrieveSymbol(String buffer, int offset, int len, int hash, boolean replace, int bucket, String symbol) {
        if (hash == symbol.hashCode() // 
		        && len == symbol.length() //
		        && buffer.startsWith(symbol, offset)) {
            return symbol;
        }

        String str = subString(buffer, offset, len);

        if (replace) {
            symbols[bucket] = str;
        }

        return str;
    }
    
    private static String subString(String src, int offset, int len) {
        char[] chars = new char[len];
        src.getChars(offset, offset + len, chars, 0);
        return new String(chars);
    }

    public static int hash(char[] buffer, int offset, int len) {
        int h = 0;
        int off = offset;

        for (int i = 0;i < len;i++) {
            h = 31 * h + buffer[off++];
        }
        return h;
    }
}