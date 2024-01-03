package com.alibaba.fastjson.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.PropertyNamingStrategy;
import com.alibaba.fastjson.annotation.JSONCreator;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.annotation.JSONPOJOBuilder;
import com.alibaba.fastjson.annotation.JSONType;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;

public class JavaBeanInfo {

    public final Class<?> clazz;
    public final Class<?> builderClass;
    public final Constructor<?> defaultConstructor;
    public final Constructor<?> creatorConstructor;
    public final Method factoryMethod;
    public final Method buildMethod;

    public final int defaultConstructorParameterSize;

    public final FieldInfo[] fields;
    public final FieldInfo[] sortedFields;

    public final int parserFeatures;

    public final JSONType jsonType;

    public final String typeName;
    public final String typeKey;

    public String[] orders;

    public Type[] creatorConstructorParameterTypes;
    public String[] creatorConstructorParameters;

    public boolean kotlin;
    public Constructor<?> kotlinDefaultConstructor;

    public JavaBeanInfo(Class<?> clazz, //
                        Class<?> builderClass, //
                        Constructor<?> defaultConstructor, //
                        Constructor<?> creatorConstructor, //
                        Method factoryMethod, //
                        Method buildMethod, //
                        JSONType jsonType, //
                        List<FieldInfo> fieldList) {
        this.clazz = clazz;
        this.builderClass = builderClass;
        this.defaultConstructor = defaultConstructor;
        this.creatorConstructor = creatorConstructor;
        this.factoryMethod = factoryMethod;
        this.parserFeatures = TypeUtils.getParserFeatures(clazz);
        this.buildMethod = buildMethod;

        this.jsonType = jsonType;
        if (jsonType != null) {
            String typeName = jsonType.typeName();
            String typeKey = jsonType.typeKey();
            this.typeKey = typeKey.length() > 0 ? typeKey : null;

            if (typeName.length() != 0) {
                this.typeName = typeName;
            } else {
                this.typeName = clazz.getName();
            }
            String[] orders = jsonType.orders();
            this.orders = orders.length == 0 ? null : orders;
        } else {
            this.typeName = clazz.getName();
            this.typeKey = null;
            this.orders = null;
        }

        fields = new FieldInfo[fieldList.size()];
        fieldList.toArray(fields);

        FieldInfo[] sortedFields = new FieldInfo[fields.length];
        if (orders != null) {
            sortFields(fieldList, sortedFields);
        } else {
            System.arraycopy(fields, 0, sortedFields, 0, fields.length);
            Arrays.sort(sortedFields);
        }

        if (Arrays.equals(fields, sortedFields)) {
            sortedFields = fields;
        }
        this.sortedFields = sortedFields;

        if (defaultConstructor != null) {
            defaultConstructorParameterSize = defaultConstructor.getParameterTypes().length;
        } else if (factoryMethod != null) {
            defaultConstructorParameterSize = factoryMethod.getParameterTypes().length;
        } else {
            defaultConstructorParameterSize = 0;
        }

        if (creatorConstructor != null) {
            initializeOrModifyConstructorParameters(clazz, creatorConstructor);
        }
    }

    private void initializeOrModifyConstructorParameters(Class<?> clazz, Constructor<?> creatorConstructor) {
        this.creatorConstructorParameterTypes = creatorConstructor.getParameterTypes();


        kotlin = TypeUtils.isKotlin(clazz);
        if (kotlin) {
            initializeConstructorParameters(clazz, creatorConstructor);
        } else {
            updateCreatorConstructorParameters(creatorConstructor);
        }
    }

    private void initializeConstructorParameters(Class<?> clazz, Constructor<?> creatorConstructor) {
        this.creatorConstructorParameters = TypeUtils.getKoltinConstructorParameters(clazz);
        try {
            this.kotlinDefaultConstructor = clazz.getConstructor();
        } catch (Throwable ex) {
            // skip
		}

        Annotation[][] paramAnnotationArrays = TypeUtils.getParameterAnnotations(creatorConstructor);
        for (int i = 0;i < creatorConstructorParameters.length && i < paramAnnotationArrays.length;++i) {
            updateJsonFieldAnnotation(paramAnnotationArrays, i);
        }
    }

    private void updateCreatorConstructorParameters(Constructor<?> creatorConstructor) {
        boolean match;
        if (creatorConstructorParameterTypes.length != fields.length) {
            match = false;
        } else {
            match = true;
            match = checkParameterTypesMatch(match);
        }

        if (!match) {
            this.creatorConstructorParameters = ASMUtils.lookupParameterNames(creatorConstructor);
        }
    }

    private void updateJsonFieldAnnotation(Annotation[][] paramAnnotationArrays, int i) {
        Annotation[] paramAnnotations = paramAnnotationArrays[i];
        JSONField fieldAnnotation = null;
        fieldAnnotation = getUpdatedJsonFieldAnnotation(paramAnnotations, fieldAnnotation);
        if (fieldAnnotation != null) {
            setFieldAnnotationName(i, fieldAnnotation);
        }
    }

    private void sortFields(List<FieldInfo> fieldList, FieldInfo[] sortedFields) {
        LinkedHashMap<String, FieldInfo> map = new LinkedHashMap<String, FieldInfo>(fieldList.size());
        for (FieldInfo field : fields) {
            map.put(field.name, field);
        }
        int i = 0;
        for (String item : orders) {
            i = updateSortedFields(sortedFields, map, i, item);
        }
        for (FieldInfo field : map.values()) {
            sortedFields[i++] = field;
        }
    }

    private boolean checkParameterTypesMatch(boolean match) {
        match = checkParameterTypesMatch_(match);
        return match;
    }

    private boolean checkParameterTypesMatch_(boolean match) {
        match = checkConstructorParameterTypesMatch(match);
        return match;
    }

    private boolean checkConstructorParameterTypesMatch(boolean match) {
        match = checkConstructorParameterTypesMatch_(match);
        return match;
    }

    private boolean checkConstructorParameterTypesMatch_(boolean match) {
        for (int i = 0;i < creatorConstructorParameterTypes.length;i++) {
            if (creatorConstructorParameterTypes[i] != fields[i].fieldClass) {
                match = false;
                break;
            }
        }
        return match;
    }

