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

package com.android.car.connecteddevice.connection.ble;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.android.car.connecteddevice.connection.DeviceMessageStream;

import java.util.concurrent.atomic.AtomicLong;

/** BLE message stream to a device. */
public class BleDeviceMessageStream extends DeviceMessageStream {

    private static final String TAG = "BleDeviceMessageStream";

    /*
     * During bandwidth testing, it was discovered that allowing the stream to send as fast as it
     * can blocked outgoing notifications from being received by the connected device. Adding a
     * throttle to the outgoing messages alleviated this block and allowed both sides to
     * send/receive in parallel successfully.
     */
    private static final long THROTTLE_DEFAULT_MS = 10L;
    private static final long THROTTLE_WAIT_MS = 75L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final AtomicLong mThrottleDelay = new AtomicLong(THROTTLE_DEFAULT_MS);

    private final BlePeripheralManager mBlePeripheralManager;

    private final BluetoothDevice mDevice;

    private final BluetoothGattCharacteristic mWriteCharacteristic;

    private final BluetoothGattCharacteristic mReadCharacteristic;

    BleDeviceMessageStream(@NonNull BlePeripheralManager blePeripheralManager,
            @NonNull BluetoothDevice device,
            @NonNull BluetoothGattCharacteristic writeCharacteristic,
            @NonNull BluetoothGattCharacteristic readCharacteristic,
            int defaultMaxWriteSize) {
        super(defaultMaxWriteSize);
        mBlePeripheralManager = blePeripheralManager;
        mDevice = device;
        mWriteCharacteristic = writeCharacteristic;
        mReadCharacteristic = readCharacteristic;
        mBlePeripheralManager.addOnCharacteristicWriteListener(this::onCharacteristicWrite);
        mBlePeripheralManager.addOnCharacteristicReadListener(this::onCharacteristicRead);
    }

    @Override
    protected void send(byte[] data) {
        mWriteCharacteristic.setValue(data);
        mBlePeripheralManager.notifyCharacteristicChanged(mDevice, mWriteCharacteristic,
                /* confirm= */ false);
    }

    private void onCharacteristicRead(@NonNull BluetoothDevice device) {
        if (!mDevice.equals(device)) {
            logw(TAG, "Received a read notification from a device (" + device.getAddress()
                    + ") that is not the expected device (" + mDevice.getAddress() + ") registered "
                    + "to this stream. Ignoring.");
            return;
        }

        logd(TAG, "Releasing lock on characteristic.");
        sendCompleted();
    }

    private void onCharacteristicWrite(@NonNull BluetoothDevice device,
            @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
        logd(TAG, "Received a message from a device (" + device.getAddress() + ").");
        if (!mDevice.equals(device)) {
            logw(TAG, "Received a message from a device (" + device.getAddress() + ") that is not "
                    + "the expected device (" + mDevice.getAddress() + ") registered to this "
                    + "stream. Ignoring.");
            return;
        }

        if (!characteristic.getUuid().equals(mReadCharacteristic.getUuid())) {
            logw(TAG, "Received a write to a characteristic (" + characteristic.getUuid() + ") that"
                    + " is not the expected UUID (" + mReadCharacteristic.getUuid() + "). "
                    + "Ignoring.");
            return;
        }
        onDataReceived(value);
    }
}
