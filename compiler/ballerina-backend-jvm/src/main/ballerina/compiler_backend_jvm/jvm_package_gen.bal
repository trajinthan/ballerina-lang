// Copyright (c) 2019 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

final map<string> fullQualifiedClassNames = {};

final map<(bir:AsyncCall,string)> lambdas = {};

function lookupFullQualifiedClassName(string key) returns string {
    var result = fullQualifiedClassNames[key];

    if (result is string) {
        return result;
    } else {
        (string, string) (pkgName, functionName) = getPackageAndFunctionName(key);
        result = jvm:lookupExternClassName(pkgName, functionName);
        if (result is string) {
            fullQualifiedClassNames[key] = result;
            return result;
        }
        error err = error("cannot find full qualified class for : " + key);
        panic err;
    }
}

public function generateImportedPackage(bir:Package module, map<byte[]> pkgEntries) {

    // generate imported modules recursively
    foreach var mod in module.importModules {
        bir:Package importedPkg = lookupModule(mod, currentBIRContext);
        generateImportedPackage(importedPkg, pkgEntries);
    }

    string orgName = module.org.value;
    string moduleName = module.name.value;
    string pkgName = getPackageName(orgName, moduleName);
    string sourceFileName = module.name.value;

    // generate object value classes
    ObjectGenerator objGen = new(module);

    map<JavaClass> jvmClassMap = generateJavaClassMap(module, untaint lambdas);
    
  
    foreach var (k,v) in jvmClassMap {
        string className = cleanupFileName(k);
        
        boolean isInitClass = false;
        if( className == "." || className == moduleName) {
            className = INIT_CLASS_NAME;
            isInitClass = true;
        }
        string moduleClass = getModuleLevelClassName(untaint orgName, untaint moduleName, untaint className);
        objGen.generateValueClasses(v.typeDefs, pkgEntries);
        generateFrameClasses(module, v, pkgEntries);

        jvm:ClassWriter cw = new(COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, moduleClass, (), OBJECT, ());
        cw.visitSource(k);
        generateDefaultConstructor(cw);

        generateUserDefinedTypeFields(cw, v.typeDefs);

        // populate global variable to class name mapping and generate them
        if (isInitClass) {
            foreach var globalVar in module.globalVars {
                if (globalVar is bir:GlobalVariableDcl) {
                    fullQualifiedClassNames[pkgName + globalVar.name.value] = moduleClass;
                    generatePackageVariable(globalVar, cw);
                }
            }
        }

        // populate function to class name mapping
        foreach var func in v.functions {
            fullQualifiedClassNames[pkgName + getFunction(func).name.value] = moduleClass;
        }

        // generate methods
        foreach var func in v.functions {
            generateMethod(getFunction(func), cw, module);
        }

        cw.visitEnd();

        byte[] classContent = cw.toByteArray();
        pkgEntries[moduleClass + ".class"] = classContent;
    }
}

public function generateEntryPackage(bir:Package module, string sourceFileName, map<byte[]> pkgEntries,
        map<string> manifestEntries) {

    string orgName = module.org.value;
    string moduleName = module.name.value;
    string pkgName = getPackageName(orgName, moduleName);
    map<JavaClass> jvmClassMap = generateJavaClassMap(module, untaint lambdas);
    
    // generate object value classes
    ObjectGenerator objGen = new(module);
    bir:Function? mainFunc = getMainFunc(module.functions);
    string mainClass = "";
    if (mainFunc is bir:Function) {
        mainClass = getModuleLevelClassName(untaint orgName, untaint moduleName,
                                                   cleanupFileName(mainFunc.pos.sourceFileName));
    }
    foreach var (k,v) in jvmClassMap {
        string className = cleanupFileName(k);
        boolean isInitClass = false;
        if( className == "." || className == pkgName) {
            className = INIT_CLASS_NAME;
            isInitClass = true;
        }
        string moduleClass = getModuleLevelClassName(untaint orgName, untaint moduleName, untaint className);
        objGen.generateValueClasses(v.typeDefs, pkgEntries);
        generateFrameClasses(module, v, pkgEntries);

        jvm:ClassWriter cw = new(COMPUTE_FRAMES);
        cw.visitSource(k);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, moduleClass, (), OBJECT, ());
        generateDefaultConstructor(cw);

        generateUserDefinedTypeFields(cw, v.typeDefs);

        // populate global variable to class name mapping and generate them
        if (isInitClass) {
            foreach var globalVar in module.globalVars {
                if (globalVar is bir:GlobalVariableDcl) {
                    fullQualifiedClassNames[pkgName + globalVar.name.value] = moduleClass;
                    generatePackageVariable(globalVar, cw);
                }
            }
        }

        // populate function to class name mapping
        foreach var func in v.functions {
            fullQualifiedClassNames[pkgName + getFunction(func).name.value] = moduleClass;
        }

       
        if (isInitClass && mainFunc is bir:Function) {
            generateMainMethod(mainFunc, cw, module, mainClass, moduleClass);
            manifestEntries["Main-Class"] = moduleClass;
        }

        // generate methods
        foreach var func in v.functions {
            generateMethod(getFunction(func), cw, module);
        }

        foreach var (name, call) in v.lambdaCalls {
            generateLambdaMethod(call[0], cw, call[1], name);
        }

        cw.visitEnd();

        byte[] classContent = cw.toByteArray();
        pkgEntries[moduleClass + ".class"] = classContent;
    }
}

