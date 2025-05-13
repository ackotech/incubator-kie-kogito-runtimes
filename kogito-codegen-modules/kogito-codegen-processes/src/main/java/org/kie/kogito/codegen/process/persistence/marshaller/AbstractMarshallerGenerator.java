/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kie.kogito.codegen.process.persistence.marshaller;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.drools.util.StringUtils;
import org.infinispan.protostream.EnumMarshaller;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.config.Configuration;
import org.infinispan.protostream.descriptors.Descriptor;
import org.infinispan.protostream.descriptors.EnumDescriptor;
import org.infinispan.protostream.descriptors.FieldDescriptor;
import org.infinispan.protostream.descriptors.FileDescriptor;
import org.infinispan.protostream.descriptors.Option;
import org.infinispan.protostream.impl.SerializationContextImpl;
import org.kie.kogito.codegen.api.context.KogitoBuildContext;
import org.kie.kogito.codegen.api.context.impl.JavaKogitoBuildContext;
import org.kie.kogito.codegen.api.template.InvalidTemplateException;
import org.kie.kogito.codegen.api.template.TemplatedGenerator;
import org.kie.kogito.codegen.core.BodyDeclarationComparator;
import org.kie.kogito.codegen.process.persistence.ExclusionTypeUtils;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import static com.github.javaparser.ast.Modifier.Keyword.PUBLIC;
import static com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS;
import static org.kie.kogito.codegen.process.persistence.proto.ProtoGenerator.KOGITO_JAVA_CLASS_OPTION;

public abstract class AbstractMarshallerGenerator<T> implements MarshallerGenerator {

    public static final String TEMPLATE_PERSISTENCE_FOLDER = "/class-templates/persistence/";
    private static final String JAVA_PACKAGE_OPTION = "java_package";
    private static final String STATE_PARAM = "state";

    private final KogitoBuildContext context;
    protected final Collection<T> modelClasses;

    public AbstractMarshallerGenerator(KogitoBuildContext context, Collection<T> rawDataClasses) {
        this.context = context;
        this.modelClasses = rawDataClasses == null ? Collections.emptyList() : rawDataClasses;
        System.out.println("******* " + this.getClass().getName());
    }

    @Override
    public List<CompilationUnit> generate(String content) throws IOException {
        FileDescriptorSource proto = FileDescriptorSource.fromString(UUID.randomUUID().toString(), content);
        return generate(proto);
    }

