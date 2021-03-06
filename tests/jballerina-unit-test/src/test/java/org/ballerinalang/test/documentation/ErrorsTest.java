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

package org.ballerinalang.test.documentation;

import org.ballerinalang.docgen.docs.BallerinaDocGenerator;
import org.ballerinalang.docgen.generator.model.Module;
import org.ballerinalang.docgen.generator.model.ModuleDoc;
import org.ballerinalang.docgen.generator.model.Project;
import org.ballerinalang.test.BCompileUtil;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Test cases related to error types in docs.
 *
 * @since 2.0
 */
public class ErrorsTest {
    private Module testModule;

    @BeforeClass
    public void setup() throws IOException {
        String sourceRoot =
                "test-src" + File.separator + "documentation" + File.separator + "errors_project";
        io.ballerina.projects.Project project = BCompileUtil.loadProject(sourceRoot);
        Map<String, ModuleDoc> moduleDocMap = BallerinaDocGenerator.generateModuleDocMap(project);
        Project docerinaProject = BallerinaDocGenerator.getDocsGenModel(moduleDocMap, project.currentPackage()
                .packageOrg().toString(), project.currentPackage().packageVersion().toString());
        testModule = docerinaProject.modules.get(0);
    }

    @Test(description = "Test error type")
    public void testErrorAsAType() {
        Assert.assertEquals(testModule.errors.size(), 2, "Two errors expected");
        Assert.assertEquals(testModule.errors.get(0).name, "CacheError", "The error should be " +
                "CacheError. But Found:" + testModule.errors.get(0).name);
        Assert.assertEquals(testModule.errors.get(1).name, "Error", "The error should be " +
                "Error. But Found:" + testModule.errors.get(1).name);
    }
}
