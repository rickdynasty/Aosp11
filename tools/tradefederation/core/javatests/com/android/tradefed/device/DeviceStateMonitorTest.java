/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IDevice.DeviceState;
import com.android.tradefed.util.RunUtil;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Unit tests for {@link DeviceStateMonitorTest}. */
@RunWith(JUnit4.class)
public class DeviceStateMonitorTest {
    private static final int WAIT_TIMEOUT_NOT_REACHED_MS = 500;
    private static final int WAIT_TIMEOUT_REACHED_MS = 100;
    private static final int WAIT_STATE_CHANGE_MS = 50;
    private static final int POLL_TIME_MS = 10;

    private static final String SERIAL_NUMBER = "1";
    private IDevice mMockDevice;
    private DeviceStateMonitor mMonitor;
    private IDeviceManager mMockMgr;
    private String mStubValue = "not found";

    @Before
    public void setUp() {
        mStubValue = "not found";
        mMockMgr = EasyMock.createMock(IDeviceManager.class);
        EasyMock.expect(mMockMgr.isFileSystemMountCheckEnabled()).andReturn(false).anyTimes();
        mMockMgr.addFastbootListener(EasyMock.anyObject());
        mMockMgr.removeFastbootListener(EasyMock.anyObject());
        EasyMock.replay(mMockMgr);
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true);
    }

    /** Test {@link DeviceStateMonitor#waitForDeviceOnline()} when device is already online */
    @Test
    public void testWaitForDeviceOnline_alreadyOnline() {
        assertEquals(mMockDevice, mMonitor.waitForDeviceOnline());
    }

    /** Test {@link DeviceStateMonitor#waitForDeviceOnline()} when device becomes online */
    @Test
    public void testWaitForDeviceOnline() {
        mMonitor.setState(TestDeviceState.NOT_AVAILABLE);
        Thread test =
                new Thread() {
                    @Override
                    public void run() {
                        RunUtil.getDefault().sleep(WAIT_STATE_CHANGE_MS);
                        mMonitor.setState(TestDeviceState.ONLINE);
                    }
                };
        test.setName(getClass().getCanonicalName() + "#testWaitForDeviceOnline");
        test.start();
        assertEquals(mMockDevice, mMonitor.waitForDeviceOnline());
    }

    /**
     * Test {@link DeviceStateMonitor#waitForDeviceOnline()} when device does not becomes online
     * within allowed time
     */
    @Test
    public void testWaitForDeviceOnline_timeout() {
        mMonitor.setState(TestDeviceState.NOT_AVAILABLE);
        assertNull(mMonitor.waitForDeviceOnline(WAIT_TIMEOUT_REACHED_MS));
    }

    /** Test {@link DeviceStateMonitor#isAdbTcp()} with a USB serial number. */
    @Test
    public void testIsAdbTcp_usb() {
        IDevice mockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mockDevice.getSerialNumber()).andStubReturn("2345asdf");
        EasyMock.expect(mockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.replay(mockDevice);
        DeviceStateMonitor monitor = new DeviceStateMonitor(mMockMgr, mockDevice, true);
        assertFalse(monitor.isAdbTcp());
    }

    /** Test {@link DeviceStateMonitor#isAdbTcp()} with a TCP serial number. */
    @Test
    public void testIsAdbTcp_tcp() {
        IDevice mockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mockDevice.getSerialNumber()).andStubReturn("192.168.1.1:5555");
        EasyMock.expect(mockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.replay(mockDevice);
        DeviceStateMonitor monitor = new DeviceStateMonitor(mMockMgr, mockDevice, true);
        assertTrue(monitor.isAdbTcp());
    }

    /**
     * Test {@link DeviceStateMonitor#waitForDeviceNotAvailable(long)} when device is already
     * offline
     */
    @Test
    public void testWaitForDeviceOffline_alreadyOffline() {
        mMonitor.setState(TestDeviceState.NOT_AVAILABLE);
        boolean res = mMonitor.waitForDeviceNotAvailable(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForDeviceNotAvailable(long)} when device becomes offline
     */
    @Test
    public void testWaitForDeviceOffline() {
        mMonitor.setState(TestDeviceState.ONLINE);
        Thread test =
                new Thread() {
                    @Override
                    public void run() {
                        RunUtil.getDefault().sleep(WAIT_STATE_CHANGE_MS);
                        mMonitor.setState(TestDeviceState.NOT_AVAILABLE);
                    }
                };
        test.setName(getClass().getCanonicalName() + "#testWaitForDeviceOffline");
        test.start();
        boolean res = mMonitor.waitForDeviceNotAvailable(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForDeviceNotAvailable(long)} when device doesn't become
     * offline
     */
    @Test
    public void testWaitForDeviceOffline_timeout() {
        mMonitor.setState(TestDeviceState.ONLINE);
        boolean res = mMonitor.waitForDeviceNotAvailable(WAIT_TIMEOUT_REACHED_MS);
        assertFalse(res);
    }

    /** Test {@link DeviceStateMonitor#waitForDeviceShell(long)} when shell is available. */
    @Test
    public void testWaitForShellAvailable() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return "/system/bin/adb";
                    }
                };
            }
        };
        boolean res = mMonitor.waitForDeviceShell(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /** Test {@link DeviceStateMonitor#waitForDeviceShell(long)} when shell become available. */
    @Test
    public void testWaitForShell_becomeAvailable() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return mStubValue;
                    }
                };
            }
            @Override
            protected long getCheckPollTime() {
                return POLL_TIME_MS;
            }
        };
        Thread test =
                new Thread() {
                    @Override
                    public void run() {
                        RunUtil.getDefault().sleep(WAIT_STATE_CHANGE_MS);
                        mStubValue = "/system/bin/adb";
                    }
                };
        test.setName(getClass().getCanonicalName() + "#testWaitForShell_becomeAvailable");
        test.start();
        boolean res = mMonitor.waitForDeviceShell(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForDeviceShell(long)} when shell never become available.
     */
    @Test
    public void testWaitForShell_timeout() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return mStubValue;
                    }
                };
            }
            @Override
            protected long getCheckPollTime() {
                return POLL_TIME_MS;
            }
        };
        boolean res = mMonitor.waitForDeviceShell(WAIT_TIMEOUT_REACHED_MS);
        assertFalse(res);
    }

    /** Test {@link DeviceStateMonitor#waitForBootComplete(long)} when boot is already complete. */
    @Test
    public void testWaitForBootComplete() throws Exception {
        IDevice mFakeDevice =
                new StubDevice("serial") {
                    @Override
                    public ListenableFuture<String> getSystemProperty(String name) {
                        SettableFuture<String> f = SettableFuture.create();
                        f.set("1");
                        return f;
                    }
                };
        mMonitor = new DeviceStateMonitor(mMockMgr, mFakeDevice, true);
        boolean res = mMonitor.waitForBootComplete(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForBootComplete(long)} when boot complete status change.
     */
    @Test
    public void testWaitForBoot_becomeComplete() throws Exception {
        mStubValue = "0";
        IDevice mFakeDevice =
                new StubDevice("serial") {
                    @Override
                    public ListenableFuture<String> getSystemProperty(String name) {
                        SettableFuture<String> f = SettableFuture.create();
                        f.set(mStubValue);
                        return f;
                    }
                };
        mMonitor = new DeviceStateMonitor(mMockMgr, mFakeDevice, true) {
            @Override
            protected long getCheckPollTime() {
                return POLL_TIME_MS;
            }
        };
        Thread test =
                new Thread() {
                    @Override
                    public void run() {
                        RunUtil.getDefault().sleep(WAIT_STATE_CHANGE_MS);
                        mStubValue = "1";
                    }
                };
        test.setName(getClass().getCanonicalName() + "#testWaitForBoot_becomeComplete");
        test.start();
        boolean res = mMonitor.waitForBootComplete(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /** Test {@link DeviceStateMonitor#waitForBootComplete(long)} when boot complete timeout. */
    @Test
    public void testWaitForBoot_timeout() throws Exception {
        mStubValue = "0";
        IDevice mFakeDevice =
                new StubDevice("serial") {
                    @Override
                    public ListenableFuture<String> getSystemProperty(String name) {
                        SettableFuture<String> f = SettableFuture.create();
                        f.set(mStubValue);
                        return f;
                    }
                };
        mMonitor = new DeviceStateMonitor(mMockMgr, mFakeDevice, true) {
            @Override
            protected long getCheckPollTime() {
                return POLL_TIME_MS;
            }
        };
        boolean res = mMonitor.waitForBootComplete(WAIT_TIMEOUT_REACHED_MS);
        assertFalse(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForPmResponsive(long)} when package manager is already
     * responsive.
     */
    @Test
    public void testWaitForPmResponsive() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return "package:com.android.awesomeclass";
                    }
                };
            }
        };
        boolean res = mMonitor.waitForPmResponsive(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForPmResponsive(long)} when package manager becomes
     * responsive
     */
    @Test
    public void testWaitForPm_becomeResponsive() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return mStubValue;
                    }
                };
            }
            @Override
            protected long getCheckPollTime() {
                return POLL_TIME_MS;
            }
        };
        Thread test =
                new Thread() {
                    @Override
                    public void run() {
                        RunUtil.getDefault().sleep(WAIT_STATE_CHANGE_MS);
                        mStubValue = "package:com.android.awesomeclass";
                    }
                };
        test.setName(getClass().getCanonicalName() + "#testWaitForPm_becomeResponsive");
        test.start();
        boolean res = mMonitor.waitForPmResponsive(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForPmResponsive(long)} when package manager check timeout
     * before becoming responsive.
     */
    @Test
    public void testWaitForPm_timeout() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return mStubValue;
                    }
                };
            }
            @Override
            protected long getCheckPollTime() {
                return POLL_TIME_MS;
            }
        };
        boolean res = mMonitor.waitForPmResponsive(WAIT_TIMEOUT_REACHED_MS);
        assertFalse(res);
    }

    /** Test {@link DeviceStateMonitor#getMountPoint(String)} return the cached mount point. */
    @Test
    public void testgetMountPoint() throws Exception {
        String expectedMountPoint = "NOT NULL";
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.expect(mMockDevice.getMountPoint((String) EasyMock.anyObject()))
                .andStubReturn(expectedMountPoint);
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true);
        assertEquals(expectedMountPoint, mMonitor.getMountPoint(""));
    }

    /**
     * Test {@link DeviceStateMonitor#getMountPoint(String)} return the mount point that is not
     * cached.
     */
    @Test
    public void testgetMountPoint_nonCached() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.expect(mMockDevice.getMountPoint((String) EasyMock.anyObject()))
                .andStubReturn(null);
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject());
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return "NONCACHED";
                    }
                };
            }
        };
        assertEquals("NONCACHED", mMonitor.getMountPoint(""));
    }

    /**
     * Test {@link DeviceStateMonitor#waitForStoreMount(long)} when mount point is already mounted
     */
    @Test
    public void testWaitForStoreMount() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return "number 10 one";
                    }
                };
            }
            @Override
            protected long getCurrentTime() {
                return 10;
            }
            @Override
            public String getMountPoint(String mountName) {
                return "";
            }
        };
        boolean res = mMonitor.waitForStoreMount(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForStoreMount(long)} when mount point return permission
     * denied should return directly false.
     */
    @Test
    public void testWaitForStoreMount_PermDenied() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE).anyTimes();
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockDevice);

        Function<Integer, DeviceStateMonitor> creator =
                (Integer count) -> new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            private int mCount = count;
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                String output = --mCount >= 0
                        ? "/system/bin/sh: cat: /sdcard/1459376318045: Permission denied"
                        : "number 10 one";
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return output;
                    }
                };
            }
            @Override
            protected long getCurrentTime() {
                return 10;
            }
            @Override
            protected long getCheckPollTime() {
                // Set retry interval to 0 so #waitForStoreMount won't fail due to timeout
                return 0;
            }
            @Override
            public String getMountPoint(String mountName) {
                return "";
            }
        };

        // 'Permission denied' is never returned. #waitForStoreMount should return true.
        mMonitor = creator.apply(0);
        assertTrue(mMonitor.waitForStoreMount(WAIT_TIMEOUT_NOT_REACHED_MS));
        // 'Permission denied' is returned once. #waitForStoreMount should return true
        // since we retry once when 'Permission denied' is returned.
        mMonitor = creator.apply(1);
        assertTrue(mMonitor.waitForStoreMount(WAIT_TIMEOUT_NOT_REACHED_MS));
        // 'Permission denied' is returned twice. #waitForStoreMount should return false
        // since the 2nd retry on 'Permission denied' still fails.
        mMonitor = creator.apply(2);
        assertFalse(mMonitor.waitForStoreMount(WAIT_TIMEOUT_NOT_REACHED_MS));
    }

    /** Test {@link DeviceStateMonitor#waitForStoreMount(long)} when mount point become available */
    @Test
    public void testWaitForStoreMount_becomeAvailable() throws Exception {
        mStubValue = null;
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return "number 10 one";
                    }
                };
            }
            @Override
            protected long getCurrentTime() {
                return 10;
            }
            @Override
            protected long getCheckPollTime() {
                return POLL_TIME_MS;
            }
            @Override
            public String getMountPoint(String mountName) {
                return mStubValue;
            }
        };
        Thread test =
                new Thread() {
                    @Override
                    public void run() {
                        RunUtil.getDefault().sleep(WAIT_STATE_CHANGE_MS);
                        mStubValue = "NOT NULL";
                    }
                };
        test.setName(getClass().getCanonicalName() + "#testWaitForStoreMount_becomeAvailable");
        test.start();
        boolean res = mMonitor.waitForStoreMount(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForStoreMount(long)} when mount point is available and the
     * output of the check (string in the file) become valid.
     */
    @Test
    public void testWaitForStoreMount_outputBecomeValid() throws Exception {
        mStubValue = "INVALID";
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected CollectingOutputReceiver createOutputReceiver() {
                return new CollectingOutputReceiver() {
                    @Override
                    public String getOutput() {
                        return mStubValue;
                    }
                };
            }
            @Override
            protected long getCurrentTime() {
                return 10;
            }
            @Override
            protected long getCheckPollTime() {
                return POLL_TIME_MS;
            }
            @Override
            public String getMountPoint(String mountName) {
                return "NOT NULL";
            }
        };
        Thread test =
                new Thread() {
                    @Override
                    public void run() {
                        RunUtil.getDefault().sleep(WAIT_STATE_CHANGE_MS);
                        mStubValue = "number 10 one";
                    }
                };
        test.setName(getClass().getCanonicalName() + "#testWaitForStoreMount_outputBecomeValid");
        test.start();
        boolean res = mMonitor.waitForStoreMount(WAIT_TIMEOUT_NOT_REACHED_MS);
        assertTrue(res);
    }

    /** Test {@link DeviceStateMonitor#waitForStoreMount(long)} when mount point check timeout */
    @Test
    public void testWaitForStoreMount_timeout() throws Exception {
        mStubValue = null;
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE);
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        mMockDevice.executeShellCommand((String) EasyMock.anyObject(),
                (CollectingOutputReceiver)EasyMock.anyObject(), EasyMock.anyInt(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            protected long getCheckPollTime() {
                return POLL_TIME_MS;
            }
            @Override
            public String getMountPoint(String mountName) {
                return mStubValue;
            }
        };
        boolean res = mMonitor.waitForStoreMount(WAIT_TIMEOUT_REACHED_MS);
        assertFalse(res);
    }

    /**
     * Test {@link DeviceStateMonitor#waitForDeviceAvailable(long)} wait for device available when
     * device is already available.
     */
    @Test
    public void testWaitForDeviceAvailable() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE).anyTimes();
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            public IDevice waitForDeviceOnline(long waitTime) {
                return mMockDevice;
            }
            @Override
            public boolean waitForBootComplete(long waitTime) {
                return true;
            }
            @Override
            protected boolean waitForPmResponsive(long waitTime) {
                return true;
            }
            @Override
            protected boolean waitForStoreMount(long waitTime) {
                return true;
            }
        };
        assertEquals(mMockDevice, mMonitor.waitForDeviceAvailable(WAIT_TIMEOUT_NOT_REACHED_MS));
    }

    @Test
    public void testWaitForDeviceAvailable_mounted() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE).anyTimes();
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.expect(mMockDevice.getMountPoint((String) EasyMock.anyObject()))
                .andStubReturn("/sdcard");
        mMockDevice.executeShellCommand(
                EasyMock.eq("stat -f -c \"%t\" /sdcard"),
                EasyMock.anyObject(),
                EasyMock.anyLong(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall()
                .andAnswer(
                        () -> {
                            CollectingOutputReceiver stat =
                                    (CollectingOutputReceiver) EasyMock.getCurrentArguments()[1];
                            String statOutput = "65735546\n"; // Fuse magic number
                            stat.addOutput(statOutput.getBytes(), 0, statOutput.length());
                            return null;
                        });
        String[] input = new String[1];
        mMockDevice.executeShellCommand(
                EasyMock.contains("echo"),
                EasyMock.anyObject(),
                EasyMock.anyLong(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall()
                .andAnswer(
                        () -> {
                            input[0] = (String) EasyMock.getCurrentArguments()[0];
                            return null;
                        });
        mMockDevice.executeShellCommand(
                EasyMock.contains("cat"),
                EasyMock.anyObject(),
                EasyMock.anyLong(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.expectLastCall()
                .andAnswer(
                        () -> {
                            CollectingOutputReceiver output =
                                    (CollectingOutputReceiver) EasyMock.getCurrentArguments()[1];
                            output.addOutput(input[0].getBytes(), 0, input[0].length());
                            return null;
                        });
        mMockDevice.executeShellCommand(
                EasyMock.contains("rm"),
                EasyMock.anyObject(),
                EasyMock.anyLong(),
                EasyMock.eq(TimeUnit.MILLISECONDS));
        EasyMock.replay(mMockDevice);
        EasyMock.reset(mMockMgr);
        EasyMock.expect(mMockMgr.isFileSystemMountCheckEnabled()).andReturn(true);
        EasyMock.replay(mMockMgr);
        mMonitor =
                new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
                    @Override
                    public IDevice waitForDeviceOnline(long waitTime) {
                        return mMockDevice;
                    }

                    @Override
                    public boolean waitForBootComplete(long waitTime) {
                        return true;
                    }

                    @Override
                    protected boolean waitForPmResponsive(long waitTime) {
                        return true;
                    }
                };
        assertEquals(mMockDevice, mMonitor.waitForDeviceAvailable(WAIT_TIMEOUT_NOT_REACHED_MS));
    }

    /** Test {@link DeviceStateMonitor#waitForDeviceAvailable(long)} when device is not online. */
    @Test
    public void testWaitForDeviceAvailable_notOnline() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE).anyTimes();
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            public IDevice waitForDeviceOnline(long waitTime) {
                return null;
            }
        };
        assertNull(mMonitor.waitForDeviceAvailable(WAIT_TIMEOUT_NOT_REACHED_MS));
    }

    /**
     * Test {@link DeviceStateMonitor#waitForDeviceAvailable(long)} when device boot is not
     * complete.
     */
    @Test
    public void testWaitForDeviceAvailable_notBootComplete() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE).anyTimes();
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            public IDevice waitForDeviceOnline(long waitTime) {
                return mMockDevice;
            }
            @Override
            public boolean waitForBootComplete(long waitTime) {
                return false;
            }
        };
        assertNull(mMonitor.waitForDeviceAvailable(WAIT_TIMEOUT_REACHED_MS));
    }

    /** Test {@link DeviceStateMonitor#waitForDeviceAvailable(long)} when pm is not responsive. */
    @Test
    public void testWaitForDeviceAvailable_pmNotResponsive() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE).anyTimes();
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            public IDevice waitForDeviceOnline(long waitTime) {
                return mMockDevice;
            }
            @Override
            public boolean waitForBootComplete(long waitTime) {
                return true;
            }
            @Override
            protected boolean waitForPmResponsive(long waitTime) {
                return false;
            }
        };
        assertNull(mMonitor.waitForDeviceAvailable(WAIT_TIMEOUT_REACHED_MS));
    }

    /**
     * Test {@link DeviceStateMonitor#waitForDeviceAvailable(long)} when mount point is not ready
     */
    @Test
    public void testWaitForDeviceAvailable_notMounted() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.ONLINE).anyTimes();
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true) {
            @Override
            public IDevice waitForDeviceOnline(long waitTime) {
                return mMockDevice;
            }
            @Override
            public boolean waitForBootComplete(long waitTime) {
                return true;
            }
            @Override
            protected boolean waitForPmResponsive(long waitTime) {
                return true;
            }
            @Override
            protected boolean waitForStoreMount(long waitTime) {
                return false;
            }
        };
        assertNull(mMonitor.waitForDeviceAvailable(WAIT_TIMEOUT_REACHED_MS));
    }

    /** Test {@link DeviceStateMonitor#waitForDeviceInSideload(long)} */
    @Test
    public void testWaitForDeviceInSideload() throws Exception {
        mMockDevice = EasyMock.createMock(IDevice.class);
        EasyMock.expect(mMockDevice.getState()).andReturn(DeviceState.SIDELOAD).anyTimes();
        EasyMock.expect(mMockDevice.getSerialNumber()).andReturn(SERIAL_NUMBER).anyTimes();
        EasyMock.replay(mMockDevice);
        mMonitor = new DeviceStateMonitor(mMockMgr, mMockDevice, true);
        assertTrue(mMonitor.waitForDeviceInSideload(WAIT_TIMEOUT_NOT_REACHED_MS));
    }
}
