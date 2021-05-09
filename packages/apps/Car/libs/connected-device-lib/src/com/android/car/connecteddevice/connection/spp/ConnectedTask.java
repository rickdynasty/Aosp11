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
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logi;

import android.bluetooth.BluetoothSocket;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This task runs during a connection with a remote device. It handles all incoming and outgoing
 * data.
 */
public class ConnectedTask implements Runnable {
    private static final String TAG = "ConnectedTask";
    private final BluetoothSocket mSocket;
    private final InputStream mInputStream;
    private final OutputStream mOutputStream;
    private Callback mCallback;

    ConnectedTask(@NonNull InputStream inputStream, @NonNull OutputStream outputStream,
            @NonNull BluetoothSocket socket,
            @NonNull Callback callback) {
        mInputStream = inputStream;
        mOutputStream = outputStream;
        mSocket = socket;
        mCallback = callback;
    }

    @Override
    public void run() {
        logi(TAG, "Begin ConnectedTask: started to listen to incoming messages.");
        // Keep listening to the InputStream when task started.
        while (true) {
            try {
                int dataLength = mInputStream.available();
                if (dataLength == 0) {
                    continue;
                }
                byte[] buffer = new byte[dataLength];
                // Read from the InputStream
                mInputStream.read(buffer);
                mCallback.onMessageReceived(buffer);
                logd(TAG, "received raw bytes from remote device with length: " + dataLength);
            } catch (IOException e) {
                loge(TAG,
                        "Encountered an exception when listening for incoming message, "
                                + "disconnected", e);
                mCallback.onDisconnected();
                break;
            }
        }
    }

    /**
     * Write to the connected OutputStream.
     *
     * @param buffer The bytes to write
     */
    void write(@NonNull byte[] buffer) {
        try {
            mOutputStream.write(buffer);
            logd(TAG, "Sent buffer to remote device with length: " + buffer.length);
        } catch (IOException e) {
            loge(TAG, "Exception during write", e);
        }
    }

    void cancel() {
        logd(TAG, "cancel connected task: close connected socket.");
        try {
            mSocket.close();
        } catch (IOException e) {
            loge(TAG, "close() of connected socket failed", e);
        }
    }

    interface Callback {
        void onMessageReceived(@NonNull byte[] message);

        void onDisconnected();
    }
}
