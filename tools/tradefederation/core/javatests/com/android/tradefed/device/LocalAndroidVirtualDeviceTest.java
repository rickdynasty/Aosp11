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
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.ZipUtil;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.zip.GZIPOutputStream;

/** Unit tests for {@link LocalAndroidVirtualDevice}. */
@RunWith(JUnit4.class)
public class LocalAndroidVirtualDeviceTest {

    private class TestableLocalAndroidVirtualDevice extends LocalAndroidVirtualDevice {

        TestableLocalAndroidVirtualDevice(
                IDevice device,
                IDeviceStateMonitor stateMonitor,
                IDeviceMonitor allocationMonitor) {
            super(device, stateMonitor, allocationMonitor);
        }

        IRunUtil currentRunUtil;
        boolean expectToConnect;

        @Override
        public boolean adbTcpConnect(String host, String port) {
            Assert.assertTrue("Unexpected method call to adbTcpConnect.", expectToConnect);
            Assert.assertEquals(IP_ADDRESS, host);
            Assert.assertEquals(PORT, port);
            return true;
        }

        @Override
        public boolean adbTcpDisconnect(String host, String port) {
            Assert.assertEquals(IP_ADDRESS, host);
            Assert.assertEquals(PORT, port);
            return true;
        }

        @Override
        public void waitForDeviceAvailable() {
            Assert.assertTrue("Unexpected method call to waitForDeviceAvailable.", expectToConnect);
        }

        @Override
        IRunUtil createRunUtil() {
            Assert.assertNotNull("Unexpected method call to createRunUtil.", currentRunUtil);
            IRunUtil returnValue = currentRunUtil;
            currentRunUtil = null;
            return returnValue;
        }
    }

    private static final String STUB_SERIAL_NUMBER = "local-virtual-device-0";
    private static final String IP_ADDRESS = "127.0.0.1";
    private static final String PORT = "6520";
    private static final String ONLINE_SERIAL_NUMBER = IP_ADDRESS + ":" + PORT;
    private static final String INSTANCE_NAME = "local-instance-1";
    private static final long ACLOUD_TIMEOUT = 12345;
    private static final String SUCCESS_REPORT_STRING =
            String.format(
                    "{"
                            + " \"command\": \"create\","
                            + " \"data\": {"
                            + "  \"devices\": ["
                            + "   {"
                            + "    \"ip\": \"%s\","
                            + "    \"instance_name\": \"%s\""
                            + "   }"
                            + "  ]"
                            + " },"
                            + " \"errors\": [],"
                            + " \"status\": \"SUCCESS\""
                            + "}",
                    ONLINE_SERIAL_NUMBER, INSTANCE_NAME);
    private static final String FAILURE_REPORT_STRING =
            String.format(
                    "{"
                            + " \"command\": \"create\","
                            + " \"data\": {"
                            + "  \"devices_failing_boot\": ["
                            + "   {"
                            + "    \"ip\": \"%s\","
                            + "    \"instance_name\": \"%s\""
                            + "   }"
                            + "  ]"
                            + " },"
                            + " \"errors\": [],"
                            + " \"status\": \"BOOT_FAIL\""
                            + "}",
                    ONLINE_SERIAL_NUMBER, INSTANCE_NAME);

    // Temporary files.
    private File mAcloud;
    private File mImageZip;
    private File mHostPackageTarGzip;
    private File mSystemImageZip;
    private File mOtaToolsZip;

    // Mock object.
    private IBuildInfo mMockBuildInfo;

    // The object under test.
    private TestableLocalAndroidVirtualDevice mLocalAvd;

