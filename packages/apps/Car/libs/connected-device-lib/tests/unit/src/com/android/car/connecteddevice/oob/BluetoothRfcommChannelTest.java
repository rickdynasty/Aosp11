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

package com.android.car.connecteddevice.oob;


import static com.android.car.connecteddevice.model.OobEligibleDevice.OOB_TYPE_BLUETOOTH;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.connecteddevice.model.OobEligibleDevice;
import com.android.car.connecteddevice.util.ByteUtils;

import com.google.common.primitives.Bytes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class BluetoothRfcommChannelTest {
    private BluetoothRfcommChannel mBluetoothRfcommChannel;
    private OobEligibleDevice mOobEligibleDevice;
    @Mock
    private OobChannel.Callback mMockCallback;
    @Mock
    private BluetoothAdapter mMockBluetoothAdapter;
    @Mock
    private BluetoothDevice mMockBluetoothDevice;
    @Mock
    private BluetoothSocket mMockBluetoothSocket;
    @Mock
    private OutputStream mMockOutputStream;

    private MockitoSession mMockingSession;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();


        mBluetoothRfcommChannel = new BluetoothRfcommChannel();

        mOobEligibleDevice = new OobEligibleDevice("00:11:22:33:44:55", OOB_TYPE_BLUETOOTH);

        when(mMockBluetoothAdapter.getRemoteDevice(
                mOobEligibleDevice.getDeviceAddress())).thenReturn(mMockBluetoothDevice);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void completeOobExchange_success() throws Exception {
        when(mMockBluetoothSocket.getOutputStream()).thenReturn(mMockOutputStream);
        when(mMockBluetoothDevice.createRfcommSocketToServiceRecord(any(UUID.class))).thenReturn(
                mMockBluetoothSocket);
        mBluetoothRfcommChannel.completeOobDataExchange(mOobEligibleDevice, mMockCallback,
                mMockBluetoothAdapter);

        verify(mMockCallback, timeout(1000)).onOobExchangeSuccess();
        OobConnectionManager oobConnectionManager = new OobConnectionManager();
        oobConnectionManager.startOobExchange(mBluetoothRfcommChannel);

        ArgumentCaptor<byte[]> oobDataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mMockOutputStream).write(oobDataCaptor.capture());
        byte[] oobData = oobDataCaptor.getValue();

        assertThat(oobData).isEqualTo(Bytes.concat(oobConnectionManager.mDecryptionIv,
                oobConnectionManager.mEncryptionIv,
                oobConnectionManager.mEncryptionKey.getEncoded()));
    }

    @Test
    public void completeOobExchange_ioExceptionCausesRetry() throws Exception {
        doThrow(IOException.class).doAnswer(invocation -> null)
                .when(mMockBluetoothSocket).connect();
        when(mMockBluetoothDevice.createRfcommSocketToServiceRecord(any(UUID.class))).thenReturn(
                mMockBluetoothSocket);
        mBluetoothRfcommChannel.completeOobDataExchange(mOobEligibleDevice, mMockCallback,
                mMockBluetoothAdapter);
        verify(mMockBluetoothSocket, timeout(3000).times(2)).connect();
    }

    @Test
    public void completeOobExchange_createRfcommSocketFails_callOnFailed() throws Exception {
        when(mMockBluetoothDevice.createRfcommSocketToServiceRecord(any(UUID.class))).thenThrow(
                IOException.class);

        mBluetoothRfcommChannel.completeOobDataExchange(mOobEligibleDevice, mMockCallback,
                mMockBluetoothAdapter);
        verify(mMockCallback).onOobExchangeFailure();
    }

    @Test
    public void sendOobData_nullBluetoothDevice_callOnFailed() {
        mBluetoothRfcommChannel.mCallback = mMockCallback;

        mBluetoothRfcommChannel.sendOobData("someData".getBytes());
        verify(mMockCallback).onOobExchangeFailure();
    }

    @Test
    public void sendOobData_writeFails_callOnFailed() throws Exception {
        byte[] testMessage = "testMessage".getBytes();
        completeOobExchange_success();
        doThrow(IOException.class).when(mMockOutputStream).write(testMessage);

        mBluetoothRfcommChannel.sendOobData(testMessage);
        verify(mMockCallback).onOobExchangeFailure();
    }

    @Test
    public void interrupt_closesSocket() throws Exception {
        when(mMockBluetoothSocket.getOutputStream()).thenReturn(mMockOutputStream);
        when(mMockBluetoothDevice.createRfcommSocketToServiceRecord(any(UUID.class))).thenReturn(
                mMockBluetoothSocket);
        mBluetoothRfcommChannel.completeOobDataExchange(mOobEligibleDevice, mMockCallback,
                mMockBluetoothAdapter);
        mBluetoothRfcommChannel.interrupt();
        mBluetoothRfcommChannel.sendOobData(ByteUtils.randomBytes(10));
        verify(mMockOutputStream, times(0)).write(any());
        verify(mMockOutputStream).flush();
        verify(mMockOutputStream).close();
    }

    @Test
    public void interrupt_preventsCallbacks() throws Exception {
        when(mMockBluetoothSocket.getOutputStream()).thenReturn(mMockOutputStream);
        when(mMockBluetoothDevice.createRfcommSocketToServiceRecord(any(UUID.class))).thenReturn(
                mMockBluetoothSocket);
        doAnswer(invocation -> {
            mBluetoothRfcommChannel.interrupt();
            return invocation.callRealMethod();
        }).when(mMockBluetoothSocket).connect();
        mBluetoothRfcommChannel.completeOobDataExchange(mOobEligibleDevice, mMockCallback,
                mMockBluetoothAdapter);
        verify(mMockCallback, times(0)).onOobExchangeSuccess();
        verify(mMockCallback, times(0)).onOobExchangeFailure();
    }
}
