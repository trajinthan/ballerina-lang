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
package org.ballerinalang.diagramutil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.syntax.tree.ClassDefinitionNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.compiler.syntax.tree.TypeDefinitionNode;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * This is the DiagramUtil class for Diagram related Utils which include the JSON conversion of the Syntax Tree.
 */
public class DiagramUtil {

    /**
     * Get the Modified JSON ST with type info.
     *
     * @param syntaxTree    SyntaxTree to be modified and in need to convert to JSON.
     * @param semanticModel Semantic model for the syntax tree.
     * @return {@link JsonObject}   ST as a Json Object
     */
    public static JsonElement getSyntaxTreeJSON(SyntaxTree syntaxTree, SemanticModel semanticModel) {
        JsonElement syntaxTreeJson;
        try {
            // Map each type data by looking at the line ranges and prepare the SyntaxTree JSON.
            Path filePath = Paths.get(syntaxTree.filePath());
            String fileName = filePath.getFileName() != null ? filePath.getFileName().toString() : "";
            SyntaxTreeMapGenerator mapGenerator = new SyntaxTreeMapGenerator(fileName, semanticModel);
            ModulePartNode modulePartNode = syntaxTree.rootNode();
            syntaxTreeJson = mapGenerator.transform(modulePartNode);
        } catch (NullPointerException e) {
            syntaxTreeJson = new JsonObject();
        }

        return syntaxTreeJson;
    }

    public static JsonElement getClassDefinitionSyntaxJson(ClassDefinitionNode classDefinitionNode,
                                                           SemanticModel semanticModel) {
        JsonElement syntaxTreeJson;
        try {
            SyntaxTreeMapGenerator mapGenerator = new SyntaxTreeMapGenerator(
                    classDefinitionNode.syntaxTree().filePath(), semanticModel);
            syntaxTreeJson = mapGenerator.transform(classDefinitionNode);
        } catch (NullPointerException e) {
            syntaxTreeJson = new JsonObject();
        }

        return syntaxTreeJson;
    }

    public static JsonElement getTypeDefinitionSyntaxJson(TypeDefinitionNode typeDefinitionNode,
                                                          SemanticModel semanticModel) {
        JsonElement syntaxTreeJson;
        try {
            SyntaxTreeMapGenerator mapGenerator = new SyntaxTreeMapGenerator(
                    typeDefinitionNode.syntaxTree().filePath(), semanticModel);
            syntaxTreeJson = mapGenerator.transform(typeDefinitionNode);
        } catch (NullPointerException e) {
            syntaxTreeJson = new JsonObject();
        }

        return syntaxTreeJson;
    }

}
