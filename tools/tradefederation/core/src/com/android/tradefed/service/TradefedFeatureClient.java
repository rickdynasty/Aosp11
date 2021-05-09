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

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.StreamUtil;

import com.proto.tradefed.feature.ErrorInfo;
import com.proto.tradefed.feature.FeatureRequest;
import com.proto.tradefed.feature.FeatureResponse;
import com.proto.tradefed.feature.TradefedInformationGrpc;
import com.proto.tradefed.feature.TradefedInformationGrpc.TradefedInformationBlockingStub;

import java.util.Map;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.DnsNameResolverProvider;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

/** A grpc client to request feature execution from the server. */
public class TradefedFeatureClient implements AutoCloseable {

    private TradefedInformationBlockingStub mBlockingStub;
    private ManagedChannel mChannel;

    public TradefedFeatureClient() {
        mChannel =
                NettyChannelBuilder.forAddress("localhost", TradefedFeatureServer.getPort())
                        .nameResolverFactory(new DnsNameResolverProvider())
                        .usePlaintext()
                        .build();
        mBlockingStub = TradefedInformationGrpc.newBlockingStub(mChannel);
    }

    public FeatureResponse triggerFeature(String featureName, Map<String, String> args) {
        try {
            CLog.d("invoking feature '%s'", featureName);
            return mBlockingStub.triggerFeature(
                    FeatureRequest.newBuilder().setName(featureName).putAllArgs(args).build());
        } catch (StatusRuntimeException e) {
            return FeatureResponse.newBuilder()
                    .setErrorInfo(
                            ErrorInfo.newBuilder()
                                    .setErrorTrace(StreamUtil.getStackTrace(e))
                                    .build())
                    .build();
        }
    }

    @Override
    public void close() {
        mChannel.shutdown();
    }
}
