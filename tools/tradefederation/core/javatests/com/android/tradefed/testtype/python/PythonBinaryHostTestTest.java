/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.testtype.python;

import static com.android.tradefed.testtype.python.PythonBinaryHostTest.TEST_OUTPUT_FILE_FLAG;
import static com.android.tradefed.testtype.python.PythonBinaryHostTest.USE_TEST_OUTPUT_FILE_OPTION;

import static com.google.common.truth.Truth.assertThat;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.invoker.ExecutionFiles.FilesKey;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.IRunUtil.EnvPriority;

import com.google.common.io.CharStreams;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.easymock.IArgumentMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;

/** Unit tests for {@link PythonBinaryHostTest}. */
@RunWith(JUnit4.class)
public final class PythonBinaryHostTestTest {

    private static final String PYTHON_OUTPUT_FILE_1 = "python_output1.txt";

    private PythonBinaryHostTest mTest;
    private IRunUtil mMockRunUtil;
    private IBuildInfo mMockBuildInfo;
    private ITestDevice mMockDevice;
    private TestInformation mTestInfo;
    private ITestInvocationListener mMockListener;
    private File mFakeAdb;
    private File mPythonBinary;
    private File mOutputFile;
    private File mModuleDir;

    @Before
    public void setUp() throws Exception {
        mFakeAdb = FileUtil.createTempFile("adb-python-tests", "");
        mMockRunUtil = EasyMock.createMock(IRunUtil.class);
        mMockBuildInfo = EasyMock.createMock(IBuildInfo.class);
        mMockListener = EasyMock.createMock(ITestInvocationListener.class);
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        mTest =
                new PythonBinaryHostTest() {
                    @Override
                    IRunUtil getRunUtil() {
                        return mMockRunUtil;
                    }

                    @Override
                    String getAdbPath() {
                        return mFakeAdb.getAbsolutePath();
                    }
                };
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        context.addDeviceBuildInfo("device", mMockBuildInfo);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
        EasyMock.expect(mMockDevice.getSerialNumber()).andStubReturn("SERIAL");
        mMockRunUtil.setEnvVariable(PythonBinaryHostTest.ANDROID_SERIAL_VAR, "SERIAL");
        mMockRunUtil.setWorkingDir(EasyMock.anyObject());
        mMockRunUtil.setEnvVariablePriority(EnvPriority.SET);
        mMockRunUtil.setEnvVariable(EasyMock.eq("PATH"), EasyMock.anyObject());

        mModuleDir = FileUtil.createTempDir("python-module");
        mPythonBinary = FileUtil.createTempFile("python-dir", "", mModuleDir);
        mTestInfo.executionFiles().put(FilesKey.HOST_TESTS_DIRECTORY, new File("/path-not-exist"));
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.deleteFile(mFakeAdb);
        FileUtil.deleteFile(mPythonBinary);
        FileUtil.deleteFile(mOutputFile);
        FileUtil.recursiveDelete(mModuleDir);
    }

