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

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.car.connecteddevice.connection.DeviceMessageStream;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Spp message stream to a device.
 */
class SppDeviceMessageStream extends DeviceMessageStream {

    private static final String TAG = "SppDeviceMessageStream";

    private final SppManager mSppManager;
    private final BluetoothDevice mDevice;
    private final Executor mCallbackExecutor = Executors.newSingleThreadExecutor();


    SppDeviceMessageStream(@NonNull SppManager sppManager,
            @NonNull BluetoothDevice device, int maxWriteSize) {
        super(maxWriteSize);
        mSppManager = sppManager;
        mDevice = device;
        mSppManager.addOnMessageReceivedListener(this::onMessageReceived, mCallbackExecutor);
    }

    @Override
    protected void send(byte[] data) {
        mSppManager.write(data);
        sendCompleted();
    }

    @VisibleForTesting
    void onMessageReceived(@NonNull BluetoothDevice device, @NonNull byte[] value) {
        logd(TAG, "Received a message from a device (" + device.getAddress() + ").");
        if (!mDevice.equals(device)) {
            logw(TAG, "Received a message from a device (" + device.getAddress() + ") that is not "
                    + "the expected device (" + mDevice.getAddress() + ") registered to this "
                    + "stream. Ignoring.");
            return;
        }

        onDataReceived(value);
    }
}
