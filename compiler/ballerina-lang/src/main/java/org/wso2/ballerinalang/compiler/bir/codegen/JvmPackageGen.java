/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.ballerinalang.compiler.bir.codegen;

import io.ballerina.identifier.Utils;
import io.ballerina.types.Env;
import org.ballerinalang.compiler.BLangCompilerException;
import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.model.symbols.SymbolKind;
import org.ballerinalang.util.diagnostic.DiagnosticErrorCode;
import org.objectweb.asm.ClassTooLargeException;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.MethodVisitor;
import org.wso2.ballerinalang.compiler.PackageCache;
import org.wso2.ballerinalang.compiler.bir.codegen.exceptions.JInteropException;
import org.wso2.ballerinalang.compiler.bir.codegen.internal.AsyncDataCollector;
import org.wso2.ballerinalang.compiler.bir.codegen.internal.JavaClass;
import org.wso2.ballerinalang.compiler.bir.codegen.methodgen.ConfigMethodGen;
import org.wso2.ballerinalang.compiler.bir.codegen.methodgen.InitMethodGen;
import org.wso2.ballerinalang.compiler.bir.codegen.methodgen.LambdaGen;
import org.wso2.ballerinalang.compiler.bir.codegen.methodgen.MainMethodGen;
import org.wso2.ballerinalang.compiler.bir.codegen.methodgen.MethodGen;
import org.wso2.ballerinalang.compiler.bir.codegen.methodgen.MethodGenUtils;
import org.wso2.ballerinalang.compiler.bir.codegen.methodgen.ModuleStopMethodGen;
import org.wso2.ballerinalang.compiler.bir.codegen.model.BIRFunctionWrapper;
import org.wso2.ballerinalang.compiler.bir.codegen.split.JvmConstantsGen;
import org.wso2.ballerinalang.compiler.bir.codegen.split.JvmMethodsSplitter;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRFunction;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRGlobalVariableDcl;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRPackage;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRTypeDefinition;
import org.wso2.ballerinalang.compiler.bir.model.BIRNode.BIRVariableDcl;
import org.wso2.ballerinalang.compiler.bir.model.BIRNonTerminator.NewInstance;
import org.wso2.ballerinalang.compiler.bir.model.VarKind;
import org.wso2.ballerinalang.compiler.bir.model.VarScope;
import org.wso2.ballerinalang.compiler.diagnostic.BLangDiagnosticLog;
import org.wso2.ballerinalang.compiler.semantics.analyzer.TypeHashVisitor;
import org.wso2.ballerinalang.compiler.semantics.analyzer.Types;
import org.wso2.ballerinalang.compiler.semantics.model.Scope;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolTable;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BObjectTypeSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BPackageSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.Symbols;
import org.wso2.ballerinalang.compiler.semantics.model.types.BInvokableType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BType;
import org.wso2.ballerinalang.compiler.util.Name;
import org.wso2.ballerinalang.compiler.util.Names;
import org.wso2.ballerinalang.compiler.util.TypeTags;
import org.wso2.ballerinalang.compiler.util.Unifier;
import org.wso2.ballerinalang.util.Flags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.ballerinalang.model.symbols.SymbolOrigin.VIRTUAL;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V21;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmCodeGenUtil.NAME_HASH_COMPARATOR;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmCodeGenUtil.getModuleLevelClassName;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmCodeGenUtil.isExternFunc;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmCodeGenUtil.toNameString;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.BALLERINA;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.CLASS_FILE_SUFFIX;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.CONSTANT_INIT_METHOD_PREFIX;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.CURRENT_MODULE_VAR_NAME;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.ENCODED_DOT_CHARACTER;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.ENCODED_JAVA_MODULE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.JVM_INIT_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.JVM_STATIC_INIT_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.LOCK_STORE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.LOCK_STORE_VAR_NAME;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MAIN_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MAX_GENERATED_METHODS_PER_CLASS;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_EXECUTE_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_GENERATED_FUNCTIONS_CLASS_NAME;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_INIT_CLASS_NAME;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_STARTED;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_START_ATTEMPTED;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_STOP_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.MODULE_TYPES_CLASS_NAME;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.NO_OF_DEPENDANT_MODULES;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.OBJECT;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.PARENT_MODULE_START_ATTEMPTED;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.SERVICE_EP_AVAILABLE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.TEST_EXECUTE_METHOD;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmConstants.VALUE_CREATOR;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmDesugarPhase.addDefaultableBooleanVarsToSignature;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmDesugarPhase.rewriteRecordInits;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmSignatures.GET_LOCK_STORE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmSignatures.GET_MODULE;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmSignatures.VOID_METHOD_DESC;
import static org.wso2.ballerinalang.compiler.bir.codegen.JvmValueGen.injectDefaultParamInitsToAttachedFuncs;
import static org.wso2.ballerinalang.compiler.bir.codegen.interop.ExternalMethodGen.createExternalFunctionWrapper;
import static org.wso2.ballerinalang.compiler.bir.codegen.interop.ExternalMethodGen.injectDefaultParamInits;

