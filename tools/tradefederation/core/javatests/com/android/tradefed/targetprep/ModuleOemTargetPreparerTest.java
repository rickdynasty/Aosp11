/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.ApexInfo;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.util.BundletoolUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.FileUtil;

import java.util.Arrays;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Unit test for {@link ModuleOemTargetPreparerTest} */
@RunWith(JUnit4.class)
public class ModuleOemTargetPreparerTest {
    private static final String SERIAL = "serial";
    private ModuleOemTargetPreparer mModuleOemTargetPreparer;
    private ITestDevice mMockDevice;
    private TestInformation mTestInfo;
    private BundletoolUtil mMockBundletoolUtil;
    private File mFakeApex;
    private File mFakeApk;

    private static final String APEX_PACKAGE_NAME = "com.android.FAKE_APEX_PACKAGE_NAME";
    private static final String APK_PACKAGE_NAME = "com.android.FAKE_APK_PACKAGE_NAME";
    private static final String APK_PACKAGE_NAME2 = "com.android.FAKE_APK_PACKAGE_NAME2";
    private static final String SPLIT_APK_PACKAGE_NAME = "com.android.SPLIT_FAKE_APK_PACKAGE_NAME";
    private static final String APEX_NAME = "fakeApex.apex";
    private static final String APEX_PATH = "/system/apex/com.android.FAKE_APEX_PACKAGE_NAME.apex";
    private static final String APEX_PRELOAD_NAME = "com.android.FAKE_APEX_PACKAGE_NAME.apex";

    @Before
    public void setUp() throws Exception {
        mFakeApex = FileUtil.createTempFile("fakeApex", ".apex");
        mFakeApk = FileUtil.createTempFile("fakeApk", ".apk");
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        mMockBundletoolUtil = Mockito.mock(BundletoolUtil.class);
        EasyMock.expect(mMockDevice.getSerialNumber()).andStubReturn(SERIAL);
        EasyMock.expect(mMockDevice.getDeviceDescriptor()).andStubReturn(null);
        IInvocationContext context = new InvocationContext();
        context.addAllocatedDevice("device", mMockDevice);
        mTestInfo = TestInformation.newBuilder().setInvocationContext(context).build();
        mModuleOemTargetPreparer =
                new ModuleOemTargetPreparer() {
                    @Override
                    protected String parsePackageName(
                            File testAppFile, DeviceDescriptor deviceDescriptor) {
                        if (testAppFile.getName().endsWith(".apex")) {
                            return APEX_PACKAGE_NAME;
                        }
                        if (testAppFile.getName().endsWith(".apk")
                                && !testAppFile.getName().contains("Split")) {
                            return APK_PACKAGE_NAME;
                        }
                        if (testAppFile.getName().endsWith(".apk")
                                && testAppFile.getName().contains("Split")) {
                            return SPLIT_APK_PACKAGE_NAME;
                        }
                        if (testAppFile.getName().endsWith(".apks")
                                && testAppFile.getName().contains("fakeApk")) {
                            return SPLIT_APK_PACKAGE_NAME;
                        }
                        return null;
                    }

                    @Override
                    protected String[] getApkDirectory(ITestDevice device, String packageName) {
                        return new String[] {APEX_PATH, APEX_PRELOAD_NAME};
                    }

                    @Override
                    protected String renameFile(
                            ITestDevice device, File moduleFile, String packageName) {
                        String newName = APEX_PRELOAD_NAME;
                        return newName;
                    }

                    @Override
                    protected void setupDevice(TestInformation testInfo) {}

                    @Override
                    protected String getPackageVersioncode(
                            ITestDevice device, String packageName, boolean isAPK) {
                        String versionCode = "V2";
                        return versionCode;
                    }

                    @Override
                    protected ModuleInfo pushFile(File moduleFile, TestInformation testInfo) {
                        ModuleInfo mi = new ModuleInfo(null, null, true);
                        if (moduleFile.getName().endsWith("apex")) {
                            mi = new ModuleInfo(APEX_PACKAGE_NAME, "V1", false);
                        } else if (moduleFile.getName().endsWith("apk")) {
                            mi = new ModuleInfo(APK_PACKAGE_NAME, "V1", true);
                        }
                        return mi;
                    }
                };
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.deleteFile(mFakeApex);
        FileUtil.deleteFile(mFakeApk);
    }