    @Before
    public void setUp() throws IOException {
        mAcloud = FileUtil.createTempFile("acloud-dev", "");
        mImageZip = ZipUtil.createZip(new ArrayList<File>());
        mHostPackageTarGzip = FileUtil.createTempFile("cvd-host_package", ".tar.gz");
        createHostPackage(mHostPackageTarGzip);
        mSystemImageZip = null;
        mOtaToolsZip = null;

        mMockBuildInfo = EasyMock.createMock(IBuildInfo.class);
        EasyMock.expect(mMockBuildInfo.getFile(EasyMock.eq(BuildInfoFileKey.DEVICE_IMAGE)))
                .andReturn(mImageZip);
        EasyMock.expect(mMockBuildInfo.getFile((String) EasyMock.anyObject()))
                .andAnswer(
                        new IAnswer<File>() {
                            @Override
                            public File answer() {
                                switch ((String) EasyMock.getCurrentArguments()[0]) {
                                    case "cvd-host_package.tar.gz":
                                        return mHostPackageTarGzip;
                                    case "system-img.zip":
                                        return mSystemImageZip;
                                    case "otatools.zip":
                                        return mOtaToolsZip;
                                    default:
                                        return null;
                                }
                            }
                        })
                .anyTimes();
        IDeviceStateMonitor mockDeviceStateMonitor = EasyMock.createMock(IDeviceStateMonitor.class);
        mockDeviceStateMonitor.setIDevice(EasyMock.anyObject());
        EasyMock.expectLastCall().anyTimes();
        IDeviceMonitor mockDeviceMonitor = EasyMock.createMock(IDeviceMonitor.class);
        EasyMock.replay(mockDeviceStateMonitor, mockDeviceMonitor);

        mLocalAvd =
                new TestableLocalAndroidVirtualDevice(
                        new StubLocalAndroidVirtualDevice(STUB_SERIAL_NUMBER),
                        mockDeviceStateMonitor,
                        mockDeviceMonitor);
        TestDeviceOptions options = mLocalAvd.getOptions();
        options.setGceCmdTimeout(ACLOUD_TIMEOUT);
        options.setAvdDriverBinary(mAcloud);
        options.setGceDriverLogLevel(LogLevel.DEBUG);
        options.getGceDriverParams().add("-test");
    }

    private void setUpSystemImageZip() throws IOException {
        mSystemImageZip = ZipUtil.createZip(new ArrayList<File>());
        mOtaToolsZip = ZipUtil.createZip(new ArrayList<File>());
    }

    @After
    public void tearDown() {
        if (mLocalAvd != null) {
            // Ensure cleanup in case the test failed before calling postInvocationTearDown.
            mLocalAvd.deleteTempDirs();
            mLocalAvd = null;
        }
        FileUtil.deleteFile(mAcloud);
        FileUtil.deleteFile(mImageZip);
        FileUtil.deleteFile(mHostPackageTarGzip);
        FileUtil.deleteFile(mSystemImageZip);
        FileUtil.deleteFile(mOtaToolsZip);
        mAcloud = null;
        mImageZip = null;
        mHostPackageTarGzip = null;
        mSystemImageZip = null;
        mOtaToolsZip = null;
    }

    private static void createHostPackage(File hostPackageTarGzip) throws IOException {
        OutputStream out = null;
        try {
            out = new FileOutputStream(hostPackageTarGzip);
            out = new BufferedOutputStream(out);
            out = new GZIPOutputStream(out);
            out = new TarArchiveOutputStream(out);
            TarArchiveOutputStream tar = (TarArchiveOutputStream) out;
            TarArchiveEntry tarEntry = new TarArchiveEntry("bin" + File.separator);
            tar.putArchiveEntry(tarEntry);
            tar.closeArchiveEntry();
            tar.finish();
        } finally {
            StreamUtil.close(out);
        }
    }

    private IAnswer<CommandResult> writeToReportFile(CommandStatus status, String reportString) {
        return new IAnswer<CommandResult>() {
            @Override
            public CommandResult answer() throws Throwable {
                Object[] args = EasyMock.getCurrentArguments();
                for (int index = 0; index < args.length; index++) {
                    if ("--report_file".equals(args[index])) {
                        index++;
                        File file = new File((String) args[index]);
                        FileUtil.writeToFile(reportString, file);
                    }
                }

                CommandResult result = new CommandResult(status);
                result.setStderr("acloud create");
                result.setStdout("acloud create");
                return result;
            }
        };
    }

