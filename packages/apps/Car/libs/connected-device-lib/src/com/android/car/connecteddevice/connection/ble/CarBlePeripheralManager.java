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

import static com.android.car.connecteddevice.ConnectedDeviceManager.DEVICE_ERROR_UNEXPECTED_DISCONNECTION;
import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.connecteddevice.AssociationCallback;
import com.android.car.connecteddevice.connection.AssociationSecureChannel;
import com.android.car.connecteddevice.connection.CarBluetoothManager;
import com.android.car.connecteddevice.connection.OobAssociationSecureChannel;
import com.android.car.connecteddevice.connection.ReconnectSecureChannel;
import com.android.car.connecteddevice.connection.SecureChannel;
import com.android.car.connecteddevice.oob.OobChannel;
import com.android.car.connecteddevice.oob.OobConnectionManager;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.ByteUtils;
import com.android.car.connecteddevice.util.EventLog;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

/**
 * Communication manager that allows for targeted connections to a specific device in the car.
 */
public class CarBlePeripheralManager extends CarBluetoothManager {

    private static final String TAG = "CarBlePeripheralManager";

    // Attribute protocol bytes attached to message. Available write size is MTU size minus att
    // bytes.
    private static final int ATT_PROTOCOL_BYTES = 3;

    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int SALT_BYTES = 8;

    private static final int TOTAL_AD_DATA_BYTES = 16;

    private static final int TRUNCATED_BYTES = 3;

    private static final String TIMEOUT_HANDLER_THREAD_NAME = "peripheralThread";