    /** Test get apex module package name. */
    @Test
    public void testGetApexModulePackageName() throws Exception {
        String expected = APEX_PACKAGE_NAME;
        File moduleFile = File.createTempFile("fakeApex", ".apex");
        EasyMock.expect(mMockDevice.executeShellCommand(String.format("pm path %s", expected)))
                .andReturn("package:/system/apex/com.google.android.fake")
                .anyTimes();
        EasyMock.replay(mMockDevice);

        String result =
                mModuleOemTargetPreparer.parsePackageName(
                        moduleFile, mMockDevice.getDeviceDescriptor());
        assertEquals(expected, result);
        EasyMock.verify(mMockDevice);
    }

    /** Test module could be remamed. */
    @Test
    public void testRenameModule() throws Exception {
        File moduleFile = File.createTempFile("fakeApex", ".apex");
        String newName =
                mModuleOemTargetPreparer.renameFile(mMockDevice, moduleFile, APEX_PACKAGE_NAME);
        EasyMock.replay(mMockDevice);
        String expect = APEX_PACKAGE_NAME + ".apex";
        assertEquals(expect, newName);
        EasyMock.verify(mMockDevice);
    }

    /** Test could get apk modules on device. */
    @Test
    public void testGetApkModules() throws Exception {
        ITestDevice.ApexInfo fakeApexData =
                new ITestDevice.ApexInfo(APEX_PACKAGE_NAME, 1, APEX_PATH);

        Set<String> modules =
                new HashSet<>(
                        Arrays.asList(APK_PACKAGE_NAME, APK_PACKAGE_NAME2, APEX_PACKAGE_NAME));
        Set<ITestDevice.ApexInfo> apexes = new HashSet<>(Arrays.asList(fakeApexData));
        Set<String> expected = new HashSet<>(Arrays.asList(APK_PACKAGE_NAME, APK_PACKAGE_NAME2));
        assertEquals(expected, mModuleOemTargetPreparer.getApkModules(modules, apexes));
    }

    /** Test module pushed success. */
    @Test
    public void testPushedModuleSuccess() throws Exception {
        ApexInfo fakeApexData = new ApexInfo(APEX_PACKAGE_NAME, 1, APEX_PATH);
        EasyMock.expect(mMockDevice.getActiveApexes())
                .andReturn(new HashSet<>(Arrays.asList(fakeApexData)))
                .atLeastOnce();
        CommandResult re = new CommandResult();
        re.setStdout("Success");
        EasyMock.expect(mMockDevice.executeShellV2Command("push " + mFakeApex))
                .andReturn(re)
                .anyTimes();

        Set<String> installableModules = new HashSet<>();
        installableModules.add(APEX_PACKAGE_NAME);
        installableModules.add(APK_PACKAGE_NAME);
        EasyMock.expect(mMockDevice.getInstalledPackageNames()).andReturn(installableModules);

        EasyMock.replay(mMockDevice);
        mModuleOemTargetPreparer.setUp(mTestInfo);
        EasyMock.verify(mMockDevice);
    }

    /** Test module pushed fail and throw exceptions. */
    @Test
    public void testModulePushFail() throws Exception {
        mModuleOemTargetPreparer.addTestFileName(APEX_NAME);
        EasyMock.expect(mMockDevice.getActiveApexes())
                .andReturn(new HashSet<ITestDevice.ApexInfo>());
        Set<String> installableModules = new HashSet<>();
        installableModules.add(APEX_PACKAGE_NAME);
        EasyMock.expect(mMockDevice.getInstalledPackageNames()).andReturn(installableModules);

        try {
            EasyMock.replay(mMockDevice);
            mModuleOemTargetPreparer.setUp(mTestInfo);
            fail("Should have thrown a TargetSetupError.");
        } catch (TargetSetupError expected) {
            // throw exceptions
        } finally {
            EasyMock.verify(mMockDevice);
        }
    }

    /** Test that teardown without setup does not cause a NPE. */
    @Test
    public void testTearDown() throws Exception {
        EasyMock.replay(mMockDevice);
        mModuleOemTargetPreparer.tearDown(mTestInfo, null);
        EasyMock.verify(mMockDevice);
    }
}