    private IRunUtil mockAcloudCreate(
            IAnswer<CommandResult> answer,
            Capture<String> reportFile,
            Capture<String> hostPackageDir,
            Capture<String> imageDir,
            Capture<String> instanceDir) {
        IRunUtil runUtil = EasyMock.createMock(IRunUtil.class);
        runUtil.setEnvVariable(EasyMock.eq("TMPDIR"), EasyMock.anyObject());

        EasyMock.expect(
                        runUtil.runTimedCmd(
                                EasyMock.eq(ACLOUD_TIMEOUT),
                                EasyMock.eq(mAcloud.getAbsolutePath()),
                                EasyMock.eq("create"),
                                EasyMock.eq("--local-instance"),
                                EasyMock.eq("--local-image"),
                                EasyMock.capture(imageDir),
                                EasyMock.eq("--local-instance-dir"),
                                EasyMock.capture(instanceDir),
                                EasyMock.eq("--local-tool"),
                                EasyMock.capture(hostPackageDir),
                                EasyMock.eq("--report_file"),
                                EasyMock.capture(reportFile),
                                EasyMock.eq("--no-autoconnect"),
                                EasyMock.eq("--yes"),
                                EasyMock.eq("--skip-pre-run-check"),
                                EasyMock.eq("-vv"),
                                EasyMock.eq("-test")))
                .andAnswer(answer);

        return runUtil;
    }

    private IRunUtil mockAcloudCreateWithSystemImageDir(
            IAnswer<CommandResult> answer,
            Capture<String> reportFile,
            Capture<String> hostPackageDir,
            Capture<String> imageDir,
            Capture<String> instanceDir,
            Capture<String> systemImageDir,
            Capture<String> otaToolsDir) {
        IRunUtil runUtil = EasyMock.createMock(IRunUtil.class);
        runUtil.setEnvVariable(EasyMock.eq("TMPDIR"), EasyMock.anyObject());

        EasyMock.expect(
                        runUtil.runTimedCmd(
                                EasyMock.eq(ACLOUD_TIMEOUT),
                                EasyMock.eq(mAcloud.getAbsolutePath()),
                                EasyMock.eq("create"),
                                EasyMock.eq("--local-instance"),
                                EasyMock.eq("--local-image"),
                                EasyMock.capture(imageDir),
                                EasyMock.eq("--local-instance-dir"),
                                EasyMock.capture(instanceDir),
                                EasyMock.eq("--local-tool"),
                                EasyMock.capture(hostPackageDir),
                                EasyMock.eq("--report_file"),
                                EasyMock.capture(reportFile),
                                EasyMock.eq("--no-autoconnect"),
                                EasyMock.eq("--yes"),
                                EasyMock.eq("--skip-pre-run-check"),
                                EasyMock.eq("--local-system-image"),
                                EasyMock.capture(systemImageDir),
                                EasyMock.eq("--local-tool"),
                                EasyMock.capture(otaToolsDir),
                                EasyMock.eq("-vv"),
                                EasyMock.eq("-test")))
                .andAnswer(answer);

        return runUtil;
    }

    private IRunUtil mockAcloudDelete(CommandStatus status) {
        IRunUtil runUtil = EasyMock.createMock(IRunUtil.class);
        runUtil.setEnvVariable(EasyMock.eq("TMPDIR"), EasyMock.anyObject());

        CommandResult result = new CommandResult(status);
        result.setStderr("acloud delete");
        result.setStdout("acloud delete");
        EasyMock.expect(
                        runUtil.runTimedCmd(
                                EasyMock.eq(ACLOUD_TIMEOUT),
                                EasyMock.eq(mAcloud.getAbsolutePath()),
                                EasyMock.eq("delete"),
                                EasyMock.eq("--local-only"),
                                EasyMock.eq("--instance-names"),
                                EasyMock.eq(INSTANCE_NAME),
                                EasyMock.eq("-vv")))
                .andReturn(result);

        return runUtil;
    }

