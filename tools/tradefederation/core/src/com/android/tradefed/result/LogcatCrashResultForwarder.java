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
package com.android.tradefed.result;

import com.android.loganalysis.item.JavaCrashItem;
import com.android.loganalysis.item.LogcatItem;
import com.android.loganalysis.parser.LogcatParser;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.TestErrorIdentifier;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;

import com.google.common.collect.ImmutableList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Special listener: on failures (instrumentation process crashing) it will attempt to extract from
 * the logcat the crash and adds it to the failure message associated with the test.
 */
public class LogcatCrashResultForwarder extends ResultForwarder {

    /** Special error message from the instrumentation when something goes wrong on device side. */
    public static final String ERROR_MESSAGE = "Process crashed.";
    public static final String SYSTEM_CRASH_MESSAGE = "System has crashed.";
    public static final List<String> TIMEOUT_MESSAGES =
            ImmutableList.of(
                    "Failed to receive adb shell test output",
                    "TimeoutException when running tests",
                    "TestTimedOutException: test timed out after");

    public static final int MAX_NUMBER_CRASH = 3;

    private Long mStartTime = null;
    private Long mLastStartTime = null;
    private ITestDevice mDevice;
    private LogcatItem mLogcatItem = null;

    public LogcatCrashResultForwarder(ITestDevice device, ITestInvocationListener... listeners) {
        super(listeners);
        mDevice = device;
    }

    public ITestDevice getDevice() {
        return mDevice;
    }

    @Override
    public void testStarted(TestDescription test, long startTime) {
        mStartTime = startTime;
        super.testStarted(test, startTime);
    }

    @Override
    public void testFailed(TestDescription test, String trace) {
        testFailed(test, FailureDescription.create(trace));
    }

    @Override
    public void testFailed(TestDescription test, FailureDescription failure) {
        if (FailureStatus.NOT_EXECUTED.equals(failure.getFailureStatus())) {
            super.testFailed(test, failure);
            return;
        }
        // If the test case was detected as crashing the instrumentation, we add the crash to it.
        String trace = extractCrashAndAddToMessage(failure.getErrorMessage(), mStartTime);
        if (isCrash(failure.getErrorMessage())) {
            failure.setErrorIdentifier(DeviceErrorIdentifier.INSTRUMENTATION_CRASH);
        } else if (isTimeout(failure.getErrorMessage())) {
            failure.setErrorIdentifier(TestErrorIdentifier.INSTRUMENTATION_TIMED_OUT);
        }
        failure.setErrorMessage(trace);
        // Add metrics for assessing uncaught IntrumentationTest crash failures (test level).
        InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.TEST_CRASH_FAILURES, 1);
        if (failure.getFailureStatus() == null) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.UNCAUGHT_TEST_CRASH_FAILURES, 1);
        }
        super.testFailed(test, failure);
    }

    @Override
    public void testEnded(TestDescription test, long endTime, HashMap<String, Metric> testMetrics) {
        super.testEnded(test, endTime, testMetrics);
        mLastStartTime = mStartTime;
        mStartTime = null;
    }

    @Override
    public void testRunFailed(String errorMessage) {
        testRunFailed(FailureDescription.create(errorMessage, FailureStatus.TEST_FAILURE));
    }

    @Override
    public void testRunFailed(FailureDescription error) {
        // Also add the failure to the run failure if the testFailed generated it.
        // A Process crash would end the instrumentation, so a testRunFailed is probably going to
        // be raised for the same reason.
        String errorMessage = error.getErrorMessage();
        if (mLogcatItem != null) {
            errorMessage = addJavaCrashToString(mLogcatItem, errorMessage);
            mLogcatItem = null;
        } else {
            errorMessage = extractCrashAndAddToMessage(errorMessage, mLastStartTime);
        }
        error.setErrorMessage(errorMessage);
        if (isCrash(errorMessage)) {
            error.setErrorIdentifier(DeviceErrorIdentifier.INSTRUMENTATION_CRASH);
        }
        // Add metrics for assessing uncaught IntrumentationTest crash failures.
        InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.CRASH_FAILURES, 1);
        if (error.getFailureStatus() == null) {
            InvocationMetricLogger.addInvocationMetrics(
                    InvocationMetricKey.UNCAUGHT_CRASH_FAILURES, 1);
        }
        super.testRunFailed(error);
    }

    @Override
    public void testRunEnded(long elapsedTime, HashMap<String, Metric> runMetrics) {
        super.testRunEnded(elapsedTime, runMetrics);
        mLastStartTime = null;
    }

    /** Attempt to extract the crash from the logcat if the test was seen as started. */
    private String extractCrashAndAddToMessage(String errorMessage, Long startTime) {
        if (isCrash(errorMessage) && startTime != null) {
            mLogcatItem = extractLogcat(mDevice, startTime);
            errorMessage = addJavaCrashToString(mLogcatItem, errorMessage);
        }
        return errorMessage;
    }

    private boolean isCrash(String errorMessage) {
        return errorMessage.contains(ERROR_MESSAGE) || errorMessage.contains(SYSTEM_CRASH_MESSAGE);
    }

    private boolean isTimeout(String errorMessage) {
        for (String timeoutMessage : TIMEOUT_MESSAGES) {
            if (errorMessage.contains(timeoutMessage)) {
                return true;
            }
        }
        return false;
    }
    /**
     * Extract a formatted object from the logcat snippet.
     *
     * @param device The device from which to pull the logcat.
     * @param startTime The beginning time of the last tests.
     * @return A {@link LogcatItem} that contains the information inside the logcat.
     */
    private LogcatItem extractLogcat(ITestDevice device, long startTime) {
        try (InputStreamSource logSource = device.getLogcatSince(startTime)) {
            if (logSource.size() == 0L) {
                return null;
            }
            LogcatParser parser = new LogcatParser();
            LogcatItem result = null;
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(logSource.createInputStream()))) {
                result = parser.parse(reader);
            }
            return result;
        } catch (IOException e) {
            CLog.e(e);
        }
        return null;
    }

    /** Append the Java crash information to the failure message. */
    private String addJavaCrashToString(LogcatItem item, String errorMsg) {
        if (item == null) {
            return errorMsg;
        }
        List<String> crashes = dedupCrash(item.getJavaCrashes());
        // Invert to report the most recent one first.
        Collections.reverse(crashes);
        int displayed = Math.min(crashes.size(), MAX_NUMBER_CRASH);
        errorMsg = String.format("%s\nCrash Messages sorted from most recent:\n", errorMsg);
        for (int i = 0; i < displayed; i++) {
            errorMsg = String.format("%s%s\n", errorMsg, crashes.get(i));
        }
        return errorMsg;
    }

    /** Remove identical crash from the list of errors. */
    private List<String> dedupCrash(List<JavaCrashItem> origList) {
        LinkedHashSet<String> dedupList = new LinkedHashSet<>();
        for (JavaCrashItem item : origList) {
            dedupList.add(String.format("%s\n%s", item.getMessage(), item.getStack()));
        }
        return new ArrayList<>(dedupList);
    }
}
