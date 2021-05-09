/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tradefed.service;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.log.LogUtil.CLog;

import com.proto.tradefed.feature.ErrorInfo;
import com.proto.tradefed.feature.FeatureRequest;
import com.proto.tradefed.feature.FeatureResponse;
import com.proto.tradefed.feature.TradefedInformationGrpc.TradefedInformationImplBase;

import java.io.IOException;
import java.util.ServiceLoader;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

/** A server that responds to requests for triggering features. */
public class TradefedFeatureServer extends TradefedInformationImplBase {

    private static final int DEFAULT_PORT = 8889;
    private static final String TF_SERVICE_PORT = "TF_SERVICE_PORT";
    private Server mServer;

    /** Returns the port used by the server. */
    public static int getPort() {
        return System.getenv(TF_SERVICE_PORT) != null
                ? Integer.parseInt(System.getenv(TF_SERVICE_PORT))
                : DEFAULT_PORT;
    }

    public TradefedFeatureServer() {
        this(ServerBuilder.forPort(getPort()));
    }

    @VisibleForTesting
    TradefedFeatureServer(ServerBuilder<?> serverBuilder) {
        mServer = serverBuilder.addService(this).build();
    }

    /** Start the grpc server to listen to requests. */
    public void start() {
        try {
            CLog.d("Starting feature server.");
            mServer.start();
        } catch (IOException e) {
            CLog.w("TradefedFeatureServer already started: %s", e.getMessage());
        }
    }

    /** Stop the grpc server. */
    public void shutdown() throws InterruptedException {
        if (mServer != null) {
            CLog.d("Stopping feature server.");
            mServer.shutdown();
            mServer.awaitTermination();
        }
    }

    @Override
    public void triggerFeature(
            FeatureRequest request, StreamObserver<FeatureResponse> responseObserver) {
        responseObserver.onNext(createResponse(request));
        responseObserver.onCompleted();
    }

    private FeatureResponse createResponse(FeatureRequest request) {
        ServiceLoader<IRemoteFeature> serviceLoader = ServiceLoader.load(IRemoteFeature.class);
        for (IRemoteFeature feature : serviceLoader) {
            if (feature.getName().equals(request.getName())) {
                return feature.execute(request);
            }
        }
        return FeatureResponse.newBuilder()
                .setErrorInfo(
                        ErrorInfo.newBuilder()
                                .setErrorTrace(
                                        String.format(
                                                "No feature matching the requested one '%s'",
                                                request.getName())))
                .build();
    }
}
