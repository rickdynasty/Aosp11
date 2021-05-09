/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.compatibility.common.tradefed.result.suite;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.tradefed.config.Option;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.proto.FileProtoResultReporter;
import com.android.tradefed.result.proto.ProtoResultParser;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;
import com.android.tradefed.util.FileUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Proto reporter that will drop a {@link TestRecord} protobuf in the result directory. */
public class CompatibilityProtoResultReporter extends FileProtoResultReporter {

    public static final String PROTO_FILE_NAME = "test-record.pb";
    public static final String PROTO_DIR = "proto";

    @Option(
            name = "skip-proto-compacting",
            description = "Option to disable compacting the protos at the end")
    private boolean mSkipProtoCompacting = false;

    private CompatibilityBuildHelper mBuildHelper;

    /** The directory containing the proto results */
    private File mResultDir = null;

    private File mBaseProtoFile = null;

    @Override
    public void processStartInvocation(
            TestRecord invocationStartRecord, IInvocationContext invocationContext) {
        if (mBuildHelper == null) {
            mBuildHelper = new CompatibilityBuildHelper(invocationContext.getBuildInfos().get(0));
            mResultDir = getProtoResultDirectory();
            mBaseProtoFile = new File(mResultDir, PROTO_FILE_NAME);
            setFileOutput(mBaseProtoFile);
        }
        super.processStartInvocation(invocationStartRecord, invocationContext);
    }

    @Override
    public void processFinalProto(TestRecord invocationEndedProto) {
        super.processFinalProto(invocationEndedProto);

        if (!isPeriodicWriting()) {
            return;
        }
        if (mSkipProtoCompacting) {
            return;
        }
        // Compact all the protos
        try {
            compactAllProtos();
        } catch (RuntimeException e) {
            CLog.e("Failed to compact the protos");
            CLog.e(e);
            FileUtil.deleteFile(mBaseProtoFile);
            return;
        }
        // Delete all the protos we compacted
        int index = 0;
        while (new File(mBaseProtoFile.getAbsolutePath() + index).exists()) {
            FileUtil.deleteFile(new File(mBaseProtoFile.getAbsolutePath() + index));
            index++;
        }
    }

    private File getProtoResultDirectory() {
        File protoDir = null;
        try {
            File resultDir = mBuildHelper.getResultDir();
            if (resultDir != null) {
                resultDir.mkdirs();
            }
            protoDir = new File(resultDir, PROTO_DIR);
            protoDir.mkdir();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        if (!protoDir.exists()) {
            throw new RuntimeException(
                    "Result Directory was not created: " + protoDir.getAbsolutePath());
        }
        CLog.d("Proto Results Directory: %s", protoDir.getAbsolutePath());
        return protoDir;
    }

    private void compactAllProtos() {
        FileProtoResultReporter fprr = new FileProtoResultReporter();
        fprr.setFileOutput(mBaseProtoFile);
        ProtoResultParser parser = new ProtoResultParser(fprr, new InvocationContext(), true);
        int index = 0;
        while (new File(mBaseProtoFile.getAbsolutePath() + index).exists()) {
            try {
                parser.processFileProto(new File(mBaseProtoFile.getAbsolutePath() + index));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            index++;
        }
    }
}