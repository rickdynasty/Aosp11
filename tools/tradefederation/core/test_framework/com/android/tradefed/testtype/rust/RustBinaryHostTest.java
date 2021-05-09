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
package com.android.tradefed.testtype.rust;

import com.android.annotations.VisibleForTesting;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.TestErrorIdentifier;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Host test meant to run a rust binary file from the Android Build system (Soong) */
@OptionClass(alias = "rust-host")
public class RustBinaryHostTest extends RustTestBase implements IBuildReceiver {

    static final String RUST_LOG_STDERR_FORMAT = "%s-stderr";
    static final String RUST_LOG_STDOUT_FORMAT = "%s-stdout";

    @Option(name = "test-file", description = "The test file name or file path.")
    private Set<String> mBinaryNames = new HashSet<>();

    private IBuildInfo mBuildInfo;
    private IRunUtil mRunUtil;

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    @Override
    public final void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        List<File> rustFilesList = findFiles();
        for (File file : rustFilesList) {
            if (!file.exists()) {
                CLog.d("ignoring %s which doesn't look like a test file.", file.getAbsolutePath());
                continue;
            }
            file.setExecutable(true);
            runSingleRustFile(listener, file);
        }
    }

    private List<File> findFiles() {
        File testsDir = null;
        if (mBuildInfo instanceof IDeviceBuildInfo) {
            testsDir = ((IDeviceBuildInfo) mBuildInfo).getTestsDir();
        }
        List<File> files = new ArrayList<>();
        for (String fileName : mBinaryNames) {
            File res = null;
            File filePath = new File(fileName);
            String paths = "";
            if (filePath.isAbsolute()) {
                res = filePath; // accept absolute file path from unit tests
            } else if (testsDir == null) {
                throw new RuntimeException(
                        String.format("Cannot find %s without test directory", fileName));
            } else {
                paths = testsDir + "\n";
                String baseName = filePath.getName();
                if (!baseName.equals(fileName)) {
                    // fileName has base directory, findFilesObject returns baseName under testsDir.
                    try {
                        Set<File> candidates = FileUtil.findFilesObject(testsDir, baseName);
                        for (File f : candidates) {
                            paths += String.format("  found: %s\n", f.getPath());
                            if (f.getPath().endsWith(fileName)) {
                                res = f;
                                break;
                            }
                        }
                        if (res == null) {
                            CLog.e("Cannot find %s; try to find %s", fileName, baseName);
                        }
                    } catch (IOException e) {
                        res = null; // report error later
                    }
                }
                if (res == null) {
                    // When fileName is a simple file name, or its path cannot be found
                    // look up the first matching baseName under testsDir.
                    res = FileUtil.findFile(testsDir, baseName);
                }
            }
            if (res == null) {
                throw new RuntimeException(
                        String.format("Cannot find %s under %s", fileName, paths));
            }
            files.add(res);
        }
        return files;
    }

    private void runSingleRustFile(ITestInvocationListener listener, File file) {
        CLog.d("Run single Rust File: %s", file.getAbsolutePath());
        // Rust binary does not support multiple inclusion filters,
        // so we run the test once for each include filter.
        List<String> includeFilters = getListOfIncludeFilters();

        // Call with --list once per include filter to add up testCount.
        // Duplicated test cases selected by different include filters should not be counted.
        Set<String> foundTests = new HashSet<>();
        for (String filter : includeFilters) {
            boolean success = countTests(file, filter, foundTests);
            if (!success) {
                FailureDescription failure =
                        FailureDescription.create(
                                "Could not count the number of tests", FailureStatus.TEST_FAILURE);
                listener.testRunStarted(file.getName(), 0);
                listener.testRunFailed(failure);
                listener.testRunEnded(0, new HashMap<String, Metric>());
                CLog.e(failure.getErrorMessage());
                return;
            }
        }
        int testCount = foundTests.size();
        CLog.d("Total test count: %d", testCount);
        long startTimeMs = System.currentTimeMillis();
        listener.testRunStarted(file.getName(), testCount, 0, startTimeMs);
        if (testCount > 0) {
            for (String filter : includeFilters) {
                try {
                    runTestWithFilter(listener, file, filter);
                } catch (IOException e) {
                    listener.testRunFailed(e.getMessage());
                    long testTimeMs = System.currentTimeMillis() - startTimeMs;
                    listener.testRunEnded(testTimeMs, new HashMap<String, Metric>());
                    throw new RuntimeException(e);
                }
            }
        }
        long testTimeMs = System.currentTimeMillis() - startTimeMs;
        listener.testRunEnded(testTimeMs, new HashMap<String, Metric>());
    }

    private boolean countTests(File file, String filter, Set<String> foundTests) {
        CLog.d("Count with filter '%s' for Rust File: %s", filter, file.getAbsolutePath());
        List<String> commandLine = new ArrayList<>();
        commandLine.add(file.getAbsolutePath());
        commandLine.addAll(mTestOptions);
        addFiltersToArgs(commandLine, filter);

        // Pass parameter to criterion so it performs the benchmarking.
        if (mIsBenchmark) {
            commandLine.add("--bench");
        }

        List<String> listCommandLine = new ArrayList<>(commandLine);
        listCommandLine.add("--list");
        CommandResult listResult =
                getRunUtil()
                        .runTimedCmdSilently(mTestTimeout, listCommandLine.toArray(new String[0]));
        // TODO: Do we want to handle non-standard test harnesses without a
        // --list param? Currently we will report 0 tests, which will cause an
        // overall failure, but we don't know how to parse arbitrary test
        // harness results.
        if (listResult.getStatus() == CommandStatus.SUCCESS) {
            collectTestLines(listResult.getStdout().split("\n"), foundTests, mIsBenchmark);
            return true;
        } else {
            CLog.w(
                    "Could not run command '%s' to get test list.",
                    String.join(" ", listCommandLine));
            return false;
        }
    }

    private void runTestWithFilter(ITestInvocationListener listener, File file, String filter)
            throws IOException {
        String runName = file.getName();
        List<String> commandLine = new ArrayList<>();
        commandLine.add(file.getAbsolutePath());
        commandLine.addAll(mTestOptions);
        addFiltersToArgs(commandLine, filter);

        // Pass parameter to criterion so it performs the benchmarking.
        if (mIsBenchmark) {
            commandLine.add("--bench");
            commandLine.add("--color");
            commandLine.add("never");
        }

        CLog.d("Running test with filter '%s'", filter);
        CommandResult result =
                getRunUtil().runTimedCmd(mTestTimeout, commandLine.toArray(new String[0]));
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            String message =
                    String.format(
                            "Something went wrong when running the rust binary:Exit Code: %s"
                                    + "\nstdout: %s\nstderr: %s",
                            result.getExitCode(), result.getStdout(), result.getStderr());
            FailureDescription failure =
                    FailureDescription.create(message, FailureStatus.TEST_FAILURE)
                            .setErrorIdentifier(TestErrorIdentifier.TEST_BINARY_EXIT_CODE_ERROR);
            listener.testRunFailed(failure);
            CLog.e(message);
        }

        File resultFile = null;
        try {
            resultFile = FileUtil.createTempFile("rust-res", ".txt");
            if (result.getStderr().length() > 0) {
                FileUtil.writeToFile(result.getStderr(), resultFile);
                try (FileInputStreamSource data = new FileInputStreamSource(resultFile)) {
                    listener.testLog(
                            String.format(RUST_LOG_STDERR_FORMAT, runName), LogDataType.TEXT, data);
                }
            }
            if (result.getStdout().length() > 0) {
                FileUtil.writeToFile(result.getStdout(), resultFile);
                try (FileInputStreamSource data = new FileInputStreamSource(resultFile)) {
                    listener.testLog(
                            String.format(RUST_LOG_STDOUT_FORMAT, runName), LogDataType.TEXT, data);
                }
            }
            IShellOutputReceiver parser = createParser(listener, runName, mIsBenchmark);
            parser.addOutput(result.getStdout().getBytes(), 0, result.getStdout().length());
            parser.flush();
        } catch (RuntimeException e) {
            listener.testRunFailed(
                    String.format("Failed to parse the rust test output: %s", e.getMessage()));
            CLog.e(e);
        } finally {
            FileUtil.deleteFile(resultFile);
        }
    }

    @VisibleForTesting
    IRunUtil getRunUtil() {
        if (mRunUtil == null) {
            mRunUtil = new RunUtil();
        }
        return mRunUtil;
    }
}