function generatePackageVariable(bir:GlobalVariableDcl globalVar, jvm:ClassWriter cw) {
    string varName = globalVar.name.value;
    bir:BType bType = globalVar.typeValue;
    generateField(cw, bType, varName);
}

function lookupModule(bir:ImportModule importModule, bir:BIRContext birContext) returns bir:Package {
    bir:ModuleID moduleId = {org: importModule.modOrg.value, name: importModule.modName.value,
                                modVersion: importModule.modVersion.value};
    return birContext.lookupBIRModule(moduleId);
}

function getModuleLevelClassName(string orgName, string moduleName, string sourceFileName) returns string {
    if (!moduleName.equalsIgnoreCase(".") && !orgName.equalsIgnoreCase("$anon")) {
        return orgName + "/" + cleanupName(moduleName) + "/" + cleanupName(sourceFileName);
    }
    return cleanupName(sourceFileName);
}

function getMainClassName(string orgName, string moduleName, string sourceFileName) returns string {
    if (!moduleName.equalsIgnoreCase(".") && !orgName.equalsIgnoreCase("$anon")) {
        return orgName + "." + cleanupName(moduleName) + "." + cleanupName(sourceFileName);
    }
    return cleanupName(sourceFileName);
}

function getPackageName(string orgName, string moduleName) returns string {
    if (!moduleName.equalsIgnoreCase(".") && !orgName.equalsIgnoreCase("$anon")) {
        return orgName + "/" + cleanupName(moduleName) + "/";
    }
    return "";
}

function getPackageAndFunctionName(string key) returns (string, string) {
    int index = key.lastIndexOf("/");
    string pkgName = key.substring(0, index);
    string functionName = key.substring(index + 1, key.length());

    return (pkgName, functionName);
}

function cleanupName(string name) returns string {
    return name.replace(".","_");
}

# Java Class will be generate for each source file. This method filters the functions, type defs and catagorize based
# on their source file name and then returns map of associated java class contents.
#
# + module - The module
# + lambdaCalls - The lambdas
# + return - The map of javaClass records on given source file name
function generateJavaClassMap(bir:Package module, map<(bir:AsyncCall,string)> lambdaCalls) returns map<JavaClass> {
    map<JavaClass> jvmClassMap = {};
    foreach var func in module.functions {
        string? balFileName = func.pos.sourceFileName;
        if (balFileName is string) {
            var javaClass = jvmClassMap[balFileName];
            if (javaClass is JavaClass) {
                javaClass.functions[javaClass.functions.length()] = func;
            } else {
                JavaClass class = {sourceFileName:balFileName};
                class.functions[0] = func;
                jvmClassMap[balFileName] = class;
            }
        }
    }

    foreach var typeDef in module.typeDefs {
        string? balFileName = typeDef.pos.sourceFileName;
        if (balFileName is string) {
            var javaClass = jvmClassMap[balFileName];
            if (javaClass is JavaClass) {
                javaClass.typeDefs[javaClass.typeDefs.length()] = typeDef;
            } else {
                JavaClass class = {sourceFileName:balFileName};
                class.typeDefs[0] = typeDef;
                jvmClassMap[balFileName] = class;
            }
        }
    }

    foreach var (k,v) in lambdaCalls {
        string? balFileName = v[0].pos.sourceFileName;
        if (balFileName is string) {
            var javaClass = jvmClassMap[balFileName];
            if (javaClass is JavaClass) {
                javaClass.lambdaCalls[k] = (v[0], v[1]);
            } else {
                JavaClass class = {sourceFileName:balFileName};
                class.lambdaCalls[k] = (v[0], v[1]);
                jvmClassMap[balFileName] = class;
            }
        }
    }
    return jvmClassMap;
}
