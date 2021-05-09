/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tradefed.testtype;

import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/** Unit tests for {@link TestTimeoutEnforcer}. */
@RunWith(JUnit4.class)
public class TestTimeoutEnforcerTest {

    private TestTimeoutEnforcer mEnforcer;
    private ITestInvocationListener mListener;
    private TestDescription mTest = new TestDescription("class", "test");

    @Before
    public void setUp() {
        mListener = EasyMock.createMock(ITestInvocationListener.class);
        mEnforcer = new TestTimeoutEnforcer(500L, TimeUnit.MILLISECONDS, mListener);
    }

    @Test
    public void testNoTimeout() {
        mListener.testStarted(mTest, 0L);
        mListener.testEnded(mTest, 250L, new HashMap<String, Metric>());

        EasyMock.replay(mListener);
        mEnforcer.testStarted(mTest, 0L);
        mEnforcer.testEnded(mTest, 250L, new HashMap<String, Metric>());
        EasyMock.verify(mListener);
    }

    @Test
    public void testTimeout() {
        mListener.testStarted(mTest, 0L);
        mListener.testFailed(
                mTest,
                FailureDescription.create(
                        "class#test took 550 ms while timeout is 500 ms", FailureStatus.TIMED_OUT));
        mListener.testEnded(mTest, 550L, new HashMap<String, Metric>());

        EasyMock.replay(mListener);
        mEnforcer.testStarted(mTest, 0L);
        mEnforcer.testEnded(mTest, 550L, new HashMap<String, Metric>());
        EasyMock.verify(mListener);
    }

    @Test
    public void testFailedTest() {
        mListener.testStarted(mTest, 0L);
        mListener.testFailed(mTest, "i failed");
        mListener.testEnded(mTest, 550L, new HashMap<String, Metric>());

        EasyMock.replay(mListener);
        mEnforcer.testStarted(mTest, 0L);
        mEnforcer.testFailed(mTest, "i failed");
        mEnforcer.testEnded(mTest, 550L, new HashMap<String, Metric>());
        EasyMock.verify(mListener);
    }
}