    private ITestLogger mockReportInstanceLogs() {
        ITestLogger testLogger = EasyMock.createMock(ITestLogger.class);
        testLogger.testLog(
                EasyMock.eq("cuttlefish_config.json"),
                EasyMock.eq(LogDataType.TEXT),
                EasyMock.anyObject());
        testLogger.testLog(
                EasyMock.eq("kernel.log"),
                EasyMock.eq(LogDataType.KERNEL_LOG),
                EasyMock.anyObject());
        testLogger.testLog(
                EasyMock.eq("logcat"), EasyMock.eq(LogDataType.LOGCAT), EasyMock.anyObject());
        testLogger.testLog(
                EasyMock.eq("launcher.log"), EasyMock.eq(LogDataType.TEXT), EasyMock.anyObject());
        return testLogger;
    }

    private void createEmptyFiles(File parent, String... names) throws IOException {
        parent.mkdirs();
        for (String name : names) {
            Assert.assertTrue(new File(parent, name).createNewFile());
        }
    }

    private void assertFinalDeviceState(IDevice device) {
        Assert.assertTrue(StubLocalAndroidVirtualDevice.class.equals(device.getClass()));
        StubLocalAndroidVirtualDevice stubDevice = (StubLocalAndroidVirtualDevice) device;
        Assert.assertEquals(STUB_SERIAL_NUMBER, stubDevice.getSerialNumber());
    }

    /**
     * Test that both {@link LocalAndroidVirtualDevice#preInvocationSetup(IBuildInfo,
     * List<IBuildInfo>)} and {@link LocalAndroidVirtualDevice#postInvocationTearDown(Throwable)}
     * succeed.
     */
    @Test
    public void testPreinvocationSetupSuccess()
            throws DeviceNotAvailableException, IOException, TargetSetupError {
        setUpSystemImageZip();

        Capture<String> reportFile = new Capture<String>();
        Capture<String> hostPackageDir = new Capture<String>();
        Capture<String> imageDir = new Capture<String>();
        Capture<String> instanceDir = new Capture<String>();
        Capture<String> systemImageDir = new Capture<String>();
        Capture<String> otaToolsDir = new Capture<String>();
        IRunUtil acloudCreateRunUtil =
                mockAcloudCreateWithSystemImageDir(
                        writeToReportFile(CommandStatus.SUCCESS, SUCCESS_REPORT_STRING),
                        reportFile,
                        hostPackageDir,
                        imageDir,
                        instanceDir,
                        systemImageDir,
                        otaToolsDir);

        IRunUtil acloudDeleteRunUtil = mockAcloudDelete(CommandStatus.SUCCESS);

        ITestLogger testLogger = mockReportInstanceLogs();

        EasyMock.replay(mMockBuildInfo, acloudCreateRunUtil, acloudDeleteRunUtil, testLogger);

        // Test setUp.
        mLocalAvd.setTestLogger(testLogger);
        mLocalAvd.currentRunUtil = acloudCreateRunUtil;
        mLocalAvd.expectToConnect = true;
        mLocalAvd.preInvocationSetup(mMockBuildInfo, null);

        Assert.assertEquals(ONLINE_SERIAL_NUMBER, mLocalAvd.getIDevice().getSerialNumber());

        EasyMock.verify(acloudCreateRunUtil);
        File capturedHostPackageDir = new File(hostPackageDir.getValue());
        File capturedImageDir = new File(imageDir.getValue());
        File capturedInstanceDir = new File(instanceDir.getValue());
        Assert.assertTrue(capturedHostPackageDir.isDirectory());
        Assert.assertTrue(capturedImageDir.isDirectory());
        Assert.assertTrue(capturedInstanceDir.isDirectory());

        // Create the logs and configuration that the local AVD object expects.
        File runtimeDir = new File(capturedInstanceDir, "cuttlefish_runtime");
        Assert.assertTrue(runtimeDir.mkdirs());
        createEmptyFiles(
                runtimeDir, "kernel.log", "logcat", "launcher.log", "cuttlefish_config.json");

        // Test tearDown.
        mLocalAvd.currentRunUtil = acloudDeleteRunUtil;
        mLocalAvd.expectToConnect = false;
        mLocalAvd.postInvocationTearDown(null);

        assertFinalDeviceState(mLocalAvd.getIDevice());

        EasyMock.verify(acloudDeleteRunUtil, testLogger);
        Assert.assertFalse(new File(reportFile.getValue()).exists());
        Assert.assertFalse(capturedHostPackageDir.exists());
        Assert.assertFalse(capturedImageDir.exists());
        Assert.assertFalse(capturedInstanceDir.exists());
    }

