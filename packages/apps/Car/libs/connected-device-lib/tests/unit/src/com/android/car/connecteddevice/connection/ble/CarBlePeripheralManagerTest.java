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

package com.android.car.connecteddevice.connection.ble;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.os.ParcelUuid;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.connecteddevice.AssociationCallback;
import com.android.car.connecteddevice.connection.AssociationSecureChannel;
import com.android.car.connecteddevice.connection.SecureChannel;
import com.android.car.connecteddevice.model.AssociatedDevice;
import com.android.car.connecteddevice.oob.OobConnectionManager;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.ByteUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class CarBlePeripheralManagerTest {
    private static final UUID ASSOCIATION_SERVICE_UUID = UUID.randomUUID();
    private static final UUID RECONNECT_SERVICE_UUID = UUID.randomUUID();
    private static final UUID RECONNECT_DATA_UUID = UUID.randomUUID();
    private static final UUID WRITE_UUID = UUID.randomUUID();
    private static final UUID READ_UUID = UUID.randomUUID();
    private static final int DEVICE_NAME_LENGTH_LIMIT = 8;
    private static final String TEST_REMOTE_DEVICE_ADDRESS = "00:11:22:33:AA:BB";
    private static final UUID TEST_REMOTE_DEVICE_ID = UUID.randomUUID();
    private static final String TEST_VERIFICATION_CODE = "000000";
    private static final String TEST_ENCRYPTED_VERIFICATION_CODE = "12345";
    private static final Duration RECONNECT_ADVERTISEMENT_DURATION = Duration.ofSeconds(2);
    private static final int DEFAULT_MTU_SIZE = 23;

    @Mock
    private BlePeripheralManager mMockPeripheralManager;
    @Mock
    private ConnectedDeviceStorage mMockStorage;
    @Mock
    private OobConnectionManager mMockOobConnectionManager;
    @Mock
    private AssociationCallback mAssociationCallback;

    private CarBlePeripheralManager mCarBlePeripheralManager;

    private MockitoSession mMockitoSession;

    @Before
    public void setUp() throws Exception {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();
        mCarBlePeripheralManager = new CarBlePeripheralManager(mMockPeripheralManager, mMockStorage,
                ASSOCIATION_SERVICE_UUID, RECONNECT_SERVICE_UUID,
                RECONNECT_DATA_UUID, WRITE_UUID, READ_UUID, RECONNECT_ADVERTISEMENT_DURATION,
                DEFAULT_MTU_SIZE);

        when(mMockOobConnectionManager.encryptVerificationCode(
                TEST_VERIFICATION_CODE.getBytes())).thenReturn(
                TEST_ENCRYPTED_VERIFICATION_CODE.getBytes());
        when(mMockOobConnectionManager.decryptVerificationCode(
                TEST_ENCRYPTED_VERIFICATION_CODE.getBytes())).thenReturn(
                TEST_VERIFICATION_CODE.getBytes());
        mCarBlePeripheralManager.start();
    }

    @After
    public void tearDown() {
        if (mCarBlePeripheralManager != null) {
            mCarBlePeripheralManager.stop();
        }
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testStartAssociationAdvertisingSuccess() {
        String testDeviceName = getNameForAssociation();
        startAssociation(mAssociationCallback, testDeviceName);
        ArgumentCaptor<AdvertiseData> advertisementDataCaptor =
                ArgumentCaptor.forClass(AdvertiseData.class);
        ArgumentCaptor<AdvertiseData> scanResponseDataCaptor =
                ArgumentCaptor.forClass(AdvertiseData.class);
        verify(mMockPeripheralManager).startAdvertising(any(), advertisementDataCaptor.capture(),
                scanResponseDataCaptor.capture(), any());
        AdvertiseData advertisementData = advertisementDataCaptor.getValue();
        ParcelUuid serviceUuid = new ParcelUuid(ASSOCIATION_SERVICE_UUID);
        assertThat(advertisementData.getServiceUuids()).contains(serviceUuid);
        AdvertiseData scanResponseData = scanResponseDataCaptor.getValue();
        assertThat(scanResponseData.getIncludeDeviceName()).isFalse();
        ParcelUuid dataUuid = new ParcelUuid(RECONNECT_DATA_UUID);
        assertThat(scanResponseData.getServiceData().get(dataUuid)).isEqualTo(
                testDeviceName.getBytes());
    }

    @Test
    public void testStartAssociationAdvertisingFailure() {
        startAssociation(mAssociationCallback, getNameForAssociation());
        ArgumentCaptor<AdvertiseCallback> callbackCaptor =
                ArgumentCaptor.forClass(AdvertiseCallback.class);
        verify(mMockPeripheralManager).startAdvertising(any(), any(), any(),
                callbackCaptor.capture());
        AdvertiseCallback advertiseCallback = callbackCaptor.getValue();
        int testErrorCode = 2;
        advertiseCallback.onStartFailure(testErrorCode);
        verify(mAssociationCallback).onAssociationStartFailure();
    }

    @Test
    public void testNotifyAssociationSuccess() {
        String testDeviceName = getNameForAssociation();
        startAssociation(mAssociationCallback, testDeviceName);
        ArgumentCaptor<AdvertiseCallback> callbackCaptor =
                ArgumentCaptor.forClass(AdvertiseCallback.class);
        verify(mMockPeripheralManager).startAdvertising(any(), any(), any(),
                callbackCaptor.capture());
        AdvertiseCallback advertiseCallback = callbackCaptor.getValue();
        AdvertiseSettings settings = new AdvertiseSettings.Builder().build();
        advertiseCallback.onStartSuccess(settings);
        verify(mAssociationCallback).onAssociationStartSuccess(eq(testDeviceName));
    }

    @Test
    public void testShowVerificationCode() {
        AssociationSecureChannel channel = getChannelForAssociation(mAssociationCallback);
        channel.getShowVerificationCodeListener().showVerificationCode(TEST_VERIFICATION_CODE);
        verify(mAssociationCallback).onVerificationCodeAvailable(eq(TEST_VERIFICATION_CODE));
    }

    @Test
    public void testAssociationSuccess() {
        SecureChannel channel = getChannelForAssociation(mAssociationCallback);
        SecureChannel.Callback channelCallback = channel.getCallback();
        assertThat(channelCallback).isNotNull();
        channelCallback.onDeviceIdReceived(TEST_REMOTE_DEVICE_ID.toString());
        channelCallback.onSecureChannelEstablished();
        ArgumentCaptor<AssociatedDevice> deviceCaptor =
                ArgumentCaptor.forClass(AssociatedDevice.class);
        verify(mMockStorage).addAssociatedDeviceForActiveUser(deviceCaptor.capture());
        AssociatedDevice device = deviceCaptor.getValue();
        assertThat(device.getDeviceId()).isEqualTo(TEST_REMOTE_DEVICE_ID.toString());
        verify(mAssociationCallback).onAssociationCompleted(eq(TEST_REMOTE_DEVICE_ID.toString()));
    }

    @Test
    public void testAssociationFailure_channelError() {
        SecureChannel channel = getChannelForAssociation(mAssociationCallback);
        SecureChannel.Callback channelCallback = channel.getCallback();
        int testErrorCode = 1;
        assertThat(channelCallback).isNotNull();
        channelCallback.onDeviceIdReceived(TEST_REMOTE_DEVICE_ID.toString());
        channelCallback.onEstablishSecureChannelFailure(testErrorCode);
        verify(mAssociationCallback).onAssociationError(eq(testErrorCode));
    }

    @Test
    public void connectToDevice_stopsAdvertisingAfterTimeout() {
        when(mMockStorage.hashWithChallengeSecret(any(), any()))
                .thenReturn(ByteUtils.randomBytes(32));
        mCarBlePeripheralManager.connectToDevice(UUID.randomUUID());
        ArgumentCaptor<AdvertiseCallback> callbackCaptor =
                ArgumentCaptor.forClass(AdvertiseCallback.class);
        verify(mMockPeripheralManager).startAdvertising(any(), any(), any(),
                callbackCaptor.capture());
        callbackCaptor.getValue().onStartSuccess(null);
        verify(mMockPeripheralManager,
                timeout(RECONNECT_ADVERTISEMENT_DURATION.plusSeconds(1).toMillis()))
                .stopAdvertising(any(AdvertiseCallback.class));
    }

    @Test
    public void disconnectDevice_stopsAdvertisingForPendingReconnect() {
        when(mMockStorage.hashWithChallengeSecret(any(), any()))
                .thenReturn(ByteUtils.randomBytes(32));
        UUID deviceId = UUID.randomUUID();
        mCarBlePeripheralManager.connectToDevice(deviceId);
        reset(mMockPeripheralManager);
        mCarBlePeripheralManager.disconnectDevice(deviceId.toString());
        verify(mMockPeripheralManager).cleanup();
    }

    private BlePeripheralManager.Callback startAssociation(AssociationCallback callback,
            String deviceName) {
        ArgumentCaptor<BlePeripheralManager.Callback> callbackCaptor =
                ArgumentCaptor.forClass(BlePeripheralManager.Callback.class);
        mCarBlePeripheralManager.startAssociation(deviceName, callback);
        verify(mMockPeripheralManager, timeout(3000)).registerCallback(callbackCaptor.capture());
        return callbackCaptor.getValue();
    }

    private AssociationSecureChannel getChannelForAssociation(AssociationCallback callback) {
        BlePeripheralManager.Callback bleManagerCallback = startAssociation(callback,
                getNameForAssociation());
        BluetoothDevice bluetoothDevice = BluetoothAdapter.getDefaultAdapter()
                .getRemoteDevice(TEST_REMOTE_DEVICE_ADDRESS);
        bleManagerCallback.onRemoteDeviceConnected(bluetoothDevice);
        return (AssociationSecureChannel) mCarBlePeripheralManager.getConnectedDeviceChannel();
    }

    private String getNameForAssociation() {
        return ByteUtils.generateRandomNumberString(DEVICE_NAME_LENGTH_LIMIT);

    }
}
