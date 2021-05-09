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

import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.util.AbiUtils;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import difflib.DiffUtils;
import difflib.Patch;

/** A test runner to run ART run-tests. */
public class ArtRunTest implements IRemoteTest, IAbiReceiver, ITestFilterReceiver {

    private static final String RUNTEST_TAG = "ArtRunTest";

    private static final Path ART_APEX_PATH = Paths.get("/apex", "com.android.art");

    private static final String DALVIKVM_CMD =
            "dalvikvm|#BITNESS#| -classpath |#CLASSPATH#| |#MAINCLASS#|";

    // Name of the Checker Python Archive (PAR) file.
    public static final String CHECKER_PAR_FILENAME = "art-run-test-checker";
    private static final long CHECKER_TIMEOUT_MS = 30 * 1000;

    @Option(
            name = "test-timeout",
            description =
                    "The max time in ms for an art run-test to "
                            + "run. Test run will be aborted if any test takes longer.",
            isTimeVal = true)
    private long mMaxTestTimeMs = 1 * 60 * 1000;

    @Option(name = "run-test-name", description = "The name to use when reporting results.")
    private String mRunTestName;

    @Option(name = "classpath", description = "Holds the paths to search when loading tests.")
    private List<String> mClasspath = new ArrayList<>();

    private ITestDevice mDevice = null;
    private IAbi mAbi = null;
    private final Set<String> mIncludeFilters = new LinkedHashSet<>();
    private final Set<String> mExcludeFilters = new LinkedHashSet<>();

    /** {@inheritDoc} */
    @Override
    public void setAbi(IAbi abi) {
        mAbi = abi;
    }

    @Override
    public IAbi getAbi() {
        return mAbi;
    }

