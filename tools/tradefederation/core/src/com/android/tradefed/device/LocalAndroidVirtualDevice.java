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

package com.android.tradefed.device;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.cloud.GceAvdInfo;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestLoggerReceiver;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.MultiMap;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.TarUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.net.HostAndPort;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The class for local virtual devices running on TradeFed host. */
public class LocalAndroidVirtualDevice extends RemoteAndroidDevice implements ITestLoggerReceiver {

    private static final int INVALID_PORT = 0;

    // Environment variables.
    private static final String ANDROID_SOONG_HOST_OUT = "ANDROID_SOONG_HOST_OUT";
    private static final String TMPDIR = "TMPDIR";

    // The build info key of the cuttlefish tools.
    private static final String CVD_HOST_PACKAGE_NAME = "cvd-host_package.tar.gz";
    // The optional build info keys for mixing images.
    private static final String SYSTEM_IMAGE_ZIP_NAME = "system-img.zip";
    private static final String OTA_TOOLS_ZIP_NAME = "otatools.zip";

    private static final String CUTTLEFISH_RUNTIME_DIR_NAME = "cuttlefish_runtime";

    private ITestLogger mTestLogger = null;

    // Temporary directories for images, runtime files, and tools.
    private File mImageDir = null;
    private File mInstanceDir = null;
    private File mHostPackageDir = null;
    private File mSystemImageDir = null;
    private File mOtaToolsDir = null;
    private List<File> mTempDirs = new ArrayList<File>();

    private GceAvdInfo mGceAvdInfo = null;

    public LocalAndroidVirtualDevice(
            IDevice device, IDeviceStateMonitor stateMonitor, IDeviceMonitor allocationMonitor) {
        super(device, stateMonitor, allocationMonitor);
    }

    /** Execute common setup procedure and launch the virtual device. */
    @Override
    public synchronized void preInvocationSetup(
            IBuildInfo info, MultiMap<String, String> attributes)
            throws TargetSetupError, DeviceNotAvailableException {
        // The setup method in super class does not require the device to be online.
        super.preInvocationSetup(info, attributes);

        prepareToolsAndImages(info);

        CommandResult result = null;
        File report = null;
        try {
            report = FileUtil.createTempFile("report", ".json");
            result = acloudCreate(report, getOptions());
            loadAvdInfo(report);
        } catch (IOException ex) {
            throw new TargetSetupError(
                    "Cannot create acloud report file.",
                    ex,
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        } finally {
            FileUtil.deleteFile(report);
        }
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            throw new TargetSetupError(
                    String.format("Cannot execute acloud command. stderr:\n%s", result.getStderr()),
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.ACLOUD_UNDETERMINED);
        }

        HostAndPort hostAndPort = mGceAvdInfo.hostAndPort();
        replaceStubDevice(hostAndPort.toString());

        RecoveryMode previousMode = getRecoveryMode();
        try {
            setRecoveryMode(RecoveryMode.NONE);
            if (!adbTcpConnect(hostAndPort.getHost(), Integer.toString(hostAndPort.getPort()))) {
                throw new TargetSetupError(
                        String.format("Cannot connect to %s.", hostAndPort),
                        getDeviceDescriptor(),
                        DeviceErrorIdentifier.FAILED_TO_CONNECT_TO_GCE);
            }
            waitForDeviceAvailable();
        } finally {
            setRecoveryMode(previousMode);
        }
    }

    /** Execute common tear-down procedure and stop the virtual device. */
    @Override
    public synchronized void postInvocationTearDown(Throwable exception) {
        TestDeviceOptions options = getOptions();
        HostAndPort hostAndPort = getHostAndPortFromAvdInfo();
        String instanceName = (mGceAvdInfo != null ? mGceAvdInfo.instanceName() : null);
        try {
            shutdown();
            reportInstanceLogs();
        } finally {
            restoreStubDevice();

            if (!options.shouldSkipTearDown()) {
                deleteTempDirs();
            } else {
                CLog.i(
                        "Skip deleting the temporary directories.\n"
                                + "Address: %s\nName: %s\n"
                                + "Host package: %s\nImage: %s\nInstance: %s\n"
                                + "System image: %s\nOTA tools: %s",
                        hostAndPort,
                        instanceName,
                        mHostPackageDir,
                        mImageDir,
                        mInstanceDir,
                        mSystemImageDir,
                        mOtaToolsDir);
                resetTempDirAttributes();
            }

            mGceAvdInfo = null;

            super.postInvocationTearDown(exception);
        }
    }

    @Override
    public void setTestLogger(ITestLogger testLogger) {
        mTestLogger = testLogger;
    }

