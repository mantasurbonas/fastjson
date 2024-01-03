package com.alibaba.fastjson.parser.deserializer;

import static com.alibaba.fastjson.util.ASMUtils.desc;
import static com.alibaba.fastjson.util.ASMUtils.type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.fastjson.asm.ClassWriter;
import com.alibaba.fastjson.asm.FieldWriter;
import com.alibaba.fastjson.asm.Label;
import com.alibaba.fastjson.asm.MethodVisitor;
import com.alibaba.fastjson.asm.MethodWriter;
import com.alibaba.fastjson.asm.Opcodes;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.DefaultJSONParser.ResolveTask;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.JSONLexer;
import com.alibaba.fastjson.parser.JSONLexerBase;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.ParseContext;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.parser.SymbolTable;
import com.alibaba.fastjson.util.*;

public class ASMDeserializerFactory implements Opcodes {

    public final ASMClassLoader classLoader;
    protected final AtomicLong  seed = new AtomicLong();

    final static String         DefaultJSONParser = type(DefaultJSONParser.class);
    final static String         JSONLexerBase = type(JSONLexerBase.class);

    public ASMDeserializerFactory(ClassLoader parentClassLoader) {
        classLoader = parentClassLoader instanceof ASMClassLoader //
            ? (ASMClassLoader) parentClassLoader //
            : new ASMClassLoader(parentClassLoader);
    }
    
    public ObjectDeserializer createJavaBeanDeserializer(ParserConfig config, JavaBeanInfo beanInfo) throws Exception {
        Class<?> clazz = beanInfo.clazz;
        if (clazz.isPrimitive()) {
            throw new IllegalArgumentException("not support type :" + clazz.getName());
        }

        String className = "FastjsonASMDeserializer_" + seed.incrementAndGet() + "_" + clazz.getSimpleName();
        String classNameType;
        String classNameFull;

        Package pkg = ASMDeserializerFactory.class.getPackage();
        if (pkg != null) {
            String packageName = pkg.getName();
            classNameType = packageName.replace('.', '/') + "/" + className;
            classNameFull = packageName + "." + className;
        } else {
            classNameType = className;
            classNameFull = className;
        }

        ClassWriter cw = new ClassWriter();
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, classNameType, type(JavaBeanDeserializer.class), null);

        _init(cw, new Context(classNameType, config, beanInfo, 3));
        _createInstance(cw, new Context(classNameType, config, beanInfo, 3));
        _deserialze(cw, new Context(classNameType, config, beanInfo, 5));

        _deserialzeArrayMapping(cw, new Context(classNameType, config, beanInfo, 4));
        byte[] code = cw.toByteArray();

