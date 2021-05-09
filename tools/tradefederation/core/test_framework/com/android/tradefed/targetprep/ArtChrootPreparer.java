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
package com.android.tradefed.targetprep;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Create chroot directory for ART tests. */
@OptionClass(alias = "art-chroot-preparer")
public class ArtChrootPreparer extends BaseTargetPreparer {

    // Predefined location of the chroot root directory.
    public static final String CHROOT_PATH = "/data/local/tmp/art-test-chroot";

    // Directories to create in the chroot.
    private static final String[] MKDIRS = {
        "/", "/apex", "/data", "/data/dalvik-cache", "/data/local/tmp", "/tmp",
    };

    // System mount points to replicate in the chroot.
    private static final String[] MOUNTS = {
        "/dev", "/linkerconfig", "/proc", "/sys", "/system", "/apex/com.android.os.statsd",
    };

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();

        // Ensure there are no files left from previous runs.
        cleanup(device);

        // Create directories required for ART testing in chroot.
        for (String dir : MKDIRS) {
            adbShell(device, "mkdir -p " + CHROOT_PATH + dir);
        }

        // Replicate system mount point in the chroot.
        for (String dir : MOUNTS) {
            adbShell(device, "mkdir -p " + CHROOT_PATH + dir);
            adbShell(device, "mount --bind " + dir + " " + CHROOT_PATH + dir);
        }

        // Activate APEXes in the chroot.
        IBuildInfo buildInfo = testInfo.getBuildInfo();
        IDeviceBuildInfo deviceBuild = (IDeviceBuildInfo) buildInfo;
        DeviceDescriptor deviceDesc = device.getDeviceDescriptor();
        File tests_dir = deviceBuild.getFile(BuildInfoFileKey.TARGET_LINKED_DIR);
        // The art_chroot is a shared module containing comment ART test data.
        File apexes_dir = FileUtil.getFileForPath(tests_dir, "art_chroot", "system", "apex");
        if (apexes_dir.listFiles() == null) {
            throw new TargetSetupError(
                    "No apex files found in " + apexes_dir.getPath(), deviceDesc);
        }
        File tempDir = null;
        try {
            tempDir = FileUtil.createTempDir("art-test-apex");
            for (File apex : apexes_dir.listFiles()) {
                activateApex(device, tempDir, apex);
            }
        } catch (IOException e) {
            throw new TargetSetupError("Error when activating apex", e, deviceDesc);
        } finally {
            FileUtil.recursiveDelete(tempDir);
        }
    }

    private void activateApex(ITestDevice device, File tempDir, File apex)
            throws TargetSetupError, IOException, DeviceNotAvailableException {
        CLog.i("Activate apex in ART chroot: " + apex.getName());
        ZipFile apex_zip = new ZipFile(apex);
        ZipArchiveEntry apex_payload = apex_zip.getEntry("apex_payload.img");
        File temp = FileUtil.createTempFile("payload-", ".img", tempDir);
        FileUtil.writeToFile(apex_zip.getInputStream(apex_payload), temp);
        String deviceApexDir = CHROOT_PATH + "/apex/" + apex.getName();
        // Rename "com.android.art.testing.apex" to just "com.android.art.apex".
        deviceApexDir = deviceApexDir.replace(".testing.apex", "").replace(".apex", "");
        String deviceApexImg = deviceApexDir + ".img";
        if (!device.pushFile(temp, deviceApexImg)) {
            throw new TargetSetupError(
                    "adb push failed for " + apex.getName(), device.getDeviceDescriptor());
        }
        // TODO(b/168048638): Work-around for cuttlefish: first losetup call always fails.
        device.executeShellV2Command("losetup -f");
        // Mount the apex file via a loopback device.
        String loopbackDevice = adbShell(device, "losetup -f -s " + deviceApexImg);
        adbShell(device, "mkdir -p " + deviceApexDir);
        adbShell(device, "mount -o loop,ro " + loopbackDevice + " " + deviceApexDir);
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        try {
            cleanup(testInfo.getDevice());
        } catch (TargetSetupError ex) {
            CLog.e("Tear-down failed: " + ex.toString());
        }
    }

    // Wrapper for executeShellV2Command that checks that the command succeeds.
    private String adbShell(ITestDevice device, String cmd)
            throws TargetSetupError, DeviceNotAvailableException {
        CommandResult result = device.executeShellV2Command(cmd);
        if (result.getStatus() != CommandStatus.SUCCESS) {
            throw new TargetSetupError(
                    String.format(
                            "adb shell command failed: '%s': %s".format(cmd, result.getStderr())));
        }
        return result.getStdout();
    }

    private void cleanup(ITestDevice device) throws TargetSetupError, DeviceNotAvailableException {
        String mounts = adbShell(device, "mount");
        Pattern pattern = Pattern.compile("^([^ ]+) on ([^ ]+) type ([^ ]+) .*$");
        for (String mount : mounts.split("\n")) {
            Matcher matcher = pattern.matcher(mount);
            if (!matcher.matches()) {
                throw new TargetSetupError("Failed to parse mount command output: " + mount);
            }
            if (matcher.group(2).startsWith(CHROOT_PATH)) {
                adbShell(device, "umount " + matcher.group(2));
            }
        }
        adbShell(device, "rm -rf " + CHROOT_PATH);
    }
}
