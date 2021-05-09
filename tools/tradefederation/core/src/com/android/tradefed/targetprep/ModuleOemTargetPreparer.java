/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.ApexInfo;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.DeviceErrorIdentifier;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.CommandResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/*
 * A {@link TargetPreparer} that attempts to install mainline dev-key signed modules on OEM device
 * and verify push success.
 */
@OptionClass(alias = "mainline-oem-installer")
public class ModuleOemTargetPreparer extends InstallApexModuleTargetPreparer {

    private static final String APEX_DIR = "/system/apex/";
    private static final String DISABLE_VERITY = "disable-verity";
    private static final String GET_APEX_PACKAGE_VERSION =
            "cmd package list packages --apex-only --show-versioncode| grep ";
    private static final String GET_APK_PACKAGE_VERSION =
            "cmd package list packages --show-versioncode| grep ";
    private static final String REMOUNT_COMMAND = "remount";
    private long delayWaitingTime = 2000;
    private RunUtil mRunUtil = new RunUtil();
    private File mTrainFolderPath;

    /**
     * Perform the target setup for testing, push modules to replace the preload ones
     *
     * @param testInfo The {@link TestInformation} of the invocation.
     * @throws TargetSetupError if fatal error occurred setting up environment
     * @throws BuildError If an error occurs due to the build being prepared
     * @throws DeviceNotAvailableException if device became unresponsive
     */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        setTestInformation(testInfo);
        ITestDevice device = testInfo.getDevice();

        setupDevice(testInfo);

        if (mTrainFolderPath != null) {
            addApksToTestFiles();
        }

        List<File> testAppFiles = getModulesToInstall(testInfo);
        if (testAppFiles.isEmpty()) {
            CLog.i("No modules to install.");
            return;
        }

        checkPreloadModules(testInfo, device.getDeviceDescriptor());
        List<ModuleInfo> pushedModules = new ArrayList<>();

        for (File moduleFile : testAppFiles) {
            if (containsApks(moduleFile)) {
                List<File> splits = getSplitsForApks(testInfo, moduleFile);
                pushedModules.add(pushFile(splits.get(0), testInfo));
            } else { // apk or apex
                pushedModules.add(pushFile(moduleFile, testInfo));
            }
        }

        device.reboot();