    private void setFieldAnnotationName(int i, JSONField fieldAnnotation) {
        String fieldAnnotationName = fieldAnnotation.name();
        if (fieldAnnotationName.length() > 0) {
            creatorConstructorParameters[i] = fieldAnnotationName;
        }
    }

    private static JSONField getUpdatedJsonFieldAnnotation(Annotation[] paramAnnotations, JSONField fieldAnnotation) {
        fieldAnnotation = updateJsonFieldFromAnnotations(paramAnnotations, fieldAnnotation);
        return fieldAnnotation;
    }

    private static JSONField updateJsonFieldFromAnnotations(Annotation[] paramAnnotations, JSONField fieldAnnotation) {
        fieldAnnotation = getUpdatedJSONField(paramAnnotations, fieldAnnotation);
        return fieldAnnotation;
    }

    private static JSONField getUpdatedJSONField(Annotation[] paramAnnotations, JSONField fieldAnnotation) {
        fieldAnnotation = extractJSONFieldFromAnnotations(paramAnnotations, fieldAnnotation);
        return fieldAnnotation;
    }

    private static JSONField extractJSONFieldFromAnnotations(Annotation[] paramAnnotations, JSONField fieldAnnotation) {
        for (Annotation paramAnnotation : paramAnnotations) {
            if (paramAnnotation instanceof JSONField) {
                fieldAnnotation = (JSONField) paramAnnotation;
                break;
            }
        }
        return fieldAnnotation;
    }

    private int updateSortedFields(FieldInfo[] sortedFields, LinkedHashMap<String, FieldInfo> map, int i, String item) {
        FieldInfo field = map.get(item);
        if (field != null) {
            sortedFields[i++] = field;
            map.remove(item);
        }
        return i;
    }

    private static FieldInfo getField(List<FieldInfo> fieldList, String propertyName) {
        for (FieldInfo item : fieldList) {
            if (item.name.equals(propertyName)) {
                return item;
            }

            Field field = item.field;
            if (field != null && item.getAnnotation() != null && field.getName().equals(propertyName)) {
                return item;
            }
        }
        return null;
    }


    static boolean add(List<FieldInfo> fieldList, FieldInfo field) {
        for (int i = fieldList.size() - 1;i >= 0;--i) {
            FieldInfo item = fieldList.get(i);

            if (item.name.equals(field.name)) {
                if (item.getOnly && !field.getOnly) {
                    continue;
                }

                if (item.fieldClass.isAssignableFrom(field.fieldClass)) {
                    fieldList.set(i, field);
                    return true;
                }

                int result = item.compareTo(field);

                if (result < 0) {
                    fieldList.set(i, field);
                    return true;
                }
                return false;
            }
        }
        fieldList.add(field);

        return true;
    }

    public static JavaBeanInfo build(Class<?> clazz, Type type, PropertyNamingStrategy propertyNamingStrategy) {
        return build(clazz, type, propertyNamingStrategy, false, TypeUtils.compatibleWithJavaBean, false);
    }

    private static Map<TypeVariable, Type> buildGenericInfo(Class<?> clazz) {
        Class<?> childClass = clazz;
        Class<?> currentClass = clazz.getSuperclass();
        if (currentClass == null) {
            return null;
        }

        Map<TypeVariable, Type> typeVarMap = null;

        //analyse the whole generic info from the class inheritance
        for (;currentClass != null && currentClass != Object.class;childClass = currentClass, currentClass = currentClass.getSuperclass()) {
            if (childClass.getGenericSuperclass() instanceof ParameterizedType) {
                typeVarMap = updateTypeVariableMapFromChildClass(childClass, currentClass, typeVarMap);
            }
        }

        return typeVarMap;
    }

    private static Map<TypeVariable, Type> updateTypeVariableMapFromChildClass(Class<?> childClass, Class<?> currentClass,
            Map<TypeVariable, Type> typeVarMap) {
        Type[] childGenericParentActualTypeArgs = ((ParameterizedType) childClass.getGenericSuperclass()).getActualTypeArguments();
        TypeVariable[] currentTypeParameters = currentClass.getTypeParameters();
        for (int i = 0;i < childGenericParentActualTypeArgs.length;i++) {
            //if the child class's generic super class actual args is defined in the child class type parameters
		    typeVarMap = updateTypeVariableMap(typeVarMap, childGenericParentActualTypeArgs, currentTypeParameters, i);
        }
        return typeVarMap;
    }

    private static Map<TypeVariable, Type> updateTypeVariableMap(Map<TypeVariable, Type> typeVarMap,
            Type[] childGenericParentActualTypeArgs, TypeVariable[] currentTypeParameters, int i) {
        if (typeVarMap == null) {
            typeVarMap = new HashMap<TypeVariable, Type>();
        }

        if (typeVarMap.containsKey(childGenericParentActualTypeArgs[i])) {
            Type actualArg = typeVarMap.get(childGenericParentActualTypeArgs[i]);
            typeVarMap.put(currentTypeParameters[i], actualArg);
        } else {
            typeVarMap.put(currentTypeParameters[i], childGenericParentActualTypeArgs[i]);
        }
        return typeVarMap;
    }


    public static JavaBeanInfo build(Class<?> clazz //
            , Type type //
            , PropertyNamingStrategy propertyNamingStrategy //
            , boolean fieldBased //
            , boolean compatibleWithJavaBean
    ) {
        return build(clazz, type, propertyNamingStrategy, fieldBased, compatibleWithJavaBean, false);
    }

