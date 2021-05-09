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

import static com.android.car.connecteddevice.ConnectedDeviceManager.DEVICE_ERROR_UNEXPECTED_DISCONNECTION;
import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;

import com.android.car.connecteddevice.AssociationCallback;
import com.android.car.connecteddevice.connection.AssociationSecureChannel;
import com.android.car.connecteddevice.connection.CarBluetoothManager;
import com.android.car.connecteddevice.connection.DeviceMessageStream;
import com.android.car.connecteddevice.connection.ReconnectSecureChannel;
import com.android.car.connecteddevice.connection.SecureChannel;
import com.android.car.connecteddevice.oob.OobChannel;
import com.android.car.connecteddevice.oob.OobConnectionManager;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.EventLog;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


/**
 * Communication manager that allows for targeted connections to a specific device from the car
 * using {@link SppManager} .
 */
public class CarSppManager extends CarBluetoothManager {

    private static final String TAG = "CarSppManager";

    private final SppManager mSppManager;

    private final UUID mAssociationServiceUuid;

    private final int mPacketMaxBytes;

    private String mReconnectDeviceId;

    private OobConnectionManager mOobConnectionManager;

    private Executor mCallbackExecutor;

    private AssociationCallback mAssociationCallback;

    /**
     * Initialize a new instance of manager.
     *
     * @param sppManager             {@link SppManager} for establishing connection.
     * @param connectedDeviceStorage Shared {@link ConnectedDeviceStorage} for companion features.
     * @param packetMaxBytes         Maximum size in bytes to write in one packet.
     */
    public CarSppManager(@NonNull SppManager sppManager,
            @NonNull ConnectedDeviceStorage connectedDeviceStorage,
            @NonNull UUID associationServiceUuid,
            int packetMaxBytes) {
        super(connectedDeviceStorage);
        mSppManager = sppManager;
        mCallbackExecutor = Executors.newSingleThreadExecutor();
        mAssociationServiceUuid = associationServiceUuid;
        mPacketMaxBytes = packetMaxBytes;
    }

    @Override
    public void stop() {
        super.stop();
        reset();
    }

    @Override
    public void disconnectDevice(@NonNull String deviceId) {
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
    public void initiateConnectionToDevice(@NonNull UUID deviceId) {
        logd(TAG, "Start spp reconnection listening for device with id: " + deviceId.toString());
        mReconnectDeviceId = deviceId.toString();
        mSppManager.unregisterCallback(mAssociationSppCallback);
        mSppManager.registerCallback(mReconnectSppCallback, mCallbackExecutor);
        mSppManager.startListening(deviceId);
    }

    @Override
    public void reset() {
        super.reset();
        mReconnectDeviceId = null;
        mAssociationCallback = null;
        mSppManager.cleanup();
    }

    /**
     * Start the association by listening to incoming connect request.
     */
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
        mSppManager.unregisterCallback(mReconnectSppCallback);
        mSppManager.registerCallback(mAssociationSppCallback, mCallbackExecutor);
        if (mSppManager.startListening(mAssociationServiceUuid)) {
            callback.onAssociationStartSuccess(/* deviceName= */ null);
        } else {
            callback.onAssociationStartFailure();
        }
    }

    /**
     * Start the association with a new device using out of band verification code exchange
     */
    @Override
    public void startOutOfBandAssociation(@NonNull String nameForAssociation,
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

    private void onDeviceConnected(BluetoothDevice device, boolean isReconnect) {
        onDeviceConnected(device, isReconnect, /* isOob= */ false);
    }

    private void onDeviceConnected(BluetoothDevice device, boolean isReconnect, boolean isOob) {
        EventLog.onDeviceConnected();
        setClientDeviceAddress(device.getAddress());
        setClientDeviceName(device.getName());
        DeviceMessageStream secureStream = new SppDeviceMessageStream(mSppManager, device,
                mPacketMaxBytes);
        secureStream.setMessageReceivedErrorListener(
                exception -> {
                    disconnectWithError("Error occurred in stream: " + exception.getMessage(),
                            exception);
                });
        SecureChannel secureChannel;
        // TODO(b/157492943): Define an out of band version of ReconnectSecureChannel
        if (isReconnect) {
            secureChannel = new ReconnectSecureChannel(secureStream, mStorage, mReconnectDeviceId,
                    /* expectedChallengeResponse= */ null);
        } else if (isOob) {
            // TODO(b/160901821): Integrate Oob with Spp channel
            loge(TAG, "Oob verification is currently not available for Spp");
            return;
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
        }
    }

    private final SppManager.ConnectionCallback mReconnectSppCallback =
            new SppManager.ConnectionCallback() {
                @Override
                public void onRemoteDeviceConnected(BluetoothDevice device) {
                    onDeviceConnected(device, /* isReconnect= */ true);
                }

                @Override
                public void onRemoteDeviceDisconnected(BluetoothDevice device) {
                    ConnectedRemoteDevice connectedDevice = getConnectedDevice(device);
                    // Reset before invoking callbacks to avoid a race condition with reconnect
                    // logic.
                    reset();
                    String deviceId = connectedDevice == null ? mReconnectDeviceId
                            : connectedDevice.mDeviceId;
                    if (deviceId != null) {
                        logd(TAG, "Connected device " + deviceId + " disconnected.");
                        mCallbacks.invoke(callback -> callback.onDeviceDisconnected(deviceId));
                    }
                }
            };

    private final SppManager.ConnectionCallback mAssociationSppCallback =
            new SppManager.ConnectionCallback() {
                @Override
                public void onRemoteDeviceConnected(BluetoothDevice device) {
                    onDeviceConnected(device, /* isReconnect= */ false);
                    ConnectedRemoteDevice connectedDevice = getConnectedDevice();
                    if (connectedDevice == null || connectedDevice.mSecureChannel == null) {
                        loge(TAG,
                                "No connected device or secure channel found when try to "
                                        + "associate.");
                        return;
                    }
                    ((AssociationSecureChannel) connectedDevice.mSecureChannel)
                            .setShowVerificationCodeListener(
                                    code -> {
                                        if (mAssociationCallback == null) {
                                            loge(TAG, "No valid callback for association.");
                                            return;
                                        }
                                        mAssociationCallback.onVerificationCodeAvailable(code);
                                    });
                }

                @Override
                public void onRemoteDeviceDisconnected(BluetoothDevice device) {
                    ConnectedRemoteDevice connectedDevice = getConnectedDevice(device);
                    if (isAssociating()) {
                        mAssociationCallback.onAssociationError(
                                DEVICE_ERROR_UNEXPECTED_DISCONNECTION);
                    }
                    // Reset before invoking callbacks to avoid a race condition with reconnect
                    // logic.
                    reset();
                    if (connectedDevice != null && connectedDevice.mDeviceId != null) {
                        mCallbacks.invoke(callback -> callback.onDeviceDisconnected(
                                connectedDevice.mDeviceId));
                    }
                }
            };
}