    private final BluetoothGattDescriptor mDescriptor =
            new BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG,
                    BluetoothGattDescriptor.PERMISSION_READ
                            | BluetoothGattDescriptor.PERMISSION_WRITE);

    private final BlePeripheralManager mBlePeripheralManager;

    private final UUID mAssociationServiceUuid;

    private final UUID mReconnectServiceUuid;

    private final UUID mReconnectDataUuid;

    private final BluetoothGattCharacteristic mWriteCharacteristic;

    private final BluetoothGattCharacteristic mReadCharacteristic;

    private HandlerThread mTimeoutHandlerThread;

    private Handler mTimeoutHandler;

    private final Duration mMaxReconnectAdvertisementDuration;

    private final int mDefaultMtuSize;

    private String mReconnectDeviceId;

    private byte[] mReconnectChallenge;

    private AdvertiseCallback mAdvertiseCallback;

    private OobConnectionManager mOobConnectionManager;

    private AssociationCallback mAssociationCallback;

    /**
     * Initialize a new instance of manager.
     *
     * @param blePeripheralManager    {@link BlePeripheralManager} for establishing connection.
     * @param connectedDeviceStorage  Shared {@link ConnectedDeviceStorage} for companion features.
     * @param associationServiceUuid  {@link UUID} of association service.
     * @param reconnectServiceUuid    {@link UUID} of reconnect service.
     * @param reconnectDataUuid       {@link UUID} key of reconnect advertisement data.
     * @param writeCharacteristicUuid {@link UUID} of characteristic the car will write to.
     * @param readCharacteristicUuid  {@link UUID} of characteristic the device will write to.
     * @param maxReconnectAdvertisementDuration Maximum duration to advertise for reconnect before
     *                                          restarting.
     * @param defaultMtuSize          Default MTU size for new channels.
     */
    public CarBlePeripheralManager(@NonNull BlePeripheralManager blePeripheralManager,
            @NonNull ConnectedDeviceStorage connectedDeviceStorage,
            @NonNull UUID associationServiceUuid,
            @NonNull UUID reconnectServiceUuid,
            @NonNull UUID reconnectDataUuid,
            @NonNull UUID writeCharacteristicUuid,
            @NonNull UUID readCharacteristicUuid,
            @NonNull Duration maxReconnectAdvertisementDuration,
            int defaultMtuSize) {
        super(connectedDeviceStorage);
        mBlePeripheralManager = blePeripheralManager;
        mAssociationServiceUuid = associationServiceUuid;
        mReconnectServiceUuid = reconnectServiceUuid;
        mReconnectDataUuid = reconnectDataUuid;
        mDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        mWriteCharacteristic = new BluetoothGattCharacteristic(writeCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PROPERTY_READ);
        mReadCharacteristic = new BluetoothGattCharacteristic(readCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        mReadCharacteristic.addDescriptor(mDescriptor);
        mMaxReconnectAdvertisementDuration = maxReconnectAdvertisementDuration;
        mDefaultMtuSize = defaultMtuSize;
    }

    @Override
    public void start() {
        super.start();
        mTimeoutHandlerThread = new HandlerThread(TIMEOUT_HANDLER_THREAD_NAME);
        mTimeoutHandlerThread.start();
        mTimeoutHandler = new Handler(mTimeoutHandlerThread.getLooper());
    }

    @Override
    public void stop() {
        super.stop();
        if (mTimeoutHandlerThread != null) {
            mTimeoutHandlerThread.quit();
        }
        reset();
    }

    @Override
    public void disconnectDevice(@NonNull String deviceId) {
        if (deviceId.equals(mReconnectDeviceId)) {
            logd(TAG, "Reconnection canceled for device " + deviceId + ".");
            reset();
            return;
        }
        ConnectedRemoteDevice connectedDevice = getConnectedDevice();
        if (connectedDevice == null || !deviceId.equals(connectedDevice.mDeviceId)) {
            return;
        }
        reset();
    }

    @Override
    public AssociationCallback getAssociationCallback() {
        return mAssociationCallback;
    }

    @Override
    public void setAssociationCallback(AssociationCallback callback) {
        mAssociationCallback = callback;
    }

    @Override
    public void reset() {
        super.reset();
        logd(TAG, "Resetting state.");
        mBlePeripheralManager.cleanup();
        mReconnectDeviceId = null;
        mReconnectChallenge = null;
        mOobConnectionManager = null;
        mAssociationCallback = null;
    }

    @Override
    public void initiateConnectionToDevice(@NonNull UUID deviceId) {
        mReconnectDeviceId = deviceId.toString();
        mAdvertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                mTimeoutHandler.postDelayed(mTimeoutRunnable,
                        mMaxReconnectAdvertisementDuration.toMillis());
                logd(TAG, "Successfully started advertising for device " + deviceId + ".");
            }
        };
        mBlePeripheralManager.unregisterCallback(mAssociationPeripheralCallback);
        mBlePeripheralManager.registerCallback(mReconnectPeripheralCallback);
        mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
        byte[] advertiseData = createReconnectData(mReconnectDeviceId);
        if (advertiseData == null) {
            loge(TAG, "Unable to create advertisement data. Aborting reconnect.");
            return;
        }
        startAdvertising(mReconnectServiceUuid, mAdvertiseCallback, advertiseData,
                mReconnectDataUuid, /* scanResponse= */ null, /* scanResponseUuid= */ null);
    }

    /**
     * Create data for reconnection advertisement.
     *
     * <p></p><p>Process:</p>
     * <ol>
     * <li>Generate random {@value SALT_BYTES} byte salt and zero-pad to
     * {@value TOTAL_AD_DATA_BYTES} bytes.
     * <li>Hash with stored challenge secret and truncate to {@value TRUNCATED_BYTES} bytes.
     * <li>Concatenate hashed {@value TRUNCATED_BYTES} bytes with salt and return.
     * </ol>
     */
    @Nullable
    private byte[] createReconnectData(String deviceId) {
        byte[] salt = ByteUtils.randomBytes(SALT_BYTES);
        byte[] zeroPadded = ByteUtils.concatByteArrays(salt,
                new byte[TOTAL_AD_DATA_BYTES - SALT_BYTES]);
        mReconnectChallenge = mStorage.hashWithChallengeSecret(deviceId, zeroPadded);
        if (mReconnectChallenge == null) {
            return null;
        }
        return ByteUtils.concatByteArrays(Arrays.copyOf(mReconnectChallenge, TRUNCATED_BYTES),
                salt);

    }

    @Override
    public void startAssociation(@NonNull String nameForAssociation,
            @NonNull AssociationCallback callback) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            loge(TAG, "Bluetooth is unavailable on this device. Unable to start associating.");
            return;
        }

        reset();
        mAssociationCallback = callback;
        mBlePeripheralManager.unregisterCallback(mReconnectPeripheralCallback);
        mBlePeripheralManager.registerCallback(mAssociationPeripheralCallback);
        mAdvertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                callback.onAssociationStartSuccess(nameForAssociation);
                logd(TAG, "Successfully started advertising for association.");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                callback.onAssociationStartFailure();
                logd(TAG, "Failed to start advertising for association. Error code: " + errorCode);
            }
        };
        startAdvertising(mAssociationServiceUuid, mAdvertiseCallback, /* advertiseData= */null,
                /* advertiseDataUuid= */ null, nameForAssociation.getBytes(), mReconnectDataUuid);
    }

    /** Start the association with a new device using out of band verification code exchange */
    @Override
    public void startOutOfBandAssociation(
            @NonNull String nameForAssociation,
            @NonNull OobChannel oobChannel,
            @NonNull AssociationCallback callback) {

        logd(TAG, "Starting out of band association.");
        startAssociation(nameForAssociation, new AssociationCallback() {
            @Override
            public void onAssociationStartSuccess(String deviceName) {
                mAssociationCallback = callback;
                boolean success = mOobConnectionManager.startOobExchange(oobChannel);
                if (!success) {
                    callback.onAssociationStartFailure();
                    return;
                }
                callback.onAssociationStartSuccess(deviceName);
            }

            @Override
            public void onAssociationStartFailure() {
                callback.onAssociationStartFailure();
            }
        });
        mOobConnectionManager = new OobConnectionManager();
    }

    private void startAdvertising(@NonNull UUID serviceUuid, @NonNull AdvertiseCallback callback,
            @Nullable byte[] advertiseData,
            @Nullable UUID advertiseDataUuid, @Nullable byte[] scanResponse,
            @Nullable UUID scanResponseUuid) {
        BluetoothGattService gattService = new BluetoothGattService(serviceUuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        gattService.addCharacteristic(mWriteCharacteristic);
        gattService.addCharacteristic(mReadCharacteristic);

        AdvertiseData.Builder advertisementBuilder =
                new AdvertiseData.Builder();
        ParcelUuid uuid = new ParcelUuid(serviceUuid);
        advertisementBuilder.addServiceUuid(uuid);
        if (advertiseData != null) {
            ParcelUuid dataUuid = uuid;
            if (advertiseDataUuid != null) {
                dataUuid = new ParcelUuid(advertiseDataUuid);
            }
            advertisementBuilder.addServiceData(dataUuid, advertiseData);
        }

        AdvertiseData.Builder scanResponseBuilder =
                new AdvertiseData.Builder();
        if (scanResponse != null && scanResponseUuid != null) {
            ParcelUuid scanResponseParcelUuid = new ParcelUuid(scanResponseUuid);
            scanResponseBuilder.addServiceData(scanResponseParcelUuid, scanResponse);
        }

        mBlePeripheralManager.startAdvertising(gattService, advertisementBuilder.build(),
                scanResponseBuilder.build(), callback);
    }

    private void addConnectedDevice(BluetoothDevice device, boolean isReconnect) {
        addConnectedDevice(device, isReconnect, /* oobConnectionManager= */ null);
    }

    private void addConnectedDevice(@NonNull BluetoothDevice device, boolean isReconnect,
            @Nullable OobConnectionManager oobConnectionManager) {
        EventLog.onDeviceConnected();
        mBlePeripheralManager.stopAdvertising(mAdvertiseCallback);
        if (mTimeoutHandler != null) {
            mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
        }

        if (device.getName() == null) {
            logd(TAG, "Device connected, but name is null; issuing request to retrieve device "
                    + "name.");
            mBlePeripheralManager.retrieveDeviceName(device);
        } else {
            setClientDeviceName(device.getName());
        }
        setClientDeviceAddress(device.getAddress());

        BleDeviceMessageStream secureStream = new BleDeviceMessageStream(mBlePeripheralManager,
                device, mWriteCharacteristic, mReadCharacteristic,
                mDefaultMtuSize - ATT_PROTOCOL_BYTES);
        secureStream.setMessageReceivedErrorListener(
                exception -> {
                    disconnectWithError("Error occurred in stream: " + exception.getMessage());
                });
        SecureChannel secureChannel;
        if (isReconnect) {
            secureChannel = new ReconnectSecureChannel(secureStream, mStorage, mReconnectDeviceId,
                    mReconnectChallenge);
        } else if (oobConnectionManager != null) {
            secureChannel = new OobAssociationSecureChannel(secureStream, mStorage,
                    oobConnectionManager);
        } else {
            secureChannel = new AssociationSecureChannel(secureStream, mStorage);
        }
        secureChannel.registerCallback(mSecureChannelCallback);
        ConnectedRemoteDevice connectedDevice = new ConnectedRemoteDevice(device, /* gatt= */ null);
        connectedDevice.mSecureChannel = secureChannel;
        addConnectedDevice(connectedDevice);
        if (isReconnect) {
            setDeviceIdAndNotifyCallbacks(mReconnectDeviceId);
            mReconnectDeviceId = null;
            mReconnectChallenge = null;
        }
    }

    private void setMtuSize(int mtuSize) {
        ConnectedRemoteDevice connectedDevice = getConnectedDevice();
        if (connectedDevice != null
                && connectedDevice.mSecureChannel != null
                && connectedDevice.mSecureChannel.getStream() != null) {
            ((BleDeviceMessageStream) connectedDevice.mSecureChannel.getStream())
                    .setMaxWriteSize(mtuSize - ATT_PROTOCOL_BYTES);
        }
    }

    private final BlePeripheralManager.Callback mReconnectPeripheralCallback =
            new BlePeripheralManager.Callback() {

                @Override
                public void onDeviceNameRetrieved(String deviceName) {
                    // Ignored.
                }

                @Override
                public void onMtuSizeChanged(int size) {
                    setMtuSize(size);
                }

                @Override
                public void onRemoteDeviceConnected(BluetoothDevice device) {
                    addConnectedDevice(device, /* isReconnect= */ true);
                }

                @Override
                public void onRemoteDeviceDisconnected(BluetoothDevice device) {
                    String deviceId = mReconnectDeviceId;
                    ConnectedRemoteDevice connectedDevice = getConnectedDevice(device);
                    // Reset before invoking callbacks to avoid a race condition with reconnect
                    // logic.
                    reset();
                    if (connectedDevice != null) {
                        deviceId = connectedDevice.mDeviceId;
                    }
                    final String finalDeviceId = deviceId;
                    if (finalDeviceId == null) {
                        logw(TAG, "Callbacks were not issued for disconnect because the device id "
                                + "was null.");
                        return;
                    }
                    logd(TAG, "Connected device " + finalDeviceId + " disconnected.");
                    mCallbacks.invoke(callback -> callback.onDeviceDisconnected(finalDeviceId));
                }
            };

    private final BlePeripheralManager.Callback mAssociationPeripheralCallback =
            new BlePeripheralManager.Callback() {
                @Override
                public void onDeviceNameRetrieved(String deviceName) {
                    if (deviceName == null) {
                        return;
                    }
                    setClientDeviceName(deviceName);
                    ConnectedRemoteDevice connectedDevice = getConnectedDevice();
                    if (connectedDevice == null || connectedDevice.mDeviceId == null) {
                        return;
                    }
                    mStorage.updateAssociatedDeviceName(connectedDevice.mDeviceId, deviceName);
                }

                @Override
                public void onMtuSizeChanged(int size) {
                    setMtuSize(size);
                }

                @Override
                public void onRemoteDeviceConnected(BluetoothDevice device) {
                    addConnectedDevice(device, /* isReconnect= */ false, mOobConnectionManager);
                    ConnectedRemoteDevice connectedDevice = getConnectedDevice();
                    if (connectedDevice == null || connectedDevice.mSecureChannel == null) {
                        return;
                    }
                    ((AssociationSecureChannel) connectedDevice.mSecureChannel)
                            .setShowVerificationCodeListener(
                                    code -> {
                                        if (!isAssociating()) {
                                            loge(TAG, "No valid callback for association.");
                                            return;
                                        }
                                        mAssociationCallback.onVerificationCodeAvailable(code);
                                    });
                }

                @Override
                public void onRemoteDeviceDisconnected(BluetoothDevice device) {
                    logd(TAG, "Remote device disconnected.");
                    ConnectedRemoteDevice connectedDevice = getConnectedDevice(device);
                    if (isAssociating()) {
                        mAssociationCallback.onAssociationError(
                                DEVICE_ERROR_UNEXPECTED_DISCONNECTION);
                    }
                    // Reset before invoking callbacks to avoid a race condition with reconnect
                    // logic.
                    reset();
                    if (connectedDevice == null || connectedDevice.mDeviceId == null) {
                        logw(TAG, "Callbacks were not issued for disconnect.");
                        return;
                    }
                    mCallbacks.invoke(callback -> callback.onDeviceDisconnected(
                            connectedDevice.mDeviceId));
                }
            };

    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            logd(TAG, "Timeout period expired without a connection. Restarting advertisement.");
            mBlePeripheralManager.stopAdvertising(mAdvertiseCallback);
            connectToDevice(UUID.fromString(mReconnectDeviceId));
        }
    };
}