    /** Test shutting down the device during the invocation. */
    @Test
    public void testShutdown() throws DeviceNotAvailableException, TargetSetupError {
        Capture<String> reportFile = new Capture<String>();
        Capture<String> hostPackageDir = new Capture<String>();
        Capture<String> imageDir = new Capture<String>();
        Capture<String> instanceDir = new Capture<String>();
        IRunUtil acloudCreateRunUtil =
                mockAcloudCreate(
                        writeToReportFile(CommandStatus.SUCCESS, SUCCESS_REPORT_STRING),
                        reportFile,
                        hostPackageDir,
                        imageDir,
                        instanceDir);

        IRunUtil acloudDeleteRunUtil = mockAcloudDelete(CommandStatus.SUCCESS);

        ITestLogger testLogger = EasyMock.createMock(ITestLogger.class);

        EasyMock.replay(mMockBuildInfo, acloudCreateRunUtil, acloudDeleteRunUtil, testLogger);

        // Test setUp.
        mLocalAvd.setTestLogger(testLogger);
        mLocalAvd.currentRunUtil = acloudCreateRunUtil;
        mLocalAvd.expectToConnect = true;
        mLocalAvd.preInvocationSetup(mMockBuildInfo, null);

        Assert.assertEquals(ONLINE_SERIAL_NUMBER, mLocalAvd.getIDevice().getSerialNumber());

        EasyMock.verify(acloudCreateRunUtil);
        File capturedHostPackageDir = new File(hostPackageDir.getValue());
        File capturedImageDir = new File(imageDir.getValue());
        File capturedInstanceDir = new File(instanceDir.getValue());
        Assert.assertTrue(capturedHostPackageDir.isDirectory());
        Assert.assertTrue(capturedImageDir.isDirectory());
        Assert.assertTrue(capturedInstanceDir.isDirectory());

        // Shutdown the device.
        mLocalAvd.currentRunUtil = acloudDeleteRunUtil;
        mLocalAvd.expectToConnect = false;
        mLocalAvd.shutdown();

        EasyMock.verify(acloudDeleteRunUtil);

        // Test that tearDown does not invoke acloud again.
        mLocalAvd.currentRunUtil = null;
        mLocalAvd.expectToConnect = false;
        mLocalAvd.postInvocationTearDown(null);

        assertFinalDeviceState(mLocalAvd.getIDevice());

        EasyMock.verify(testLogger);
        Assert.assertFalse(new File(reportFile.getValue()).exists());
        Assert.assertFalse(capturedHostPackageDir.exists());
        Assert.assertFalse(capturedImageDir.exists());
        Assert.assertFalse(capturedInstanceDir.exists());
    }

