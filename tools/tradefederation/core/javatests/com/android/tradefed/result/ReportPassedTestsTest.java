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

import static org.junit.Assert.assertEquals;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.testtype.suite.ModuleDefinition;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.Collections;

/** Unit tests for {@link ReportPassedTests}. */
@RunWith(JUnit4.class)
public class ReportPassedTestsTest {

    private String mExpectedString;

    private ReportPassedTests mReporter =
            new ReportPassedTests() {
                @Override
                public void testLog(
                        String dataName, LogDataType dataType, InputStreamSource dataStream) {
                    String logged = null;
                    try {
                        logged = new String(dataStream.createInputStream().readAllBytes());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    assertEquals(mExpectedString, logged);
                }
            };

    @Test
    public void testReport() {
        mExpectedString = "run-name\nrun-name2\n";
        mReporter.testRunStarted("run-name", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testRunStarted("run-name2", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());

        mReporter.invocationEnded(0L);
    }

    @Test
    public void testReport_withRunFailure() {
        mExpectedString = "run-name2\n";
        mReporter.testRunStarted("run-name", 0);
        mReporter.testRunFailed("failed");
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testRunStarted("run-name2", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());

        mReporter.invocationEnded(0L);
    }

    @Test
    public void testReport_withTestFailure() {
        mExpectedString = "run-name2\n";
        mReporter.testRunStarted("run-name", 1);
        TestDescription tid = new TestDescription("class", "testName");
        mReporter.testStarted(tid);
        mReporter.testFailed(tid, "failed");
        mReporter.testEnded(tid, Collections.emptyMap());
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testRunStarted("run-name2", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());

        mReporter.invocationEnded(0L);
    }

    @Test
    public void testReport_module() {
        mExpectedString = "x86 module1\nrun-name2\n";
        mReporter.testModuleStarted(createModule("x86 module1"));
        mReporter.testRunStarted("run-name", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testModuleEnded();
        mReporter.testRunStarted("run-name2", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());

        mReporter.invocationEnded(0L);
    }

    private IInvocationContext createModule(String id) {
        IInvocationContext context = new InvocationContext();
        context.addInvocationAttribute(ModuleDefinition.MODULE_ID, id);
        return context;
    }
}
