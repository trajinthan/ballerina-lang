// Copyright (c) 2020 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

import ballerina/io;

# Prints `Hello World`.
# Contains an if condition to test the code coverage

public function main() {
    // counter
    // test
    int a = 10;
    if (a == 10) {
        io:println("a is equal to 10");
    } else {
        io:println("a is notttttt equal to 10");
    }
   
    printHello();
}

// Prints hello
public function printHello() {
    // Prints hello
    io:println("Hello World!!");
}
