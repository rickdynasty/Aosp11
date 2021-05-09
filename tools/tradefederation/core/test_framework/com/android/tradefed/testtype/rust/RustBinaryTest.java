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

import static com.android.tradefed.testtype.coverage.CoverageOptions.Toolchain.GCOV;

import com.android.ddmlib.FileListingService;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.testtype.IDeviceTest;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** A Test that runs a rust binary on given device. */
@OptionClass(alias = "rust-device")
public class RustBinaryTest extends RustTestBase implements IDeviceTest, IConfigurationReceiver {

    static final String DEFAULT_TEST_PATH = "/data/local/tmp";

    // TODO(chh): add "ld-library-path" option and set up LD_LIBRARY_PATH

    @Option(
            name = "test-device-path",
            description = "The path on the device where tests are located.")
    private String mTestDevicePath = DEFAULT_TEST_PATH;

    @Option(name = "module-name", description = "The name of the test module to run.")
    private String mTestModule = null;

    private IConfiguration mConfiguration = null;

    private ITestDevice mDevice = null;

    /** {@inheritDoc} */
    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    /** {@inheritDoc} */
    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    /** {@inheritDoc} */
    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    public void setModuleName(String name) {
        mTestModule = name;
    }

    public String getTestModule() {
        return mTestModule;
    }

    /**
     * Gets the path where tests live on the device.
     *
     * @return The path on the device where the tests live.
     */
    private String getTestPath() {
        StringBuilder testPath = new StringBuilder(mTestDevicePath);
        String testModule = getTestModule();
        if (testModule != null) {
            testPath.append(FileListingService.FILE_SEPARATOR);
            testPath.append(testModule);
        }
        return testPath.toString();
    }

    // Returns true if given fullPath is not executable.
    private boolean shouldSkipFile(String fullPath) throws DeviceNotAvailableException {
        return fullPath == null || fullPath.isEmpty() || !mDevice.isExecutable(fullPath);
    }

    /**
     * Executes all tests in a folder as well as in all subfolders recursively.
     *
     * @param root The root folder to begin searching for tests
     * @param testDevice The device to run tests on
     * @param listener the {@link ITestInvocationListener}
     * @throws DeviceNotAvailableException
     */
    private boolean doRunAllTestsInSubdirectory(
            String root, ITestDevice testDevice, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        // returns true iff some test is found
        if (testDevice.isDirectory(root)) {
            // recursively run tests in all subdirectories
            CLog.d("Look into rust directory %s on %s", root, testDevice.getSerialNumber());
            boolean found = false;
            for (String child : testDevice.getChildren(root)) {
                CLog.d("Look into child path %s", (root + "/" + child));
                if (doRunAllTestsInSubdirectory(root + "/" + child, testDevice, listener)) {
                    found = true;
                }
            }
            return found;
        } else if (shouldSkipFile(root)) {
            CLog.d("Skip rust test %s on %s", root, testDevice.getSerialNumber());
            return false;
        } else {
            CLog.d("To run rust test %s on %s", root, testDevice.getSerialNumber());
            runTest(
                    testDevice,
                    listener,
                    createParser(listener, new File(root).getName(), mIsBenchmark),
                    root);
            return true;
        }
    }

    /**
     * Run the given Rust binary
     *
     * @param testDevice the {@link ITestDevice}
     * @param resultParser the test run output parser
     * @param fullPath absolute file system path to rust binary on device
     * @throws DeviceNotAvailableException
     */
    private void runTest(
            final ITestDevice testDevice,
            final ITestInvocationListener listener,
            final IShellOutputReceiver resultParser,
            final String fullPath)
            throws DeviceNotAvailableException {
        CLog.d("RustBinaryTest runTest: " + fullPath);
        File file = new File(fullPath);
        String dir = file.getParent();
        String cmd = "cd " + dir + " && " + fullPath;
        if (mTestOptions.size() > 0) {
            cmd += " " + String.join(" ", mTestOptions);
        }

        // Pass parameter to criterion to run benchmark. Parameter is required when listing tests.
        // TODO(qtr): Explore using CRITERION_HOME for setting criterion output. Right now it's
        // going to be put in the working directory (which is next to binary due to cd above).
        if (mIsBenchmark) {
            cmd += " --bench --color never";
        }

        // Rust binary does not support multiple inclusion filters,
        // so we run the test once for each include filter.
        List<String> includeFilters = getListOfIncludeFilters();

        // Call with --list once per include filter to add up testCount.
        // Duplicated test cases selected by different include filters should not be counted.
        Set<String> foundTests = new HashSet<>();
        for (String filter : includeFilters) {
            String newCmd = addFiltersToCommand(cmd, filter);
            try {
                String[] testList = testDevice.executeShellCommand(newCmd + " --list").split("\n");
                collectTestLines(testList, foundTests, mIsBenchmark);
            } catch (DeviceNotAvailableException e) {
                CLog.e("Could not retrieve tests list from device: %s", e.getMessage());
                throw e;
            }
        }
        int testCount = foundTests.size();
        CLog.d("Total test count: %d", testCount);
        long startTimeMs = System.currentTimeMillis();
        listener.testRunStarted(new File(fullPath).getName(), testCount, 0, startTimeMs);
        for (String filter : includeFilters) {
            String newCmd = addFiltersToCommand(cmd, filter);

            if (mConfiguration != null
                    && mConfiguration.getCoverageOptions().getCoverageToolchains().contains(GCOV)) {
                newCmd = "GCOV_PREFIX=/data/misc/trace " + newCmd;
            }

            try {
                testDevice.executeShellCommand(
                        newCmd,
                        resultParser,
                        mTestTimeout,
                        TimeUnit.MILLISECONDS,
                        0 /* retryAttempts */);
            } catch (DeviceNotAvailableException e) {
                listener.testRunFailed(String.format("Device not available: %s", e.getMessage()));
            } finally {
                resultParser.flush();
            }
        }
        long testTimeMs = System.currentTimeMillis() - startTimeMs;
        listener.testRunEnded(testTimeMs, new HashMap<String, Metric>());
    }

    private void wrongTestPath(String msg, String testPath, ITestInvocationListener listener) {
        CLog.e(msg + testPath);
        // mock a test run start+fail+end
        long startTimeMs = System.currentTimeMillis();
        listener.testRunStarted(testPath, 1, 0, startTimeMs);
        listener.testRunFailed(msg + testPath);
        listener.testRunEnded(0, new HashMap<String, Metric>());
    }

    /** {@inheritDoc} */
    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        if (mDevice == null) {
            throw new IllegalArgumentException("Device has not been set");
        }

        String testPath = getTestPath();
        if (!mDevice.doesFileExist(testPath)) {
            wrongTestPath("Could not find test directory ", testPath, listener);
            return;
        }

        CLog.d("To run tests in directory " + testPath);

        if (!doRunAllTestsInSubdirectory(testPath, mDevice, listener)) {
            wrongTestPath("No test found under ", testPath, listener);
        }
    }
}