        Class<?> deserClass = classLoader.defineClassPublic(classNameFull, code, 0, code.length);
        Constructor<?> constructor = deserClass.getConstructor(ParserConfig.class, JavaBeanInfo.class);
        return (ObjectDeserializer) constructor.newInstance(config, beanInfo);
    }

    private void _setFlag(MethodVisitor mw, Context context, int i) {
        String varName = "_asm_flag_" + (i / 32);

        mw.visitVarInsn(ILOAD, context.var(varName));
        mw.visitLdcInsn(1 << i);
        mw.visitInsn(IOR);
        mw.visitVarInsn(ISTORE, context.var(varName));
    }

    private void _isFlag(MethodVisitor mw, Context context, int i, Label label) {
        mw.visitVarInsn(ILOAD, context.var("_asm_flag_" + (i / 32)));
        mw.visitLdcInsn(1 << i);
        mw.visitInsn(IAND);

        mw.visitJumpInsn(IFEQ, label);
    }

    private void _deserialzeArrayMapping(ClassWriter cw, Context context) {
        MethodVisitor mw = new MethodWriter(cw, ACC_PUBLIC, "deserialzeArrayMapping",
                                            "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                            null, null);

        defineVarLexer(context, mw);

        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitVarInsn(ALOAD, 1);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getSymbolTable", "()" + desc(SymbolTable.class));
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanTypeName", "(" + desc(SymbolTable.class) + ")Ljava/lang/String;");
        mw.visitVarInsn(ASTORE, context.var("typeName"));

        Label typeNameNotNull_ = new Label();
        mw.visitVarInsn(ALOAD, context.var("typeName"));
        mw.visitJumpInsn(IFNULL, typeNameNotNull_);

        mw.visitVarInsn(ALOAD, 1);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getConfig", "()" + desc(ParserConfig.class));
        mw.visitVarInsn(ALOAD, 0);
        mw.visitFieldInsn(GETFIELD, type(JavaBeanDeserializer.class), "beanInfo", desc(JavaBeanInfo.class));
        mw.visitVarInsn(ALOAD, context.var("typeName"));
        mw.visitMethodInsn(INVOKESTATIC, type(JavaBeanDeserializer.class), "getSeeAlso"
                , "(" + desc(ParserConfig.class) + desc(JavaBeanInfo.class) + "Ljava/lang/String;)" + desc(JavaBeanDeserializer.class));
        mw.visitVarInsn(ASTORE, context.var("userTypeDeser"));
        mw.visitVarInsn(ALOAD, context.var("userTypeDeser"));
        mw.visitTypeInsn(INSTANCEOF, type(JavaBeanDeserializer.class));
        mw.visitJumpInsn(IFEQ, typeNameNotNull_);

        mw.visitVarInsn(ALOAD, context.var("userTypeDeser"));
        mw.visitVarInsn(ALOAD, Context.parser);
        loadVariables(mw);
        mw.visitVarInsn(ALOAD, 4);
        mw.visitMethodInsn(INVOKEVIRTUAL, //
                type(JavaBeanDeserializer.class), //
                "deserialzeArrayMapping", //
                "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        mw.visitInsn(ARETURN);

        mw.visitLabel(typeNameNotNull_);

        _createInstance(context, mw);

        FieldInfo[] sortedFieldInfoList = context.beanInfo.sortedFields;
        int fieldListSize = sortedFieldInfoList.length;
        for (int i = 0;i < fieldListSize;++i)
            parseFieldByType_(context, mw, sortedFieldInfoList, fieldListSize, i);

        _batchSet(context, mw, false);

        Label quickElse_ = new Label();
        Label quickElseIf_ = new Label();
        Label quickElseIfEOI_ = new Label();
        Label quickEnd_ = new Label();
        invokeLexerCurrent(context, mw);
        mw.visitInsn(DUP);
        mw.visitVarInsn(ISTORE, context.var("ch"));
        mw.visitVarInsn(BIPUSH, ',');
        mw.visitJumpInsn(IF_ICMPNE, quickElseIf_);

        invokeLexerNext(context, mw);
        mw.visitLdcInsn(JSONToken.COMMA);
        invokeSetTokenJump(mw, quickEnd_);

        mw.visitLabel(quickElseIf_);
        mw.visitVarInsn(ILOAD, context.var("ch"));
        mw.visitVarInsn(BIPUSH, ']');
        mw.visitJumpInsn(IF_ICMPNE, quickElseIfEOI_);

        invokeLexerNext(context, mw);
        mw.visitLdcInsn(JSONToken.RBRACKET);
        invokeSetTokenJump(mw, quickEnd_);

        mw.visitLabel(quickElseIfEOI_);
        mw.visitVarInsn(ILOAD, context.var("ch"));
        mw.visitVarInsn(BIPUSH, (char) JSONLexer.EOI);
        mw.visitJumpInsn(IF_ICMPNE, quickElse_);

        invokeLexerNext(context, mw);
        mw.visitLdcInsn(JSONToken.EOF);
        invokeSetTokenJump(mw, quickEnd_);

        mw.visitLabel(quickElse_);
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(JSONToken.COMMA);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "nextToken", "(I)V");

        mw.visitLabel(quickEnd_);

        mw.visitVarInsn(ALOAD, context.var("instance"));
        mw.visitInsn(ARETURN);
        mw.visitMaxs(5, context.variantIndex);
        mw.visitEnd();
    }

    private void parseFieldByType_(Context context, MethodVisitor mw, FieldInfo[] sortedFieldInfoList, int fieldListSize,
                                      int i) {
        boolean last = i == fieldListSize - 1;
        char seperator = last ? ']' : ',';
        FieldInfo fieldInfo = sortedFieldInfoList[i];
        Class<?> fieldClass = fieldInfo.fieldClass;
        Type fieldType = fieldInfo.fieldType;
        if (fieldClass == byte.class //
		        || fieldClass == short.class //
		        || fieldClass == int.class) {
            pushLexerSeparator(context, mw, seperator);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanInt", "(C)I");
            mw.visitVarInsn(ISTORE, context.var_asm(fieldInfo));
        }
        else if (fieldClass == Byte.class) {
            invokeScanIntMethod(context, mw, seperator, fieldInfo);
        }
        else if (fieldClass == Short.class) {
            scanShortValue(context, mw, seperator, fieldInfo);
        }
        else if (fieldClass == Integer.class) {
            scanAndStoreInteger(context, mw, seperator, fieldInfo);
        }
        else if (fieldClass == long.class) {
            pushLexerSeparator(context, mw, seperator);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanLong", "(C)J");
            mw.visitVarInsn(LSTORE, context.var_asm(fieldInfo, 2));

        }
        else if (fieldClass == Long.class) {
            invokeScanLongMethod(context, mw, seperator, fieldInfo);
        }
        else if (fieldClass == boolean.class) {
            pushLexerSeparator(context, mw, seperator);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanBoolean", "(C)Z");
            mw.visitVarInsn(ISTORE, context.var_asm(fieldInfo));
        }
        else if (fieldClass == float.class) {
            pushLexerSeparator(context, mw, seperator);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFloat", "(C)F");
            mw.visitVarInsn(FSTORE, context.var_asm(fieldInfo));

        }
        else if (fieldClass == Float.class) {
            invokeScanFloatMethod(context, mw, seperator, fieldInfo);

        }
        else if (fieldClass == double.class) {
            pushLexerSeparator(context, mw, seperator);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanDouble", "(C)D");
            mw.visitVarInsn(DSTORE, context.var_asm(fieldInfo, 2));

        }
        else if (fieldClass == Double.class) {
            invokeScanDoubleMethod(context, mw, seperator, fieldInfo);

        }
        else if (fieldClass == char.class) {
            invokeScanStringMethod(context, mw, seperator, fieldInfo);
        }
        else if (fieldClass == String.class) {
            pushLexerSeparator(context, mw, seperator);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanString", "(C)Ljava/lang/String;");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        }
        else if (fieldClass == BigDecimal.class) {
            pushLexerSeparator(context, mw, seperator);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanDecimal", "(C)Ljava/math/BigDecimal;");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        }
        else if (fieldClass == java.util.Date.class) {
            pushLexerSeparator(context, mw, seperator);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanDate", "(C)Ljava/util/Date;");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        }
        else if (fieldClass == java.util.UUID.class) {
            pushLexerSeparator(context, mw, seperator);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanUUID", "(C)Ljava/util/UUID;");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        }
        else if (fieldClass.isEnum()) {
            parseEnumValue(context, mw, seperator, fieldInfo, fieldClass);
        }
        else if (Collection.class.isAssignableFrom(fieldClass)) {
            
            handleCollectionParsing(context, mw, i, seperator, fieldInfo, fieldClass, fieldType);
        }
        else{
            if (!fieldClass.isArray()) {
                invokeDeserializationWithDateCheck(context, mw, i, last, seperator, fieldInfo, fieldClass);
                return;
            }
            parseAndCastJsonToObject(context, mw, i, fieldInfo, fieldClass);
        }
    }

    private void invokeDeserializationWithDateCheck(Context context, MethodVisitor mw, int i, boolean last, char seperator, FieldInfo fieldInfo,
            Class<?> fieldClass) {
        Label objElseIf_ = new Label();
        Label objEndIf_ = new Label();

        if (fieldClass == java.util.Date.class) {
            invokeDateScanMethod(context, mw, seperator, fieldInfo, objElseIf_, objEndIf_);
        }

        mw.visitLabel(objElseIf_);

        _quickNextToken(context, mw, JSONToken.LBRACKET);

        _deserObject(context, mw, fieldInfo, fieldClass, i);

        invokeLexerAndVisitInsn(context, mw);
        mw.visitJumpInsn(IF_ICMPEQ, objEndIf_);
//                mw.visitInsn(POP);
//                mw.visitInsn(POP);

		mw.visitVarInsn(ALOAD, 0);
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        if (!last) {
            mw.visitLdcInsn(JSONToken.COMMA);
        } else {
            mw.visitLdcInsn(JSONToken.RBRACKET);
        }
        mw.visitMethodInsn(INVOKESPECIAL, //
		                   type(JavaBeanDeserializer.class), //
		                   "check", "(" + desc(JSONLexer.class) + "I)V");

        mw.visitLabel(objEndIf_);
    }

    private void handleCollectionParsing(Context context, MethodVisitor mw, int i, char seperator, FieldInfo fieldInfo,
            Class<?> fieldClass, Type fieldType) {
        Class<?> itemClass = TypeUtils.getCollectionItemClass(fieldType);
        if (itemClass == String.class) {
            createCollectionAndScanStringArray(context, mw, seperator, fieldInfo, fieldClass);
            
        } else {
            parseJsonArray(context, mw, i, fieldInfo, fieldClass, itemClass);
        }
    }

    private void invokeDateScanMethod(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo, Label objElseIf_,
            Label objEndIf_) {
        invokeLexerCurrent(context, mw);
        mw.visitLdcInsn((int) '1');
        mw.visitJumpInsn(IF_ICMPNE, objElseIf_);

        mw.visitTypeInsn(NEW, type(java.util.Date.class));
        mw.visitInsn(DUP);

        pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanLong", "(C)J");

        mw.visitMethodInsn(INVOKESPECIAL, type(java.util.Date.class), "<init>", "(J)V");
        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        mw.visitJumpInsn(GOTO, objEndIf_);
    }

    private void parseAndCastJsonToObject(Context context, MethodVisitor mw, int i, FieldInfo fieldInfo, Class<?> fieldClass) {
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(com.alibaba.fastjson.parser.JSONToken.LBRACKET);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "nextToken", "(I)V");

        mw.visitVarInsn(ALOAD, Context.parser);
        mw.visitVarInsn(ALOAD, 0);
        mw.visitLdcInsn(i);
        mw.visitMethodInsn(INVOKEVIRTUAL, type(JavaBeanDeserializer.class), "getFieldType",
                           "(I)Ljava/lang/reflect/Type;");
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "parseObject",
                           "(Ljava/lang/reflect/Type;)Ljava/lang/Object;");

        mw.visitTypeInsn(CHECKCAST, type(fieldClass)); // cast
		mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
    }

    private void parseJsonArray(Context context, MethodVisitor mw, int i, FieldInfo fieldInfo, Class<?> fieldClass,
            Class<?> itemClass) {
        Label notError_ = new Label();
        invokeLexerTokenMethod(context, mw);
        mw.visitVarInsn(ISTORE, context.var("token"));

        mw.visitVarInsn(ILOAD, context.var("token"));
        int token = i == 0 ? JSONToken.LBRACKET : JSONToken.COMMA;
        mw.visitLdcInsn(token);
        mw.visitJumpInsn(IF_ICMPEQ, notError_);

        mw.visitVarInsn(ALOAD, 1); // DefaultJSONParser
		mw.visitLdcInsn(token);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "throwException", "(I)V");

        mw.visitLabel(notError_);

        Label quickElse_ = new Label();
        Label quickEnd_ = new Label();
        invokeLexerCurrent(context, mw);
        mw.visitVarInsn(BIPUSH, '[');
        mw.visitJumpInsn(IF_ICMPNE, quickElse_);

        invokeLexerNext(context, mw);
        mw.visitLdcInsn(JSONToken.LBRACKET);
        invokeSetTokenJump(mw, quickEnd_);

        mw.visitLabel(quickElse_);
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(JSONToken.LBRACKET);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "nextToken", "(I)V");
        mw.visitLabel(quickEnd_);

        _newCollection(mw, fieldClass, i, false);
        mw.visitInsn(DUP);
        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        _getCollectionFieldItemDeser(context, mw, fieldInfo, itemClass);
        visitInsnType(mw, itemClass);
        mw.visitVarInsn(ALOAD, 3);
        mw.visitMethodInsn(INVOKESTATIC, type(JavaBeanDeserializer.class),
                           "parseArray",
                           "(Ljava/util/Collection;" //
		                                 + desc(ObjectDeserializer.class) //
		                                 + "L" + DefaultJSONParser + ";" //
		                                 + "Ljava/lang/reflect/Type;Ljava/lang/Object;)V");
    }

    private void createCollectionAndScanStringArray(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo,
            Class<?> fieldClass) {
        if (fieldClass == List.class
                || fieldClass == Collections.class
                || fieldClass == ArrayList.class
        ) {
            mw.visitTypeInsn(NEW, type(ArrayList.class));
            mw.visitInsn(DUP);
            mw.visitMethodInsn(INVOKESPECIAL, type(ArrayList.class), "<init>", "()V");
        } else {
            mw.visitLdcInsn(com.alibaba.fastjson.asm.Type.getType(desc(fieldClass)));
            mw.visitMethodInsn(INVOKESTATIC, type(TypeUtils.class), "createCollection",
                               "(Ljava/lang/Class;)Ljava/util/Collection;");
        }
        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitVarInsn(ALOAD, context.var_asm(fieldInfo));
        mw.visitVarInsn(BIPUSH, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanStringArray", "(Ljava/util/Collection;C)V");
        
        checkValueNull(context, mw, fieldInfo);
    }

    private void parseEnumValue(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo,
            Class<?> fieldClass) {
        Label enumNumIf_ = new Label();
        Label enumNumErr_ = new Label();
        Label enumStore_ = new Label();
        Label enumQuote_ = new Label();

        invokeLexerCurrent(context, mw);
        mw.visitInsn(DUP);
        mw.visitVarInsn(ISTORE, context.var("ch"));
        mw.visitLdcInsn((int) 'n');
        mw.visitJumpInsn(IF_ICMPEQ, enumQuote_);

        mw.visitVarInsn(ILOAD, context.var("ch"));
        mw.visitLdcInsn((int) '\"');
        mw.visitJumpInsn(IF_ICMPNE, enumNumIf_);

        mw.visitLabel(enumQuote_);
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(com.alibaba.fastjson.asm.Type.getType(desc(fieldClass)));
        mw.visitVarInsn(ALOAD, 1);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getSymbolTable", "()" + desc(SymbolTable.class));
        mw.visitVarInsn(BIPUSH, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanEnum",
                           "(Ljava/lang/Class;" + desc(SymbolTable.class) + "C)Ljava/lang/Enum;");
        mw.visitJumpInsn(GOTO, enumStore_);

        // (ch >= '0' && ch <= '9') {
		mw.visitLabel(enumNumIf_);
        mw.visitVarInsn(ILOAD, context.var("ch"));
        mw.visitLdcInsn((int) '0');
        mw.visitJumpInsn(IF_ICMPLT, enumNumErr_);

        mw.visitVarInsn(ILOAD, context.var("ch"));
        mw.visitLdcInsn((int) '9');
        mw.visitJumpInsn(IF_ICMPGT, enumNumErr_);

        _getFieldDeser(context, mw, fieldInfo);
        mw.visitTypeInsn(CHECKCAST, type(EnumDeserializer.class)); // cast
		pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanInt", "(C)I");
        mw.visitMethodInsn(INVOKEVIRTUAL, type(EnumDeserializer.class), "valueOf", "(I)Ljava/lang/Enum;");
        mw.visitJumpInsn(GOTO, enumStore_);

        mw.visitLabel(enumNumErr_);
        mw.visitVarInsn(ALOAD, 0);
        pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, type(JavaBeanDeserializer.class), "scanEnum",
                           "(L" + JSONLexerBase + ";C)Ljava/lang/Enum;");

        mw.visitLabel(enumStore_);
        mw.visitTypeInsn(CHECKCAST, type(fieldClass)); // cast
		mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
    }

    private void invokeScanStringMethod(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo) {
        pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanString", "(C)Ljava/lang/String;");
        mw.visitInsn(ICONST_0);
        mw.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C");
        mw.visitVarInsn(ISTORE, context.var_asm(fieldInfo));
    }

    private void invokeScanDoubleMethod(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo) {
        pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanDouble", "(C)D");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void invokeScanFloatMethod(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo) {
        pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFloat", "(C)F");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void invokeScanLongMethod(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo) {
        pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanLong", "(C)J");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void scanAndStoreInteger(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo) {
        pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanInt", "(C)I");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void scanShortValue(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo) {
        pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanInt", "(C)I");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void invokeScanIntMethod(Context context, MethodVisitor mw, char seperator, FieldInfo fieldInfo) {
        pushLexerSeparator(context, mw, seperator);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanInt", "(C)I");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void invokeSetTokenJump(MethodVisitor mw, Label quickEnd_) {
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "setToken", "(I)V");
        mw.visitJumpInsn(GOTO, quickEnd_);
    }

    private void invokeLexerNext(Context context, MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "next", "()C");
        mw.visitInsn(POP);
        mw.visitVarInsn(ALOAD, context.var("lexer"));
    }

    private void invokeLexerCurrent(Context context, MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "getCurrent", "()C");
    }

    private void checkValueNull(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        Label valueNullEnd_ = new Label();
        getLexerMatchStatus(context, mw);
        mw.visitLdcInsn(com.alibaba.fastjson.parser.JSONLexerBase.VALUE_NULL);
        mw.visitJumpInsn(IF_ICMPNE, valueNullEnd_);
        mw.visitInsn(ACONST_NULL);
        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        mw.visitLabel(valueNullEnd_);
    }

    private void pushLexerSeparator(Context context, MethodVisitor mw, char seperator) {
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitVarInsn(BIPUSH, seperator);
    }

    private void _deserialze(ClassWriter cw, Context context) {
        if (context.fieldInfoList.length == 0) {
            return;
        }

        for (FieldInfo fieldInfo : context.fieldInfoList) {
            Class<?> fieldClass = fieldInfo.fieldClass;
            Type fieldType = fieldInfo.fieldType;

            if (fieldClass == char.class) {
                return;
            }

            if (Collection.class.isAssignableFrom(fieldClass)) {
                if (!(fieldType instanceof ParameterizedType))
                    return;
                Type itemType = ((ParameterizedType) fieldType).getActualTypeArguments()[0];
                if (!(itemType instanceof Class))
                    return;
                continue;
            }
        }

        JavaBeanInfo beanInfo = context.beanInfo;
        context.fieldInfoList = beanInfo.sortedFields;

        MethodVisitor mw = new MethodWriter(cw, ACC_PUBLIC, "deserialze",
                "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;Ljava/lang/Object;I)Ljava/lang/Object;",
                null, null);

        Label reset_ = new Label();
        Label super_ = new Label();
        Label return_ = new Label();
        Label end_ = new Label();

        defineVarLexer(context, mw);

        {
            invokeArrayToBeanDeserialization(context, beanInfo, mw);
        }

        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(Feature.SortFeidFastMatch.mask);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "isEnabled", "(I)Z");

        Label continue_ = new Label();
        mw.visitJumpInsn(IFNE, continue_);
        mw.visitJumpInsn(GOTO_W, super_);
        mw.visitLabel(continue_);

        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(context.clazz.getName());
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanType", "(Ljava/lang/String;)I");

        mw.visitLdcInsn(com.alibaba.fastjson.parser.JSONLexerBase.NOT_MATCH);

        Label continue_2 = new Label();
        mw.visitJumpInsn(IF_ICMPNE, continue_2);
        mw.visitJumpInsn(GOTO_W, super_);
        mw.visitLabel(continue_2);

        mw.visitVarInsn(ALOAD, 1); // parser
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getContext", "()" + desc(ParseContext.class));
        mw.visitVarInsn(ASTORE, context.var("mark_context"));

        // ParseContext context = parser.getContext();
        mw.visitInsn(ICONST_0);
        mw.visitVarInsn(ISTORE, context.var("matchedCount"));

        _createInstance(context, mw);

        {
            setParserContext(context, mw);
        }

        invokeLexerStatusCheck(context, mw);

        Label continue_3 = new Label();
        mw.visitJumpInsn(IF_ICMPNE, continue_3);
        mw.visitJumpInsn(GOTO_W, return_);
        mw.visitLabel(continue_3);

        mw.visitInsn(ICONST_0); // UNKOWN
        mw.visitIntInsn(ISTORE, context.var("matchStat"));

        int fieldListSize = context.fieldInfoList.length;
        for (int i = 0;i < fieldListSize;i += 32) {
            mw.visitInsn(ICONST_0);
            mw.visitVarInsn(ISTORE, context.var("_asm_flag_" + (i / 32)));
        }

        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(Feature.InitStringFieldAsEmpty.mask);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "isEnabled", "(I)Z");
        mw.visitIntInsn(ISTORE, context.var("initStringFieldAsEmpty"));

        // declare and init
        for (int i = 0;i < fieldListSize;++i) {
            initializeFieldClassType(context, mw, i);
        }

        for (int i = 0;i < fieldListSize;++i)
            parseFieldByType(context, mw, reset_, end_, fieldListSize, i); // endFor

        mw.visitLabel(end_);

        if (!context.clazz.isInterface() && !Modifier.isAbstract(context.clazz.getModifiers())) {
            _batchSet(context, mw);
        }

        mw.visitLabel(return_);

        _setContext(context, mw);
        mw.visitVarInsn(ALOAD, context.var("instance"));

        Method buildMethod = context.beanInfo.buildMethod;
        if (buildMethod != null) {
            mw.visitMethodInsn(INVOKEVIRTUAL, type(context.getInstClass()), buildMethod.getName(),
                    "()" + desc(buildMethod.getReturnType()));
        }

        mw.visitInsn(ARETURN);

        mw.visitLabel(reset_);

        _batchSet(context, mw);
        loadMethodVisitorVariables(mw);
        mw.visitVarInsn(ALOAD, context.var("instance"));
        mw.visitVarInsn(ILOAD, 4);


        int flagSize = fieldListSize / 32;

        if (fieldListSize != 0 && (fieldListSize % 32) != 0) {
            flagSize += 1;
        }

        if (flagSize == 1) {
            mw.visitInsn(ICONST_1);
        }
        else {
            mw.visitIntInsn(BIPUSH, flagSize);
        }
        mw.visitIntInsn(NEWARRAY, T_INT);
        for (int i = 0;i < flagSize;++i) {
            pushArrayElement(context, mw, i);
        }

        mw.visitMethodInsn(INVOKEVIRTUAL, type(JavaBeanDeserializer.class),
                "parseRest", "(L" + DefaultJSONParser
                + ";Ljava/lang/reflect/Type;Ljava/lang/Object;Ljava/lang/Object;I[I)Ljava/lang/Object;");
        mw.visitTypeInsn(CHECKCAST, type(context.clazz)); // cast
        mw.visitInsn(ARETURN);

        mw.visitLabel(super_);
        loadMethodVisitorVariables(mw);
        mw.visitVarInsn(ILOAD, 4);
        mw.visitMethodInsn(INVOKESPECIAL, type(JavaBeanDeserializer.class), //
                "deserialze", //
                "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;Ljava/lang/Object;I)Ljava/lang/Object;");
        mw.visitInsn(ARETURN);

        mw.visitMaxs(10, context.variantIndex);
        mw.visitEnd();

    }

	private void loadMethodVisitorVariables(MethodVisitor mw) {
		mw.visitVarInsn(ALOAD, 0);
        mw.visitVarInsn(ALOAD, 1);
        loadVariables(mw);
	}

    private void initializeFieldClassType(Context context, MethodVisitor mw, int i) {
        FieldInfo fieldInfo = context.fieldInfoList[i];
        Class<?> fieldClass = fieldInfo.fieldClass;

        if (fieldClass == boolean.class //
		    || fieldClass == byte.class //
		    || fieldClass == short.class //
		    || fieldClass == int.class) {
            mw.visitInsn(ICONST_0);
            mw.visitVarInsn(ISTORE, context.var_asm(fieldInfo));
        } else if (fieldClass == long.class) {
            mw.visitInsn(LCONST_0);
            mw.visitVarInsn(LSTORE, context.var_asm(fieldInfo, 2));
        } else if (fieldClass == float.class) {
            mw.visitInsn(FCONST_0);
            mw.visitVarInsn(FSTORE, context.var_asm(fieldInfo));
        } else if (fieldClass == double.class) {
            mw.visitInsn(DCONST_0);
            mw.visitVarInsn(DSTORE, context.var_asm(fieldInfo, 2));
        } else {
            setFieldClassType(context, mw, i, fieldInfo, fieldClass);
        }
    }

    private void parseFieldByType(Context context, MethodVisitor mw, Label reset_, Label end_, int fieldListSize, int i) {
        FieldInfo fieldInfo = context.fieldInfoList[i];
        Class<?> fieldClass = fieldInfo.fieldClass;
        Type fieldType = fieldInfo.fieldType;
        Label notMatch_ = new Label();
        if (fieldClass == boolean.class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldBoolean", "([C)Z");
            mw.visitVarInsn(ISTORE, context.var_asm(fieldInfo));
        }
        else if (fieldClass == byte.class)
			invokeScanFieldIntMethod__(context, mw, fieldInfo);
		else if (fieldClass == Byte.class) {
            invokeScanFieldByteMethod(context, mw, fieldInfo);

        }
        else if (fieldClass == short.class)
			invokeScanFieldIntMethod__(context, mw, fieldInfo);
		else if (fieldClass == Short.class) {
            invokeScanFieldIntMethod(context, mw, fieldInfo);

        }
        else if (fieldClass == int.class)
			invokeScanFieldIntMethod__(context, mw, fieldInfo);
		else if (fieldClass == Integer.class) {
            invokeScanFieldIntMethod_(context, mw, fieldInfo);

        }
        else if (fieldClass == long.class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldLong", "([C)J");
            mw.visitVarInsn(LSTORE, context.var_asm(fieldInfo, 2));

        }
        else if (fieldClass == Long.class) {
            invokeScanLongFieldMethod(context, mw, fieldInfo);

        }
        else if (fieldClass == float.class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldFloat", "([C)F");
            mw.visitVarInsn(FSTORE, context.var_asm(fieldInfo));

        }
        else if (fieldClass == Float.class) {
            invokeScanFloatMethod_(context, mw, fieldInfo);
        }
        else if (fieldClass == double.class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldDouble", "([C)D");
            mw.visitVarInsn(DSTORE, context.var_asm(fieldInfo, 2));

        }
        else if (fieldClass == Double.class) {
            invokeScanDoubleMethod_(context, mw, fieldInfo);
        }
        else if (fieldClass == String.class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldString", "([C)Ljava/lang/String;");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        }
        else if (fieldClass == java.util.Date.class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldDate", "([C)Ljava/util/Date;");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        }
        else if (fieldClass == java.util.UUID.class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldUUID", "([C)Ljava/util/UUID;");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        }
        else if (fieldClass == BigDecimal.class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldDecimal", "([C)Ljava/math/BigDecimal;");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        }
        else if (fieldClass == BigInteger.class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldBigInteger", "([C)Ljava/math/BigInteger;");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        }
        else if (fieldClass == int[].class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldIntArray", "([C)[I");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        }
        else if (fieldClass == float[].class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldFloatArray", "([C)[F");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        }
        else if (fieldClass == float[][].class) {
            loadLexerField(context, mw, fieldInfo);
            mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldFloatArray2", "([C)[[F");
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        }
        else if (fieldClass.isEnum()) {
            deserializeEnumField(context, mw, fieldInfo, fieldClass);
        }
        else{
            if (!Collection.class.isAssignableFrom(fieldClass)) {
                deserializeFieldInfo(context, mw, reset_, fieldListSize, i, fieldInfo, fieldClass);

                return;
            }
            loadLexerField(context, mw, fieldInfo);

            Class<?> itemClass = TypeUtils.getCollectionItemClass(fieldType);

            if (itemClass != String.class) {
                deserializeFieldList(context, mw, reset_, fieldListSize, i, fieldInfo, fieldClass, itemClass);
                return;
            }
            mw.visitLdcInsn(com.alibaba.fastjson.asm.Type.getType(desc(fieldClass))); // cast
		    mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldStringArray",
                    "([CLjava/lang/Class;)" + desc(Collection.class));
            mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        }
        getLexerMatchStatus(context, mw);
        Label flag_ = new Label();
        // mw.visitInsn(DUP);
		mw.visitJumpInsn(IFLE, flag_);
        _setFlag(mw, context, i);
        mw.visitLabel(flag_);
        getLexerMatchStatus(context, mw);
        mw.visitInsn(DUP);
        mw.visitVarInsn(ISTORE, context.var("matchStat"));
        mw.visitLdcInsn(com.alibaba.fastjson.parser.JSONLexerBase.NOT_MATCH);
        mw.visitJumpInsn(IF_ICMPEQ, reset_);
        getLexerMatchStatus(context, mw);
        mw.visitJumpInsn(IFLE, notMatch_);
        // increment matchedCount
		mw.visitVarInsn(ILOAD, context.var("matchedCount"));
        mw.visitInsn(ICONST_1);
        mw.visitInsn(IADD);
        mw.visitVarInsn(ISTORE, context.var("matchedCount"));
        invokeLexerStatusCheck(context, mw);
        mw.visitJumpInsn(IF_ICMPEQ, end_);
        mw.visitLabel(notMatch_);
        if (i == fieldListSize - 1) {
            invokeLexerStatusCheck(context, mw);
            mw.visitJumpInsn(IF_ICMPNE, reset_);
        }
    }

	private void invokeLexerStatusCheck(Context context, MethodVisitor mw) {
		getLexerMatchStatus(context, mw);
        mw.visitLdcInsn(com.alibaba.fastjson.parser.JSONLexerBase.END);
	}

	private void invokeScanFieldIntMethod__(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
		loadLexerField(context, mw, fieldInfo);
		invokeScanFieldInt(context, mw, fieldInfo);
	}

    private void deserializeFieldInfo(Context context, MethodVisitor mw, Label reset_, int fieldListSize, int i,
            FieldInfo fieldInfo, Class<?> fieldClass) {
        _deserialze_obj(context, mw, reset_, fieldInfo, fieldClass, i);

        if (i == fieldListSize - 1) {
            _deserialize_endCheck(context, mw, reset_);
        }
    }

    private void deserializeFieldList(Context context, MethodVisitor mw, Label reset_, int fieldListSize, int i,
            FieldInfo fieldInfo, Class<?> fieldClass, Class<?> itemClass) {
        _deserialze_list_obj(context, mw, reset_, fieldInfo, fieldClass, itemClass, i);

        if (i == fieldListSize - 1) {
            _deserialize_endCheck(context, mw, reset_);
        }
    }

    private void setFieldClassType(Context context, MethodVisitor mw, int i, FieldInfo fieldInfo, Class<?> fieldClass) {
        if (fieldClass == String.class) {
            setStringFieldDefault(context, mw, i);
        } else {
            mw.visitInsn(ACONST_NULL);
        }

        mw.visitTypeInsn(CHECKCAST, type(fieldClass)); // cast
		mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
    }

    private void invokeArrayToBeanDeserialization(Context context, JavaBeanInfo beanInfo, MethodVisitor mw) {
        Label next_ = new Label();

        // isSupportArrayToBean

		invokeLexerTokenMethod(context, mw);
        mw.visitLdcInsn(JSONToken.LBRACKET);
        mw.visitJumpInsn(IF_ICMPNE, next_);

        if ((beanInfo.parserFeatures & Feature.SupportArrayToBean.mask) == 0) {
            checkLexerFeatureSupport(context, mw, next_);
        }

        mw.visitVarInsn(ALOAD, 0);
        mw.visitVarInsn(ALOAD, Context.parser);
        loadVariables(mw);
        mw.visitInsn(ACONST_NULL); //mw.visitVarInsn(ALOAD, 5);
		mw.visitMethodInsn(INVOKESPECIAL, //
		                   context.className, //
		                   "deserialzeArrayMapping", //
		                   "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        mw.visitInsn(ARETURN);

        mw.visitLabel(next_);
        // deserialzeArrayMapping
	}

    private void pushArrayElement(Context context, MethodVisitor mw, int i) {
        mw.visitInsn(DUP);
        if (i == 0) {
            mw.visitInsn(ICONST_0);
        } else if (i == 1) {
            mw.visitInsn(ICONST_1);
        } else {
            mw.visitIntInsn(BIPUSH, i);
        }
        mw.visitVarInsn(ILOAD, context.var("_asm_flag_" + i));
        mw.visitInsn(IASTORE);
    }

    private void deserializeEnumField(Context context, MethodVisitor mw, FieldInfo fieldInfo, Class<?> fieldClass) {
        mw.visitVarInsn(ALOAD, 0);
        loadLexerField(context, mw, fieldInfo);
        _getFieldDeser(context, mw, fieldInfo);
        mw.visitMethodInsn(INVOKEVIRTUAL, type(JavaBeanDeserializer.class), "scanEnum"
                , "(L" + JSONLexerBase + ";[C" + desc(ObjectDeserializer.class) + ")Ljava/lang/Enum;");
        mw.visitTypeInsn(CHECKCAST, type(fieldClass)); // cast
		mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

//            } else if (fieldClass.isEnum()) {
//                mw.visitVarInsn(ALOAD, context.var("lexer"));
//                mw.visitVarInsn(ALOAD, 0);
//                mw.visitFieldInsn(GETFIELD, context.className, context.fieldName(fieldInfo), "[C");
//                Label enumNull_ = new Label();
//                mw.visitInsn(ACONST_NULL);
//                mw.visitTypeInsn(CHECKCAST, type(fieldClass)); // cast
//                mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
//
//                mw.visitVarInsn(ALOAD, 1);
//
//                mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getSymbolTable", "()" + desc(SymbolTable.class));
//
//                mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldSymbol",
//                        "([C" + desc(SymbolTable.class) + ")Ljava/lang/String;");
//                mw.visitInsn(DUP);
//                mw.visitVarInsn(ASTORE, context.var(fieldInfo.name + "_asm_enumName"));
//
//                mw.visitJumpInsn(IFNULL, enumNull_);
//
//                mw.visitVarInsn(ALOAD, context.var(fieldInfo.name + "_asm_enumName"));
//                mw.visitMethodInsn(INVOKEVIRTUAL, type(String.class), "length", "()I");
//                mw.visitJumpInsn(IFEQ, enumNull_);
//
//                mw.visitVarInsn(ALOAD, context.var(fieldInfo.name + "_asm_enumName"));
//                mw.visitMethodInsn(INVOKESTATIC, type(fieldClass), "valueOf",
//                        "(Ljava/lang/String;)" + desc(fieldClass));
//                mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
//                mw.visitLabel(enumNull_);
	}

    private void invokeScanDoubleMethod_(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        loadLexerField(context, mw, fieldInfo);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldDouble", "([C)D");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void invokeScanFloatMethod_(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        loadLexerField(context, mw, fieldInfo);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldFloat", "([C)F");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void invokeScanLongFieldMethod(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        loadLexerField(context, mw, fieldInfo);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldLong", "([C)J");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void invokeScanFieldIntMethod_(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        loadLexerField(context, mw, fieldInfo);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldInt", "([C)I");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void invokeScanFieldIntMethod(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        loadLexerField(context, mw, fieldInfo);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldInt", "([C)I");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void invokeScanFieldByteMethod(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        loadLexerField(context, mw, fieldInfo);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldInt", "([C)I");
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;");

        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        checkValueNull(context, mw, fieldInfo);
    }

    private void setStringFieldDefault(Context context, MethodVisitor mw, int i) {
        Label flagEnd_ = new Label();
        Label flagElse_ = new Label();
        mw.visitVarInsn(ILOAD, context.var("initStringFieldAsEmpty"));
        mw.visitJumpInsn(IFEQ, flagElse_);
        _setFlag(mw, context, i);
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "stringDefaultValue", "()Ljava/lang/String;");
        mw.visitJumpInsn(GOTO, flagEnd_);

        mw.visitLabel(flagElse_);
        mw.visitInsn(ACONST_NULL);

        mw.visitLabel(flagEnd_);
    }

    private void setParserContext(Context context, MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, 1); // parser
		mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getContext", "()" + desc(ParseContext.class));
        mw.visitVarInsn(ASTORE, context.var("context"));

        mw.visitVarInsn(ALOAD, 1); // parser
		mw.visitVarInsn(ALOAD, context.var("context"));
        mw.visitVarInsn(ALOAD, context.var("instance"));
        mw.visitVarInsn(ALOAD, 3); // fieldName
		mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "setContext", //
		                   "(" + desc(ParseContext.class) + "Ljava/lang/Object;Ljava/lang/Object;)"
                                                                           + desc(ParseContext.class));
        mw.visitVarInsn(ASTORE, context.var("childContext"));
    }

    private void checkLexerFeatureSupport(Context context, MethodVisitor mw, Label next_) {
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitVarInsn(ILOAD, 4);
        mw.visitLdcInsn(Feature.SupportArrayToBean.mask);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "isEnabled", "(II)Z");
        mw.visitJumpInsn(IFEQ, next_);
    }

    private void invokeScanFieldInt(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "scanFieldInt", "([C)I");
        mw.visitVarInsn(ISTORE, context.var_asm(fieldInfo));
    }

    private void loadLexerField(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitVarInsn(ALOAD, 0);
        mw.visitFieldInsn(GETFIELD, context.className, context.fieldName(fieldInfo), "[C");
    }

    private void getLexerMatchStatus(Context context, MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitFieldInsn(GETFIELD, JSONLexerBase, "matchStat", "I");
    }

    private void loadVariables(MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, 2);
        mw.visitVarInsn(ALOAD, 3);
    }

    private void defineVarLexer(Context context, MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, 1);
        mw.visitFieldInsn(GETFIELD, DefaultJSONParser, "lexer", desc(JSONLexer.class));
        mw.visitTypeInsn(CHECKCAST, JSONLexerBase); // cast
        mw.visitVarInsn(ASTORE, context.var("lexer"));
    }

    private void _createInstance(Context context, MethodVisitor mw) {
        JavaBeanInfo beanInfo = context.beanInfo;
        Constructor<?> defaultConstructor = beanInfo.defaultConstructor;
        if (Modifier.isPublic(defaultConstructor.getModifiers())) {
            mw.visitTypeInsn(NEW, type(context.getInstClass()));
            mw.visitInsn(DUP);

            mw.visitMethodInsn(INVOKESPECIAL, type(defaultConstructor.getDeclaringClass()), "<init>", "()V");
        } else {
            invokeCreateInstanceMethod(context, mw);
        }

        mw.visitVarInsn(ASTORE, context.var("instance"));
    }

    private void invokeCreateInstanceMethod(Context context, MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, 0);
        mw.visitVarInsn(ALOAD, 1);
        mw.visitVarInsn(ALOAD, 0);
        mw.visitFieldInsn(GETFIELD, type(JavaBeanDeserializer.class), "clazz", "Ljava/lang/Class;");
        mw.visitMethodInsn(INVOKESPECIAL, type(JavaBeanDeserializer.class), "createInstance",
                           "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;)Ljava/lang/Object;");
        mw.visitTypeInsn(CHECKCAST, type(context.getInstClass())); // cast
	}

    private void _batchSet(Context context, MethodVisitor mw) {
        _batchSet(context, mw, true);
    }

    private void _batchSet(Context context, MethodVisitor mw, boolean flag) {
        for (int i = 0, size = context.fieldInfoList.length;i < size;++i) {
            handleFlagAndLoadField(context, mw, flag, i);
        }
    }

    private void handleFlagAndLoadField(Context context, MethodVisitor mw, boolean flag, int i) {
        Label notSet_ = new Label();

        if (flag) {
            _isFlag(mw, context, i, notSet_);
        }

        FieldInfo fieldInfo = context.fieldInfoList[i];
        _loadAndSet(context, mw, fieldInfo);

        if (flag) {
            mw.visitLabel(notSet_);
        }
    }

    private void _loadAndSet(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        Class<?> fieldClass = fieldInfo.fieldClass;
        Type fieldType = fieldInfo.fieldType;

        if (fieldClass == boolean.class)
            setFieldInstance(context, mw, fieldInfo);
        else if (fieldClass == byte.class //
                   || fieldClass == short.class //
                   || fieldClass == int.class //
                   || fieldClass == char.class)
            setFieldInstance(context, mw, fieldInfo);
        else if (fieldClass == long.class) {
            invokeFieldInfoMethod(context, mw, fieldInfo);
        } else if (fieldClass == float.class) {
            mw.visitVarInsn(ALOAD, context.var("instance"));
            mw.visitVarInsn(FLOAD, context.var_asm(fieldInfo));
            _set(context, mw, fieldInfo);
        } else if (fieldClass == double.class) {
            mw.visitVarInsn(ALOAD, context.var("instance"));
            mw.visitVarInsn(DLOAD, context.var_asm(fieldInfo, 2));
            _set(context, mw, fieldInfo);
        } else if (fieldClass == String.class)
			setFieldInstanceContext(context, mw, fieldInfo);
		else if (fieldClass.isEnum())
			setFieldInstanceContext(context, mw, fieldInfo);
		else if (Collection.class.isAssignableFrom(fieldClass)) {
            castAndSetFieldValue(context, mw, fieldInfo, fieldClass, fieldType);

        } else
			setFieldInstanceContext(context, mw, fieldInfo);
    }

	private void setFieldInstanceContext(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
		mw.visitVarInsn(ALOAD, context.var("instance"));
		setFieldContext(context, mw, fieldInfo);
	}

    private void invokeFieldInfoMethod(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        mw.visitVarInsn(ALOAD, context.var("instance"));
        mw.visitVarInsn(LLOAD, context.var_asm(fieldInfo, 2));
        if (fieldInfo.method != null) {
            invokeFieldMethod(context, mw, fieldInfo);
        } else {
            mw.visitFieldInsn(PUTFIELD, type(fieldInfo.declaringClass), fieldInfo.field.getName(),
                              desc(fieldInfo.fieldClass));
        }
    }

    private void castAndSetFieldValue(Context context, MethodVisitor mw, FieldInfo fieldInfo, Class<?> fieldClass,
            Type fieldType) {
        mw.visitVarInsn(ALOAD, context.var("instance"));
        Type itemType = TypeUtils.getCollectionItemClass(fieldType);
        if (itemType == String.class) {
            mw.visitVarInsn(ALOAD, context.var_asm(fieldInfo));
            mw.visitTypeInsn(CHECKCAST, type(fieldClass)); // cast
		} else {
            mw.visitVarInsn(ALOAD, context.var_asm(fieldInfo));
        }
        _set(context, mw, fieldInfo);
    }

    private void invokeFieldMethod(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        mw.visitMethodInsn(INVOKEVIRTUAL, type(context.getInstClass()), fieldInfo.method.getName(),
                           desc(fieldInfo.method));
        if (!fieldInfo.method.getReturnType().equals(Void.TYPE)) {
            mw.visitInsn(POP);
        }
    }

    private void setFieldContext(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        mw.visitVarInsn(ALOAD, context.var_asm(fieldInfo));
        _set(context, mw, fieldInfo);
    }

    private void setFieldInstance(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        mw.visitVarInsn(ALOAD, context.var("instance"));
        mw.visitVarInsn(ILOAD, context.var_asm(fieldInfo));
        _set(context, mw, fieldInfo);
    }

    private void _set(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        Method method = fieldInfo.method;
        if (method != null) {
            invokeMethodInstruction(mw, fieldInfo, method);
        } else {
            mw.visitFieldInsn(PUTFIELD, type(fieldInfo.declaringClass), fieldInfo.field.getName(),
                              desc(fieldInfo.fieldClass));
        }
    }

    private void invokeMethodInstruction(MethodVisitor mw, FieldInfo fieldInfo, Method method) {
        Class<?> declaringClass = method.getDeclaringClass();
        mw.visitMethodInsn(declaringClass.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL, type(fieldInfo.declaringClass), method.getName(), desc(method));

        if (!fieldInfo.method.getReturnType().equals(Void.TYPE)) {
            mw.visitInsn(POP);
        }
    }

    private void _setContext(Context context, MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, 1); // parser
        mw.visitVarInsn(ALOAD, context.var("context"));
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "setContext", "(" + desc(ParseContext.class) + ")V");

        Label endIf_ = new Label();
        mw.visitVarInsn(ALOAD, context.var("childContext"));
        mw.visitJumpInsn(IFNULL, endIf_);

        mw.visitVarInsn(ALOAD, context.var("childContext"));
        mw.visitVarInsn(ALOAD, context.var("instance"));
        mw.visitFieldInsn(PUTFIELD, type(ParseContext.class), "object", "Ljava/lang/Object;");

        mw.visitLabel(endIf_);
    }

    private void _deserialize_endCheck(Context context, MethodVisitor mw, Label reset_) {
        mw.visitIntInsn(ILOAD, context.var("matchedCount"));
        mw.visitJumpInsn(IFLE, reset_);

        invokeLexerTokenMethod(context, mw);
        mw.visitLdcInsn(JSONToken.RBRACE);
        mw.visitJumpInsn(IF_ICMPNE, reset_);

        // mw.visitLabel(nextToken_);
        _quickNextTokenComma(context, mw);
    }

    private void _deserialze_list_obj(Context context, MethodVisitor mw, Label reset_, FieldInfo fieldInfo,
                                      Class<?> fieldClass, Class<?> itemType, int i) {
        Label _end_if = new Label();

        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "matchField", "([C)Z");
        mw.visitJumpInsn(IFEQ, _end_if);

        _setFlag(mw, context, i);

        Label valueNotNull_ = new Label();
        invokeLexerTokenMethod(context, mw);
        mw.visitLdcInsn(JSONToken.NULL);
        mw.visitJumpInsn(IF_ICMPNE, valueNotNull_);

        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(JSONToken.COMMA);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "nextToken", "(I)V");
        mw.visitJumpInsn(GOTO, _end_if);
        // loop_end_

        mw.visitLabel(valueNotNull_);

        Label storeCollection_ = new Label();
        Label endSet_ = new Label();
        Label lbacketNormal_ = new Label();
        invokeLexerTokenMethod(context, mw);
        mw.visitLdcInsn(JSONToken.SET);
        mw.visitJumpInsn(IF_ICMPNE, endSet_);

        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(JSONToken.LBRACKET);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "nextToken", "(I)V");

        _newCollection(mw, fieldClass, i, true);

        mw.visitJumpInsn(GOTO, storeCollection_);

        mw.visitLabel(endSet_);

        invokeLexerTokenMethod(context, mw);
        mw.visitLdcInsn(JSONToken.LBRACKET);
        mw.visitJumpInsn(IF_ICMPEQ, lbacketNormal_);

        invokeLexerTokenMethod(context, mw);
        mw.visitLdcInsn(JSONToken.LBRACE);
        mw.visitJumpInsn(IF_ICMPNE, reset_);

        _newCollection(mw, fieldClass, i, false);
        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        _getCollectionFieldItemDeser(context, mw, fieldInfo, itemType);
        visitInsnType(mw, itemType);
        mw.visitInsn(ICONST_0);
        invokeDeserializationMethods(context, mw);

        addListItem(context, mw, fieldInfo, fieldClass);

        mw.visitJumpInsn(GOTO, _end_if);

        mw.visitLabel(lbacketNormal_);

        _newCollection(mw, fieldClass, i, false);

        mw.visitLabel(storeCollection_);
        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        boolean isPrimitive = ParserConfig.isPrimitive2(fieldInfo.fieldClass);
        _getCollectionFieldItemDeser(context, mw, fieldInfo, itemType);
        if (isPrimitive) {
            mw.visitMethodInsn(INVOKEINTERFACE, type(ObjectDeserializer.class), "getFastMatchToken", "()I");
            mw.visitVarInsn(ISTORE, context.var("fastMatchToken"));

            invokeNextToken(context, mw);
        } else {
            invokeQuickNextToken(context, mw);
        }

        { // setContext
            invokeParserContextMethod(context, mw, fieldInfo);
        }

        Label loop_ = new Label();
        Label loop_end_ = new Label();

        // for (;;) {
        mw.visitInsn(ICONST_0);
        mw.visitVarInsn(ISTORE, context.var("i"));
        mw.visitLabel(loop_);

        invokeLexerAndVisitInsn(context, mw);

        mw.visitJumpInsn(IF_ICMPEQ, loop_end_);

        // Object value = itemDeserializer.deserialze(parser, null);
        // array.add(value);

        mw.visitVarInsn(ALOAD, 0);
        mw.visitFieldInsn(GETFIELD, context.className, fieldInfo.name + "_asm_list_item_deser__",
                          desc(ObjectDeserializer.class));
        visitInsnType(mw, itemType);
        mw.visitVarInsn(ILOAD, context.var("i"));
        invokeDeserializationMethods(context, mw);

        mw.visitIincInsn(context.var("i"), 1);

        mw.visitVarInsn(ALOAD, context.var_asm(fieldInfo));
        mw.visitVarInsn(ALOAD, context.var("list_item_value"));
        if (fieldClass.isInterface()) {
            mw.visitMethodInsn(INVOKEINTERFACE, type(fieldClass), "add", "(Ljava/lang/Object;)Z");
        } else {
            mw.visitMethodInsn(INVOKEVIRTUAL, type(fieldClass), "add", "(Ljava/lang/Object;)Z");
        }
        mw.visitInsn(POP);

        invokeMethodVisitor(context, mw, fieldInfo);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "checkListResolve", "(Ljava/util/Collection;)V");

        invokeLexerTokenMethod(context, mw);
        mw.visitLdcInsn(JSONToken.COMMA);
        mw.visitJumpInsn(IF_ICMPNE, loop_);

        if (isPrimitive) {
            invokeNextToken(context, mw);
        } else {
            _quickNextToken(context, mw, JSONToken.LBRACE);
        }
        
        mw.visitJumpInsn(GOTO, loop_);

        mw.visitLabel(loop_end_);

        // mw.visitVarInsn(ASTORE, context.var("context"));
        // parser.setContext(context);
        { // setContext
            mw.visitVarInsn(ALOAD, 1); // parser
            mw.visitVarInsn(ALOAD, context.var("listContext"));
            mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "setContext", "(" + desc(ParseContext.class) + ")V");
        }

        invokeLexerAndVisitInsn(context, mw);
        mw.visitJumpInsn(IF_ICMPNE, reset_);

        _quickNextTokenComma(context, mw);
        // lexer.nextToken(JSONToken.COMMA);

        mw.visitLabel(_end_if);
    }

	private void invokeLexerAndVisitInsn(Context context, MethodVisitor mw) {
		invokeLexerTokenMethod(context, mw);
        mw.visitLdcInsn(JSONToken.RBRACKET);
	}

    private void invokeParserContextMethod(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        mw.visitVarInsn(ALOAD, 1);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getContext", "()" + desc(ParseContext.class));
        mw.visitVarInsn(ASTORE, context.var("listContext"));

        invokeMethodVisitor(context, mw, fieldInfo);
        mw.visitLdcInsn(fieldInfo.name);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "setContext",
                           "(Ljava/lang/Object;Ljava/lang/Object;)" + desc(ParseContext.class));
        mw.visitInsn(POP);
    }

    private void invokeQuickNextToken(Context context, MethodVisitor mw) {
        mw.visitInsn(POP);
        mw.visitLdcInsn(JSONToken.LBRACE);
        mw.visitVarInsn(ISTORE, context.var("fastMatchToken"));

        _quickNextToken(context, mw, JSONToken.LBRACE);
    }

    private void invokeMethodVisitor(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        mw.visitVarInsn(ALOAD, 1); // parser
		mw.visitVarInsn(ALOAD, context.var_asm(fieldInfo));
    }

    private void invokeNextToken(Context context, MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitVarInsn(ILOAD, context.var("fastMatchToken"));
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "nextToken", "(I)V");
    }

    private void addListItem(Context context, MethodVisitor mw, FieldInfo fieldInfo, Class<?> fieldClass) {
        mw.visitVarInsn(ALOAD, context.var_asm(fieldInfo));
        mw.visitVarInsn(ALOAD, context.var("list_item_value"));
        if (fieldClass.isInterface()) {
            mw.visitMethodInsn(INVOKEINTERFACE, type(fieldClass), "add", "(Ljava/lang/Object;)Z");
        } else {
            mw.visitMethodInsn(INVOKEVIRTUAL, type(fieldClass), "add", "(Ljava/lang/Object;)Z");
        }
        mw.visitInsn(POP);
    }

    private void invokeDeserializationMethods(Context context, MethodVisitor mw) {
        mw.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");
        mw.visitMethodInsn(INVOKEINTERFACE, type(ObjectDeserializer.class), "deserialze",
                "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;Ljava/lang/Object;)Ljava/lang/Object;");
        mw.visitVarInsn(ASTORE, context.var("list_item_value"));
    }

    private void visitInsnType(MethodVisitor mw, Class<?> itemType) {
        mw.visitVarInsn(ALOAD, 1);
        mw.visitLdcInsn(com.alibaba.fastjson.asm.Type.getType(desc(itemType)));
    }

    private void invokeLexerTokenMethod(Context context, MethodVisitor mw) {
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "token", "()I");
    }

    private void _quickNextToken(Context context, MethodVisitor mw, int token) {
        Label quickElse_ = new Label();
        Label quickEnd_ = new Label();
        invokeLexerCurrent(context, mw);
        if (token == JSONToken.LBRACE) {
            mw.visitVarInsn(BIPUSH, '{');
        }
        else{
            if (token != JSONToken.LBRACKET)
                throw new IllegalStateException();
            mw.visitVarInsn(BIPUSH, '[');
        }

        mw.visitJumpInsn(IF_ICMPNE, quickElse_);

        invokeLexerNext(context, mw);
        mw.visitLdcInsn(token);
        invokeSetTokenJump(mw, quickEnd_);

        mw.visitLabel(quickElse_);
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(token);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "nextToken", "(I)V");

        mw.visitLabel(quickEnd_);
    }
    
    private void _quickNextTokenComma(Context context, MethodVisitor mw) {
        Label quickElse_ = new Label();
        Label quickElseIf0_ = new Label();
        Label quickElseIf1_ = new Label();
        Label quickElseIf2_ = new Label();
        Label quickEnd_ = new Label();
        invokeLexerCurrent(context, mw);
        mw.visitInsn(DUP);
        mw.visitVarInsn(ISTORE, context.var("ch"));
        mw.visitVarInsn(BIPUSH, ',');
        mw.visitJumpInsn(IF_ICMPNE, quickElseIf0_);

        invokeLexerNext(context, mw);
        mw.visitLdcInsn(JSONToken.COMMA);
        invokeSetTokenJump(mw, quickEnd_);
        
        mw.visitLabel(quickElseIf0_);
        mw.visitVarInsn(ILOAD, context.var("ch"));
        mw.visitVarInsn(BIPUSH, '}');
        mw.visitJumpInsn(IF_ICMPNE, quickElseIf1_);

        invokeLexerNext(context, mw);
        mw.visitLdcInsn(JSONToken.RBRACE);
        invokeSetTokenJump(mw, quickEnd_);
        
        mw.visitLabel(quickElseIf1_);
        mw.visitVarInsn(ILOAD, context.var("ch"));
        mw.visitVarInsn(BIPUSH, ']');
        mw.visitJumpInsn(IF_ICMPNE, quickElseIf2_);

        invokeLexerNext(context, mw);
        mw.visitLdcInsn(JSONToken.RBRACKET);
        invokeSetTokenJump(mw, quickEnd_);
        
        mw.visitLabel(quickElseIf2_);
        mw.visitVarInsn(ILOAD, context.var("ch"));
        mw.visitVarInsn(BIPUSH, JSONLexer.EOI);
        mw.visitJumpInsn(IF_ICMPNE, quickElse_);

        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitLdcInsn(JSONToken.EOF);
        invokeSetTokenJump(mw, quickEnd_);

        mw.visitLabel(quickElse_);
        mw.visitVarInsn(ALOAD, context.var("lexer"));
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "nextToken", "()V");

        mw.visitLabel(quickEnd_);
    }
    
    private void _getCollectionFieldItemDeser(Context context, MethodVisitor mw, FieldInfo fieldInfo,
                                              Class<?> itemType) {
        Label notNull_ = new Label();
        mw.visitVarInsn(ALOAD, 0);
        mw.visitFieldInsn(GETFIELD, context.className, fieldInfo.name + "_asm_list_item_deser__",
                          desc(ObjectDeserializer.class));
        mw.visitJumpInsn(IFNONNULL, notNull_);

        mw.visitVarInsn(ALOAD, 0);

        mw.visitVarInsn(ALOAD, 1);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getConfig", "()" + desc(ParserConfig.class));
        mw.visitLdcInsn(com.alibaba.fastjson.asm.Type.getType(desc(itemType)));
        mw.visitMethodInsn(INVOKEVIRTUAL, type(ParserConfig.class), "getDeserializer",
                           "(Ljava/lang/reflect/Type;)" + desc(ObjectDeserializer.class));

        mw.visitFieldInsn(PUTFIELD, context.className, fieldInfo.name + "_asm_list_item_deser__",
                          desc(ObjectDeserializer.class));

        mw.visitLabel(notNull_);
        mw.visitVarInsn(ALOAD, 0);
        mw.visitFieldInsn(GETFIELD, context.className, fieldInfo.name + "_asm_list_item_deser__",
                          desc(ObjectDeserializer.class));
    }

    private void _newCollection(MethodVisitor mw, Class<?> fieldClass, int i, boolean set) {
        if (fieldClass.isAssignableFrom(ArrayList.class) && !set) {
            mw.visitTypeInsn(NEW, "java/util/ArrayList");
            mw.visitInsn(DUP);
            mw.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V");
        } else if (fieldClass.isAssignableFrom(LinkedList.class) && !set) {
            mw.visitTypeInsn(NEW, type(LinkedList.class));
            mw.visitInsn(DUP);
            mw.visitMethodInsn(INVOKESPECIAL, type(LinkedList.class), "<init>", "()V");
        } else if (fieldClass.isAssignableFrom(HashSet.class))
            initHashSet(mw);
        else if (fieldClass.isAssignableFrom(TreeSet.class)) {
            mw.visitTypeInsn(NEW, type(TreeSet.class));
            mw.visitInsn(DUP);
            mw.visitMethodInsn(INVOKESPECIAL, type(TreeSet.class), "<init>", "()V");
        } else if (fieldClass.isAssignableFrom(LinkedHashSet.class)) {
            mw.visitTypeInsn(NEW, type(LinkedHashSet.class));
            mw.visitInsn(DUP);
            mw.visitMethodInsn(INVOKESPECIAL, type(LinkedHashSet.class), "<init>", "()V");
        } else if (set)
            initHashSet(mw);
        else {
            createCollectionFromFieldType(mw, i);
        }
        mw.visitTypeInsn(CHECKCAST, type(fieldClass)); // cast
    }

    private void createCollectionFromFieldType(MethodVisitor mw, int i) {
        mw.visitVarInsn(ALOAD, 0);
        mw.visitLdcInsn(i);
        mw.visitMethodInsn(INVOKEVIRTUAL, type(JavaBeanDeserializer.class), "getFieldType",
                           "(I)Ljava/lang/reflect/Type;");
        mw.visitMethodInsn(INVOKESTATIC, type(TypeUtils.class), "createCollection",
                           "(Ljava/lang/reflect/Type;)Ljava/util/Collection;");
    }

    private void initHashSet(MethodVisitor mw) {
        mw.visitTypeInsn(NEW, type(HashSet.class));
        mw.visitInsn(DUP);
        mw.visitMethodInsn(INVOKESPECIAL, type(HashSet.class), "<init>", "()V");
    }

    private void _deserialze_obj(Context context, MethodVisitor mw, Label reset_, FieldInfo fieldInfo,
                                 Class<?> fieldClass, int i) {
        Label matched_ = new Label();
        Label _end_if = new Label();

        loadLexerField(context, mw, fieldInfo);
        mw.visitMethodInsn(INVOKEVIRTUAL, JSONLexerBase, "matchField", "([C)Z");
        mw.visitJumpInsn(IFNE, matched_);
        mw.visitInsn(ACONST_NULL);
        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));

        mw.visitJumpInsn(GOTO, _end_if);

        mw.visitLabel(matched_);

        _setFlag(mw, context, i);

        // increment matchedCount
        mw.visitVarInsn(ILOAD, context.var("matchedCount"));
        mw.visitInsn(ICONST_1);
        mw.visitInsn(IADD);
        mw.visitVarInsn(ISTORE, context.var("matchedCount"));

        _deserObject(context, mw, fieldInfo, fieldClass, i);

        mw.visitVarInsn(ALOAD, 1);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getResolveStatus", "()I");
        mw.visitLdcInsn(com.alibaba.fastjson.parser.DefaultJSONParser.NeedToResolve);
        mw.visitJumpInsn(IF_ICMPNE, _end_if);

        mw.visitVarInsn(ALOAD, 1);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getLastResolveTask", "()" + desc(ResolveTask.class));
        mw.visitVarInsn(ASTORE, context.var("resolveTask"));

        mw.visitVarInsn(ALOAD, context.var("resolveTask"));
        mw.visitVarInsn(ALOAD, 1);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getContext", "()" + desc(ParseContext.class));
        mw.visitFieldInsn(PUTFIELD, type(ResolveTask.class), "ownerContext", desc(ParseContext.class));

        mw.visitVarInsn(ALOAD, context.var("resolveTask"));
        mw.visitVarInsn(ALOAD, 0);
        mw.visitLdcInsn(fieldInfo.name);
        mw.visitMethodInsn(INVOKEVIRTUAL, type(JavaBeanDeserializer.class), "getFieldDeserializer",
                           "(Ljava/lang/String;)" + desc(FieldDeserializer.class));
        mw.visitFieldInsn(PUTFIELD, type(ResolveTask.class), "fieldDeserializer", desc(FieldDeserializer.class));

        mw.visitVarInsn(ALOAD, 1);
        mw.visitLdcInsn(com.alibaba.fastjson.parser.DefaultJSONParser.NONE);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "setResolveStatus", "(I)V");

        mw.visitLabel(_end_if);

    }

    private void _deserObject(Context context, MethodVisitor mw, FieldInfo fieldInfo, Class<?> fieldClass, int i) {
        _getFieldDeser(context, mw, fieldInfo);

        Label instanceOfElse_ = new Label();
        Label instanceOfEnd_ = new Label();
        if ((fieldInfo.parserFeatures & Feature.SupportArrayToBean.mask) != 0) {
            deserializeJavaBean(context, mw, fieldInfo, fieldClass, i, instanceOfElse_, instanceOfEnd_);
        }

        invokeFieldInfo(mw, fieldInfo, i);
        mw.visitMethodInsn(INVOKEINTERFACE, type(ObjectDeserializer.class), "deserialze",
                           "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;Ljava/lang/Object;)Ljava/lang/Object;");
        mw.visitTypeInsn(CHECKCAST, type(fieldClass)); // cast
        mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        
        mw.visitLabel(instanceOfEnd_);
    }

    private void deserializeJavaBean(Context context, MethodVisitor mw, FieldInfo fieldInfo, Class<?> fieldClass, int i,
            Label instanceOfElse_, Label instanceOfEnd_) {
        mw.visitInsn(DUP);
        mw.visitTypeInsn(INSTANCEOF, type(JavaBeanDeserializer.class));
        mw.visitJumpInsn(IFEQ, instanceOfElse_);
        
        mw.visitTypeInsn(CHECKCAST, type(JavaBeanDeserializer.class)); // cast
		invokeFieldInfo(mw, fieldInfo, i);
        mw.visitLdcInsn(fieldInfo.parserFeatures);
        mw.visitMethodInsn(INVOKEVIRTUAL, type(JavaBeanDeserializer.class), "deserialze",
                           "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;Ljava/lang/Object;I)Ljava/lang/Object;");
        mw.visitTypeInsn(CHECKCAST, type(fieldClass)); // cast
		mw.visitVarInsn(ASTORE, context.var_asm(fieldInfo));
        
        mw.visitJumpInsn(GOTO, instanceOfEnd_);
        
        mw.visitLabel(instanceOfElse_);
    }

    private void invokeFieldInfo(MethodVisitor mw, FieldInfo fieldInfo, int i) {
        mw.visitVarInsn(ALOAD, 1);
        if (fieldInfo.fieldType instanceof Class) {
            mw.visitLdcInsn(com.alibaba.fastjson.asm.Type.getType(desc(fieldInfo.fieldClass)));
        } else {
            mw.visitVarInsn(ALOAD, 0);
            mw.visitLdcInsn(i);
            mw.visitMethodInsn(INVOKEVIRTUAL, type(JavaBeanDeserializer.class), "getFieldType",
                               "(I)Ljava/lang/reflect/Type;");
        }
        mw.visitLdcInsn(fieldInfo.name);
    }

    private void _getFieldDeser(Context context, MethodVisitor mw, FieldInfo fieldInfo) {
        Label notNull_ = new Label();
        mw.visitVarInsn(ALOAD, 0);
        mw.visitFieldInsn(GETFIELD, context.className, context.fieldDeserName(fieldInfo), desc(ObjectDeserializer.class));
        mw.visitJumpInsn(IFNONNULL, notNull_);

        mw.visitVarInsn(ALOAD, 0);

        mw.visitVarInsn(ALOAD, 1);
        mw.visitMethodInsn(INVOKEVIRTUAL, DefaultJSONParser, "getConfig", "()" + desc(ParserConfig.class));
        mw.visitLdcInsn(com.alibaba.fastjson.asm.Type.getType(desc(fieldInfo.fieldClass)));
        mw.visitMethodInsn(INVOKEVIRTUAL, type(ParserConfig.class), "getDeserializer",
                           "(Ljava/lang/reflect/Type;)" + desc(ObjectDeserializer.class));

        mw.visitFieldInsn(PUTFIELD, context.className, context.fieldDeserName(fieldInfo), desc(ObjectDeserializer.class));

        mw.visitLabel(notNull_);

        mw.visitVarInsn(ALOAD, 0);
        mw.visitFieldInsn(GETFIELD, context.className, context.fieldDeserName(fieldInfo), desc(ObjectDeserializer.class));
    }

    static class Context {

        static final int                   parser = 1;
        static final int                   type = 2;
        static final int                   fieldName = 3;

        private int                        variantIndex = -1;
        private final Map<String, Integer> variants = new HashMap<String, Integer>();

        private final Class<?>             clazz;
        private final JavaBeanInfo         beanInfo;
        private final String               className;
        private FieldInfo[]                fieldInfoList;

        public Context(String className, ParserConfig config, JavaBeanInfo beanInfo, int initVariantIndex) {
            this.className = className;
            this.clazz = beanInfo.clazz;
            this.variantIndex = initVariantIndex;
            this.beanInfo = beanInfo;
            fieldInfoList = beanInfo.fields;
        }

        public Class<?> getInstClass() {
            Class<?> instClass = beanInfo.builderClass;
            if (instClass == null) {
                instClass = clazz;
            }

            return instClass;
        }

        public int var(String name, int increment) {
            Integer i = variants.get(name);
            if (i == null) {
                variants.put(name, variantIndex);
                variantIndex += increment;
            }
            i = variants.get(name);
            return i.intValue();
        }

        public int var(String name) {
            Integer i = variants.get(name);
            if (i == null) {
                variants.put(name, variantIndex++);
            }
            i = variants.get(name);
            return i.intValue();
        }

        public int var_asm(FieldInfo fieldInfo) {
            return var(fieldInfo.name + "_asm");
        }

        public int var_asm(FieldInfo fieldInfo, int increment) {
            return var(fieldInfo.name + "_asm", increment);
        }

        public String fieldName(FieldInfo fieldInfo) {
            return validIdent(fieldInfo.name)
                    ? fieldInfo.name + "_asm_prefix__"
                    : "asm_field_" + TypeUtils.fnv1a_64_extract(fieldInfo.name);
        }


        public String fieldDeserName(FieldInfo fieldInfo) {
            return validIdent(fieldInfo.name)
                    ? fieldInfo.name + "_asm_deser__"
                    : "_asm_deser__" + TypeUtils.fnv1a_64_extract(fieldInfo.name);
        }


        boolean validIdent(String name) {
            for (int i = 0;i < name.length();++i) {
                char ch = name.charAt(i);
                if (ch == 0) {
                    if (!IOUtils.firstIdentifier(ch)) {
                        return false;
                    }
                } else {
                    if (!IOUtils.isIdent(ch)) {
                        return false;
                    }
                }
            }

            return true;
        }
    }

    private void _init(ClassWriter cw, Context context) {
        for (int i = 0, size = context.fieldInfoList.length;i < size;++i) {
            FieldInfo fieldInfo = context.fieldInfoList[i];

            FieldWriter fw = new FieldWriter(cw, ACC_PUBLIC, context.fieldName(fieldInfo), "[C");
            fw.visitEnd();
        }

        for (int i = 0, size = context.fieldInfoList.length;i < size;++i) {
            FieldInfo fieldInfo = context.fieldInfoList[i];
            Class<?> fieldClass = fieldInfo.fieldClass;

            if (fieldClass.isPrimitive()) {
                continue;
            }

            if (Collection.class.isAssignableFrom(fieldClass)) {
                FieldWriter fw = new FieldWriter(cw, ACC_PUBLIC, fieldInfo.name + "_asm_list_item_deser__",
                                                 desc(ObjectDeserializer.class));
                fw.visitEnd();
            } else {
                FieldWriter fw = new FieldWriter(cw, ACC_PUBLIC, context.fieldDeserName(fieldInfo),
                                                 desc(ObjectDeserializer.class));
                fw.visitEnd();
            }
        }

        MethodVisitor mw = new MethodWriter(cw, ACC_PUBLIC, "<init>",
                                            "(" + desc(ParserConfig.class) + desc(JavaBeanInfo.class) + ")V", null, null);
        mw.visitVarInsn(ALOAD, 0);
        mw.visitVarInsn(ALOAD, 1);
        mw.visitVarInsn(ALOAD, 2);
        mw.visitMethodInsn(INVOKESPECIAL, type(JavaBeanDeserializer.class), "<init>",
                           "(" + desc(ParserConfig.class) + desc(JavaBeanInfo.class) + ")V");

        // init fieldNamePrefix
        for (int i = 0, size = context.fieldInfoList.length;i < size;++i) {
            FieldInfo fieldInfo = context.fieldInfoList[i];

            mw.visitVarInsn(ALOAD, 0);
            mw.visitLdcInsn("\"" + fieldInfo.name + "\":"); // public char[] toCharArray()
            mw.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C");
            mw.visitFieldInsn(PUTFIELD, context.className, context.fieldName(fieldInfo), "[C");

        }

        mw.visitInsn(RETURN);
        mw.visitMaxs(4, 4);
        mw.visitEnd();
    }

    private void _createInstance(ClassWriter cw, Context context) {
        Constructor<?> defaultConstructor = context.beanInfo.defaultConstructor;
        if (!Modifier.isPublic(defaultConstructor.getModifiers())) {
            return;
        }
        
        MethodVisitor mw = new MethodWriter(cw, ACC_PUBLIC, "createInstance",
                                            "(L" + DefaultJSONParser + ";Ljava/lang/reflect/Type;)Ljava/lang/Object;",
                                            null, null);

        mw.visitTypeInsn(NEW, type(context.getInstClass()));
        mw.visitInsn(DUP);
        mw.visitMethodInsn(INVOKESPECIAL, type(context.getInstClass()), "<init>", "()V");

        mw.visitInsn(ARETURN);
        mw.visitMaxs(3, 3);
        mw.visitEnd();
    }

}