    /**
     * As of now it does not check for a overloaded method with different types. We can now ignore it as
     * we are using it for checking getters and setters only
     * 
     * @param methodName
     * @param clazz
     * @return
     */
    public boolean isMethodPublicAndPresent(String methodName, Class<?> clazz) {
        try {
            // Get all methods of the class
            for (Method method : clazz.getMethods()) {
                // Check if the method name matches and is public
                if (method.getName().equals(methodName) && Modifier.isPublic(method.getModifiers())) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            //ignore
        }
        return false;
    }

    public boolean isMethodPublicAndPresent(MethodCallExpr methodCallExpr, Class<?> clazz) {
        String methodName = methodCallExpr.getNameAsString();

        return isMethodPublicAndPresent(methodName, clazz);
    }

    public List<CompilationUnit> generate(FileDescriptorSource proto) throws IOException {
        List<CompilationUnit> units = new ArrayList<>();
        TemplatedGenerator generator = TemplatedGenerator.builder()
                .withFallbackContext(JavaKogitoBuildContext.CONTEXT_NAME)
                .withTemplateBasePath(TEMPLATE_PERSISTENCE_FOLDER)
                .build(context, "MessageMarshaller");

        Predicate<String> typeExclusions = ExclusionTypeUtils.createTypeExclusions();

        // filter types that don't require to create a marshaller
        Predicate<Descriptor> packagePredicate = (msg) -> !msg.getFileDescriptor().getPackage().equals("kogito");
        Predicate<Descriptor> jacksonPredicate = (msg) -> !typeExclusions.test(packageFromOption(msg.getFileDescriptor(), msg) + "." + msg.getName());

        Predicate<Descriptor> predicate = packagePredicate.and(jacksonPredicate);

        CompilationUnit parsedClazzFile = generator.compilationUnitOrThrow();

        SerializationContext serializationContext = new SerializationContextImpl(Configuration.builder().build());
        FileDescriptorSource kogitoTypesDescriptor = new FileDescriptorSource().addProtoFile("kogito-types.proto", context.getClassLoader().getResourceAsStream("META-INF/kogito-types.proto"));
        serializationContext.registerProtoFiles(kogitoTypesDescriptor);
        serializationContext.registerProtoFiles(proto);

        Map<String, FileDescriptor> descriptors = serializationContext.getFileDescriptors();

        for (Entry<String, FileDescriptor> entry : descriptors.entrySet()) {
            System.out.println("*** entry " + entry);

            FileDescriptor d = entry.getValue();
            List<Descriptor> messages = d.getMessageTypes().stream().filter(predicate).collect(Collectors.toList());

            for (Descriptor msg : messages) {
                System.out.println("*** message " + msg);

                CompilationUnit clazzFile = parsedClazzFile.clone();
                units.add(clazzFile);

                String javaType = packageFromOption(d, msg) + "." + msg.getName();

                clazzFile.setPackageDeclaration(d.getPackage());
                ClassOrInterfaceDeclaration clazz = clazzFile.findFirst(ClassOrInterfaceDeclaration.class, sl -> true)
                        .orElseThrow(() -> new InvalidTemplateException(generator, "No class found"));
                clazz.setName(msg.getName() + "MessageMarshaller");
                clazz.getImplementedTypes(0).setTypeArguments(NodeList.nodeList(new ClassOrInterfaceType(null, javaType)));
                boolean addedObjectMapper = false;

                MethodDeclaration getJavaClassMethod =
                        clazz.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals("getJavaClass"))
                                .orElseThrow(() -> new InvalidTemplateException(generator, "No getJavaClass method found"));
                getJavaClassMethod.setType(new ClassOrInterfaceType(null, new SimpleName(Class.class.getName()), NodeList.nodeList(new ClassOrInterfaceType(null, javaType))));
                BlockStmt getJavaClassMethodBody = new BlockStmt();
                getJavaClassMethodBody.addStatement(new ReturnStmt(new NameExpr(javaType + ".class")));
                getJavaClassMethod.setBody(getJavaClassMethodBody);

                MethodDeclaration getTypeNameMethod =
                        clazz.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals("getTypeName"))
                                .orElseThrow(() -> new InvalidTemplateException(generator, "No getTypeName method found"));
                BlockStmt getTypeNameMethodBody = new BlockStmt();
                getTypeNameMethodBody.addStatement(new ReturnStmt(new StringLiteralExpr(msg.getFullName())));
                getTypeNameMethod.setBody(getTypeNameMethodBody);

                MethodDeclaration readFromMethod =
                        clazz.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals("readFrom"))
                                .orElseThrow(() -> new InvalidTemplateException(generator, "No readFrom method found"));
                readFromMethod.setType(javaType);
                readFromMethod.setBody(new BlockStmt());

                MethodDeclaration writeToMethod =
                        clazz.findFirst(MethodDeclaration.class, md -> md.getNameAsString().equals("writeTo"))
                                .orElseThrow(() -> new InvalidTemplateException(generator, "No writeTo method found"));
                writeToMethod.getParameter(1).setType(javaType);
                writeToMethod.setBody(new BlockStmt());

                ClassOrInterfaceType classType = new ClassOrInterfaceType(null, javaType);

                // read method

                Class<?> javaClazz = null;
                boolean classIsAbstract = false;
                try {
                    javaClazz = Class.forName(javaType);
                    classIsAbstract = Modifier.isAbstract(javaClazz.getModifiers()) || javaClazz.isInterface();
                } catch (ClassNotFoundException e) {
                    System.out.println("Not able to check class name " + javaType);
                }

                if (javaType.equals(Serializable.class.getName()) || classIsAbstract) {
                    //TODO
                    System.out.println("$$$$$$$$$$$$$$$$$#@ Serializable/Abstract " + javaType + " Need to add statements");

                    // Add a static ObjectMapper field
                    this.addObjectMapperToClass(clazz);
                    addedObjectMapper = true;

                    /*
                     * Read body
                     * Serializable value = objectMapper.readValue(reader.readString("obj"), Serializable.class);
                     */
                    MethodCallExpr deserialiseWithJackson = new MethodCallExpr(new NameExpr("objectMapper"), "readValue")
                            .addArgument(new MethodCallExpr(new NameExpr("reader"), "readString")
                                    .addArgument(new StringLiteralExpr("obj")))
                            .addArgument(new NameExpr(javaType + ".class"));
                    VariableDeclarationExpr instance = new VariableDeclarationExpr(new VariableDeclarator(classType,
                            "value", deserialiseWithJackson));
                    readFromMethod.getBody().ifPresent(b -> b.addStatement(instance));

                    /*
                     * Write body
                     * writer.writeString("obj", objectMapper.writeValueAsString(t));
                     */
                    MethodCallExpr serializeWithJackson = new MethodCallExpr(new NameExpr("objectMapper"), "writeValueAsString")
                            .addArgument(new NameExpr("t"));
                    MethodCallExpr write = new MethodCallExpr(new NameExpr("writer"), "writeString")
                            .addArgument(new StringLiteralExpr("obj"))
                            .addArgument(serializeWithJackson);
                    // write method
                    writeToMethod
                            .getBody()
                            .orElseThrow(() -> new NoSuchElementException("A method declaration doesn't contain a body!"))
                            .addStatement(write);
                } else {
                    VariableDeclarationExpr instance = new VariableDeclarationExpr(new VariableDeclarator(classType,
                            "value", new ObjectCreationExpr(null, classType, NodeList.nodeList())));
                    readFromMethod.getBody().ifPresent(b -> b.addStatement(instance));
                }

                for (FieldDescriptor field : msg.getFields()) {
                    System.out.println("*** field " + field);

                    String protoStreamMethodType = protoStreamMethodType(field.getTypeName());
                    Expression write = null;
                    Expression read = null;
                    String getterMethodName = null;
                    if (protoStreamMethodType != null && !field.isRepeated()) {

                        // has a mapped type
                        read = new MethodCallExpr(new NameExpr("reader"), "read" + protoStreamMethodType)
                                .addArgument(new StringLiteralExpr(field.getName()));
                        String accessor = protoStreamMethodType.equals("Boolean") ? "is" : "get";
                        getterMethodName = accessor + StringUtils.ucFirst(field.getName());
                        write = new MethodCallExpr(new NameExpr("writer"), "write" + protoStreamMethodType)
                                .addArgument(new StringLiteralExpr(field.getName()))
                                .addArgument(new MethodCallExpr(new NameExpr("t"), getterMethodName));
                    } else {
                        //                        System.out.println("## field : " + field.getName() + " else custom types");
                        // custom types 
                        String customTypeName = javaTypeForMessage(d, field.getTypeName(), serializationContext);
                        getterMethodName = "get" + StringUtils.ucFirst(field.getName());
                        if (field.isRepeated()) {
                            //                            System.out.println("## field : " + field.getName() + " repeated");
                            if (null == customTypeName || customTypeName.isEmpty()) {
                                customTypeName = primaryTypeClassName(field.getTypeName());
                            }

                            String writeMethod;

                            if (isArray(javaType, field)) {
                                //                                System.out.println("## field : " + field.getName() + " array");
                                writeMethod = "writeArray";
                                read = new MethodCallExpr(new NameExpr("reader"), "readArray")
                                        .addArgument(new StringLiteralExpr(field.getName()))
                                        .addArgument(new NameExpr(customTypeName + ".class"));
                            } else {
                                //                                System.out.println("## field : " + field.getName() + " not array but repeated");
                                writeMethod = "writeCollection";
                                read = new MethodCallExpr(new NameExpr("reader"), "readCollection")
                                        .addArgument(new StringLiteralExpr(field.getName()))
                                        .addArgument(new ObjectCreationExpr(null, new ClassOrInterfaceType(null, ArrayList.class.getCanonicalName()), NodeList.nodeList()))
                                        .addArgument(new NameExpr(customTypeName + ".class"));
                            }

                            write = new MethodCallExpr(new NameExpr("writer"), writeMethod)
                                    .addArgument(new StringLiteralExpr(field.getName()))
                                    .addArgument(new MethodCallExpr(new NameExpr("t"), getterMethodName))
                                    .addArgument(new NameExpr(customTypeName + ".class"));
                        } else {
                            //                            System.out.println("## field : " + field.getName() + " not repeated " + javaClazz);
                            Class<?> fieldJavaClazz = null;
                            //                            int modifiers;
                            boolean isAbstract = false;
                            try {
                                fieldJavaClazz = Class.forName(customTypeName);
                                //                                modifiers = fieldJavaClazz.getModifiers();
                                //                                System.out.println("$$$$$$   isAbstract " + Modifier.isAbstract(modifiers) + " isinterface " + fieldJavaClazz.isInterface());
                                //                                System.out.println("## original instance " + instanceOld);
                                isAbstract = Modifier.isAbstract(fieldJavaClazz.getModifiers()) || fieldJavaClazz.isInterface();
                            } catch (ClassNotFoundException e) {
                                // isAbstract is false
                            }
                            if (isAbstract) {
                                //                                System.out.println("## field : " + field.getName() + " jackson");
                                if (!addedObjectMapper) {
                                    // Add a static ObjectMapper field
                                    this.addObjectMapperToClass(clazz);
                                    addedObjectMapper = true;
                                }
                                String fieldClazz = (String) field.getOptionByName(KOGITO_JAVA_CLASS_OPTION);
                                if (fieldClazz == null) {
                                    throw new IllegalArgumentException(String.format("Serializable proto field '%s' is missing value for option %s", field.getName(), KOGITO_JAVA_CLASS_OPTION));
                                }
                                // Use Jackson for serialization/deserialization
                                MethodCallExpr serializeWithJackson = new MethodCallExpr(new NameExpr("objectMapper"), "writeValueAsString")
                                        .addArgument(new MethodCallExpr(new NameExpr("t"), getterMethodName));

                                write = new MethodCallExpr(new NameExpr("writer"), "writeString")
                                        .addArgument(new StringLiteralExpr(field.getName()))
                                        .addArgument(serializeWithJackson);

                                read = new MethodCallExpr(new NameExpr("objectMapper"), "readValue")
                                        .addArgument(new MethodCallExpr(new NameExpr("reader"), "readString").addArgument(new StringLiteralExpr(field.getName())))
                                        .addArgument(new NameExpr(fieldClazz + ".class"));

                            } else {
                                //                                System.out.println("## field : " + field.getName() + " Object");
                                read = new MethodCallExpr(new NameExpr("reader"), "readObject")
                                        .addArgument(new StringLiteralExpr(field.getName()))
                                        .addArgument(new NameExpr(customTypeName + ".class"));
                                write = new MethodCallExpr(new NameExpr("writer"), "writeObject")
                                        .addArgument(new StringLiteralExpr(field.getName()))
                                        .addArgument(new MethodCallExpr(new NameExpr("t"), getterMethodName))
                                        .addArgument(new NameExpr(customTypeName + ".class"));
                            }
                        }

                        /*
                         * if (customTypeName.equals(Serializable.class.getName())) {
                         * 
                         * String fieldClazz = (String) field.getOptionByName(KOGITO_JAVA_CLASS_OPTION);
                         * System.out.println("$$$$$$$$$$$$$$$$$#@ Serializable " + fieldClazz);
                         * if (fieldClazz == null) {
                         * throw new IllegalArgumentException(String.format("Serializable proto field '%s' is missing value for option %s", field.getName(), KOGITO_JAVA_CLASS_OPTION));
                         * } else {
                         * //read = new CastExpr().setExpression(new EnclosedExpr(read)).setType(fieldClazz);
                         * int argumentIndex = 1;
                         * MethodCallExpr writeMethod = null;
                         * if (write instanceof MethodCallExpr &&
                         * (writeMethod = (MethodCallExpr) write).getArguments() != null &&
                         * writeMethod.getArguments().size() > argumentIndex) {
                         * Expression argument = writeMethod.getArgument(argumentIndex);
                         * System.out.println(writeMethod.setArgument(
                         * argumentIndex,
                         * new CastExpr().setExpression(new EnclosedExpr(argument)).setType(fieldClazz)));
                         * }
                         * }
                         * }
                         */
                    }

                    MethodCallExpr setter = new MethodCallExpr(new NameExpr("value"), "set" + StringUtils.ucFirst(field.getName())).addArgument(read);
                    // if the getter or setter is not present for a particular object then we should ignore this
                    boolean isSetterPresent = javaClazz == null || this.isMethodPublicAndPresent(setter, javaClazz);
                    boolean isGetterPresent = javaClazz == null || this.isMethodPublicAndPresent(getterMethodName, javaClazz);

                    if (isSetterPresent && isGetterPresent) {
                        System.out.println("## field : " + field.getName() + " setter & getter present");

                        readFromMethod.getBody().ifPresent(b -> b.addStatement(setter));
                        //                    System.out.println("&&&&&&& Read " + read);
                        //                    System.out.println("&&&&&&& Write " + write);

                        // write method
                        writeToMethod
                                .getBody()
                                .orElseThrow(() -> new NoSuchElementException("A method declaration doesn't contain a body!"))
                                .addStatement(write);
                    }
                }

                readFromMethod.getBody().ifPresent(b -> b.addStatement(new ReturnStmt(new NameExpr("value"))));
                clazz.getMembers().sort(new BodyDeclarationComparator());
            }

