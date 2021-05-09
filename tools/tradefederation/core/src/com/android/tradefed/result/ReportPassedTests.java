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
package com.android.tradefed.result;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.testtype.suite.ModuleDefinition;

import java.util.HashMap;

/** Report in a file possible filters to exclude passed test. */
public class ReportPassedTests extends CollectingTestListener {

    private static final String PASSED_TEST_LOG = "passed_tests";
    private boolean mInvocationFailed = false;
    private ITestLogger mLogger;

    public void setLogger(ITestLogger logger) {
        mLogger = logger;
    }

    @Override
    public void testRunEnded(long elapsedTime, HashMap<String, Metric> runMetrics) {
        super.testRunEnded(elapsedTime, runMetrics);
        if (getCurrentRunResults().hasFailedTests() || getCurrentRunResults().isRunFailure()) {
            clearResultsForName(getCurrentRunResults().getName());
        }
    }

    @Override
    public void invocationFailed(FailureDescription failure) {
        super.invocationFailed(failure);
        mInvocationFailed = true;
    }

    @Override
    public void invocationEnded(long elapsedTime) {
        super.invocationEnded(elapsedTime);
        if (mInvocationFailed) {
            return;
        }
        createPassedLog();
    }

    private void createPassedLog() {
        if (mLogger == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        // TODO: Support more granular than module level
        for (TestRunResult result : getMergedTestRunResults()) {
            IInvocationContext context = getModuleContextForRunResult(result.getName());
            // If it's a test module
            if (context != null) {
                sb.append(context.getAttributes().getUniqueMap().get(ModuleDefinition.MODULE_ID));
                sb.append("\n");
            } else {
                sb.append(result.getName());
                sb.append("\n");
            }
        }
        if (sb.length() == 0) {
            return;
        }
        try (ByteArrayInputStreamSource source =
                new ByteArrayInputStreamSource(sb.toString().getBytes())) {
            mLogger.testLog(PASSED_TEST_LOG, LogDataType.PASSED_TESTS, source);
        }
    }
}
