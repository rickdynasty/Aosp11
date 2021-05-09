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

import com.android.ddmlib.IShellOutputReceiver;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.MockFileUtil;
import com.android.tradefed.targetprep.ArtChrootPreparer;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.result.ITestInvocationListener;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ArtGTest}. */
@RunWith(JUnit4.class)
public class ArtGTestTest {
    private ITestInvocationListener mMockInvocationListener = null;
    private IShellOutputReceiver mMockReceiver = null;
    private ITestDevice mMockITestDevice = null;
    private GTest mGTest;
    private TestInformation mTestInfo;

    @Before
    public void setUp() throws Exception {
        mMockInvocationListener = EasyMock.createMock(ITestInvocationListener.class);
        mMockReceiver = EasyMock.createMock(IShellOutputReceiver.class);
        mMockITestDevice = EasyMock.createMock(ITestDevice.class);
        mGTest =
                new ArtGTest() {
                    @Override
                    IShellOutputReceiver createResultParser(
                            String runName, ITestInvocationListener listener) {
                        return mMockReceiver;
                    }
                };
        mGTest.setDevice(mMockITestDevice);
        mGTest.setNativeTestDevicePath(ArtChrootPreparer.CHROOT_PATH);
        mTestInfo = TestInformation.newBuilder().build();

        EasyMock.expect(mMockITestDevice.getSerialNumber()).andStubReturn("serial");
    }

    private void replayMocks() {
        EasyMock.replay(mMockInvocationListener, mMockITestDevice);
    }

    private void verifyMocks() {
        EasyMock.verify(mMockInvocationListener, mMockITestDevice);
    }

    @Test
    public void testChroot_testRun() throws DeviceNotAvailableException {
        final String nativeTestPath = ArtChrootPreparer.CHROOT_PATH;
        final String test1 = "test1";
        final String testPath1 = String.format("%s/%s", nativeTestPath, test1);

        MockFileUtil.setMockDirContents(mMockITestDevice, nativeTestPath, test1);
        EasyMock.expect(mMockITestDevice.doesFileExist(nativeTestPath)).andReturn(true);
        EasyMock.expect(mMockITestDevice.isDirectory(nativeTestPath)).andReturn(true);
        EasyMock.expect(mMockITestDevice.isDirectory(testPath1)).andReturn(false);
        EasyMock.expect(mMockITestDevice.isExecutable(testPath1)).andReturn(true);

        String[] files = new String[] {"test1"};
        EasyMock.expect(mMockITestDevice.getChildren(nativeTestPath)).andReturn(files);
        mMockITestDevice.executeShellCommand(
                EasyMock.startsWith("chroot /data/local/tmp/art-test-chroot /test1"),
                EasyMock.anyObject(),
                EasyMock.anyLong(),
                EasyMock.anyObject(),
                EasyMock.anyInt());

        replayMocks();
        mGTest.run(mTestInfo, mMockInvocationListener);
        verifyMocks();
    }
}