/**
 * BIR module to JVM byte code generation class.
 *
 * @since 1.2.0
 */
public class JvmPackageGen {

    private static final Unifier unifier = new Unifier();
    public final SymbolTable symbolTable;
    public final PackageCache packageCache;
    private final MethodGen methodGen;
    private final InitMethodGen initMethodGen;
    private final ConfigMethodGen configMethodGen;
    private final Map<String, BIRFunctionWrapper> birFunctionMap;
    private final Map<String, String> globalVarClassMap;
    private final BLangDiagnosticLog dlog;
    private final Types types;
    private final boolean isRemoteMgtEnabled;
    private final Env typeEnv;

    JvmPackageGen(SymbolTable symbolTable, PackageCache packageCache, BLangDiagnosticLog dlog, Types types,
                  boolean isRemoteMgtEnabled) {
        birFunctionMap = new HashMap<>();
        globalVarClassMap = new HashMap<>();
        this.symbolTable = symbolTable;
        this.packageCache = packageCache;
        this.dlog = dlog;
        this.types = types;
        this.isRemoteMgtEnabled = isRemoteMgtEnabled;
        methodGen = new MethodGen(this, types);
        initMethodGen = new InitMethodGen(symbolTable);
        configMethodGen = new ConfigMethodGen();
        JvmInstructionGen.anyType = symbolTable.anyType;
        this.typeEnv = symbolTable.typeEnv();
    }

    private static String getBvmAlias(String orgName, String moduleName) {
        if (Names.ANON_ORG.value.equals(orgName)) {
            return moduleName;
        }
        return orgName + "/" + moduleName;
    }

    private static void addBuiltinImports(PackageID currentModule, Set<PackageID> dependentModuleArray) {
        // Add the builtin and utils modules to the imported list of modules
        if (JvmCodeGenUtil.isSameModule(currentModule, PackageID.ANNOTATIONS)) {
            return;
        }

        dependentModuleArray.add(PackageID.ANNOTATIONS);

        if (JvmCodeGenUtil.isSameModule(currentModule, PackageID.JAVA)) {
            return;
        }

        dependentModuleArray.add(PackageID.JAVA);

        if (isLangModule(currentModule)) {
            return;
        }

        if (JvmCodeGenUtil.isSameModule(currentModule, PackageID.INTERNAL)) {
            return;
        }
        dependentModuleArray.add(PackageID.INTERNAL);
        dependentModuleArray.add(PackageID.ARRAY);
        dependentModuleArray.add(PackageID.DECIMAL);
        dependentModuleArray.add(PackageID.VALUE);
        dependentModuleArray.add(PackageID.ERROR);
        dependentModuleArray.add(PackageID.FLOAT);
        dependentModuleArray.add(PackageID.FUNCTION);
        dependentModuleArray.add(PackageID.FUTURE);
        dependentModuleArray.add(PackageID.INT);
        dependentModuleArray.add(PackageID.MAP);
        dependentModuleArray.add(PackageID.OBJECT);
        dependentModuleArray.add(PackageID.STREAM);
        dependentModuleArray.add(PackageID.REGEXP);
        dependentModuleArray.add(PackageID.STRING);
        dependentModuleArray.add(PackageID.TABLE);
        dependentModuleArray.add(PackageID.XML);
        dependentModuleArray.add(PackageID.TYPEDESC);
        dependentModuleArray.add(PackageID.BOOLEAN);
        dependentModuleArray.add(PackageID.QUERY);
        dependentModuleArray.add(PackageID.TRANSACTION);
    }

    public static boolean isLangModule(PackageID moduleId) {
        if (!BALLERINA.equals(moduleId.orgName.value)) {
            return false;
        }
        return moduleId.name.value.startsWith("lang" + ENCODED_DOT_CHARACTER) ||
                moduleId.name.value.equals(ENCODED_JAVA_MODULE);
    }