    public static JavaBeanInfo build(Class<?> clazz //
                                        , Type type //
                                        , PropertyNamingStrategy propertyNamingStrategy //
                                        , boolean fieldBased //
                                        , boolean compatibleWithJavaBean
                                        , boolean jacksonCompatible
    ) {
        JSONType jsonType = TypeUtils.getAnnotation(clazz, JSONType.class);
        if (jsonType != null) {
            propertyNamingStrategy = updatePropertyNamingStrategy(propertyNamingStrategy, jsonType);
        }

        Class<?> builderClass = getBuilderClass(clazz, jsonType);

        Field[] declaredFields = clazz.getDeclaredFields();
        Method[] methods = clazz.getMethods();
        Map<TypeVariable, Type> genericInfo = buildGenericInfo(clazz);

        boolean kotlin = TypeUtils.isKotlin(clazz);
        Constructor[] constructors = clazz.getDeclaredConstructors();

        Constructor<?> defaultConstructor = null;
        if ((!kotlin) || constructors.length == 1) {
            defaultConstructor = getAppropriateConstructor(clazz, builderClass, constructors);
        }

        Constructor<?> creatorConstructor = null;
        Method buildMethod = null;
        Method factoryMethod = null;

        List<FieldInfo> fieldList = new ArrayList<FieldInfo>();

        if (fieldBased) {
            for (Class<?> currentClass = clazz;currentClass != null;currentClass = currentClass.getSuperclass()) {
                Field[] fields = currentClass.getDeclaredFields();

                computeFields(clazz, type, propertyNamingStrategy, fieldList, fields);
            }

            setConstructorAccessibility(defaultConstructor);

            return new JavaBeanInfo(clazz, builderClass, defaultConstructor, null, factoryMethod, buildMethod, jsonType, fieldList);
        }

        boolean isInterfaceOrAbstract = clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers());
        if ((defaultConstructor == null && builderClass == null) || isInterfaceOrAbstract) {

            Type mixInType = JSON.getMixInAnnotations(clazz);
            if (mixInType instanceof Class) {
                creatorConstructor = getUpdatedCreatorConstructor(clazz, creatorConstructor, mixInType);
            }

            if (creatorConstructor == null) {
                creatorConstructor = getCreatorConstructor(constructors);
            }

            if (creatorConstructor != null && !isInterfaceOrAbstract) { // 基于标记 JSONCreator 注解的构造方法
                TypeUtils.setAccessible(creatorConstructor);

                Class<?>[] types = creatorConstructor.getParameterTypes();

                String[] lookupParameterNames = null;
                if (types.length > 0) {
                    processFieldAnnotations(clazz, declaredFields, kotlin, creatorConstructor, fieldList, types, lookupParameterNames);
                }

                //return new JavaBeanInfo(clazz, builderClass, null, creatorConstructor, null, null, jsonType, fieldList);
            }
            else if ((factoryMethod = getFactoryMethod(clazz, methods, jacksonCompatible)) != null) {
                TypeUtils.setAccessible(factoryMethod);

                String[] lookupParameterNames = null;
                Class<?>[] types = factoryMethod.getParameterTypes();
                if (types.length > 0) {
                    Annotation[][] paramAnnotationArrays = TypeUtils.getParameterAnnotations(factoryMethod);
                    for (int i = 0;i < types.length;++i) {
                        Annotation[] paramAnnotations = paramAnnotationArrays[i];
                        JSONField fieldAnnotation = null;
                        fieldAnnotation = getUpdatedJsonFieldAnnotation(paramAnnotations, fieldAnnotation);
                        if (fieldAnnotation == null && !(jacksonCompatible && TypeUtils.isJacksonCreator(factoryMethod))) {
                            throw new JSONException("illegal json creator");
                        }

                        String fieldName = null;
                        int ordinal = 0;
                        int serialzeFeatures = 0;
                        int parserFeatures = 0;

                        if (fieldAnnotation != null) {
                            fieldName = fieldAnnotation.name();
                            ordinal = fieldAnnotation.ordinal();
                            serialzeFeatures = SerializerFeature.of(fieldAnnotation.serialzeFeatures());
                            parserFeatures = Feature.of(fieldAnnotation.parseFeatures());
                        }

                        if (fieldName == null || fieldName.length() == 0) {
                            if (lookupParameterNames == null) {
                                lookupParameterNames = ASMUtils.lookupParameterNames(factoryMethod);
                            }
                            fieldName = lookupParameterNames[i];
                        }

                        Class<?> fieldClass = types[i];
                        Type fieldType = factoryMethod.getGenericParameterTypes()[i];

                        Field field = TypeUtils.getField(clazz, fieldName, declaredFields);
                        FieldInfo fieldInfo = new FieldInfo(fieldName, clazz, fieldClass, fieldType, field,
                                ordinal, serialzeFeatures, parserFeatures);
                        add(fieldList, fieldInfo);
                    }

                    return new JavaBeanInfo(clazz, builderClass, null, null, factoryMethod, null, jsonType, fieldList);
                }
            }
            else if (!isInterfaceOrAbstract) {
                String className = clazz.getName();

                String[] paramNames = null;
                if (kotlin && constructors.length > 0) {
                    paramNames = TypeUtils.getKoltinConstructorParameters(clazz);
                    creatorConstructor = TypeUtils.getKotlinConstructor(constructors, paramNames);
                    TypeUtils.setAccessible(creatorConstructor);
                }
                else {

                    for (Constructor constructor : constructors) {
                        Class<?>[] parameterTypes = constructor.getParameterTypes();

                        if (className.equals("org.springframework.security.web.authentication.WebAuthenticationDetails")) {
                            if (parameterTypes.length == 2 && parameterTypes[0] == String.class && parameterTypes[1] == String.class) {
                                constructor.setAccessible(true);
                                creatorConstructor = (Constructor<?>) constructor;
                                paramNames = ASMUtils.lookupParameterNames(constructor);
                                break;
                            }
                            else {
                                continue;
                            }
                        }

                        if (className.equals("org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken")) {
                            if (parameterTypes.length == 3
                                    && parameterTypes[0] == Object.class
                                    && parameterTypes[1] == Object.class
                                    && parameterTypes[2] == Collection.class) {
                                creatorConstructor = constructor;
                                creatorConstructor.setAccessible(true);
                                paramNames = new String[]{"principal", "credentials", "authorities"};
                                break;
                            }
                            else {
                                continue;
                            }
                        }

                        if (className.equals("org.springframework.security.core.authority.SimpleGrantedAuthority")) {
                            if (parameterTypes.length == 1
                                    && parameterTypes[0] == String.class) {
                                creatorConstructor = constructor;
                                paramNames = new String[]{"authority"};
                                break;
                            }
                            else {
                                continue;
                            }
                        }

                        //


                        boolean is_public = (constructor.getModifiers() & Modifier.PUBLIC) != 0;
                        if (!is_public) {
                            continue;
                        }
                        String[] lookupParameterNames = ASMUtils.lookupParameterNames(constructor);
                        if (lookupParameterNames == null || lookupParameterNames.length == 0) {
                            continue;
                        }

                        if (creatorConstructor != null
                                && paramNames != null && lookupParameterNames.length <= paramNames.length) {
                            continue;
                        }

                        paramNames = lookupParameterNames;
                        creatorConstructor = constructor;
                    }
                }

                Class<?>[] types = null;
                if (paramNames != null) {
                    types = creatorConstructor.getParameterTypes();
                }

                if (!(paramNames != null
                        && types.length == paramNames.length))
                    throw new JSONException("default constructor not found. " + clazz);
                processConstructorParameters(clazz, declaredFields, creatorConstructor, fieldList, className, paramNames, types);

                if ((!kotlin) && !clazz.getName().equals("javax.servlet.http.Cookie")) {
                    return new JavaBeanInfo(clazz, builderClass, null, creatorConstructor, null, null, jsonType, fieldList);
                }
            }
        }