    /** {@inheritDoc} */
    @Override
    public void addIncludeFilter(String filter) {
        mIncludeFilters.add(filter);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllIncludeFilters(Set<String> filters) {
        mIncludeFilters.addAll(filters);
    }

    /** {@inheritDoc} */
    @Override
    public void addExcludeFilter(String filter) {
        mExcludeFilters.add(filter);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllExcludeFilters(Set<String> filters) {
        mExcludeFilters.addAll(filters);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getIncludeFilters() {
        return mIncludeFilters;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getExcludeFilters() {
        return mExcludeFilters;
    }

    /** {@inheritDoc} */
    @Override
    public void clearIncludeFilters() {
        mIncludeFilters.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void clearExcludeFilters() {
        mExcludeFilters.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        mDevice = testInfo.getDevice();
        if (mDevice == null) {
            throw new IllegalArgumentException("Device has not been set.");
        }
        if (mAbi == null) {
            throw new IllegalArgumentException("ABI has not been set.");
        }
        if (mRunTestName == null) {
            throw new IllegalArgumentException("Run-test name has not been set.");
        }
        if (mClasspath.isEmpty()) {
            throw new IllegalArgumentException("Classpath is empty.");
        }

        runArtTest(testInfo, listener);
    }

    /**
     * Run a single ART run-test (on device).
     *
     * @param listener The {@link ITestInvocationListener} object associated to the executed test
     * @throws DeviceNotAvailableException If there was a problem communicating with the device.
     */
    void runArtTest(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        String abi = mAbi.getName();
        String runName = String.format("%s_%s", RUNTEST_TAG, abi);
        TestDescription testId = new TestDescription(runName, mRunTestName);
        if (shouldSkipCurrentTest(testId)) {
            return;
        }

        String deviceSerialNumber = mDevice.getSerialNumber();
        CLog.i("Running ArtRunTest %s on %s", mRunTestName, deviceSerialNumber);

        String testCmd = DALVIKVM_CMD;
        testCmd = testCmd.replace("|#BITNESS#|", AbiUtils.getBitness(abi));
        testCmd = testCmd.replace("|#CLASSPATH#|", ArrayUtil.join(File.pathSeparator, mClasspath));
        // TODO: Turn this into an an option of the `ArtRunTest` class?
        testCmd = testCmd.replace("|#MAINCLASS#|", "Main");

        CLog.d("About to run run-test command: `%s`", testCmd);
        // Note: We only run one test at the moment.
        int testCount = 1;
        listener.testRunStarted(runName, testCount);
        listener.testStarted(testId);

        try {
            // TODO: The "run" step should be configurable, as is the case in current ART
            // `run-test` scripts).

            // Execute the test on device.
            CommandResult testResult =
                    mDevice.executeShellV2Command(
                            testCmd, mMaxTestTimeMs, TimeUnit.MILLISECONDS, /* retryAttempts */ 0);
            if (testResult.getStatus() != CommandStatus.SUCCESS) {
                String message =
                        String.format(
                                "Test command execution failed with status %s: %s",
                                testResult.getStatus(), testResult);
                CLog.e(message);
                listener.testFailed(testId, message);
                return;
            }
            Integer exitCode = testResult.getExitCode();
            CLog.v("`%s` on %s returned exit code: %d", testCmd, deviceSerialNumber, exitCode);
            String actualStdoutText = testResult.getStdout();
            CLog.v("`%s` on %s returned stdout: %s", testCmd, deviceSerialNumber, actualStdoutText);
            String actualStderrText = testResult.getStderr();
            CLog.v("`%s` on %s returned stderr: %s", testCmd, deviceSerialNumber, actualStderrText);

            // TODO: The "check" step should be configurable, as is the case in current ART
            // `run-test` scripts).

            // List of encountered errors during the test.
            List<String> errors = new ArrayList<>();

            // Check the test's exit code.
            Optional<String> exitCodeError = checkExitCode(exitCode);
            exitCodeError.ifPresent(e -> errors.add(e));

            // Check the test's standard output.
            Optional<String> stdoutError =
                    checkTestOutput(
                            testInfo,
                            actualStdoutText,
                            /* outputShortName */ "stdout",
                            /* outputPrettyName */ "standard output");
            stdoutError.ifPresent(e -> errors.add(e));

            // Check the test's standard error.
            Optional<String> stderrError =
                    checkTestOutput(
                            testInfo,
                            actualStderrText,
                            /* outputShortName */ "stderr",
                            /* outputPrettyName */ "standard error");
            stderrError.ifPresent(e -> errors.add(e));

            // If the test us a Checker test, run Checker and check its output.
            if (mRunTestName.contains("-checker-")) {
                Optional<String> checkerError = executeCheckerTest(testInfo, listener);
                checkerError.ifPresent(e -> errors.add(e));
            }

            // Process potential errors.
            if (!errors.isEmpty()) {
                String errorMessage = String.join("\n", errors);
                listener.testFailed(testId, errorMessage);
            }
        } finally {
            HashMap<String, Metric> emptyTestMetrics = new HashMap<>();
            listener.testEnded(testId, emptyTestMetrics);
            HashMap<String, Metric> emptyTestRunMetrics = new HashMap<>();
            // TODO: Pass an actual value as `elapsedTimeMillis` argument.
            listener.testRunEnded(/* elapsedTimeMillis*/ 0, emptyTestRunMetrics);
        }
    }

    /**
     * Check the exit code returned by a test command.
     *
     * @param exitCode The exit code returned by the test command
     * @return An optional error message, empty if the test exit code indicated success
     */
    protected Optional<String> checkExitCode(Integer exitCode) {
        if (exitCode != 0) {
            String errorMessage =
                    String.format("Test `%s` exited with code %d", mRunTestName, exitCode);
            CLog.i(errorMessage);
            return Optional.of(errorMessage);
        }
        return Optional.empty();
    }

    /**
     * Check an output produced by a test command.
     *
     * <p>Used to check the standard output and the standard error of a test.
     *
     * @param testInfo The {@link TestInformation} object associated to the executed test
     * @param actualOutputText The output produced by the test
     * @param outputShortName The short name of the output channel
     * @param outputPrettyName A prettier name for the output channel, used in error messages
     * @return An optional error message, empty if the checked output is valid
     */
    protected Optional<String> checkTestOutput(
            TestInformation testInfo,
            String actualOutputText,
            String outputShortName,
            String outputPrettyName) {
        final String expectedFileName = String.format("expected-%s.txt", outputShortName);
        final String actualFileName = outputShortName;

        if (actualOutputText == null) {
            String errorMessage =
                    String.format(
                            "No %s received to compare to for test `%s`",
                            outputPrettyName, mRunTestName);
            CLog.e(errorMessage);
            return Optional.of(errorMessage);
        }
        try {
            String expectedOutputFileName = String.format("%s-%s", mRunTestName, expectedFileName);
            File expectedOutputFile =
                    testInfo.getDependencyFile(expectedOutputFileName, /* targetFirst */ true);
            CLog.i(
                    "Found expected %s for run-test `%s`: `%s`",
                    outputPrettyName, mRunTestName, expectedOutputFile);
            String expectedOutputText = FileUtil.readStringFromFile(expectedOutputFile);

            if (!actualOutputText.equals(expectedOutputText)) {
                // Produce a unified diff output for the error message.
                String diff =
                        computeDiff(
                                expectedOutputText,
                                actualOutputText,
                                expectedFileName,
                                actualFileName);
                String errorMessage =
                        String.format(
                                "The actual %s does not match the expected %s for test `%s`:\n%s",
                                outputPrettyName, outputPrettyName, mRunTestName, diff);
                CLog.i(errorMessage);
                return Optional.of(errorMessage);
            }
        } catch (IOException ioe) {
            String errorMessage =
                    String.format(
                            "I/O error while accessing expected %s for test `%s`: %s",
                            outputPrettyName, mRunTestName, ioe);
            CLog.e(errorMessage);
            return Optional.of(errorMessage);
        }
        return Optional.empty();
    }

    /**
     * Execute a Checker test and check its output.
     *
     * <p>Checker tests are additional tests included in some ART run-tests, written as annotations
     * in the comments of a test's source files, and used to verify ART's compiler.
     *
     * @param testInfo The {@link TestInformation} object associated to the executed test
     * @param listener The {@link ITestInvocationListener} object associated to the executed test
     * @return An optional error message, empty if the Checker test succeeded
     */
    protected Optional<String> executeCheckerTest(
            TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        // TODO: Encapsulate the device temp dir creation logic in its own method.
        String tmpCheckerDir =
                String.format("/data/local/tmp/%s", mRunTestName.replaceAll("/", "-"));
        String mkdirCmd = String.format("mkdir -p \"%s\"", tmpCheckerDir);
        CommandResult mkdirResult = mDevice.executeShellV2Command(mkdirCmd);
        if (mkdirResult.getStatus() != CommandStatus.SUCCESS) {
            String errorMessage =
                    String.format(
                            "Cannot create directory `%s` on device", mkdirResult.getStderr());
            CLog.e(errorMessage);
            return Optional.of(errorMessage);
        }

        String cfgPath = tmpCheckerDir + "/graph.cfg";
        String oatPath = tmpCheckerDir + "/output.oat";
        String abi = mAbi.getName();
        String dex2oatBinary = "dex2oat" + AbiUtils.getBitness(abi);
        Path dex2oatPath = Paths.get(ART_APEX_PATH.toString(), "bin", dex2oatBinary);
        String dex2oatCmd =
                String.format(
                        "%s --dex-file=%s --oat-file=%s --dump-cfg=%s -j1",
                        dex2oatPath, mClasspath.get(0), oatPath, cfgPath);
        CommandResult dex2oatResult = mDevice.executeShellV2Command(dex2oatCmd);
        if (dex2oatResult.getStatus() != CommandStatus.SUCCESS) {
            String errorMessage =
                    String.format("Error while running dex2oat: %s", dex2oatResult.getStderr());
            CLog.e(errorMessage);
            return Optional.of(errorMessage);
        }

        File runTestDir;
        try {
            runTestDir =
                    Files.createTempDirectory(testInfo.dependenciesFolder().toPath(), mRunTestName)
                            .toFile();
        } catch (IOException e) {
            String errorMessage = String.format("I/O error while creating test dir: %s", e);
            CLog.e(errorMessage);
            return Optional.of(errorMessage);
        }

        File localCfgPath = new File(runTestDir, "graph.cfg");
        if (localCfgPath.isFile()) {
            localCfgPath.delete();
        }

        if (!mDevice.pullFile(cfgPath, localCfgPath)) {
            String errorMessage = "Cannot pull CFG file from the device";
            CLog.e(errorMessage);
            return Optional.of(errorMessage);
        }

        File tempJar = new File(runTestDir, "temp.jar");
        if (!mDevice.pullFile(mClasspath.get(0), tempJar)) {
            String errorMessage = "Cannot pull JAR file from the device";
            CLog.e(errorMessage);
            return Optional.of(errorMessage);
        }

        try {
            extractSourcesFromJar(runTestDir, tempJar);
        } catch (IOException e) {
            String errorMessage = String.format("Error unpacking test JAR file: %s", e);
            CLog.e(errorMessage);
            return Optional.of(errorMessage);
        }

        String checkerArch = AbiUtils.getArchForAbi(mAbi.getName()).toUpperCase();

        File checkerBinary = getCheckerBinaryPath(testInfo);

        String[] checkerCommandLine = {
            checkerBinary.getAbsolutePath(),
            "--no-print-cfg",
            "-q",
            "--arch=" + checkerArch,
            localCfgPath.getAbsolutePath(),
            runTestDir.getAbsolutePath()
        };

        Optional<String> checkerError = runChecker(checkerCommandLine);
        if (checkerError.isPresent()) {
            listener.testLog("graph.cfg", LogDataType.CFG, new FileInputStreamSource(localCfgPath));
            CLog.i(checkerError.get());
            return checkerError;
        }

        FileUtil.recursiveDelete(runTestDir);
        return Optional.empty();
    }

    /** Find the Checker binary (Python Archive). */
    protected File getCheckerBinaryPath(TestInformation testInfo) {
        File checkerBinary;
        try {
            checkerBinary =
                    testInfo.getDependencyFile(CHECKER_PAR_FILENAME, /* targetFirst */ false);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(
                    String.format("Couldn't find Checker binary file `%s`", CHECKER_PAR_FILENAME));
        }
        checkerBinary.setExecutable(true);
        return checkerBinary;
    }

    /**
     * Run a Checker command and check its result.
     *
     * @param checkerCommandLine The Checker command line to execute
     * @return An optional error message, empty if the Checker invocation was successful
     */
    protected Optional<String> runChecker(String[] checkerCommandLine) {
        CLog.d("About to run Checker command: %s", String.join(" ", checkerCommandLine));
        CommandResult result = RunUtil.getDefault().runTimedCmd(CHECKER_TIMEOUT_MS,
                checkerCommandLine);
        if (result.getStatus() != CommandStatus.SUCCESS) {
            String errorMessage;
            if (result.getStatus() == CommandStatus.TIMED_OUT) {
                errorMessage =
                        String.format("Checker command timed out after %s ms", CHECKER_TIMEOUT_MS);
            } else {
                errorMessage =
                        String.format(
                                "Checker command finished unsuccessfully: status=%s, exit"
                                        + " code=%s,\n"
                                        + "stdout=\n"
                                        + "%s\n"
                                        + "stderr=\n"
                                        + "%s\n",
                                result.getStatus(),
                                result.getExitCode(),
                                result.getStdout(),
                                result.getStderr());
            }
            CLog.i(errorMessage);
            return Optional.of(errorMessage);
        }
        return Optional.empty();
    }

    /**
     * Extract src directory from given jar file to given directory
     */
    protected void extractSourcesFromJar(File runTestDir, File jar) throws IOException {
        try (ZipFile archive = new ZipFile(jar)) {
            File srcFile = new File(runTestDir, "src");
            if (srcFile.exists()) {
                FileUtil.recursiveDelete(srcFile);
            }

            List<? extends ZipEntry> entries =
                    archive.stream()
                            .sorted(Comparator.comparing(ZipEntry::getName))
                            .collect(Collectors.toList());

            for (ZipEntry entry : entries) {
                if (entry.getName().startsWith("src")) {
                    Path entryDest = runTestDir.toPath().resolve(entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectory(entryDest);
                    } else {
                        Files.copy(archive.getInputStream(entry), entryDest);
                    }
                }
            }
        }
    }

    /**
     * Check if current test should be skipped.
     *
     * @param description The test in progress.
     * @return true if the test should be skipped.
     */
    private boolean shouldSkipCurrentTest(TestDescription description) {
        // Force to skip any test not listed in include filters, or listed in exclude filters.
        // exclude filters have highest priority.
        String testName = description.getTestName();
        String descString = description.toString();
        if (mExcludeFilters.contains(testName) || mExcludeFilters.contains(descString)) {
            return true;
        }
        if (!mIncludeFilters.isEmpty()) {
            return !mIncludeFilters.contains(testName) && !mIncludeFilters.contains(descString);
        }
        return false;
    }

    /**
     * Compute the difference between expected and actual outputs as a unified diff.
     *
     * @param expected The expected output
     * @param actual The actual output
     * @param expectedFileName The name of the expected output file name (used in diff header)
     * @param actualFileName The name of the actual output file name (used in diff header)
     * @return The unified diff between the expected and actual outputs
     */
    private String computeDiff(
            String expected, String actual, String expectedFileName, String actualFileName) {
        List<String> expectedLines = Arrays.asList(expected.split("\\r?\\n"));
        List<String> actualLines = Arrays.asList(actual.split("\\r?\\n"));
        Patch<String> diff = DiffUtils.diff(expectedLines, actualLines);
        List<String> unifiedDiff =
                DiffUtils.generateUnifiedDiff(
                        expectedFileName, actualFileName, expectedLines, diff, 3);
        StringBuilder diffOutput = new StringBuilder();
        for (String delta : unifiedDiff) {
            diffOutput.append(delta).append('\n');
        }
        return diffOutput.toString();
    }
}