    private static void generatePackageVariable(BIRGlobalVariableDcl globalVar, ClassWriter cw) {
        String varName = globalVar.name.value;
        BType bType = globalVar.type;
        String descriptor = JvmCodeGenUtil.getFieldTypeSignature(bType);
        FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, varName, descriptor, null, null);
        fv.visitEnd();
    }

    private static void generateLockStoreVariable(ClassWriter cw) {
        FieldVisitor fv;
        fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, LOCK_STORE_VAR_NAME, GET_LOCK_STORE, null, null);
        fv.visitEnd();
    }

    private static void generateStaticInitializer(ClassWriter cw, String className, BIRPackage birPackage,
                                                  boolean isInitClass, boolean serviceEPAvailable,
                                                  JvmConstantsGen jvmConstantsGen) {
        if (!isInitClass) {
            return;
        }
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, JVM_STATIC_INIT_METHOD, VOID_METHOD_DESC, null, null);
        setConstantFields(mv, birPackage, jvmConstantsGen);
        setLockStoreField(mv, className);
        setServiceEPAvailableField(cw, mv, serviceEPAvailable, className);
        setModuleStatusField(cw, mv, className);
        setCurrentModuleField(cw, mv, jvmConstantsGen, birPackage.packageID, className);
        mv.visitInsn(RETURN);
        JvmCodeGenUtil.visitMaxStackForMethod(mv, JVM_STATIC_INIT_METHOD, className);
        mv.visitEnd();
    }

    private static void setConstantFields(MethodVisitor mv, BIRPackage birPackage,
                                          JvmConstantsGen jvmConstantsGen) {
        if (birPackage.constants.isEmpty()) {
            return;
        }
        mv.visitMethodInsn(INVOKESTATIC, jvmConstantsGen.getConstantClass(), CONSTANT_INIT_METHOD_PREFIX,
                VOID_METHOD_DESC, false);
    }

    private static void setLockStoreField(MethodVisitor mv, String className) {
        mv.visitTypeInsn(NEW, LOCK_STORE);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, LOCK_STORE, JVM_INIT_METHOD, VOID_METHOD_DESC, false);
        mv.visitFieldInsn(PUTSTATIC, className, LOCK_STORE_VAR_NAME, GET_LOCK_STORE);
    }

    private static void setServiceEPAvailableField(ClassWriter cw, MethodVisitor mv, boolean serviceEPAvailable,
                                                   String initClass) {
        FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, SERVICE_EP_AVAILABLE, "Z", null, null);
        fv.visitEnd();

        if (serviceEPAvailable) {
            mv.visitInsn(ICONST_1);
        } else {
            mv.visitInsn(ICONST_0);
        }
        mv.visitFieldInsn(PUTSTATIC, initClass, SERVICE_EP_AVAILABLE, "Z");
    }

    private static void setModuleStatusField(ClassWriter cw, MethodVisitor mv, String initClass) {
        FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, MODULE_START_ATTEMPTED, "Z", null, null);
        fv.visitEnd();

        mv.visitInsn(ICONST_0);
        mv.visitFieldInsn(PUTSTATIC, initClass, MODULE_START_ATTEMPTED, "Z");

        fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, MODULE_STARTED, "Z", null, null);
        fv.visitEnd();

        mv.visitInsn(ICONST_0);
        mv.visitFieldInsn(PUTSTATIC, initClass, MODULE_STARTED, "Z");

        fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, PARENT_MODULE_START_ATTEMPTED, "Z", null, null);
        fv.visitEnd();
        mv.visitInsn(ICONST_0);
        mv.visitFieldInsn(PUTSTATIC, initClass, PARENT_MODULE_START_ATTEMPTED, "Z");
        fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, NO_OF_DEPENDANT_MODULES, "I", null, null);
        fv.visitEnd();
        mv.visitInsn(ICONST_0);
        mv.visitFieldInsn(PUTSTATIC, initClass, NO_OF_DEPENDANT_MODULES, "I");
    }

    private static void setCurrentModuleField(ClassWriter cw, MethodVisitor mv, JvmConstantsGen jvmConstantsGen,
                                              PackageID packageID, String moduleInitClass) {
        FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_STATIC, CURRENT_MODULE_VAR_NAME, GET_MODULE, null, null);
        fv.visitEnd();
        String varName = jvmConstantsGen.getModuleConstantVar(packageID);
        mv.visitFieldInsn(GETSTATIC, jvmConstantsGen.getModuleConstantClass(), varName, GET_MODULE);
        mv.visitFieldInsn(PUTSTATIC, moduleInitClass, CURRENT_MODULE_VAR_NAME, GET_MODULE);
    }

    public static BIRFunctionWrapper getFunctionWrapper(Env typeEnv, BIRFunction currentFunc, PackageID packageID,
                                                        String moduleClass) {
        BInvokableType functionTypeDesc = currentFunc.type;
        BIRVariableDcl receiver = currentFunc.receiver;

        BType retType = functionTypeDesc.retType;
        if (isExternFunc(currentFunc) && Symbols.isFlagOn(retType.getFlags(), Flags.PARAMETERIZED)) {
            retType = unifier.build(typeEnv, retType);
        }
        String jvmMethodDescription;
        if (receiver == null) {
            jvmMethodDescription = JvmCodeGenUtil.getMethodDesc(typeEnv, functionTypeDesc.paramTypes, retType);
        } else {
            jvmMethodDescription = JvmCodeGenUtil.getMethodDesc(typeEnv, functionTypeDesc.paramTypes, retType,
                                                                receiver.type);
        }
        return new BIRFunctionWrapper(packageID, currentFunc, moduleClass, jvmMethodDescription);
    }

    private static BIRFunction findFunction(BIRNode parentNode, String funcName) {
        BIRFunction func;
        if (parentNode instanceof BIRTypeDefinition typeDef) {
            func = findFunction(typeDef.attachedFuncs, funcName);
        } else if (parentNode instanceof BIRPackage pkg) {
            func = findFunction(pkg.functions, funcName);
        } else {
            // some generated functions will not have bir function
            return null;
        }
        return func;
    }

    private static BIRFunction findFunction(List<BIRFunction> functions, String funcName) {
        for (BIRFunction func : functions) {
            if (func.name.value.equals(funcName)) {
                return func;
            }
        }
        return null;
    }

    private void generateModuleClasses(BIRPackage module, JarEntries jarEntries, String moduleInitClass,
                                       String typesClass, JvmTypeGen jvmTypeGen, JvmCastGen jvmCastGen,
                                       JvmConstantsGen jvmConstantsGen, Map<String, JavaClass> jvmClassMapping,
                                       boolean serviceEPAvailable, BIRFunction mainFunc, BIRFunction testExecuteFunc,
                                       AsyncDataCollector asyncDataCollector, Set<PackageID> immediateImports) {
        jvmClassMapping.forEach((moduleClass, javaClass) -> {
            ClassWriter cw = new BallerinaClassWriter(COMPUTE_FRAMES);
            asyncDataCollector.setCurrentSourceFileName(javaClass.sourceFileName);
            asyncDataCollector.setCurrentSourceFileWithoutExt(javaClass.cleanedBalFileName);
            boolean isInitClass = Objects.equals(moduleClass, moduleInitClass);
            boolean isTestable = testExecuteFunc != null;
            if (isInitClass) {
                cw.visit(V21, ACC_PUBLIC + ACC_SUPER, moduleClass, null, VALUE_CREATOR, null);
                JvmCodeGenUtil.generateInitClassConstructor(cw, VALUE_CREATOR);
                jvmTypeGen.generateUserDefinedTypeFields(cw, module.typeDefs);
                jvmTypeGen.generateGetTypeMethod(cw, moduleClass);
                jvmTypeGen.generateValueCreatorMethods(cw, moduleClass);

                // populate global variable to class name mapping and generate them
                for (BIRGlobalVariableDcl globalVar : module.globalVars) {
                    if (globalVar != null) {
                        generatePackageVariable(globalVar, cw);
                    }
                }
                MainMethodGen mainMethodGen = new MainMethodGen(symbolTable, jvmTypeGen,
                        isRemoteMgtEnabled);
                mainMethodGen.generateMainMethod(mainFunc, cw, module, moduleClass, serviceEPAvailable, isTestable);
                initMethodGen.generateLambdaForModuleExecuteFunction(cw, moduleClass, jvmCastGen, mainFunc,
                        testExecuteFunc);
                initMethodGen.generateLambdaForPackageInit(cw, module, moduleClass);
                if (isTestable) {
                    initMethodGen.generateGetTestExecutionState(cw, moduleClass);
                }
                generateLockStoreVariable(cw);
                initMethodGen.generateModuleInitializer(cw, module, moduleInitClass, typesClass);
                initMethodGen.generateModuleStop(cw, moduleInitClass, asyncDataCollector, jvmConstantsGen);
                ModuleStopMethodGen stopMethodGen = new ModuleStopMethodGen(jvmTypeGen, jvmConstantsGen);
                stopMethodGen.generateExecutionStopMethod(cw, moduleInitClass, module, asyncDataCollector,
                        immediateImports);
            } else {
                cw.visit(V21, ACC_PUBLIC + ACC_SUPER, moduleClass, null, OBJECT, null);
                JvmCodeGenUtil.generateDefaultConstructor(cw, OBJECT);
            }
            cw.visitSource(javaClass.sourceFileName, null);
            // generate methods
            for (BIRFunction func : javaClass.functions) {
                methodGen.generateMethod(func, cw, module, null, moduleClass, jvmTypeGen, jvmCastGen, jvmConstantsGen
                        , asyncDataCollector);
            }
            generateStaticInitializer(cw, moduleClass, module, isInitClass, serviceEPAvailable,
                    jvmConstantsGen);
            cw.visitEnd();
            byte[] bytes = getBytes(cw, module);
            jarEntries.put(moduleClass + CLASS_FILE_SUFFIX, bytes);
        });
    }

    /**
     * Java Class will be generated for each source file. This method add class mappings to globalVar and filters the
     * functions based on their source file name and then returns map of associated java class contents.
     *
     * @param module           bir module
     * @param initClass        module init class name
     * @param isEntry          is entry module flag
     * @return The map of javaClass records on given source file name
     */
    private Map<String, JavaClass> generateClassNameLinking(BIRPackage module, String initClass, boolean isEntry) {

        Map<String, JavaClass> jvmClassMap = new HashMap<>();

        // link global variables with class names
        linkGlobalVars(module, initClass, isEntry);

        // link module functions with class names

        linkModuleFunctions(module, initClass, isEntry, jvmClassMap);

        // link module stop function that will be generated
        linkModuleFunction(module.packageID, initClass, MODULE_STOP_METHOD);

        // link module execute function that will be generated
        linkModuleFunction(module.packageID, initClass, MODULE_EXECUTE_METHOD);

        // link typedef - object attached native functions
        linkTypeDefinitions(module, isEntry);
        return jvmClassMap;
    }

    private void linkGlobalVars(BIRPackage module, String initClass, boolean isEntry) {
        if (isEntry) {
            for (BIRNode.BIRConstant constant : module.constants) {
                module.globalVars.add(new BIRGlobalVariableDcl(constant.pos, constant.flags, constant.constValue.type,
                        null, constant.name, constant.originalName, VarScope.GLOBAL, VarKind.CONSTANT, "",
                        constant.origin));
            }
        }
        String pkgName = JvmCodeGenUtil.getPackageName(module.packageID);
        for (BIRGlobalVariableDcl globalVar : module.globalVars) {
            if (globalVar != null) {
                globalVarClassMap.put(pkgName + globalVar.name.value, initClass);
            }
        }
        globalVarClassMap.put(pkgName + LOCK_STORE_VAR_NAME, initClass);
    }

    private void linkTypeDefinitions(BIRPackage module, boolean isEntry) {
        List<BIRTypeDefinition> typeDefs = module.typeDefs;
        for (BIRTypeDefinition optionalTypeDef : typeDefs) {
            BType bType = JvmCodeGenUtil.getImpliedType(optionalTypeDef.type);
            if ((bType.tag != TypeTags.OBJECT || !Symbols.isFlagOn(bType.tsymbol.flags, Flags.CLASS))) {
                continue;
            }
            List<BIRFunction> attachedFuncs = optionalTypeDef.attachedFuncs;
            String typeName = toNameString(bType);
            for (BIRFunction func : attachedFuncs) {
                // link the bir function for lookup
                String functionName = func.name.value;
                String lookupKey = typeName + "." + functionName;
                String pkgName = JvmCodeGenUtil.getPackageName(module.packageID);
                String className = JvmValueGen.getTypeValueClassName(pkgName, typeName);
                try {
                    BIRFunctionWrapper birFuncWrapperOrError =
                            getBirFunctionWrapper(isEntry, module.packageID, func, className);
                    birFunctionMap.put(pkgName + lookupKey, birFuncWrapperOrError);
                } catch (JInteropException e) {
                    dlog.error(func.pos, e.getCode(), e.getMessage());
                }
            }
        }
    }

    private void linkModuleFunction(PackageID packageID, String initClass, String funcName) {
        BInvokableType funcType =
                new BInvokableType(typeEnv, Collections.emptyList(), null, symbolTable.nilType, null);
        BIRFunction moduleStopFunction = new BIRFunction(null, new Name(funcName), 0, funcType, new Name(""), 0,
                                                        VIRTUAL);
        birFunctionMap.put(JvmCodeGenUtil.getPackageName(packageID) + funcName,
                           getFunctionWrapper(typeEnv, moduleStopFunction, packageID, initClass));
    }

    private void linkModuleFunctions(BIRPackage birPackage, String initClass, boolean isEntry,
                                     Map<String, JavaClass> jvmClassMap) {
        // filter out functions.
        List<BIRFunction> functions = birPackage.functions;
        if (functions.isEmpty()) {
            return;
        }
        int funcSize = functions.size();
        int count = 0;
        // Generate init class. Init function should be the first function of the package, hence check first
        // function.
        BIRFunction initFunc = functions.getFirst();
        String functionName = Utils.encodeFunctionIdentifier(initFunc.name.value);
        String fileName = initFunc.pos.lineRange().fileName();
        JavaClass klass = new JavaClass(fileName, fileName);
        klass.functions.addFirst(initFunc);
        PackageID packageID = birPackage.packageID;
        jvmClassMap.put(initClass, klass);
        String pkgName = JvmCodeGenUtil.getPackageName(packageID);
        birFunctionMap.put(pkgName + functionName, getFunctionWrapper(typeEnv, initFunc, packageID, initClass));
        count += 1;

        // Add start function
        BIRFunction startFunc = functions.get(1);
        functionName = Utils.encodeFunctionIdentifier(startFunc.name.value);
        birFunctionMap.put(pkgName + functionName, getFunctionWrapper(typeEnv, startFunc, packageID, initClass));
        klass.functions.add(1, startFunc);
        count += 1;

        // Add stop function
        BIRFunction stopFunc = functions.get(2);
        functionName = Utils.encodeFunctionIdentifier(stopFunc.name.value);
        birFunctionMap.put(pkgName + functionName, getFunctionWrapper(typeEnv, stopFunc, packageID, initClass));
        klass.functions.add(2, stopFunc);
        count += 1;
        int genMethodsCount = 0;
        int genClassNum = 0;

        // Generate classes for other functions.
        while (count < funcSize) {
            BIRFunction birFunc = functions.get(count);
            count = count + 1;
            // link the bir function for lookup
            String birFuncName = birFunc.name.value;
            String balFileName;
            if (birFunc.pos == symbolTable.builtinPos) {
                balFileName = MODULE_INIT_CLASS_NAME;
            }  else if (birFunc.pos == null) {
                balFileName = MODULE_GENERATED_FUNCTIONS_CLASS_NAME + genClassNum;
                if (genMethodsCount > MAX_GENERATED_METHODS_PER_CLASS) {
                    genMethodsCount = 0;
                    genClassNum++;
                } else {
                    genMethodsCount++;
                }
            } else {
                balFileName = birFunc.pos.lineRange().fileName();
            }

            String cleanedBalFileName = balFileName;
            if (!birFunc.name.value.startsWith(MethodGenUtils.encodeModuleSpecialFuncName(".<test"))) {
                // skip removing `.bal` from generated file names. otherwise `.<testinit>` brakes because,
                // it's "file name" may end in `.bal` due to module. see #27201
                cleanedBalFileName = JvmCodeGenUtil.cleanupPathSeparators(balFileName);
            }
            String birModuleClassName = getModuleLevelClassName(packageID, cleanedBalFileName);

            if (!JvmCodeGenUtil.isBallerinaBuiltinModule(packageID.orgName.value, packageID.name.value)) {
                JavaClass javaClass = jvmClassMap.get(birModuleClassName);
                if (javaClass != null) {
                    javaClass.functions.add(birFunc);
                } else {
                    klass = new JavaClass(balFileName, cleanedBalFileName);
                    klass.functions.addFirst(birFunc);
                    jvmClassMap.put(birModuleClassName, klass);
                }
            }
            try {
                BIRFunctionWrapper birFuncWrapperOrError = getBirFunctionWrapper(isEntry, packageID, birFunc,
                                                                                 birModuleClassName);
                birFunctionMap.put(pkgName + birFuncName, birFuncWrapperOrError);
            } catch (JInteropException e) {
                dlog.error(birFunc.pos, e.getCode(), e.getMessage());
            }
        }
    }

    private BIRFunctionWrapper getBirFunctionWrapper(boolean isEntry, PackageID packageID,
                                                     BIRFunction birFunc, String birModuleClassName) {
        BIRFunctionWrapper birFuncWrapperOrError;
        if (isExternFunc(birFunc) && isEntry) {
            birFuncWrapperOrError = createExternalFunctionWrapper(typeEnv, true, birFunc, packageID,
                    birModuleClassName);
        } else {
            if (isEntry && birFunc.receiver == null) {
                addDefaultableBooleanVarsToSignature(typeEnv, birFunc);
            }
            birFuncWrapperOrError = getFunctionWrapper(typeEnv, birFunc, packageID, birModuleClassName);
        }
        return birFuncWrapperOrError;
    }

    public byte[] getBytes(ClassWriter cw, BIRNode node) {
        byte[] result;
        try {
            return cw.toByteArray();
        } catch (MethodTooLargeException e) {
            String funcName = e.getMethodName();
            BIRFunction func = findFunction(node, funcName);
            if (func != null && func.pos != null) {
                dlog.error(func.pos, DiagnosticErrorCode.METHOD_TOO_LARGE,
                        Utils.decodeIdentifier(func.name.value));
            } else {
                dlog.error(node.pos, DiagnosticErrorCode.METHOD_TOO_LARGE,
                        Utils.decodeIdentifier(funcName));
            }
            result = new byte[0];
        } catch (ClassTooLargeException e) {
            dlog.error(node.pos, DiagnosticErrorCode.FILE_TOO_LARGE,
                    Utils.decodeIdentifier(e.getClassName()));
            result = new byte[0];
        } catch (Throwable e) {
            throw new BLangCompilerException(e.getMessage(), e);
        }
        return result;
    }

    private void clearPackageGenInfo() {
        birFunctionMap.clear();
        globalVarClassMap.clear();
    }

    public BIRFunctionWrapper lookupBIRFunctionWrapper(String lookupKey) {
        return this.birFunctionMap.get(lookupKey);
    }

    BType lookupTypeDef(NewInstance objectNewIns) {
        if (!objectNewIns.isExternalDef) {
            return objectNewIns.def.type;
        } else {
            PackageID id = objectNewIns.externalPackageId;
            assert id != null;
            BPackageSymbol symbol = packageCache.getSymbol(id.orgName + "/" + id.name);
            if (symbol != null) {
                Name lookupKey = new Name(Utils.decodeIdentifier(objectNewIns.objectName));
                BSymbol typeSymbol = symbol.scope.lookup(lookupKey).symbol;
                BObjectTypeSymbol objectTypeSymbol;
                if (typeSymbol.kind == SymbolKind.TYPE_DEF) {
                    objectTypeSymbol = (BObjectTypeSymbol) typeSymbol.type.tsymbol;
                } else {
                    //class symbols
                    objectTypeSymbol = (BObjectTypeSymbol) typeSymbol;
                }
                if (objectTypeSymbol != null) {
                    return objectTypeSymbol.type;
                }
            }
            throw new BLangCompilerException("Reference to unknown type " + objectNewIns.externalPackageId
                    + "/" + objectNewIns.objectName);
        }
    }

    public String lookupGlobalVarClassName(String pkgName, String varName) {
        String key = pkgName + varName;
        if (!globalVarClassMap.containsKey(key)) {
            return pkgName + MODULE_INIT_CLASS_NAME;
        } else {
            return globalVarClassMap.get(key);
        }
    }

    CompiledJarFile generate(BIRPackage module) {
        boolean serviceEPAvailable = module.isListenerAvailable;
        for (BIRNode.BIRImportModule importModule : module.importModules) {
            BPackageSymbol pkgSymbol = packageCache.getSymbol(
                    getBvmAlias(importModule.packageID.orgName.value, importModule.packageID.name.value));
            if (pkgSymbol.bir != null) {
                String moduleInitClass =
                        JvmCodeGenUtil.getModuleLevelClassName(pkgSymbol.bir.packageID, MODULE_INIT_CLASS_NAME);
                generateClassNameLinking(pkgSymbol.bir, moduleInitClass, false);
            }
            serviceEPAvailable |= listenerDeclarationFound(pkgSymbol);
        }
        String moduleInitClass = JvmCodeGenUtil.getModuleLevelClassName(module.packageID, MODULE_INIT_CLASS_NAME);
        String typesClass = getModuleLevelClassName(module.packageID, MODULE_TYPES_CLASS_NAME);
        Map<String, JavaClass> jvmClassMapping = generateClassNameLinking(module, moduleInitClass, true);

        CompiledJarFile compiledJarFile = new CompiledJarFile(getModuleLevelClassName(module.packageID,
                MODULE_INIT_CLASS_NAME, "."));
        // use a ByteArrayOutputStream to store class byte values
        final JarEntries jarEntries = compiledJarFile.jarEntries;
        // desugar parameter initialization
        injectDefaultParamInits(typeEnv, module, initMethodGen);
        injectDefaultParamInitsToAttachedFuncs(typeEnv, module, initMethodGen);

        BIRFunction mainFunc = getMainFunction(module);
        BIRFunction testExecuteFunc = getTestExecuteFunction(module);

        // Getting the non-duplicate immediateImports
        Set<PackageID> immediateImports = new LinkedHashSet<>();
        addBuiltinImports(module.packageID, immediateImports);
        for (BIRNode.BIRImportModule immediateImport : module.importModules) {
            BPackageSymbol pkgSymbol = packageCache.getSymbol(
                    getBvmAlias(immediateImport.packageID.orgName.value, immediateImport.packageID.name.value));
            immediateImports.add(pkgSymbol.pkgID);
        }

        // enrich current package with package initializers
        initMethodGen.enrichPkgWithInitializers(birFunctionMap, jvmClassMapping, moduleInitClass, module,
                immediateImports, mainFunc, testExecuteFunc);
        TypeHashVisitor typeHashVisitor = new TypeHashVisitor();
        AsyncDataCollector asyncDataCollector = new AsyncDataCollector(module);
        JvmConstantsGen jvmConstantsGen = new JvmConstantsGen(module, moduleInitClass, types, typeHashVisitor);
        JvmTypeGen jvmTypeGen = new JvmTypeGen(jvmConstantsGen, module.packageID, typeHashVisitor, symbolTable);
        JvmMethodsSplitter jvmMethodsSplitter = new JvmMethodsSplitter(this, jvmConstantsGen, module, moduleInitClass,
                typeHashVisitor, jvmTypeGen);
        configMethodGen.generateConfigMapper(immediateImports, module, moduleInitClass, jvmConstantsGen,
                                             typeHashVisitor, jarEntries, symbolTable);

        // generate the shutdown listener class.
        new ShutDownListenerGen().generateShutdownSignalListener(moduleInitClass, jarEntries);

        removeSourceAnnotationTypeDefs(module.typeDefs);
        // desugar the record init function
        rewriteRecordInits(typeEnv, module.typeDefs);

        // generate object/record value classes
        JvmValueGen valueGen = new JvmValueGen(module, this, methodGen, typeHashVisitor, types);
        JvmCastGen jvmCastGen = new JvmCastGen(symbolTable, jvmTypeGen, types);
        LambdaGen lambdaGen = new LambdaGen(this, jvmCastGen, module);
        valueGen.generateValueClasses(jarEntries, jvmConstantsGen, jvmTypeGen, asyncDataCollector);

        // generate module classes
        generateModuleClasses(module, jarEntries, moduleInitClass, typesClass, jvmTypeGen, jvmCastGen, jvmConstantsGen,
                jvmClassMapping, serviceEPAvailable, mainFunc, testExecuteFunc, asyncDataCollector, immediateImports);

        List<BIRNode.BIRFunction> sortedFunctions = new ArrayList<>(module.functions);
        sortedFunctions.sort(NAME_HASH_COMPARATOR);
        jvmMethodsSplitter.generateMethods(jarEntries, jvmCastGen, sortedFunctions);
        jvmConstantsGen.generateConstants(jarEntries);
        lambdaGen.generateLambdaClasses(asyncDataCollector, jarEntries);

        // clear class name mappings
        clearPackageGenInfo();
        return compiledJarFile;
    }

    private void removeSourceAnnotationTypeDefs(List<BIRTypeDefinition> typeDefs) {
        typeDefs.removeIf(def -> Symbols.isFlagOn(def.flags, Flags.SOURCE_ANNOTATION));
    }

    private BIRFunction getMainFunction(BIRPackage module) {
        BIRFunction mainFunc = null;
        if (module.packageID.skipTests) {
            mainFunc = getFunction(module, MAIN_METHOD);
        }
        return mainFunc;
    }

    private BIRFunction getTestExecuteFunction(BIRPackage module) {
        BIRFunction testExecuteFunc = null;
        if (!module.packageID.skipTests) {
            testExecuteFunc = getFunction(module, TEST_EXECUTE_METHOD);
        }
        return testExecuteFunc;
    }

    private BIRFunction getFunction(BIRPackage module, String funcName) {
        BIRFunction function = null;
        for (BIRFunction birFunc : module.functions) {
            if (birFunc.name.value.equals(funcName)) {
                function = birFunc;
                break;
            }
        }
        return function;
    }

    private boolean listenerDeclarationFound(BPackageSymbol packageSymbol) {
        if (packageSymbol.bir == null) {
            for (Scope.ScopeEntry entry : packageSymbol.scope.entries.values()) {
                BSymbol symbol = entry.symbol;
                if (symbol != null && Symbols.isFlagOn(symbol.flags, Flags.LISTENER)) {
                    return true;
                }
            }
        } else {
            return packageSymbol.bir.isListenerAvailable;
        }
        for (BPackageSymbol importPkgSymbol : packageSymbol.imports) {
            if (importPkgSymbol != null && listenerDeclarationFound(importPkgSymbol)) {
                return true;
            }
        }
        return false;
    }
}
