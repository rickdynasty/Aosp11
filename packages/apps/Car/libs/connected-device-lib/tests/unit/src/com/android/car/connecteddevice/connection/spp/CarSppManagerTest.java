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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;

import com.android.car.connecteddevice.AssociationCallback;
import com.android.car.connecteddevice.connection.AssociationSecureChannel;
import com.android.car.connecteddevice.connection.SecureChannel;
import com.android.car.connecteddevice.model.AssociatedDevice;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CarSppManagerTest {
    private static final String TEST_REMOTE_DEVICE_ADDRESS = "00:11:22:33:AA:BB";
    private static final UUID TEST_REMOTE_DEVICE_ID = UUID.randomUUID();
    private static final UUID TEST_SERVICE_UUID = UUID.randomUUID();
    private static final String TEST_VERIFICATION_CODE = "000000";
    private static final int MAX_PACKET_SIZE = 700;
    @Mock
    private SppManager mMockSppManager;
    @Mock
    private ConnectedDeviceStorage mMockStorage;

    private CarSppManager mCarSppManager;

    private MockitoSession mMockitoSession;

    @Before
    public void setUp() throws Exception {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();
        mCarSppManager = new CarSppManager(mMockSppManager, mMockStorage, TEST_SERVICE_UUID,
                MAX_PACKET_SIZE);
    }

    @After
    public void tearDown() {
        if (mCarSppManager != null) {
            mCarSppManager.stop();
        }
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testStartAssociationSuccess() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        when(mMockSppManager.startListening(TEST_SERVICE_UUID)).thenReturn(true);

        mCarSppManager.startAssociation(null, callback);

        verify(mMockSppManager).startListening(TEST_SERVICE_UUID);
        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onAssociationStartSuccess(eq(null));
    }

    @Test
    public void testStartAssociationFailure() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        when(mMockSppManager.startListening(TEST_SERVICE_UUID)).thenReturn(false);

        mCarSppManager.startAssociation(null, callback);

        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onAssociationStartFailure();
    }

    @Test
    public void testShowVerificationCode() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        AssociationSecureChannel channel = getChannelForAssociation(callback);

        channel.getShowVerificationCodeListener().showVerificationCode(TEST_VERIFICATION_CODE);

        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onVerificationCodeAvailable(eq(TEST_VERIFICATION_CODE));
    }

    @Test
    public void testAssociationSuccess() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        AssociationCallback callback = createAssociationCallback(semaphore);
        SecureChannel channel = getChannelForAssociation(callback);
        SecureChannel.Callback channelCallback = channel.getCallback();

        assertThat(channelCallback).isNotNull();

        channelCallback.onDeviceIdReceived(TEST_REMOTE_DEVICE_ID.toString());
        channelCallback.onSecureChannelEstablished();
        ArgumentCaptor<AssociatedDevice> deviceCaptor =
                ArgumentCaptor.forClass(AssociatedDevice.class);
        verify(mMockStorage).addAssociatedDeviceForActiveUser(deviceCaptor.capture());
        AssociatedDevice device = deviceCaptor.getValue();

        assertThat(device.getDeviceId()).isEqualTo(TEST_REMOTE_DEVICE_ID.toString());
        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onAssociationCompleted(eq(TEST_REMOTE_DEVICE_ID.toString()));
    }

    private boolean tryAcquire(Semaphore semaphore) throws InterruptedException {
        return semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
    }

    private AssociationSecureChannel getChannelForAssociation(AssociationCallback callback) {
        ArgumentCaptor<SppManager.ConnectionCallback> callbackCaptor =
                ArgumentCaptor.forClass(SppManager.ConnectionCallback.class);
        mCarSppManager.startAssociation(null, callback);
        verify(mMockSppManager).registerCallback(callbackCaptor.capture(), any());
        BluetoothDevice bluetoothDevice = BluetoothAdapter.getDefaultAdapter()
                .getRemoteDevice(TEST_REMOTE_DEVICE_ADDRESS);
        callbackCaptor.getValue().onRemoteDeviceConnected(bluetoothDevice);
        return (AssociationSecureChannel) mCarSppManager.getConnectedDeviceChannel();
    }

    @NonNull
    private AssociationCallback createAssociationCallback(@NonNull final Semaphore semaphore) {
        return spy(new AssociationCallback() {
            @Override
            public void onAssociationStartSuccess(String deviceName) {
                semaphore.release();
            }

            @Override
            public void onAssociationStartFailure() {
                semaphore.release();
            }

            @Override
            public void onAssociationError(int error) {
                semaphore.release();
            }

            @Override
            public void onVerificationCodeAvailable(String code) {
                semaphore.release();
            }

            @Override
            public void onAssociationCompleted(String deviceId) {
                semaphore.release();
            }
        });
    }
}
