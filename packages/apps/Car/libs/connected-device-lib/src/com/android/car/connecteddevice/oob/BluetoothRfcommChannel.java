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

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.connecteddevice.model.OobEligibleDevice;


import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles out of band data exchange over a secure RFCOMM channel.
 */
public class BluetoothRfcommChannel implements OobChannel {
    private static final String TAG = "BluetoothRfcommChannel";
    // TODO (b/159500330) Generate random UUID.
    private static final UUID RFCOMM_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final Duration CONNECT_RETRY_WAIT = Duration.ofSeconds(1);
    private BluetoothSocket mBluetoothSocket;
    private AtomicBoolean mIsInterrupted = new AtomicBoolean();
    @VisibleForTesting
    Callback mCallback;

    @Override
    public void completeOobDataExchange(@NonNull OobEligibleDevice device,
            @NonNull Callback callback) {
        completeOobDataExchange(device, callback, BluetoothAdapter.getDefaultAdapter());
    }

    @VisibleForTesting
    void completeOobDataExchange(OobEligibleDevice device, Callback callback,
            BluetoothAdapter bluetoothAdapter) {
        mCallback = callback;

        BluetoothDevice remoteDevice = bluetoothAdapter.getRemoteDevice(device.getDeviceAddress());

        try {
            mBluetoothSocket = remoteDevice.createRfcommSocketToServiceRecord(RFCOMM_UUID);
        } catch (IOException e) {
            notifyFailure("Rfcomm socket creation with " + remoteDevice.getName() + " failed.", e);
            return;
        }

        bluetoothAdapter.cancelDiscovery();

        new Thread() {
            @Override
            public void run() {
                while (!isInterrupted()) {
                    try {
                        mBluetoothSocket.connect();
                        break;
                    } catch (IOException e) {
                        logd(TAG, "Unable to connect, trying again in "
                                + CONNECT_RETRY_WAIT.toMillis() + " ms.");
                    }
                    try {
                        Thread.sleep(CONNECT_RETRY_WAIT.toMillis());
                    } catch (InterruptedException e) {
                        loge(TAG, "Thread was interrupted before connection could be made.", e);
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                notifySuccess();
            }
        }.start();
    }

    @Override
    public void sendOobData(byte[] oobData) {
        if (isInterrupted()) {
            return;
        }
        if (mBluetoothSocket == null) {
            notifyFailure("Bluetooth socket is null, oob data cannot be sent",
                    /* exception= */ null);
            return;
        }
        try {
            OutputStream stream = mBluetoothSocket.getOutputStream();
            stream.write(oobData);
            stream.flush();
            stream.close();
        } catch (IOException e) {
            notifyFailure("Sending oob data failed", e);
        }
    }

    @Override
    public void interrupt() {
        logd(TAG, "Interrupt received.");
        mIsInterrupted.set(true);
    }

    @VisibleForTesting
    boolean isInterrupted() {
        if (!mIsInterrupted.get()) {
            return false;
        }

        if (mBluetoothSocket == null) {
            return true;
        }

        try {
            OutputStream stream = mBluetoothSocket.getOutputStream();
            stream.flush();
            stream.close();
        } catch (IOException e) {
            loge(TAG, "Unable to clean up bluetooth socket on interrupt.", e);
        }

        mBluetoothSocket = null;
        return true;
    }

    private void notifyFailure(@NonNull String message, @Nullable Exception exception) {
        loge(TAG, message, exception);
        if (mCallback != null && !isInterrupted()) {
            mCallback.onOobExchangeFailure();
        }
    }

    private void notifySuccess() {
        if (mCallback != null && !isInterrupted()) {
            mCallback.onOobExchangeSuccess();
        }
    }
}
