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

package com.android.tradefed.device.metric;

import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.io.Files.getNameWithoutExtension;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.testtype.coverage.CoverageOptions;
import com.android.tradefed.util.AdbRootElevator;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.JavaCodeCoverageFlusher;
import com.android.tradefed.util.ProcessInfo;
import com.android.tradefed.util.PsParser;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

/**
 * A {@link com.android.tradefed.device.metric.BaseDeviceMetricCollector} that will pull Java
 * coverage measurements off of the device and log them as test artifacts.
 */
public final class JavaCodeCoverageCollector extends BaseDeviceMetricCollector
        implements IConfigurationReceiver {

    public static final String MERGE_COVERAGE_MEASUREMENTS_TEST_NAME = "mergeCoverageMeasurements";
    public static final String COVERAGE_MEASUREMENT_KEY = "coverageFilePath";
    public static final String COVERAGE_DIRECTORY = "/data/misc/trace";
    public static final String FIND_COVERAGE_FILES =
            String.format("find %s -name '*.ec'", COVERAGE_DIRECTORY);

    @Option(
            name = "merge-coverage-measurements",
            description =
                    "Merge coverage measurements after all tests are complete rather than logging individual measurements.")
    private boolean mMergeCoverageMeasurements = false;

    private final ExecFileLoader mExecFileLoader = new ExecFileLoader();

    private JavaCodeCoverageFlusher mFlusher;
    private IConfiguration mConfiguration;

    @Override
    public ITestInvocationListener init(
            IInvocationContext context, ITestInvocationListener listener) {
        super.init(context, listener);

        if (isJavaCoverageEnabled()) {
            try (AdbRootElevator adbRoot = new AdbRootElevator(getDevices().get(0))) {
                getCoverageFlusher().resetCoverage();
            } catch (DeviceNotAvailableException e) {
                throw new RuntimeException(e);
            }
        }

        return this;
    }

    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    private JavaCodeCoverageFlusher getCoverageFlusher() {
        if (mFlusher == null) {
            mFlusher =
                    new JavaCodeCoverageFlusher(
                            getRealDevices().get(0),
                            mConfiguration.getCoverageOptions().getCoverageProcesses());
        }
        return mFlusher;
    }

    @VisibleForTesting
    public void setCoverageFlusher(JavaCodeCoverageFlusher flusher) {
        mFlusher = flusher;
    }

    @VisibleForTesting
    public void setMergeMeasurements(boolean merge) {
        mMergeCoverageMeasurements = merge;
    }

    @Override
    public void onTestRunEnd(DeviceMetricData runData, final Map<String, Metric> runMetrics) {
        if (!isJavaCoverageEnabled()) {
            return;
        }
        if (MERGE_COVERAGE_MEASUREMENTS_TEST_NAME.equals(getRunName())) {
            // Log the merged runtime coverage measurement.
            try {
                File mergedMeasurements =
                        FileUtil.createTempFile(
                                "merged_runtime_coverage_",
                                "." + LogDataType.COVERAGE.getFileExt());

                mExecFileLoader.save(mergedMeasurements, false);

                // Save the merged measurement as a test log.
                logCoverageMeasurement("merged_runtime_coverage", mergedMeasurements);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            ImmutableList.Builder<String> devicePaths = ImmutableList.builder();

            // Get the path of the coverage measurement on the device.
            Metric devicePathMetric = runMetrics.get(COVERAGE_MEASUREMENT_KEY);
            if (devicePathMetric == null) {
                CLog.d("No Java code coverage measurement.");
            } else {
                String testCoveragePath = devicePathMetric.getMeasurements().getSingleString();
                if (testCoveragePath == null) {
                    CLog.d("No Java code coverage measurement.");
                } else {
                    devicePaths.add(testCoveragePath);
                }
            }

            ITestDevice device = getRealDevices().get(0);

            try (AdbRootElevator adbRoot = new AdbRootElevator(device)) {
                if (mConfiguration.getCoverageOptions().isCoverageFlushEnabled()) {
                    getCoverageFlusher().forceCoverageFlush();
                }

                // Find all .ec files in /data/misc/trace and pull them from the device as well.
                String fileList = device.executeShellCommand(FIND_COVERAGE_FILES);
                devicePaths.addAll(Splitter.on('\n').omitEmptyStrings().split(fileList));

                collectAndLogCoverageMeasurements(device, devicePaths.build());
            } catch (DeviceNotAvailableException | IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void logCoverageMeasurement(String name, File coverageFile) {
        try (FileInputStreamSource source = new FileInputStreamSource(coverageFile, true)) {
            testLog(name, LogDataType.COVERAGE, source);
        }
    }

    private void collectAndLogCoverageMeasurements(ITestDevice device, List<String> devicePaths)
            throws IOException, DeviceNotAvailableException {
        List<Integer> activePids = getRunningProcessIds(device);

        for (String devicePath : devicePaths) {
            File coverageFile = device.pullFile(devicePath);

            if (devicePath.endsWith(".mm.ec")) {
                // Check if the process was still running. The file will have the format
                // /data/misc/trace/jacoco-XXXXX.mm.ec where XXXXX is the process id.
                int start = devicePath.indexOf('-') + 1;
                int end = devicePath.indexOf('.');
                int pid = Integer.parseInt(devicePath.substring(start, end));
                if (!activePids.contains(pid)) {
                    device.deleteFile(devicePath);
                }
            } else {
                device.deleteFile(devicePath);
            }

            verifyNotNull(
                    coverageFile, "Failed to pull the Java code coverage file from %s", devicePath);

            // When merging, load the measurement data. Otherwise log the measurement
            // immediately.
            try {
                if (mMergeCoverageMeasurements) {
                    mExecFileLoader.load(coverageFile);
                } else {
                    logCoverageMeasurement(
                            getRunName()
                                    + "_"
                                    + getNameWithoutExtension(devicePath)
                                    + "_runtime_coverage",
                            coverageFile);
                }
            } finally {
                FileUtil.deleteFile(coverageFile);
            }
        }
    }

    private List<Integer> getRunningProcessIds(ITestDevice device)
            throws DeviceNotAvailableException {
        List<ProcessInfo> processes = PsParser.getProcesses(device.executeShellCommand("ps -e"));
        List<Integer> pids = new ArrayList<>();

        for (ProcessInfo process : processes) {
            pids.add(process.getPid());
        }
        return pids;
    }

    private boolean isJavaCoverageEnabled() {
        return mConfiguration != null
                && mConfiguration.getCoverageOptions().isCoverageEnabled()
                && mConfiguration
                        .getCoverageOptions()
                        .getCoverageToolchains()
                        .contains(CoverageOptions.Toolchain.JACOCO);
    }
}