        CLog.i("Check pushed module version code after device reboot");
        checkModuleAfterPush(device, pushedModules);
    }

    /**
     * Rename to-be-updated file name to same one under /system.
     *
     * @param device test device
     * @param moduleFile module file to be installed
     * @param packageName name under /system/*
     * @return new file name after rename
     * @throws TargetSetupError if file cannot be renamed
     */
    protected String renameFile(ITestDevice device, File moduleFile, String packageName)
            throws TargetSetupError, DeviceNotAvailableException {
        String filename = moduleFile.getAbsolutePath();
        CLog.i("To-be-updated test filename: %s", filename);
        String[] pathSplit = filename.split("/");
        String filePath =
                filename.substring(
                        0, (filename.length() - pathSplit[pathSplit.length - 1].length()));
        CLog.i("To-be-updated module file path: %s", filePath);

        String newFileName;
        if (containsApk(moduleFile)) {
            String preloadApkName = getApkDirectory(device, packageName)[1];
            newFileName = filePath + preloadApkName;
        } else { // apex
            newFileName = filePath + packageName + APEX_SUFFIX;
        }

        if (newFileName.equals(filename)) {
            CLog.i(
                    "File name '%s' is same with preload one '%s', no need to rename.",
                    filename, newFileName);
            return newFileName;
        }
        CLog.i("New file name of package '%s' is: %s", packageName, newFileName);

        return newFileName;
    }

    /**
     * Push files to /system/apex/ for apex or /system/** for apk
     *
     * @param moduleFile module file
     * @param testInfo the {@link TestInformation} for the invocation.
     * @throws TargetSetupError if cannot push file via adb
     * @throws DeviceNotAvailableException if device not available
     */
    protected ModuleInfo pushFile(File moduleFile, TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        String packageName = parsePackageName(moduleFile, device.getDeviceDescriptor());
        String preloadVersion = null;
        boolean isAPK = true;
        if (containsApk(moduleFile)) {
            preloadVersion = getPackageVersioncode(device, packageName, isAPK);
        } else { // apex
            preloadVersion = getPackageVersioncode(device, packageName, !isAPK);
        }

        String newFileName = renameFile(device, moduleFile, packageName);
        File newFile = new File(newFileName);
        remountDevice(testInfo);


        if (moduleFile.renameTo(newFile)) {
            CLog.i("File name changed to the preload ones");
            if (newFileName.endsWith(APEX_SUFFIX)) {
                String preloadPath = APEX_DIR + newFile.getName();
                if (device.pushFile(newFile, preloadPath)) {
                    CLog.i(
                            "Local file %s got pushed to the preload path '%s",
                            newFile.getName(), preloadPath);
                } else {
                    throw new TargetSetupError(
                            String.format(
                                    "Failed to push File '%s' to '%s' on device %s.",
                                    newFile, preloadPath, device.getSerialNumber()),
                            device.getDeviceDescriptor(),
                            DeviceErrorIdentifier.FAIL_PUSH_FILE);
                }
            } else { // apk
                String modulePathOnDevice = getApkDirectory(device, packageName)[0];
                CLog.i("apk module path on device is %s", modulePathOnDevice);
                if (modulePathOnDevice == null) {
                    throw new TargetSetupError(
                            String.format("Not found package %s on device.", packageName),
                            device.getDeviceDescriptor());
                }
                modulePathOnDevice += newFile.getName();
                if (device.pushFile(newFile, modulePathOnDevice)) {
                    CLog.i(
                            "Local file %s got pushed to the preload path '%s",
                            newFile.getName(), modulePathOnDevice);
                } else {
                    throw new TargetSetupError(
                            String.format(
                                    "Failed to push File '%s' to '%s' on device %s.",
                                    newFile, modulePathOnDevice, device.getSerialNumber()),
                            device.getDeviceDescriptor(),
                            DeviceErrorIdentifier.FAIL_PUSH_FILE);
                }
            }
        } else {
            throw new TargetSetupError(
                    String.format(
                            "Failed to rename File to '%s' on device %s.",
                            newFileName, device.getSerialNumber()),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.FAIL_PUSH_FILE);
        }

        ModuleInfo moduleInfo =
                new ModuleInfo(packageName, preloadVersion, containsApk(moduleFile));
        return moduleInfo;
    }

    /**
     * adb root and remount device before push files under /system
     *
     * @param testInfo the {@link TestInformation} for the invocation.
     * @throws TargetSetupError if device cannot be remount.
     */
    protected void setupDevice(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        device.enableAdbRoot();
        String disableVerity = device.executeAdbCommand(DISABLE_VERITY);
        CLog.i("disable-verity status: %s", disableVerity);

        if (disableVerity.contains("disabled")) {
            CLog.d("disable-verity status: %s", disableVerity);
        } else {
            throw new TargetSetupError(
                    String.format(
                            "Failed to disable verity on device %s", device.getSerialNumber()),
                    device.getDeviceDescriptor(),
                    DeviceErrorIdentifier.DEVICE_FAILED_TO_RESET);
        }

        device.reboot();
        remountDevice(testInfo);
        device.reboot();
    }

    /** remount device function. */
    private void remountDevice(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        ITestDevice device = testInfo.getDevice();
        device.enableAdbRoot();

        String remount = device.executeAdbCommand(REMOUNT_COMMAND);
        CLog.i("adb remount status: %s", remount);

        if (remount.contains("remount succeed")) {
            CLog.i("Remount Success, output is %s", remount);
        } else {
            throw new TargetSetupError(
                    String.format(
                            "Failed to remount device on %s. Error log: '%s'",
                            device.getSerialNumber(), remount),
                    device.getDeviceDescriptor());
        }
    }

    /**
     * Get apk file path under system directory
     *
     * @param packageName module package name
     * @return string array with apk name and path
     * @throws DeviceNotAvailableException if device is not available
     * @throws TargetSetupError if cannot find the path of the package
     */
    protected String[] getApkDirectory(ITestDevice device, String packageName)
            throws TargetSetupError, DeviceNotAvailableException {
        CommandResult res = device.executeShellV2Command("pm path " + packageName);

        String apkNameWithPath = res.getStdout().split(":")[1];
        CLog.i("Apk full path on device is %s", apkNameWithPath);
        String[] splitApkPath = apkNameWithPath.split("/");
        String apkName = splitApkPath[splitApkPath.length - 1].replaceAll("[\\n]", "");
        String apkSrcPath =
                apkNameWithPath.substring(0, (apkNameWithPath.length() - apkName.length() - 1));

        if (res.getStdout().contains("package")) {
            CLog.i("Apk path on device is %s", apkSrcPath);
        } else {
            throw new TargetSetupError(
                    String.format(
                            "Failed to find apk path on the device %s. Error log: '%s'",
                            device.getSerialNumber(), res),
                    device.getDeviceDescriptor());
        }
        return new String[] {apkSrcPath, apkName};
    }

    /***
     *
     * Check package version after pushed module given package name.
     *
     * @param device
     * @param packageName pushed package name
     * @throws DeviceNotAvailableException throws exception if device not found.
     * @return
     */
    protected String getPackageVersioncode(ITestDevice device, String packageName, boolean isAPK)
            throws DeviceNotAvailableException {
        String packageVersion;
        String outputs;
        if (isAPK) {
            outputs =
                    device.executeShellV2Command(GET_APK_PACKAGE_VERSION + packageName).getStdout();
        } else {
            outputs =
                    device.executeShellV2Command(GET_APEX_PACKAGE_VERSION + packageName)
                            .getStdout();
        }
        // TODO(liuyg@): add wait-time to get output info and try to fix flakiness
        RunUtil.getDefault().sleep(delayWaitingTime);
        CLog.i("Output string is %s", outputs);
        String[] splits = outputs.split(":", -1);
        packageVersion = splits[splits.length - 1].replaceAll("[\\n]", "");
        CLog.i("Package '%s' version code is %s", packageName, packageVersion);
        return packageVersion;
    }

    /**
     * Checks if the app need to be installed contains apks.
     *
     * @param testFileName Test module file
     */
    private boolean containsApks(File testFileName) {
        return testFileName.getName().endsWith(SPLIT_APKS_SUFFIX);
    }

    /**
     * Checks if the app need to be installed contains apk.
     *
     * @param testFileName Test module file
     */
    private boolean containsApk(File testFileName) {
        return testFileName.getName().endsWith(APK_SUFFIX);
    }

    /**
     * Check preload modules info
     *
     * @param testInfo test info
     * @throws DeviceNotAvailableException throws exception if devices no available
     * @throws TargetSetupError throws exception if no modules preloaded
     */
    protected void checkPreloadModules(TestInformation testInfo, DeviceDescriptor deviceDescriptor)
            throws DeviceNotAvailableException, TargetSetupError {
        ITestDevice device = testInfo.getDevice();

        Set<ApexInfo> activatedApexes = device.getActiveApexes();
        CLog.i("Activated apex packages list before module push:");
        for (ApexInfo info : activatedApexes) {
            CLog.i("Activated apex: %s", info.toString());
        }
        CLog.i("Preloaded modules:");
        String out =
                device.executeShellV2Command("pm get-moduleinfo | grep 'com.google'").getStdout();
        if (out != null) {
            CLog.i("Preload modules are as below: \n %s", out);
        } else {
            throw new TargetSetupError("no modules preloaded", deviceDescriptor);
        }
    }

    /** A simple struct class to store information about a module */
    public static class ModuleInfo {
        public final String packageName;
        public final String versionCode;
        public final boolean isApk;

        public ModuleInfo(String packageName, String versionCode, boolean isApk) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.isApk = isApk;
        }
    }

    /**
     * Check module name and version code after pushed
     *
     * @param pushedModules List of modules pushed
     * @throws TargetSetupError throw exception if no module pushed
     * @throws DeviceNotAvailableException throw exception if no device available
     */
    public void checkModuleAfterPush(ITestDevice device, List<ModuleInfo> pushedModules)
            throws TargetSetupError, DeviceNotAvailableException {
        String newVersionCode = null;
        for (ModuleInfo mi : pushedModules) {
            newVersionCode = getPackageVersioncode(device, mi.packageName, mi.isApk);
            String preloadVersion = mi.versionCode;
            if (preloadVersion.equals(newVersionCode)) {
                throw new TargetSetupError(
                        String.format(
                                "Push failed since no version changed: preload version is: "
                                        + "'%s', after push version is: '%s'",
                                preloadVersion, newVersionCode),
                        device.getDeviceDescriptor());
            } else {
                CLog.i(
                        "Packages %s push success! version code after is: %s",
                        mi.packageName, newVersionCode);
            }
        }
    }
}
