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
package com.android.tradefed.targetprep;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.CollectingOutputReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.SparseImageUtil;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.ZipUtil2;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link ITargetPreparer} that sets up a system image on top of a device build with the Dynamic
 * System Update.
 */
@OptionClass(alias = "dynamic-system-update")
public class DynamicSystemPreparer extends BaseTargetPreparer {
    static final int DSU_MAX_WAIT_SEC = 10 * 60;

    private static final String SYSTEM_IMAGE_NAME = "system.img";
    private static final String DEST_PATH = "/sdcard/system.raw.gz";

    @Option(
            name = "system-image-zip-name",
            description = "The name of the zip file containing system.img.")
    private String mSystemImageZipName = "system-img.zip";

    @Option(
            name = "user-data-size-in-gb",
            description = "Number of GB to be allocated for DSU user-data.")
    private long mUserDataSizeInGb = 16L; // 16GB

    @Option(
            name = "wait-for-device-online",
            description = "whether to wait for device online after install DSU.")
    private boolean mWaitForDeviceOnline = true;

    private boolean isDSURunning(ITestDevice device) throws DeviceNotAvailableException {
        CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        device.executeShellCommand("gsi_tool status", receiver);
        return receiver.getOutput().contains("running");
    }

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        IBuildInfo buildInfo = testInfo.getBuildInfo();
        File systemImageZipFile = buildInfo.getFile(mSystemImageZipName);
        if (systemImageZipFile == null) {
            throw new BuildError(
                    "Cannot find " + mSystemImageZipName + " in build info.",
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
        }

        List<File> tempFiles = new ArrayList<File>();
        File systemImage = null;
        File rawSystemImage = null;
        File systemImageGZ = null;
        try {
            if (systemImageZipFile.isDirectory()) {
                systemImage = new File(systemImageZipFile, SYSTEM_IMAGE_NAME);
            } else {
                try (ZipFile zipFile = new ZipFile(systemImageZipFile)) {
                    systemImage = ZipUtil2.extractFileFromZip(zipFile, SYSTEM_IMAGE_NAME);
                }
                if (systemImage != null) {
                    tempFiles.add(systemImage);
                }
            }
            if (systemImage == null || !systemImage.isFile()) {
                throw new BuildError(
                        String.format(
                                "Cannot find %s in %s.", SYSTEM_IMAGE_NAME, mSystemImageZipName),
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.CONFIGURED_ARTIFACT_NOT_FOUND);
            }

            if (SparseImageUtil.isSparse(systemImage)) {
                rawSystemImage = FileUtil.createTempFile("system", ".raw");
                tempFiles.add(rawSystemImage);
                SparseImageUtil.unsparse(systemImage, rawSystemImage);
            } else {
                // system.img is already non-sparse
                rawSystemImage = systemImage;
            }
            systemImageGZ = FileUtil.createTempFile("system", ".raw.gz");
            tempFiles.add(systemImageGZ);
            long rawSize = rawSystemImage.length();
            ZipUtil.gzipFile(rawSystemImage, systemImageGZ);
            CLog.i("Pushing %s to %s", systemImageGZ.getAbsolutePath(), DEST_PATH);
            if (!device.pushFile(systemImageGZ, DEST_PATH)) {
                throw new TargetSetupError(
                        String.format(
                                "Failed to push %s to %s", systemImageGZ.getName(), DEST_PATH),
                        device.getDeviceDescriptor());
            }
            device.setProperty("persist.sys.fflag.override.settings_dynamic_system", "true");

            String command =
                    "am start-activity "
                            + "-n com.android.dynsystem/com.android.dynsystem.VerificationActivity "
                            + "-a android.os.image.action.START_INSTALL "
                            + "-d file://"
                            + DEST_PATH
                            + " "
                            + "--el KEY_SYSTEM_SIZE "
                            + rawSize
                            + " "
                            + "--el KEY_USERDATA_SIZE "
                            + mUserDataSizeInGb * 1024 * 1024 * 1024
                            + " --ez KEY_ENABLE_WHEN_COMPLETED true";
            device.executeShellCommand(command);
            // Check if device shows as unavailable (as expected after the activity finished).
            if (!device.waitForDeviceNotAvailable(DSU_MAX_WAIT_SEC * 1000)) {
                throw new TargetSetupError(
                        "Timed out waiting for DSU installation to complete and reboot",
                        device.getDeviceDescriptor());
            }

            // If installing user build by DSU, the device will boot up without user debug mode.
            // The adb command can not be executed immediately after upgrade to a user build by DSU.
            if (!mWaitForDeviceOnline) {
                return;
            }

            try {
                // waitForDeviceOnline() throws DeviceNotAvailableException if device does not
                // become online within timeout.
                device.waitForDeviceOnline();
            } catch (DeviceNotAvailableException e) {
                throw new TargetSetupError(
                        "Timed out booting into DSU", e, device.getDeviceDescriptor());
            }
            if (!isDSURunning(device)) {
                throw new TargetSetupError("Failed to boot into DSU", device.getDeviceDescriptor());
            }
            CommandResult result = device.executeShellV2Command("gsi_tool enable");
            if (CommandStatus.SUCCESS.equals(result.getStatus())) {
                // success
                return;
            } else {
                throw new TargetSetupError("fail on gsi_tool enable", device.getDeviceDescriptor());
            }
        } catch (IOException e) {
            CLog.e(e);
            throw new TargetSetupError(
                    "fail to install the DynamicSystemUpdate", e, device.getDeviceDescriptor());
        } finally {
            for (File tempFile : tempFiles) {
                FileUtil.deleteFile(tempFile);
            }
        }
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (e instanceof DeviceNotAvailableException) {
            CLog.e("skip tearDown on DeviceNotAvailableException");
            return;
        }
        ITestDevice device = testInfo.getDevice();
        // Disable the DynamicSystemUpdate installation
        device.executeShellCommand("gsi_tool disable");
        // Enable the one-shot mode when DynamicSystemUpdate is disabled
        device.executeShellCommand("gsi_tool enable -s");
        // Disable the DynamicSystemUpdate installation
        device.executeShellCommand("gsi_tool disable");
        // Reboot into the original system image
        device.reboot();
    }
}