    /**
     * Extract a file if the format is tar.gz or zip.
     *
     * @param file the file to be extracted.
     * @return a temporary directory containing the extracted content if the file is an archive;
     *     otherwise return the input file.
     * @throws IOException if the file cannot be extracted.
     */
    private File extractArchive(File file) throws IOException {
        if (file.isDirectory()) {
            return file;
        }
        if (TarUtil.isGzip(file)) {
            file = TarUtil.extractTarGzipToTemp(file, file.getName());
            mTempDirs.add(file);
        } else if (ZipUtil.isZipFileValid(file, false)) {
            file = ZipUtil.extractZipToTemp(file, file.getName());
            mTempDirs.add(file);
        } else {
            CLog.w("Cannot extract %s.", file);
        }
        return file;
    }

    /** Find a file in build info and extract it to a temporary directory. */
    private File findAndExtractFile(IBuildInfo buildInfo, String fileKey) throws TargetSetupError {
        File file = buildInfo.getFile(fileKey);
        try {
            return file != null ? extractArchive(file) : null;
        } catch (IOException ex) {
            throw new TargetSetupError(
                    String.format("Cannot extract %s.", fileKey),
                    ex,
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        }
    }

    /** Find a file in build info and extract it; fall back to environment variable. */
    private File findAndExtractFile(IBuildInfo buildInfo, String fileKey, String envVar)
            throws TargetSetupError {
        File dir = findAndExtractFile(buildInfo, fileKey);
        if (dir != null) {
            return dir;
        }

        String envDir = System.getenv(envVar);
        if (!Strings.isNullOrEmpty(envDir)) {
            dir = new File(envDir);
            if (dir.isDirectory()) {
                CLog.i(
                        "Use the files in %s as the build info does not provide %s.",
                        envVar, fileKey);
                return dir;
            }
            CLog.w("Cannot use the files in %s as it is not a directory.", envVar);
        }
        return null;
    }

    /** Find device images in build info and extract to a temporary directory. */
    private File findDeviceImages(IBuildInfo buildInfo) throws TargetSetupError {
        File imageZip = buildInfo.getFile(BuildInfoFileKey.DEVICE_IMAGE);
        if (imageZip == null) {
            throw new TargetSetupError(
                    "Cannot find image zip in build info.",
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
        }
        try {
            return extractArchive(imageZip);
        } catch (IOException ex) {
            throw new TargetSetupError(
                    "Cannot extract image zip.",
                    ex,
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        }
    }

    /** Create a temporary directory that will be deleted when teardown. */
    private File createTempDir() throws TargetSetupError {
        try {
            File tempDir = FileUtil.createTempDir("LocalVirtualDevice");
            mTempDirs.add(tempDir);
            return tempDir;
        } catch (IOException ex) {
            throw new TargetSetupError(
                    "Cannot create temporary directory.",
                    ex,
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.FAIL_TO_CREATE_FILE);
        }
    }

    /** Get the necessary files to create the instance. */
    private void prepareToolsAndImages(IBuildInfo info) throws TargetSetupError {
        try {
            mHostPackageDir =
                    findAndExtractFile(info, CVD_HOST_PACKAGE_NAME, ANDROID_SOONG_HOST_OUT);
            if (mHostPackageDir == null) {
                throw new TargetSetupError(
                        String.format(
                                "Cannot find %s in build info and %s.",
                                CVD_HOST_PACKAGE_NAME, ANDROID_SOONG_HOST_OUT),
                        getDeviceDescriptor(),
                        InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
            }
            mImageDir = findDeviceImages(info);
            mSystemImageDir = findAndExtractFile(info, SYSTEM_IMAGE_ZIP_NAME);
            mOtaToolsDir = findAndExtractFile(info, OTA_TOOLS_ZIP_NAME);
            mInstanceDir = createTempDir();
        } catch (TargetSetupError ex) {
            deleteTempDirs();
            throw ex;
        }
        FileUtil.chmodRWXRecursively(new File(mHostPackageDir, "bin"));
        if (mOtaToolsDir != null) {
            FileUtil.chmodRWXRecursively(new File(mOtaToolsDir, "bin"));
        }
    }

    /** Reset all temp dir paths to null. */
    private void resetTempDirAttributes() {
        mTempDirs.clear();
        mImageDir = null;
        mInstanceDir = null;
        mHostPackageDir = null;
        mSystemImageDir = null;
        mOtaToolsDir = null;
    }

    /** Delete all temporary directories. */
    @VisibleForTesting
    void deleteTempDirs() {
        for (File tempDir : mTempDirs) {
            FileUtil.recursiveDelete(tempDir);
        }
        resetTempDirAttributes();
    }

    /**
     * Change the initial serial number of {@link StubLocalAndroidVirtualDevice}.
     *
     * @param newSerialNumber the serial number of the new stub device.
     * @throws TargetSetupError if the original device type is not expected.
     */
    private void replaceStubDevice(String newSerialNumber) throws TargetSetupError {
        IDevice device = getIDevice();
        if (!StubLocalAndroidVirtualDevice.class.equals(device.getClass())) {
            throw new TargetSetupError(
                    "Unexpected device type: " + device.getClass(),
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.OPTION_CONFIGURATION_ERROR);
        }
        setIDevice(new StubLocalAndroidVirtualDevice(newSerialNumber));
        setFastbootEnabled(false);
    }

    /** Restore the {@link StubLocalAndroidVirtualDevice} with the initial serial number. */
    private void restoreStubDevice() {
        setIDevice(new StubLocalAndroidVirtualDevice(getInitialSerial()));
        setFastbootEnabled(false);
    }

    private void addSystemImageDirToAcloudCommand(List<String> command) {
        if (mSystemImageDir != null) {
            command.add("--local-system-image");
            command.add(mSystemImageDir.getAbsolutePath());
        }
        if (mOtaToolsDir != null) {
            command.add("--local-tool");
            command.add(mOtaToolsDir.getAbsolutePath());
        }
    }

    private static void addLogLevelToAcloudCommand(List<String> command, LogLevel logLevel) {
        if (LogLevel.VERBOSE.equals(logLevel)) {
            command.add("-v");
        } else if (LogLevel.DEBUG.equals(logLevel)) {
            command.add("-vv");
        }
    }

    private CommandResult acloudCreate(File report, TestDeviceOptions options) {
        CommandResult result = null;

        File acloud = options.getAvdDriverBinary();
        if (acloud == null || !acloud.isFile()) {
            CLog.e("Specified AVD driver binary is not a file.");
            result = new CommandResult(CommandStatus.EXCEPTION);
            result.setStderr("Specified AVD driver binary is not a file.");
            return result;
        }
        acloud.setExecutable(true);

        for (int attempt = 0; attempt < options.getGceMaxAttempt(); attempt++) {
            result =
                    acloudCreate(
                            options.getGceCmdTimeout(),
                            acloud,
                            report,
                            options.getGceDriverLogLevel(),
                            options.getGceDriverParams());
            if (CommandStatus.SUCCESS.equals(result.getStatus())) {
                break;
            }
            CLog.w(
                    "Failed to start local virtual instance with attempt: %d; command status: %s",
                    attempt, result.getStatus());
        }
        return result;
    }

    private CommandResult acloudCreate(
            long timeout,
            File acloud,
            File report,
            LogLevel logLevel,
            List<String> args) {
        IRunUtil runUtil = createRunUtil();
        // The command creates files under TMPDIR.
        runUtil.setEnvVariable(
                TMPDIR, new File(System.getProperty("java.io.tmpdir")).getAbsolutePath());

        List<String> command =
                new ArrayList<String>(
                        Arrays.asList(
                                acloud.getAbsolutePath(),
                                "create",
                                "--local-instance",
                                "--local-image",
                                mImageDir.getAbsolutePath(),
                                "--local-instance-dir",
                                mInstanceDir.getAbsolutePath(),
                                "--local-tool",
                                mHostPackageDir.getAbsolutePath(),
                                "--report_file",
                                report.getAbsolutePath(),
                                "--no-autoconnect",
                                "--yes",
                                "--skip-pre-run-check"));
        addSystemImageDirToAcloudCommand(command);
        addLogLevelToAcloudCommand(command, logLevel);
        command.addAll(args);

        CommandResult result = runUtil.runTimedCmd(timeout, command.toArray(new String[0]));
        CLog.i("acloud create stdout:\n%s", result.getStdout());
        CLog.i("acloud create stderr:\n%s", result.getStderr());
        return result;
    }

    /**
     * Get valid host and port from mGceAvdInfo.
     *
     * @return {@link HostAndPort} if the port is valid; null otherwise.
     */
    private HostAndPort getHostAndPortFromAvdInfo() {
        if (mGceAvdInfo == null) {
            return null;
        }
        HostAndPort hostAndPort = mGceAvdInfo.hostAndPort();
        if (hostAndPort == null
                || !hostAndPort.hasPort()
                || hostAndPort.getPort() == INVALID_PORT) {
            return null;
        }
        return hostAndPort;
    }

    /** Initialize instance name, host address, and port from an acloud report file. */
    private void loadAvdInfo(File report) throws TargetSetupError {
        mGceAvdInfo = GceAvdInfo.parseGceInfoFromFile(report, getDeviceDescriptor(), INVALID_PORT);
        if (mGceAvdInfo == null) {
            throw new TargetSetupError(
                    "Cannot read acloud report file.",
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.NO_ACLOUD_REPORT);
        }

        if (!GceAvdInfo.GceStatus.SUCCESS.equals(mGceAvdInfo.getStatus())) {
            throw new TargetSetupError(
                    "Cannot launch virtual device: " + mGceAvdInfo.getErrors(),
                    getDeviceDescriptor(),
                    mGceAvdInfo.getErrorType());
        }

        if (Strings.isNullOrEmpty(mGceAvdInfo.instanceName())) {
            throw new TargetSetupError(
                    "No instance name in acloud report.",
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.NO_ACLOUD_REPORT);
        }

        if (getHostAndPortFromAvdInfo() == null) {
            throw new TargetSetupError(
                    "No port in acloud report.",
                    getDeviceDescriptor(),
                    InfraErrorIdentifier.NO_ACLOUD_REPORT);
        }
    }

    /** Shutdown the device. */
    public synchronized void shutdown() {
        TestDeviceOptions options = getOptions();
        if (options.shouldSkipTearDown()) {
            CLog.i("Skip shutting down the virtual device.");
            return;
        }
        HostAndPort hostAndPort = getHostAndPortFromAvdInfo();
        String instanceName = (mGceAvdInfo != null ? mGceAvdInfo.instanceName() : null);

        try {
            if (hostAndPort != null) {
                if (!adbTcpDisconnect(
                        hostAndPort.getHost(), Integer.toString(hostAndPort.getPort()))) {
                    CLog.e("Cannot disconnect from %s", hostAndPort.toString());
                }
            } else {
                CLog.i("Skip disconnecting.");
            }

            if (instanceName != null) {
                CommandResult result = acloudDelete(instanceName, options);
                if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
                    CLog.e("Cannot stop the virtual device.");
                }
            } else {
                CLog.i("Skip acloud delete.");
            }
        } finally {
            // Remove AVD info to avoid deleting the device more than once.
            mGceAvdInfo = null;
        }
    }

    private CommandResult acloudDelete(String instanceName, TestDeviceOptions options) {
        File acloud = options.getAvdDriverBinary();
        if (acloud == null || !acloud.isFile()) {
            CLog.e("Specified AVD driver binary is not a file.");
            return new CommandResult(CommandStatus.EXCEPTION);
        }
        acloud.setExecutable(true);

        IRunUtil runUtil = createRunUtil();
        runUtil.setEnvVariable(
                TMPDIR, new File(System.getProperty("java.io.tmpdir")).getAbsolutePath());

        List<String> command =
                new ArrayList<String>(
                        Arrays.asList(
                                acloud.getAbsolutePath(),
                                "delete",
                                "--local-only",
                                "--instance-names",
                                instanceName));
        addLogLevelToAcloudCommand(command, options.getGceDriverLogLevel());

        CommandResult result =
                runUtil.runTimedCmd(options.getGceCmdTimeout(), command.toArray(new String[0]));
        CLog.i("acloud delete stdout:\n%s", result.getStdout());
        CLog.i("acloud delete stderr:\n%s", result.getStderr());
        return result;
    }

    private void reportInstanceLogs() {
        if (mTestLogger == null || mInstanceDir == null) {
            return;
        }
        File runtimeDir = new File(mInstanceDir, CUTTLEFISH_RUNTIME_DIR_NAME);
        reportInstanceLog(new File(runtimeDir, "kernel.log"), LogDataType.KERNEL_LOG);
        reportInstanceLog(new File(runtimeDir, "logcat"), LogDataType.LOGCAT);
        reportInstanceLog(new File(runtimeDir, "launcher.log"), LogDataType.TEXT);
        reportInstanceLog(new File(runtimeDir, "cuttlefish_config.json"), LogDataType.TEXT);
    }

    private void reportInstanceLog(File file, LogDataType type) {
        if (file.exists()) {
            try (InputStreamSource source = new FileInputStreamSource(file)) {
                mTestLogger.testLog(file.getName(), type, source);
            }
        } else {
            CLog.w("%s doesn't exist.", file.getAbsolutePath());
        }
    }

    @VisibleForTesting
    IRunUtil createRunUtil() {
        return new RunUtil();
    }
}