        setConstructorAccessibility(defaultConstructor);

        if (builderClass != null) {
            buildMethod = processBuilderClassMethods(clazz, type, builderClass, genericInfo, buildMethod, fieldList);
        }

        for (Method method : methods) { //
                int ordinal = 0;
                int serialzeFeatures = 0;
                int parserFeatures = 0;
                String methodName = method.getName();

                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }

                // support builder set
                Class<?> returnType = method.getReturnType();
                if (!(returnType.equals(Void.TYPE) || returnType.equals(method.getDeclaringClass()))) {
                    continue;
                }

                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }

                Class<?>[] types = method.getParameterTypes();

                if (types.length == 0 || types.length > 2) {
                    continue;
                }

                JSONField annotation = TypeUtils.getAnnotation(method, JSONField.class);
                if (annotation != null
                        && types.length == 2
                        && types[0] == String.class
                        && types[1] == Object.class) {
                    add(fieldList, new FieldInfo("", method, null, clazz, type, ordinal,
                            serialzeFeatures, parserFeatures, annotation, null, null, genericInfo));
                    continue;
                }

                if (types.length != 1) {
                    continue;
                }

                if (annotation == null) {
                    annotation = TypeUtils.getSuperMethodAnnotation(clazz, method);
                }

                if (annotation == null && methodName.length() < 4) {
                    continue;
                }

                if (annotation != null) {
                    if (!annotation.deserialize()) {
                        continue;
                    }

                    ordinal = annotation.ordinal();
                    serialzeFeatures = SerializerFeature.of(annotation.serialzeFeatures());
                    parserFeatures = Feature.of(annotation.parseFeatures());

                    if (annotation.name().length() != 0) {
                        String propertyName = annotation.name();
                        add(fieldList, new FieldInfo(propertyName, method, null, clazz, type, ordinal, serialzeFeatures, parserFeatures,
                                annotation, null, null, genericInfo));
                        continue;
                    }
                }

                if (annotation == null && !methodName.startsWith("set") || builderClass != null) { // TODO "set"的判断放在 JSONField 注解后面，意思是允许非 setter 方法标记 JSONField 注解？
                    continue;
                }

                char c3 = methodName.charAt(3);

                String propertyName;
                Field field = null;
                // 用于存储KotlinBean中所有的get方法, 方便后续判断
                List<String> getMethodNameList = null;

                if (kotlin) {
                    getMethodNameList = getGetterMethods(methods);
                }

                if (Character.isUpperCase(c3) //
                        || c3 > 512 // for unicode method name
                ) {
                    // 这里本身的逻辑是通过setAbc这类方法名解析出成员变量名为abc或者Abc, 但是在kotlin中, isAbc, abc成员变量的set方法都是setAbc
                    // 因此如果是kotlin的话还需要进行不一样的判断, 判断的方式是通过get方法进行判断, isAbc的get方法名为isAbc(), abc的get方法名为getAbc()
                    propertyName = getPropertyNameByMethod(kotlin, methodName);

                }
                else if (c3 == '_') {
                    // 这里本身的逻辑是通过set_abc这类方法名解析出成员变量名为abc, 但是在kotlin中, is_abc和_abc成员变量的set方法都是set_abc
                    // 因此如果是kotlin的话还需要进行不一样的判断, 判断的方式是通过get方法进行判断, is_abc的get方法名为is_abc(), _abc的get方法名为get_abc()
                    if (kotlin) {
                        String getMethodName = "g" + methodName.substring(1);
                        if (getMethodNameList.contains(getMethodName)) {
                            propertyName = methodName.substring(3);
                        }
                        else {
                            propertyName = "is" + methodName.substring(3);
                        }
                        field = TypeUtils.getField(clazz, propertyName, declaredFields);
                    }
                    else {
                        propertyName = methodName.substring(4);
                        field = TypeUtils.getField(clazz, propertyName, declaredFields);
                        if (field == null) {
                            String temp = propertyName;
                            propertyName = methodName.substring(3);
                            field = TypeUtils.getField(clazz, propertyName, declaredFields);
                            if (field == null) {
                                propertyName = temp; //减少修改代码带来的影响
                            }
                        }
                    }

                }
                else if (c3 == 'f') {
                    propertyName = methodName.substring(3);
                }
                else if (methodName.length() >= 5 && Character.isUpperCase(methodName.charAt(4))) {
                    propertyName = TypeUtils.decapitalize(methodName.substring(3));
                }
                else {
                    propertyName = methodName.substring(3);
                    field = TypeUtils.getField(clazz, propertyName, declaredFields);
                    if (field == null) {
                        continue;
                    }
                }

                if (field == null) {
                    field = TypeUtils.getField(clazz, propertyName, declaredFields);
                }

                if (field == null && types[0] == boolean.class) {
                    String isFieldName = "is" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
                    field = TypeUtils.getField(clazz, isFieldName, declaredFields);
                }

                JSONField fieldAnnotation = null;
                if (field != null) {
                    fieldAnnotation = TypeUtils.getAnnotation(field, JSONField.class);

                    if (fieldAnnotation != null) {
                        if (!fieldAnnotation.deserialize()) {
                            continue;
                        }

                        ordinal = fieldAnnotation.ordinal();
                        serialzeFeatures = SerializerFeature.of(fieldAnnotation.serialzeFeatures());
                        parserFeatures = Feature.of(fieldAnnotation.parseFeatures());

                        if (fieldAnnotation.name().length() != 0) {
                            propertyName = fieldAnnotation.name();
                            add(fieldList, new FieldInfo(propertyName, method, field, clazz, type, ordinal,
                                    serialzeFeatures, parserFeatures, annotation, fieldAnnotation, null, genericInfo));
                            continue;
                        }
                    }

                }

                if (propertyNamingStrategy != null) {
                    propertyName = propertyNamingStrategy.translate(propertyName);
                }

                add(fieldList, new FieldInfo(propertyName, method, field, clazz, type, ordinal, serialzeFeatures, parserFeatures,
                        annotation, fieldAnnotation, null, genericInfo));
            }

        Field[] fields = clazz.getFields();
        computeFields(clazz, type, propertyNamingStrategy, fieldList, fields);

        for (Method method : clazz.getMethods())
            processGetMethod(clazz, type, propertyNamingStrategy, builderClass, declaredFields, genericInfo, fieldList,
                    method);

        if (fieldList.size() == 0) {
            processClassFields(clazz, type, propertyNamingStrategy, fieldBased, declaredFields, fieldList);
        }

        return new JavaBeanInfo(clazz, builderClass, defaultConstructor, creatorConstructor, factoryMethod, buildMethod, jsonType, fieldList);
    }

	private static void processFieldAnnotations(Class<?> clazz, Field[] declaredFields, boolean kotlin,
            Constructor<?> creatorConstructor, List<FieldInfo> fieldList, Class<?>[] types,
            String[] lookupParameterNames) {
        Annotation[][] paramAnnotationArrays = TypeUtils.getParameterAnnotations(creatorConstructor);
        for (int i = 0;i < types.length && i < paramAnnotationArrays.length;++i) {
            JSONField fieldAnnotation = getJsonFieldAnnotation(paramAnnotationArrays, i);

            Class<?> fieldClass = types[i];
            Type fieldType = creatorConstructor.getGenericParameterTypes()[i];

            String fieldName = null;
            Field field = null;
            int ordinal = 0;
            int serialzeFeatures = 0;
            int parserFeatures = 0;
            if (fieldAnnotation != null) {
                field = TypeUtils.getField(clazz, fieldAnnotation.name(), declaredFields);
                ordinal = fieldAnnotation.ordinal();
                serialzeFeatures = SerializerFeature.of(fieldAnnotation.serialzeFeatures());
                parserFeatures = Feature.of(fieldAnnotation.parseFeatures());
                fieldName = fieldAnnotation.name();
            }

            if (fieldName == null || fieldName.length() == 0) {
                if (lookupParameterNames == null) {
                    lookupParameterNames = ASMUtils.lookupParameterNames(creatorConstructor);
                }
                fieldName = lookupParameterNames[i];
            }

            if (field == null) {
                if (lookupParameterNames == null) {
                    lookupParameterNames = getConstructorParameterNames(clazz, kotlin, creatorConstructor);
                }

                if (lookupParameterNames.length > i) {
                    String parameterName = lookupParameterNames[i];
                    field = TypeUtils.getField(clazz, parameterName, declaredFields);
                }
            }

            FieldInfo fieldInfo = new FieldInfo(fieldName, clazz, fieldClass, fieldType, field,
                    ordinal, serialzeFeatures, parserFeatures);
            add(fieldList, fieldInfo);
        }
    }

    private static void processConstructorParameters(Class<?> clazz, Field[] declaredFields, Constructor<?> creatorConstructor,
            List<FieldInfo> fieldList, String className, String[] paramNames, Class<?>[] types) {
        Annotation[][] paramAnnotationArrays = TypeUtils.getParameterAnnotations(creatorConstructor);
        for (int i = 0;i < types.length;++i) {
            Annotation[] paramAnnotations = paramAnnotationArrays[i];
            String paramName = paramNames[i];

            JSONField fieldAnnotation = null;
            fieldAnnotation = getUpdatedJsonFieldAnnotation(paramAnnotations, fieldAnnotation);

            Class<?> fieldClass = types[i];
            Type fieldType = creatorConstructor.getGenericParameterTypes()[i];
            Field field = TypeUtils.getField(clazz, paramName, declaredFields);
            if (field != null) {
                if (fieldAnnotation == null) {
                    fieldAnnotation = TypeUtils.getAnnotation(field, JSONField.class);
                }
            }
            int ordinal;
            int serialzeFeatures;
            int parserFeatures;
            if (fieldAnnotation == null) {
                ordinal = 0;
                serialzeFeatures = 0;

                if ("org.springframework.security.core.userdetails.User".equals(className)
                        && "password".equals(paramName)) {
                    parserFeatures = Feature.InitStringFieldAsEmpty.mask;
                } else {
                    parserFeatures = 0;
                }
            } else {
                String nameAnnotated = fieldAnnotation.name();
                if (nameAnnotated.length() != 0) {
                    paramName = nameAnnotated;
                }
                ordinal = fieldAnnotation.ordinal();
                serialzeFeatures = SerializerFeature.of(fieldAnnotation.serialzeFeatures());
                parserFeatures = Feature.of(fieldAnnotation.parseFeatures());
            }
            FieldInfo fieldInfo = new FieldInfo(paramName, clazz, fieldClass, fieldType, field,
                    ordinal, serialzeFeatures, parserFeatures);
            add(fieldList, fieldInfo);
        }
    }

    private static void processClassFields(Class<?> clazz, Type type, PropertyNamingStrategy propertyNamingStrategy,
            boolean fieldBased, Field[] declaredFields, List<FieldInfo> fieldList) {
        if (TypeUtils.isXmlField(clazz)) {
            fieldBased = true;
        }

        if (fieldBased) {
            processClassHierarchyFields(clazz, type, propertyNamingStrategy, declaredFields, fieldList);
        }
    }

    private static String getPropertyNameByMethod(boolean kotlin, String methodName) {
        String propertyName;
        if (kotlin) {
            String getMethodName = "g" + methodName.substring(1);
            propertyName = TypeUtils.getPropertyNameByMethodName(getMethodName);
        } else {
            propertyName = getPropertyName(methodName);
        }
        return propertyName;
    }

    private static Method processBuilderClassMethods(Class<?> clazz, Type type, Class<?> builderClass,
            Map<TypeVariable, Type> genericInfo, Method buildMethod, List<FieldInfo> fieldList) {
        String withPrefix = null;

        JSONPOJOBuilder builderAnno = TypeUtils.getAnnotation(builderClass, JSONPOJOBuilder.class);
        if (builderAnno != null) {
            withPrefix = builderAnno.withPrefix();
        }

        if (withPrefix == null) {
            withPrefix = "with";
        }

        for (Method method : builderClass.getMethods())
            processJsonFieldAnnotation(clazz, type, builderClass, genericInfo, fieldList, withPrefix, method);

        if (builderClass != null) {
            buildMethod = getBuilderMethod(builderClass, buildMethod);
        }
        return buildMethod;
    }

    private static void processClassHierarchyFields(Class<?> clazz, Type type, PropertyNamingStrategy propertyNamingStrategy,
            Field[] declaredFields, List<FieldInfo> fieldList) {
        for (Class<?> currentClass = clazz;currentClass != null;currentClass = currentClass.getSuperclass()) {
            computeFields(clazz, type, propertyNamingStrategy, fieldList, declaredFields);
        }
    }

    private static void processGetMethod(Class<?> clazz, Type type, PropertyNamingStrategy propertyNamingStrategy,
            Class<?> builderClass, Field[] declaredFields, Map<TypeVariable, Type> genericInfo,
            List<FieldInfo> fieldList, Method method) {
        String methodName = method.getName();
        if (methodName.length() < 4) {
            return;
        }
        if (Modifier.isStatic(method.getModifiers())) {
            return;
        }
        if (builderClass == null && methodName.startsWith("get") && Character.isUpperCase(methodName.charAt(3))) {
            if (method.getParameterTypes().length != 0) {
                return;
            }

            if (Collection.class.isAssignableFrom(method.getReturnType()) //
		            || Map.class.isAssignableFrom(method.getReturnType()) //
		            || AtomicBoolean.class == method.getReturnType() //
		            || AtomicInteger.class == method.getReturnType() //
		            || AtomicLong.class == method.getReturnType() //
		            ) {
                String propertyName;
                Field collectionField = null;

                JSONField annotation = TypeUtils.getAnnotation(method, JSONField.class);
                if (annotation != null && annotation.deserialize()) {
                    return;
                }

                if (annotation != null && annotation.name().length() > 0) {
                    propertyName = annotation.name();
                } else {
                    propertyName = TypeUtils.getPropertyNameByMethodName(methodName);

                    Field field = TypeUtils.getField(clazz, propertyName, declaredFields);
                    if (field != null) {
                        JSONField fieldAnnotation = TypeUtils.getAnnotation(field, JSONField.class);
                        if (fieldAnnotation != null && !fieldAnnotation.deserialize()) {
                            return;
                        }

                        if (Collection.class.isAssignableFrom(method.getReturnType())
                            || Map.class.isAssignableFrom(method.getReturnType())) {
                            collectionField = field;
                        }
                    }
                }

                if (propertyNamingStrategy != null) {
                    propertyName = propertyNamingStrategy.translate(propertyName);
                }

                FieldInfo fieldInfo = getField(fieldList, propertyName);
                if (fieldInfo != null) {
                    return;
                }

                add(fieldList, new FieldInfo(propertyName, method, collectionField, clazz, type, 0, 0, 0, annotation, null, null, genericInfo));
            }
        }
    }

    private static String getPropertyName(String methodName) {
        String propertyName;
        if (TypeUtils.compatibleWithJavaBean) {
            propertyName = TypeUtils.decapitalize(methodName.substring(3));
        } else {
            propertyName = TypeUtils.getPropertyNameByMethodName(methodName);
        }
        return propertyName;
    }

    private static List<String> getGetterMethods(Method[] methods) {
        List<String> getMethodNameList;
        getMethodNameList = new ArrayList();
        for (int i = 0;i < methods.length;i++) {
            if (methods[i].getName().startsWith("get")) {
                getMethodNameList.add(methods[i].getName());
            }
        }
        return getMethodNameList;
    }

    private static Method getBuilderMethod(Class<?> builderClass, Method buildMethod) {
        JSONPOJOBuilder builderAnnotation = TypeUtils.getAnnotation(builderClass, JSONPOJOBuilder.class);

        String buildMethodName = null;
        if (builderAnnotation != null) {
            buildMethodName = builderAnnotation.buildMethod();
        }

        if (buildMethodName == null || buildMethodName.length() == 0) {
            buildMethodName = "build";
        }

        try {
            buildMethod = builderClass.getMethod(buildMethodName);
        } catch (NoSuchMethodException e) {
            // skip
		} catch (SecurityException e) {
            // skip
		}

        if (buildMethod == null) {
            try {
                buildMethod = builderClass.getMethod("create");
            } catch (NoSuchMethodException e) {
                // skip
		    } catch (SecurityException e) {
                // skip
		    }
        }

        if (buildMethod == null) {
            throw new JSONException("buildMethod not found.");
        }

        TypeUtils.setAccessible(buildMethod);
        return buildMethod;
    }

    private static void processJsonFieldAnnotation(Class<?> clazz, Type type, Class<?> builderClass, Map<TypeVariable, Type> genericInfo,
            List<FieldInfo> fieldList, String withPrefix, Method method) {
        if (Modifier.isStatic(method.getModifiers())) {
            return;
        }
        if (!(method.getReturnType().equals(builderClass))) {
            return;
        }
        int ordinal = 0;
        int serialzeFeatures = 0;
        int parserFeatures = 0;
        JSONField annotation = TypeUtils.getAnnotation(method, JSONField.class);
        if (annotation == null) {
            annotation = TypeUtils.getSuperMethodAnnotation(clazz, method);
        }
        if (annotation != null) {
            if (!annotation.deserialize()) {
                return;
            }

            ordinal = annotation.ordinal();
            serialzeFeatures = SerializerFeature.of(annotation.serialzeFeatures());
            parserFeatures = Feature.of(annotation.parseFeatures());

            if (annotation.name().length() != 0) {
                String propertyName = annotation.name();
                add(fieldList, new FieldInfo(propertyName, method, null, clazz, type, ordinal, serialzeFeatures, parserFeatures,
                        annotation, null, null, genericInfo));
                return;
            }
        }
        String methodName = method.getName();
        StringBuilder properNameBuilder;
        if (methodName.startsWith("set") && methodName.length() > 3) {
            properNameBuilder = new StringBuilder(methodName.substring(3));
        } else {
            if (withPrefix.length() == 0) {
                properNameBuilder = new StringBuilder(methodName);
            } else {
                if (!methodName.startsWith(withPrefix)) {
                    return;
                }

                if (methodName.length() <= withPrefix.length()) {
                    return;
                }
                
                properNameBuilder = new StringBuilder(methodName.substring(withPrefix.length()));
            }
        }
        char c0 = properNameBuilder.charAt(0);
        if (withPrefix.length() != 0 && !Character.isUpperCase(c0)) {
            return;
        }
        properNameBuilder.setCharAt(0, Character.toLowerCase(c0));
        String propertyName = properNameBuilder.toString();
        add(fieldList, new FieldInfo(propertyName, method, null, clazz, type, ordinal, serialzeFeatures, parserFeatures,
                annotation, null, null, genericInfo));
    }

    private static String[] getConstructorParameterNames(Class<?> clazz, boolean kotlin, Constructor<?> creatorConstructor) {
        String[] lookupParameterNames;
        if (kotlin) {
            lookupParameterNames = TypeUtils.getKoltinConstructorParameters(clazz);
        } else {
            lookupParameterNames = ASMUtils.lookupParameterNames(creatorConstructor);
        }
        return lookupParameterNames;
    }

    private static Constructor<?> getUpdatedCreatorConstructor(Class<?> clazz, Constructor<?> creatorConstructor, Type mixInType) {
        Constructor<?>[] mixInConstructors = ((Class<?>) mixInType).getConstructors();
        Constructor<?> mixInCreator = getCreatorConstructor(mixInConstructors);
        if (mixInCreator != null) {
            try {
                creatorConstructor = clazz.getConstructor(mixInCreator.getParameterTypes());
            } catch (NoSuchMethodException e) {
                // skip
		    }
        }
        return creatorConstructor;
    }

    private static Constructor<?> getAppropriateConstructor(Class<?> clazz, Class<?> builderClass, Constructor[] constructors) {
        Constructor<?> defaultConstructor;
        if (builderClass == null) {
            defaultConstructor = getDefaultConstructor(clazz, constructors);
        } else {
            defaultConstructor = getDefaultConstructor(builderClass, builderClass.getDeclaredConstructors());
        }
        return defaultConstructor;
    }

    private static PropertyNamingStrategy updatePropertyNamingStrategy(PropertyNamingStrategy propertyNamingStrategy, JSONType jsonType) {
        PropertyNamingStrategy jsonTypeNaming = jsonType.naming();
        if (jsonTypeNaming != null && jsonTypeNaming != PropertyNamingStrategy.CamelCase) {
            propertyNamingStrategy = jsonTypeNaming;
        }
        return propertyNamingStrategy;
    }

    private static JSONField getJsonFieldAnnotation(Annotation[][] paramAnnotationArrays, int i) {
        Annotation[] paramAnnotations = paramAnnotationArrays[i];
        JSONField fieldAnnotation = null;
        return getUpdatedJsonFieldAnnotation(paramAnnotations, fieldAnnotation);
    }

    private static void setConstructorAccessibility(Constructor<?> defaultConstructor) {
        if (defaultConstructor != null) {
            TypeUtils.setAccessible(defaultConstructor);
        }
    }

    private static void computeFields(Class<?> clazz, Type type, PropertyNamingStrategy propertyNamingStrategy, List<FieldInfo> fieldList, Field[] fields) {
        Map<TypeVariable, Type> genericInfo = buildGenericInfo(clazz);

        for (Field field : fields)
            processFieldInfo(clazz, type, propertyNamingStrategy, fieldList, genericInfo, field);
    }

    private static void processFieldInfo(Class<?> clazz, Type type, PropertyNamingStrategy propertyNamingStrategy,
            List<FieldInfo> fieldList, Map<TypeVariable, Type> genericInfo, Field field) {
        int modifiers = field.getModifiers();
        if ((modifiers & Modifier.STATIC) != 0) {
            return;
        }
        if ((modifiers & Modifier.FINAL) != 0) {
            Class<?> fieldType = field.getType();
            boolean supportReadOnly = Map.class.isAssignableFrom(fieldType)
                    || Collection.class.isAssignableFrom(fieldType)
                    || AtomicLong.class.equals(fieldType) //
		            || AtomicInteger.class.equals(fieldType) //
		            || AtomicBoolean.class.equals(fieldType);
            if (!supportReadOnly) {
                return;
            }
        }
        boolean contains = false;
        contains = checkFieldExists(fieldList, field, contains);
        if (contains) {
            return;
        }
        int ordinal = 0;
        int serialzeFeatures = 0;
        int parserFeatures = 0;
        String propertyName = field.getName();
        JSONField fieldAnnotation = TypeUtils.getAnnotation(field, JSONField.class);
        if (fieldAnnotation != null) {
            if (!fieldAnnotation.deserialize()) {
                return;
            }

            ordinal = fieldAnnotation.ordinal();
            serialzeFeatures = SerializerFeature.of(fieldAnnotation.serialzeFeatures());
            parserFeatures = Feature.of(fieldAnnotation.parseFeatures());

            if (fieldAnnotation.name().length() != 0) {
                propertyName = fieldAnnotation.name();
            }
        }
        if (propertyNamingStrategy != null) {
            propertyName = propertyNamingStrategy.translate(propertyName);
        }
        add(fieldList, new FieldInfo(propertyName, null, field, clazz, type, ordinal, serialzeFeatures, parserFeatures, null,
                fieldAnnotation, null, genericInfo));
    }

    private static boolean checkFieldExists(List<FieldInfo> fieldList, Field field, boolean contains) {
        for (FieldInfo item : fieldList) {
            if (item.name.equals(field.getName())) {
                contains = true;
                break; // 已经是 contains = true，无需继续遍历
            }
        }
        return contains;
    }

    static Constructor<?> getDefaultConstructor(Class<?> clazz, Constructor<?>[] constructors) {
        if (Modifier.isAbstract(clazz.getModifiers())) {
            return null;
        }

        Constructor<?> defaultConstructor = null;

        defaultConstructor = findDefaultConstructor(constructors, defaultConstructor);

        if (defaultConstructor == null) {
            defaultConstructor = getConstructorForClass(clazz, constructors, defaultConstructor);
        }

        return defaultConstructor;
    }

    private static Constructor<?> getConstructorForClass(Class<?> clazz, Constructor<?>[] constructors,
            Constructor<?> defaultConstructor) {
        if (clazz.isMemberClass() && !Modifier.isStatic(clazz.getModifiers())) {
            defaultConstructor = findMatchingConstructor(clazz, constructors, defaultConstructor);
        }
        return defaultConstructor;
    }

    private static Constructor<?> findMatchingConstructor(Class<?> clazz, Constructor<?>[] constructors,
            Constructor<?> defaultConstructor) {
        Class<?>[] types;
        for (Constructor<?> constructor : constructors) {
            if ((types = constructor.getParameterTypes()).length == 1
                    && types[0].equals(clazz.getDeclaringClass())) {
                defaultConstructor = constructor;
                break;
            }
        }
        return defaultConstructor;
    }

    private static Constructor<?> findDefaultConstructor(Constructor<?>[] constructors, Constructor<?> defaultConstructor) {
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterTypes().length == 0) {
                defaultConstructor = constructor;
                break;
            }
        }
        return defaultConstructor;
    }

    public static Constructor<?> getCreatorConstructor(Constructor[] constructors) {
        Constructor<?> creatorConstructor = null;

        for (Constructor<?> constructor : constructors) {
            creatorConstructor = setJSONCreatorConstructor(creatorConstructor, constructor);
        }
        if (creatorConstructor != null) {
            return creatorConstructor;
        }

        return findMatchingConstructor___(constructors, creatorConstructor);
    }

    private static Constructor<?> findMatchingConstructor___(Constructor[] constructors, Constructor<?> creatorConstructor) {
        for (Constructor constructor : constructors)
            creatorConstructor = updateConstructorWithAnnotations(creatorConstructor, constructor);
        return creatorConstructor;
    }

    private static Constructor<?> updateConstructorWithAnnotations(Constructor<?> creatorConstructor, Constructor constructor) {
        Annotation[][] paramAnnotationArrays = TypeUtils.getParameterAnnotations(constructor);
        if (paramAnnotationArrays.length == 0) {
            return creatorConstructor;
        }
        boolean match = checkAnnotationMatch(paramAnnotationArrays, true);
        if (match) {
            creatorConstructor = setCreatorConstructor_(creatorConstructor, constructor);
        }
        return creatorConstructor;
    }

    private static boolean checkAnnotationMatch(Annotation[][] paramAnnotationArrays, boolean match) {
        for (Annotation[] paramAnnotationArray : paramAnnotationArrays) {
            boolean paramMatch = false;
            paramMatch = checkJSONFieldAnnotation(paramAnnotationArray, paramMatch);
            if (!paramMatch) {
                match = false;
                break;
            }
        }
        return match;
    }

    private static Constructor<?> setCreatorConstructor_(Constructor<?> creatorConstructor, Constructor constructor) {
        if (creatorConstructor != null) {
            throw new JSONException("multi-JSONCreator");
        }

        return constructor;
    }

    private static boolean checkJSONFieldAnnotation(Annotation[] paramAnnotationArray, boolean paramMatch) {
        for (Annotation paramAnnotation : paramAnnotationArray) {
            if (paramAnnotation instanceof JSONField) {
                paramMatch = true;
                break;
            }
        }
        return paramMatch;
    }

    private static Constructor<?> setJSONCreatorConstructor(Constructor<?> creatorConstructor, Constructor<?> constructor) {
        JSONCreator annotation = constructor.getAnnotation(JSONCreator.class);
        if (annotation != null)
            creatorConstructor = setCreatorConstructor(creatorConstructor, constructor);
        return creatorConstructor;
    }

    private static Constructor<?> setCreatorConstructor(Constructor<?> creatorConstructor, Constructor<?> constructor) {
        if (creatorConstructor != null) {
            throw new JSONException("multi-JSONCreator");
        }
		return constructor;
    }

    private static Method getFactoryMethod(Class<?> clazz, Method[] methods, boolean jacksonCompatible) {
        Method factoryMethod = null;

        factoryMethod = findFactoryMethod(clazz, methods, factoryMethod);

        if (factoryMethod == null && jacksonCompatible) {
            factoryMethod = findJacksonCreatorMethod(methods, factoryMethod);
        }
        return factoryMethod;
    }

    private static Method findFactoryMethod(Class<?> clazz, Method[] methods, Method factoryMethod) {
        for (Method method : methods)
            factoryMethod = getFactoryMethod_(clazz, factoryMethod, method);
        return factoryMethod;
    }

    private static Method getFactoryMethod_(Class<?> clazz, Method factoryMethod, Method method) {
        if (!Modifier.isStatic(method.getModifiers())) {
            return factoryMethod;
        }
        if (!clazz.isAssignableFrom(method.getReturnType())) {
            return factoryMethod;
        }
        JSONCreator annotation = TypeUtils.getAnnotation(method, JSONCreator.class);
        if (annotation != null) {
            factoryMethod = setFactoryMethod(factoryMethod, method);
        }
        return factoryMethod;
    }

    private static Method findJacksonCreatorMethod(Method[] methods, Method factoryMethod) {
        for (Method method : methods) {
            if (TypeUtils.isJacksonCreator(method)) {
                factoryMethod = method;
                break;
            }
        }
        return factoryMethod;
    }

    private static Method setFactoryMethod(Method factoryMethod, Method method) {
        if (factoryMethod != null) {
            throw new JSONException("multi-JSONCreator");
        }

        return method;
    }

    /**
     * @deprecated
     */
    public static Class<?> getBuilderClass(JSONType type) {
        return getBuilderClass(null, type);
    }

    public static Class<?> getBuilderClass(Class<?> clazz, JSONType type) {
        if (clazz != null && clazz.getName().equals("org.springframework.security.web.savedrequest.DefaultSavedRequest")) {
            return TypeUtils.loadClass("org.springframework.security.web.savedrequest.DefaultSavedRequest$Builder");
        }

        if (type == null) {
            return null;
        }

        Class<?> builderClass = type.builder();

        if (builderClass == Void.class) {
            return null;
        }

        return builderClass;
    }
}
