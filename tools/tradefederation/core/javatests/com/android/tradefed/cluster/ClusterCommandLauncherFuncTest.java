/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.cluster;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunInterruptedException;
import com.android.tradefed.util.RunUtil;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

/** Functional tests for {@link ClusterCommandLauncher}. */
@RunWith(MockitoJUnitRunner.class)
public class ClusterCommandLauncherFuncTest {

    private static final String LEGACY_TRADEFED_JAR = "/testdata/tradefed-prebuilt-cts-8.0_r21.jar";
    private static final String LEGACY_TRADEFED_COMMAND = "fake.xml --null-device --run testRun PF";
    private static final String LEGACY_TRADEFED_COMMAND_FOR_INVOCATION_FAILURE =
            "fake.xml --null-device --fail-invocation-with-cause cause";
    private static final String LEGACY_TRADEFED_COMMAND_FOR_LARGE_TEST =
            "fake.xml --null-device --run testRun P100";

    private File mRootDir;
    private IConfiguration mConfiguration;
    private IInvocationContext mInvocationContext;
    private OptionSetter mOptionSetter;
    @Mock private TestInformation mTestInformation;

    @Spy private ClusterCommandLauncher mLauncher;
    @Mock private ITestInvocationListener mListener;

    @Before
    public void setUp() throws Exception {
        mRootDir = FileUtil.createTempDir(getClass().getName() + "_RootDir");
        mConfiguration = new Configuration("name", "description");
        mConfiguration.getCommandOptions().setInvocationTimeout(60_000L); // 1 minute
        mInvocationContext = new InvocationContext();
        mLauncher.setConfiguration(mConfiguration);
        mLauncher.setInvocationContext(mInvocationContext);
        mOptionSetter = new OptionSetter(mLauncher);
        mOptionSetter.setOptionValue("cluster:root-dir", mRootDir.getAbsolutePath());
        mOptionSetter.setOptionValue("cluster:env-var", "TF_WORK_DIR", mRootDir.getAbsolutePath());
    }

    @After
    public void tearDown() {
        FileUtil.recursiveDelete(mRootDir);
    }

    @Test
    public void testRun_withLegacyTradefed()
            throws IOException, ConfigurationException, DeviceNotAvailableException {
        File tfJar = new File(mRootDir, "tradefed.jar");
        FileUtil.writeToFile(getClass().getResourceAsStream(LEGACY_TRADEFED_JAR), tfJar);
        FileUtil.writeToFile(
                getClass().getResourceAsStream("/config/tf/fake.xml"),
                new File(mRootDir, "fake.xml"));
        mOptionSetter.setOptionValue("cluster:env-var", "TF_PATH", mRootDir.getAbsolutePath());
        mOptionSetter.setOptionValue("cluster:use-subprocess-reporting", "true");
        mOptionSetter.setOptionValue("cluster:command-line", LEGACY_TRADEFED_COMMAND);

        mLauncher.run(mTestInformation, mListener);

        InOrder inOrder = Mockito.inOrder(mListener);
        HashMap<String, MetricMeasurement.Metric> emptyMap = new HashMap<>();
        inOrder.verify(mListener).testRunStarted(eq("testRun"), anyInt(), anyInt(), anyLong());
        inOrder.verify(mListener).testStarted(any(TestDescription.class), anyLong());
        inOrder.verify(mListener).testEnded(any(TestDescription.class), anyLong(), eq(emptyMap));
        inOrder.verify(mListener).testStarted(any(TestDescription.class), anyLong());
        inOrder.verify(mListener)
                .testFailed(any(TestDescription.class), any(FailureDescription.class));
        inOrder.verify(mListener).testEnded(any(TestDescription.class), anyLong(), eq(emptyMap));
        inOrder.verify(mListener).testRunEnded(anyLong(), eq(emptyMap));
    }

    @Test
    public void testRun_withLegacyTradefed_invocationFailed()
            throws IOException, ConfigurationException, DeviceNotAvailableException {
        File tfJar = new File(mRootDir, "tradefed.jar");
        FileUtil.writeToFile(getClass().getResourceAsStream(LEGACY_TRADEFED_JAR), tfJar);
        FileUtil.writeToFile(
                getClass().getResourceAsStream("/config/tf/fake.xml"),
                new File(mRootDir, "fake.xml"));
        mOptionSetter.setOptionValue("cluster:env-var", "TF_PATH", mRootDir.getAbsolutePath());
        mOptionSetter.setOptionValue("cluster:use-subprocess-reporting", "true");
        mOptionSetter.setOptionValue(
                "cluster:command-line", LEGACY_TRADEFED_COMMAND_FOR_INVOCATION_FAILURE);

        try {
            mLauncher.run(mTestInformation, mListener);
            fail("SubprocessCommandException should be thrown");
        } catch (SubprocessCommandException e) {
            Assert.assertThat(e.getCause().getMessage(), CoreMatchers.containsString("cause"));
        }

        verify(mListener).invocationFailed(any(Throwable.class));
    }

    @Test
    public void testRun_withLegacyTradefed_invocationInterrupted()
            throws IOException, ConfigurationException, DeviceNotAvailableException {
        File tfJar = new File(mRootDir, "tradefed.jar");
        FileUtil.writeToFile(getClass().getResourceAsStream(LEGACY_TRADEFED_JAR), tfJar);
        FileUtil.writeToFile(
                getClass().getResourceAsStream("/config/tf/fake.xml"),
                new File(mRootDir, "fake.xml"));
        mOptionSetter.setOptionValue("cluster:env-var", "TF_PATH", mRootDir.getAbsolutePath());
        mOptionSetter.setOptionValue("cluster:use-subprocess-reporting", "true");
        mOptionSetter.setOptionValue(
                "cluster:command-line", LEGACY_TRADEFED_COMMAND_FOR_LARGE_TEST);
        IRunUtil runUtil = RunUtil.getDefault();
        runUtil.allowInterrupt(true);
        Thread thread = Thread.currentThread();
        doAnswer(
                        invocation -> {
                            runUtil.interrupt(
                                    thread,
                                    "interrupt",
                                    InfraErrorIdentifier.TRADEFED_SHUTTING_DOWN);
                            return null;
                        })
                .when(mListener)
                .testRunStarted(any(String.class), anyInt(), anyInt(), anyLong());
        try {
            mLauncher.run(mTestInformation, mListener);
            fail("RunInterruptedException should be thrown");
        } catch (RunInterruptedException e) {
        }
        HashMap<String, MetricMeasurement.Metric> emptyMap = new HashMap<>();
        verify(mListener).testRunStarted(eq("testRun"), anyInt(), anyInt(), anyLong());
        verify(mListener).testRunEnded(anyLong(), eq(emptyMap));
    }
}