            for (EnumDescriptor msg : d.getEnumTypes()) {
                CompilationUnit compilationUnit = new CompilationUnit();
                units.add(compilationUnit);

                String javaType = packageFromOption(d, msg) + "." + msg.getName();

                ClassOrInterfaceDeclaration classDeclaration = compilationUnit.setPackageDeclaration(d.getPackage())
                        .addClass(msg.getName() + "EnumMarshaller").setPublic(true);
                classDeclaration.addImplementedType(EnumMarshaller.class).getImplementedTypes(0)
                        .setTypeArguments(NodeList.nodeList(new ClassOrInterfaceType(null, javaType)));
                classDeclaration.addMethod("getTypeName", PUBLIC)
                        .setType(String.class)
                        .setBody(new BlockStmt().addStatement(new ReturnStmt(new StringLiteralExpr(msg.getFullName()))));
                classDeclaration.addMethod("getJavaClass", PUBLIC)
                        .setType(new ClassOrInterfaceType(null, new SimpleName(Class.class.getName()), NodeList.nodeList(new ClassOrInterfaceType(null, javaType))))
                        .setBody(new BlockStmt().addStatement(new ReturnStmt(new ClassExpr(new ClassOrInterfaceType(null, javaType)))));

                BlockStmt encodeBlock = new BlockStmt()
                        .addStatement(
                                new IfStmt(
                                        new BinaryExpr(new NullLiteralExpr(), new NameExpr(STATE_PARAM), EQUALS),
                                        new ThrowStmt(new ObjectCreationExpr(
                                                null,
                                                new ClassOrInterfaceType(null, IllegalArgumentException.class.getName()),
                                                NodeList.nodeList(new StringLiteralExpr("Invalid value provided to enum")))),
                                        null))
                        .addStatement(new ReturnStmt(new MethodCallExpr(new NameExpr(STATE_PARAM), "ordinal")));
                classDeclaration.addMethod("encode", PUBLIC)
                        .setType("int")
                        .addParameter(javaType, STATE_PARAM)
                        .setBody(encodeBlock);

                MethodDeclaration decode = classDeclaration.addMethod("decode", PUBLIC)
                        .setType(javaType)
                        .addParameter("int", "value");
                SwitchStmt decodeSwitch = new SwitchStmt().setSelector(new NameExpr("value"));
                msg.getValues().forEach(v -> {
                    SwitchEntry dEntry = new SwitchEntry();
                    dEntry.getLabels().add(new IntegerLiteralExpr(v.getNumber()));
                    dEntry.addStatement(new ReturnStmt(new NameExpr(javaType + "." + v.getName())));
                    decodeSwitch.getEntries().add(dEntry);
                });
                decodeSwitch.getEntries()
                        .add(new SwitchEntry().addStatement(
                                new ThrowStmt(new ObjectCreationExpr(
                                        null,
                                        new ClassOrInterfaceType(null, IllegalArgumentException.class.getName()),
                                        NodeList.nodeList(new StringLiteralExpr("Invalid value provided to enum"))))));
                decode.setBody(new BlockStmt().addStatement(decodeSwitch));
            }
        }
        System.out.println("@@@@@@@@ Compilation Units " + units);

        return units;
    }

    protected String packageFromOption(FileDescriptor d, Descriptor msg) {
        return packageFromOption(d, msg.getOption(JAVA_PACKAGE_OPTION));
    }

    protected String packageFromOption(FileDescriptor d, EnumDescriptor msg) {
        return packageFromOption(d, msg.getOption(JAVA_PACKAGE_OPTION));
    }

    private String packageFromOption(FileDescriptor d, Option customPackage) {
        return (customPackage == null ? d.getPackage() : customPackage.getValue().toString());
    }

    protected String javaTypeForMessage(FileDescriptor d, String messageName, SerializationContext serializationContext) {
        Map<String, FileDescriptor> descriptors = serializationContext.getFileDescriptors();
        for (Entry<String, FileDescriptor> entry : descriptors.entrySet()) {

            List<Descriptor> messages = entry.getValue().getMessageTypes();

            for (Descriptor msg : messages) {
                if (messageName.equals(msg.getName())) {
                    return packageFromOption(d, msg) + "." + messageName;
                } else if (messageName.equals(msg.getFullName())) {
                    return packageFromOption(d, msg) + "." + msg.getName();
                }
            }
            List<EnumDescriptor> enums = entry.getValue().getEnumTypes();
            for (EnumDescriptor msg : enums) {
                if (messageName.equals(msg.getName())) {
                    return packageFromOption(d, msg) + "." + messageName;
                } else if (messageName.equals(msg.getFullName())) {
                    return packageFromOption(d, msg) + "." + msg.getName();
                }
            }
        }
        return null;
    }

    protected String protoStreamMethodType(String type) {
        String methodReader = null;

        switch (type) {
            case "string":
                methodReader = "String";
                break;
            case "int32":
                methodReader = "Int";
                break;
            case "int64":
                methodReader = "Long";
                break;
            case "double":
                methodReader = "Double";
                break;
            case "float":
                methodReader = "Float";
                break;
            case "bool":
                methodReader = "Boolean";
                break;
            case "bytes":
                methodReader = "Bytes";
                break;
            default:
                methodReader = null;
        }

        return methodReader;
    }

    protected String primaryTypeClassName(String type) {
        String className = null;

        switch (type) {
            case "string":
                className = "String";
                break;
            case "int32":
                className = "Integer";
                break;
            case "int64":
                className = "Long";
                break;
            case "double":
                className = "Double";
                break;
            case "float":
                className = "Float";
                break;
            case "bool":
                className = "Boolean";
                break;
            default:
                className = null;
        }

        return className;
    }

    private void addObjectMapperToClass(ClassOrInterfaceDeclaration clazz) {
        // Add a static ObjectMapper field
        FieldDeclaration objectMapperField = new FieldDeclaration()
                .addVariable(new VariableDeclarator(
                        new ClassOrInterfaceType(null, "com.fasterxml.jackson.databind.ObjectMapper"),
                        "objectMapper",
                        new ObjectCreationExpr(null, new ClassOrInterfaceType(null, "com.fasterxml.jackson.databind.ObjectMapper"), NodeList.nodeList())))
                .setModifiers(com.github.javaparser.ast.Modifier.Keyword.PRIVATE,
                        com.github.javaparser.ast.Modifier.Keyword.STATIC,
                        com.github.javaparser.ast.Modifier.Keyword.FINAL);

        // Add the field to the class
        clazz.addMember(objectMapperField);
    }

    protected abstract boolean isArray(String javaType, FieldDescriptor field);
}