    /** Test that the acloud command reports failure. */
    @Test
    public void testPreInvocationSetupBootFailure() throws DeviceNotAvailableException {
        Capture<String> reportFile = new Capture<String>();
        Capture<String> hostPackageDir = new Capture<String>();
        Capture<String> imageDir = new Capture<String>();
        Capture<String> instanceDir = new Capture<String>();
        IRunUtil acloudCreateRunUtil =
                mockAcloudCreate(
                        writeToReportFile(CommandStatus.SUCCESS, FAILURE_REPORT_STRING),
                        reportFile,
                        hostPackageDir,
                        imageDir,
                        instanceDir);

        IRunUtil acloudDeleteRunUtil = mockAcloudDelete(CommandStatus.FAILED);

        ITestLogger testLogger = EasyMock.createMock(ITestLogger.class);

        EasyMock.replay(mMockBuildInfo, acloudCreateRunUtil, acloudDeleteRunUtil, testLogger);

        // Test setUp.
        TargetSetupError expectedException = null;
        mLocalAvd.setTestLogger(testLogger);
        mLocalAvd.currentRunUtil = acloudCreateRunUtil;
        try {
            mLocalAvd.preInvocationSetup(mMockBuildInfo, null);
            Assert.fail("TargetSetupError is not thrown");
        } catch (TargetSetupError e) {
            expectedException = e;
        }

        Assert.assertEquals(STUB_SERIAL_NUMBER, mLocalAvd.getIDevice().getSerialNumber());

        EasyMock.verify(acloudCreateRunUtil);
        File capturedHostPackageDir = new File(hostPackageDir.getValue());
        File capturedImageDir = new File(imageDir.getValue());
        File capturedInstanceDir = new File(instanceDir.getValue());
        Assert.assertTrue(capturedHostPackageDir.isDirectory());
        Assert.assertTrue(capturedImageDir.isDirectory());
        Assert.assertTrue(capturedInstanceDir.isDirectory());

        // Test tearDown.
        mLocalAvd.currentRunUtil = acloudDeleteRunUtil;
        mLocalAvd.postInvocationTearDown(expectedException);

        assertFinalDeviceState(mLocalAvd.getIDevice());

        EasyMock.verify(acloudDeleteRunUtil, testLogger);
        Assert.assertFalse(new File(reportFile.getValue()).exists());
        Assert.assertFalse(capturedHostPackageDir.exists());
        Assert.assertFalse(capturedImageDir.exists());
        Assert.assertFalse(capturedInstanceDir.exists());
    }

    /** Test that the acloud command fails, and the report is empty. */
    @Test
    public void testPreInvocationSetupFailure() throws DeviceNotAvailableException {
        Capture<String> reportFile = new Capture<String>();
        Capture<String> hostPackageDir = new Capture<String>();
        Capture<String> imageDir = new Capture<String>();
        Capture<String> instanceDir = new Capture<String>();
        IRunUtil acloudCreateRunUtil =
                mockAcloudCreate(
                        writeToReportFile(CommandStatus.FAILED, ""),
                        reportFile,
                        hostPackageDir,
                        imageDir,
                        instanceDir);

        ITestLogger testLogger = EasyMock.createMock(ITestLogger.class);

        EasyMock.replay(mMockBuildInfo, acloudCreateRunUtil, testLogger);

        // Test setUp.
        TargetSetupError expectedException = null;
        mLocalAvd.setTestLogger(testLogger);
        mLocalAvd.currentRunUtil = acloudCreateRunUtil;
        try {
            mLocalAvd.preInvocationSetup(mMockBuildInfo, null);
            Assert.fail("TargetSetupError is not thrown");
        } catch (TargetSetupError e) {
            expectedException = e;
        }

        Assert.assertEquals(STUB_SERIAL_NUMBER, mLocalAvd.getIDevice().getSerialNumber());

        EasyMock.verify(acloudCreateRunUtil);
        File capturedHostPackageDir = new File(hostPackageDir.getValue());
        File capturedImageDir = new File(imageDir.getValue());
        File capturedInstanceDir = new File(instanceDir.getValue());
        Assert.assertTrue(capturedHostPackageDir.isDirectory());
        Assert.assertTrue(capturedImageDir.isDirectory());
        Assert.assertTrue(capturedInstanceDir.isDirectory());

        // Test tearDown.
        mLocalAvd.currentRunUtil = null;
        mLocalAvd.postInvocationTearDown(expectedException);

        assertFinalDeviceState(mLocalAvd.getIDevice());

        EasyMock.verify(testLogger);
        Assert.assertFalse(new File(reportFile.getValue()).exists());
        Assert.assertFalse(capturedHostPackageDir.exists());
        Assert.assertFalse(capturedImageDir.exists());
        Assert.assertFalse(capturedInstanceDir.exists());
    }
}
