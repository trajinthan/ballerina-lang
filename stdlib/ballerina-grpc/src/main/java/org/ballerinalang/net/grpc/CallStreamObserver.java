/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.ballerinalang.net.grpc;

/**
 * Receives notifications from an observable stream of messages.
 * <p>
 * Referenced from grpc-java implementation.
 * <p>
 * <p>It is used by both the client stubs and service implementations for sending or receiving
 * stream messages.
 *
 * @param <V> Request/Response Message Type.
 */
public abstract class CallStreamObserver<V> implements StreamObserver<V> {

    /**
     * Indicates that the observer is capable of sending additional messages.
     *
     * @return true, if the stream is open and ready to send messages, false otherwise.
     */
    public abstract boolean isReady();

    /**
     * Sets message compression for subsequent calls.
     *
     * @param enable flag to enable compression.
     */
    public abstract void setMessageCompression(boolean enable);
}
