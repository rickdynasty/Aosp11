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

package com.android.car.connecteddevice.connection.spp;

import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.connecteddevice.util.ByteUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class SppDeviceMessageStreamTest {
    private static final int MAX_WRITE_SIZE = 700;

    @Mock
    private SppManager mMockSppManager;
    private BluetoothDevice mBluetoothDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(
            "00:11:22:33:44:55");
    private MockitoSession mMockingSession;
    private SppDeviceMessageStream mSppDeviceMessageStream;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();
        mSppDeviceMessageStream = spy(
                new SppDeviceMessageStream(mMockSppManager, mBluetoothDevice, MAX_WRITE_SIZE));
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void send_callsWriteAndSendCompleted() {
        byte[] data = ByteUtils.randomBytes(10);
        mSppDeviceMessageStream.send(data);
        verify(mMockSppManager).write(data);
        verify(mSppDeviceMessageStream).sendCompleted();
    }
}