    /** Test that when running a python binary the output is parsed to obtain results. */
    @Test
    public void testRun() throws Exception {
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        mOutputFile = readInFile(PYTHON_OUTPUT_FILE_1);
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", mPythonBinary.getAbsolutePath());
            mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            res.setStdout("python binary stdout.");
            res.setStderr(FileUtil.readStringFromFile(mOutputFile));
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(),
                                    EasyMock.isNull(),
                                    (OutputStream) EasyMock.anyObject(),
                                    EasyMock.eq(mPythonBinary.getAbsolutePath())))
                    .andAnswer(
                            new IAnswer<CommandResult>() {
                                @Override
                                public CommandResult answer() throws Throwable {
                                    OutputStream stream = (OutputStream) getCurrentArguments()[2];
                                    StreamUtil.copyFileToStream(mOutputFile, stream);
                                    return res;
                                }
                            });
            mMockListener.testRunStarted(
                    EasyMock.eq(mPythonBinary.getName()),
                    EasyMock.eq(11),
                    EasyMock.eq(0),
                    EasyMock.anyLong());
            mMockListener.testLog(
                    EasyMock.eq(mPythonBinary.getName() + "-stdout"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            mMockListener.testLog(
                    EasyMock.eq(mPythonBinary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mTestInfo, mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(mPythonBinary);
        }
    }

    /** Test that when running a non-unittest python binary with any filter, the test shall fail. */
    @Test
    public void testRun_failWithIncludeFilters() throws Exception {
        mOutputFile = readInFile(PYTHON_OUTPUT_FILE_1);
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", mPythonBinary.getAbsolutePath());
            mTest.addIncludeFilter("test1");
            mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            res.setStderr(FileUtil.readStringFromFile(mOutputFile));
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(),
                                    EasyMock.isNull(),
                                    (OutputStream) EasyMock.anyObject(),
                                    EasyMock.eq(mPythonBinary.getAbsolutePath())))
                    .andAnswer(
                            new IAnswer<CommandResult>() {
                                @Override
                                public CommandResult answer() throws Throwable {
                                    throw new RuntimeException("Parser error");
                                }
                            });
            mMockListener.testRunStarted(EasyMock.eq(mPythonBinary.getName()), EasyMock.eq(0));
            mMockListener.testRunFailed((FailureDescription) EasyMock.anyObject());
            mMockListener.testRunEnded(
                    EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
            mMockListener.testLog(
                    EasyMock.eq(mPythonBinary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mTestInfo, mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(mPythonBinary);
        }
    }

    /**
     * Test that when running a python binary with include filters, the output is parsed to obtain
     * results.
     */
    @Test
    public void testRun_withIncludeFilters() throws Exception {
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", mPythonBinary.getAbsolutePath());
            mTest.addIncludeFilter("__main__.Class1#test_1");
            mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            String output =
                    "test_1 (__main__.Class1)\n"
                        + "run first test. ... ok\n"
                        + "test_2 (__main__.Class1)\n"
                        + "run second test. ... ok\n"
                        + "test_3 (__main__.Class1)\n"
                        + "run third test. ... ok\n"
                        + "----------------------------------------------------------------------\n"
                        + "Ran 3 tests in 1s\n";
            res.setStderr(output);
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(),
                                    EasyMock.isNull(),
                                    (OutputStream) EasyMock.anyObject(),
                                    EasyMock.eq(mPythonBinary.getAbsolutePath())))
                    .andAnswer(
                            new IAnswer<CommandResult>() {
                                @Override
                                public CommandResult answer() throws Throwable {
                                    OutputStream stream = (OutputStream) getCurrentArguments()[2];
                                    StreamUtil.copyStreams(
                                            new ByteArrayInputStream(output.getBytes()), stream);
                                    return res;
                                }
                            });
            // 3 tests are started and ended.
            for (int i = 0; i < 3; i++) {
                mMockListener.testStarted(
                        EasyMock.<TestDescription>anyObject(), EasyMock.anyLong());
                mMockListener.testEnded(
                        EasyMock.<TestDescription>anyObject(),
                        EasyMock.anyLong(),
                        EasyMock.<HashMap<String, Metric>>anyObject());
            }
            // 2 tests are ignored.
            mMockListener.testIgnored(EasyMock.<TestDescription>anyObject());
            mMockListener.testIgnored(EasyMock.<TestDescription>anyObject());
            mMockListener.testRunStarted(
                    EasyMock.eq(mPythonBinary.getName()),
                    EasyMock.eq(3),
                    EasyMock.eq(0),
                    EasyMock.anyLong());
            mMockListener.testRunEnded(
                    EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
            mMockListener.testLog(
                    EasyMock.eq(mPythonBinary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mTestInfo, mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(mPythonBinary);
        }
    }

    /**
     * Test that when running a python binary with exclude filters, the output is parsed to obtain
     * results.
     */
    @Test
    public void testRun_withExcludeFilters() throws Exception {
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", mPythonBinary.getAbsolutePath());
            mTest.addExcludeFilter("__main__.Class1#test_1");
            mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            String output =
                    "test_1 (__main__.Class1)\n"
                        + "run first test. ... ok\n"
                        + "test_2 (__main__.Class1)\n"
                        + "run second test. ... ok\n"
                        + "test_3 (__main__.Class1)\n"
                        + "run third test. ... ok\n"
                        + "----------------------------------------------------------------------\n"
                        + "Ran 3 tests in 1s\n";
            res.setStderr(output);
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(),
                                    EasyMock.isNull(),
                                    (OutputStream) EasyMock.anyObject(),
                                    EasyMock.eq(mPythonBinary.getAbsolutePath())))
                    .andAnswer(
                            new IAnswer<CommandResult>() {
                                @Override
                                public CommandResult answer() throws Throwable {
                                    OutputStream stream = (OutputStream) getCurrentArguments()[2];
                                    StreamUtil.copyStreams(
                                            new ByteArrayInputStream(output.getBytes()), stream);
                                    return res;
                                }
                            });
            // 3 tests are started and ended.
            for (int i = 0; i < 3; i++) {
                mMockListener.testStarted(
                        EasyMock.<TestDescription>anyObject(), EasyMock.anyLong());
                mMockListener.testEnded(
                        EasyMock.<TestDescription>anyObject(),
                        EasyMock.anyLong(),
                        EasyMock.<HashMap<String, Metric>>anyObject());
            }
            // 1 test is ignored.
            mMockListener.testIgnored(EasyMock.<TestDescription>anyObject());
            mMockListener.testRunStarted(
                    EasyMock.eq(mPythonBinary.getName()),
                    EasyMock.eq(3),
                    EasyMock.eq(0),
                    EasyMock.anyLong());
            mMockListener.testRunEnded(
                    EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());
            mMockListener.testLog(
                    EasyMock.eq(mPythonBinary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mTestInfo, mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(mPythonBinary);
        }
    }

    /**
     * Test running the python tests when an adb path has been set. In that case we ensure the
     * python script will use the provided adb.
     */
    @Test
    public void testRun_withAdbPath() throws Exception {
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        mOutputFile = readInFile(PYTHON_OUTPUT_FILE_1);
        mTestInfo.executionFiles().put(FilesKey.ADB_BINARY, new File("/test/adb"));

        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", mPythonBinary.getAbsolutePath());
            mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
            expectedAdbPath(new File("/test/adb"));

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            res.setStderr(FileUtil.readStringFromFile(mOutputFile));
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(),
                                    EasyMock.isNull(),
                                    (OutputStream) EasyMock.anyObject(),
                                    EasyMock.eq(mPythonBinary.getAbsolutePath())))
                    .andAnswer(
                            new IAnswer<CommandResult>() {
                                @Override
                                public CommandResult answer() throws Throwable {
                                    OutputStream stream = (OutputStream) getCurrentArguments()[2];
                                    StreamUtil.copyFileToStream(mOutputFile, stream);
                                    return res;
                                }
                            });
            mMockListener.testRunStarted(
                    EasyMock.eq(mPythonBinary.getName()),
                    EasyMock.eq(11),
                    EasyMock.eq(0),
                    EasyMock.anyLong());
            mMockListener.testLog(
                    EasyMock.eq(mPythonBinary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mTestInfo, mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(mPythonBinary);
        }
    }

    /** Test running the python tests when shared lib is available in HOST_TESTS_DIRECTORY. */
    @Test
    public void testRun_withSharedLibInHostTestsDir() throws Exception {
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        mOutputFile = readInFile(PYTHON_OUTPUT_FILE_1);
        File hostTestsDir = FileUtil.createTempDir("host-test-cases");
        mTestInfo.executionFiles().put(FilesKey.HOST_TESTS_DIRECTORY, hostTestsDir);
        File binary = FileUtil.createTempFile("python-dir", "", hostTestsDir);
        File lib = new File(hostTestsDir, "lib");
        lib.mkdirs();
        File lib64 = new File(hostTestsDir, "lib64");
        lib64.mkdirs();

        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", binary.getAbsolutePath());
            mMockRunUtil.setEnvVariable(
                    PythonBinaryHostTest.LD_LIBRARY_PATH,
                    lib.getAbsolutePath()
                            + ":"
                            + lib64.getAbsolutePath()
                            + ":"
                            + hostTestsDir.getAbsolutePath());
            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            res.setStderr(FileUtil.readStringFromFile(mOutputFile));
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(),
                                    EasyMock.isNull(),
                                    (OutputStream) EasyMock.anyObject(),
                                    EasyMock.eq(binary.getAbsolutePath())))
                    .andAnswer(
                            new IAnswer<CommandResult>() {
                                @Override
                                public CommandResult answer() throws Throwable {
                                    OutputStream stream = (OutputStream) getCurrentArguments()[2];
                                    StreamUtil.copyFileToStream(mOutputFile, stream);
                                    return res;
                                }
                            });
            mMockListener.testRunStarted(
                    EasyMock.eq(binary.getName()),
                    EasyMock.eq(11),
                    EasyMock.eq(0),
                    EasyMock.anyLong());
            mMockListener.testLog(
                    EasyMock.eq(binary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mTestInfo, mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.recursiveDelete(hostTestsDir);
        }
    }

    /** Test running the python tests when shared lib is available in TESTS_DIRECTORY. */
    @Test
    public void testRun_withSharedLib() throws Exception {
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        mOutputFile = readInFile(PYTHON_OUTPUT_FILE_1);
        File testsDir = FileUtil.createTempDir("host-test-cases");
        mTestInfo.executionFiles().put(FilesKey.TESTS_DIRECTORY, testsDir);
        File binary = FileUtil.createTempFile("python-dir", "", testsDir);
        File lib = new File(testsDir, "lib");
        lib.mkdirs();
        File lib64 = new File(testsDir, "lib64");
        lib64.mkdirs();

        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", binary.getAbsolutePath());
            mMockRunUtil.setEnvVariable(
                    PythonBinaryHostTest.LD_LIBRARY_PATH,
                    lib.getAbsolutePath()
                            + ":"
                            + lib64.getAbsolutePath()
                            + ":"
                            + testsDir.getAbsolutePath());
            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.SUCCESS);
            res.setStderr(FileUtil.readStringFromFile(mOutputFile));
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(),
                                    EasyMock.isNull(),
                                    (OutputStream) EasyMock.anyObject(),
                                    EasyMock.eq(binary.getAbsolutePath())))
                    .andAnswer(
                            new IAnswer<CommandResult>() {
                                @Override
                                public CommandResult answer() throws Throwable {
                                    OutputStream stream = (OutputStream) getCurrentArguments()[2];
                                    StreamUtil.copyFileToStream(mOutputFile, stream);
                                    return res;
                                }
                            });
            mMockListener.testRunStarted(
                    EasyMock.eq(binary.getName()),
                    EasyMock.eq(11),
                    EasyMock.eq(0),
                    EasyMock.anyLong());
            mMockListener.testLog(
                    EasyMock.eq(binary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mTestInfo, mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.recursiveDelete(testsDir);
        }
    }

    /**
     * If the binary returns an exception status, we should throw a runtime exception since
     * something went wrong with the binary setup.
     */
    @Test
    public void testRunFail_exception() throws Exception {
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", mPythonBinary.getAbsolutePath());
            mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.EXCEPTION);
            res.setStderr("Could not execute.");
            String output = "Could not execute.";
            res.setStderr(output);
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(),
                                    EasyMock.isNull(),
                                    (OutputStream) EasyMock.anyObject(),
                                    EasyMock.eq(mPythonBinary.getAbsolutePath())))
                    .andAnswer(
                            new IAnswer<CommandResult>() {
                                @Override
                                public CommandResult answer() throws Throwable {
                                    OutputStream stream = (OutputStream) getCurrentArguments()[2];
                                    StreamUtil.copyStreams(
                                            new ByteArrayInputStream(output.getBytes()), stream);
                                    return res;
                                }
                            });
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));

            mMockListener.testLog(
                    EasyMock.eq(mPythonBinary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            // Report a failure if we cannot parse the logs
            mMockListener.testRunStarted(mPythonBinary.getName(), 0);
            FailureDescription failure =
                    FailureDescription.create(
                            "Failed to parse the python logs: Parser finished in unexpected "
                                    + "state TEST_CASE. Please ensure that verbosity of output "
                                    + "is high enough to be parsed.");
            failure.setFailureStatus(FailureStatus.TEST_FAILURE);
            mMockListener.testRunFailed(failure);
            mMockListener.testRunEnded(
                    EasyMock.anyLong(), EasyMock.<HashMap<String, Metric>>anyObject());

            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mTestInfo, mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(mPythonBinary);
        }
    }

    /**
     * If the binary reports a FAILED status but the output actually have some tests, it most *
     * likely means that some tests failed. So we simply continue with parsing the results.
     */
    @Test
    public void testRunFail_failureOnly() throws Exception {
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        mOutputFile = readInFile(PYTHON_OUTPUT_FILE_1);
        try {
            OptionSetter setter = new OptionSetter(mTest);
            setter.setOptionValue("python-binaries", mPythonBinary.getAbsolutePath());
            mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
            expectedAdbPath(mFakeAdb);

            CommandResult res = new CommandResult();
            res.setStatus(CommandStatus.FAILED);
            res.setStderr(FileUtil.readStringFromFile(mOutputFile));
            EasyMock.expect(
                            mMockRunUtil.runTimedCmd(
                                    EasyMock.anyLong(),
                                    EasyMock.isNull(),
                                    (OutputStream) EasyMock.anyObject(),
                                    EasyMock.eq(mPythonBinary.getAbsolutePath())))
                    .andAnswer(
                            new IAnswer<CommandResult>() {
                                @Override
                                public CommandResult answer() throws Throwable {
                                    OutputStream stream = (OutputStream) getCurrentArguments()[2];
                                    StreamUtil.copyFileToStream(mOutputFile, stream);
                                    return res;
                                }
                            });
            mMockListener.testRunStarted(
                    EasyMock.eq(mPythonBinary.getName()),
                    EasyMock.eq(11),
                    EasyMock.eq(0),
                    EasyMock.anyLong());
            mMockListener.testLog(
                    EasyMock.eq(mPythonBinary.getName() + "-stderr"),
                    EasyMock.eq(LogDataType.TEXT),
                    EasyMock.anyObject());
            EasyMock.expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));
            EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
            mTest.run(mTestInfo, mMockListener);
            EasyMock.verify(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
        } finally {
            FileUtil.deleteFile(mPythonBinary);
        }
    }

    @Test
    public void testRun_useTestOutputFileOptionSet_parsesSubprocessOutputFile() throws Exception {
        mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        mOutputFile = readInFile(PYTHON_OUTPUT_FILE_1);
        newDefaultOptionSetter(mTest).setOptionValue(USE_TEST_OUTPUT_FILE_OPTION, "true");
        expectRunThatWritesTestOutputFile(
                newCommandResult(CommandStatus.SUCCESS, "NOT TEST OUTPUT"),
                FileUtil.readStringFromFile(mOutputFile));
        mMockListener.testRunStarted(anyObject(), eq(11), eq(0), anyLong());
        replayAllMocks();

        mTest.run(mTestInfo, mMockListener);

        EasyMock.verify(mMockListener);
    }

    @Test
    public void testRun_useTestOutputFileOptionSet_parsesUnitTestOutputFile() throws Exception {
        mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        newDefaultOptionSetter(mTest).setOptionValue(USE_TEST_OUTPUT_FILE_OPTION, "true");
        expectRunThatWritesTestOutputFile(
                newCommandResult(CommandStatus.SUCCESS, "NOT TEST OUTPUT"),
                "test_1 (__main__.Class1)\n"
                        + "run first test. ... ok\n"
                        + "test_2 (__main__.Class1)\n"
                        + "run second test. ... ok\n"
                        + "----------------------------------------------------------------------\n"
                        + "Ran 2 tests in 1s");
        mMockListener.testRunStarted(anyObject(), eq(2), eq(0), anyLong());
        replayAllMocks();

        mTest.run(mTestInfo, mMockListener);

        EasyMock.verify(mMockListener);
    }

    @Test
    public void testRun_useTestOutputFileOptionSet_logsErrorOutput() throws Exception {
        mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        String errorOutput = "NOT TEST OUTPUT";
        newDefaultOptionSetter(mTest).setOptionValue(USE_TEST_OUTPUT_FILE_OPTION, "true");
        expectRunThatWritesTestOutputFile(
                newCommandResult(CommandStatus.SUCCESS, errorOutput),
                "TEST_RUN_STARTED {\"testCount\": 5, \"runName\": \"TestSuite\"}");
        mMockListener.testLog(
                anyObject(), eq(LogDataType.TEXT), inputStreamSourceContainsText(errorOutput));
        replayAllMocks();

        mTest.run(mTestInfo, mMockListener);

        EasyMock.verify(mMockListener);
    }

    @Test
    public void testRun_useTestOutputFileOptionSet_logsTestOutput() throws Exception {
        mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        String testOutput = "TEST_RUN_STARTED {\"testCount\": 5, \"runName\": \"TestSuite\"}";
        newDefaultOptionSetter(mTest).setOptionValue(USE_TEST_OUTPUT_FILE_OPTION, "true");
        expectRunThatWritesTestOutputFile(
                newCommandResult(CommandStatus.SUCCESS, "NOT TEST OUTPUT"), testOutput);
        mMockListener.testLog(
                EasyMock.eq(mPythonBinary.getName() + "-stderr"),
                EasyMock.eq(LogDataType.TEXT),
                EasyMock.anyObject());
        mMockListener.testLog(
                anyObject(), eq(LogDataType.TEXT), inputStreamSourceContainsText(testOutput));
        replayAllMocks();

        mTest.run(mTestInfo, mMockListener);

        EasyMock.verify(mMockListener);
    }

    @Test
    public void testRun_useTestOutputFileOptionSet_failureMessageContainsHints() throws Exception {
        mMockRunUtil.setEnvVariable(EasyMock.eq("LD_LIBRARY_PATH"), EasyMock.anyObject());
        mMockListener = EasyMock.createNiceMock(ITestInvocationListener.class);
        newDefaultOptionSetter(mTest).setOptionValue(USE_TEST_OUTPUT_FILE_OPTION, "true");
        expectRunThatWritesTestOutputFile(
                newCommandResult(CommandStatus.SUCCESS, "NOT TEST OUTPUT"), "BAD OUTPUT FORMAT");
        Capture<FailureDescription> description = new Capture<>();
        mMockListener.testRunFailed(capture(description));
        replayAllMocks();

        mTest.run(mTestInfo, mMockListener);

        String message = description.getValue().getErrorMessage();
        EasyMock.verify(mMockListener);
        assertThat(message).contains("--" + TEST_OUTPUT_FILE_FLAG);
        assertThat(message).contains("verbosity");
    }

    private OptionSetter newDefaultOptionSetter(PythonBinaryHostTest test) throws Exception {
        OptionSetter setter = new OptionSetter(test);
        setter.setOptionValue("python-binaries", mPythonBinary.getAbsolutePath());
        return setter;
    }

    private static CommandResult newCommandResult(CommandStatus status, String stderr) {
        CommandResult res = new CommandResult();
        res.setStatus(status);
        res.setStderr(stderr);
        return res;
    }

    private void expectRunThatWritesTestOutputFile(
            CommandResult result, String testOutputFileContents) {
        Capture<String> testOutputFilePath = new Capture<>();
        expect(
                        mMockRunUtil.runTimedCmd(
                                anyLong(),
                                eq(mPythonBinary.getAbsolutePath()),
                                eq("--test-output-file"),
                                capture(testOutputFilePath)))
                .andStubAnswer(
                        new IAnswer<CommandResult>() {
                            @Override
                            public CommandResult answer() {
                                try {
                                    FileUtil.writeToFile(
                                            testOutputFileContents,
                                            new File(testOutputFilePath.getValue()));
                                } catch (IOException ex) {
                                    throw new RuntimeException(ex);
                                }
                                return result;
                            }
                        });
        expect(mMockDevice.getIDevice()).andReturn(new StubDevice("serial"));
        expectedAdbPath(mFakeAdb);
    }

    private void replayAllMocks() {
        EasyMock.replay(mMockRunUtil, mMockBuildInfo, mMockListener, mMockDevice);
    }

    private void expectedAdbPath(File adbPath) {
        CommandResult pathRes = new CommandResult();
        pathRes.setStatus(CommandStatus.SUCCESS);
        pathRes.setStdout("bin/");
        EasyMock.expect(mMockRunUtil.runTimedCmd(60000L, "/bin/bash", "-c", "echo $PATH"))
                .andReturn(pathRes);
        mMockRunUtil.setEnvVariable("PATH", String.format("%s:bin/", adbPath.getParent()));

        CommandResult versionRes = new CommandResult();
        versionRes.setStatus(CommandStatus.SUCCESS);
        versionRes.setStdout("bin/");
        EasyMock.expect(mMockRunUtil.runTimedCmd(60000L, "adb", "version")).andReturn(versionRes);
    }

    private static InputStreamSource inputStreamSourceContainsText(String text) {
        EasyMock.reportMatcher(
                new IArgumentMatcher() {
                    @Override
                    public boolean matches(Object actual) {
                        if (!(actual instanceof InputStreamSource)) {
                            return false;
                        }

                        InputStream is = ((InputStreamSource) actual).createInputStream();
                        String contents;

                        try {
                            contents =
                                    CharStreams.toString(
                                            new InputStreamReader(is)); // Assumes default charset.
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }

                        return contents.contains(text);
                    }

                    @Override
                    public void appendTo(StringBuffer buffer) {
                        buffer.append("inputStreamSourceContainsText(\"");
                        buffer.append(text);
                        buffer.append("\")");
                    }
                });
        return null;
    }

    private File readInFile(String filename) throws IOException {
        File output = FileUtil.createTempFile("python-host-test", ".txt");
        InputStream stream =
                getClass()
                        .getResourceAsStream(
                                File.separator + "testtype" + File.separator + filename);
        FileUtil.writeToFile(stream, output);
        return output;
    }
}
