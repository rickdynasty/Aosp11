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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.car.connecteddevice.util.ThreadSafeCallbacks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A generic class that handles all the Spp connection events including:
 * <ol>
 *     <li>listen and accept connection request from client.
 *     <li>send a message through an established connection.
 *     <li>notify any connection or message events happening during the connection.
 * </ol>
 */
public class SppManager {
    private static final String TAG = "SppManager";
    // Service names and UUIDs of SDP(Service Discovery Protocol) record, need to keep it consistent
    // among client and server.
    private final BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
    private final boolean mIsSecure;
    private Object mLock = new Object();
    /**
     * Task to listen to secure RFCOMM channel.
     */
    @VisibleForTesting
    AcceptTask mAcceptTask;
    /**
     * Task to start and maintain a connection.
     */
    @VisibleForTesting
    ConnectedTask mConnectedTask;
    @VisibleForTesting
    ExecutorService mConnectionExecutor = Executors.newSingleThreadExecutor();
    private BluetoothDevice mDevice;
    @GuardedBy("mLock")
    private final SppPayloadStream mPayloadStream = new SppPayloadStream();
    @GuardedBy("mLock")
    @VisibleForTesting
    ConnectionState mState;
    private final ThreadSafeCallbacks<ConnectionCallback> mCallbacks = new ThreadSafeCallbacks<>();
    private final ThreadSafeCallbacks<OnMessageReceivedListener> mReceivedListeners =
            new ThreadSafeCallbacks<>();

    public SppManager(@NonNull boolean isSecure) {
        mPayloadStream.setMessageCompletedListener(this::onMessageCompleted);
        mIsSecure = isSecure;
    }

    @VisibleForTesting
    enum ConnectionState {
        NONE,
        LISTEN,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
    }

    /**
     * Registers the given callback to be notified of various events within the {@link SppManager}.
     *
     * @param callback The callback to be notified.
     */
    void registerCallback(@NonNull ConnectionCallback callback, @NonNull Executor executor) {
        mCallbacks.add(callback, executor);
    }

    /**
     * Unregisters a previously registered callback.
     *
     * @param callback The callback to unregister.
     */
    void unregisterCallback(@NonNull ConnectionCallback callback) {
        mCallbacks.remove(callback);
    }

    /**
     * Adds a listener to be notified of a write to characteristics.
     *
     * @param listener The listener to invoke.
     */
    void addOnMessageReceivedListener(@NonNull OnMessageReceivedListener listener,
            @NonNull Executor executor) {
        mReceivedListeners.add(listener, executor);
    }

    /**
     * Removes the given listener from being notified of characteristic writes.
     *
     * @param listener The listener to remove.
     */
    void removeOnMessageReceivedListener(@NonNull OnMessageReceivedListener listener) {
        mReceivedListeners.remove(listener);
    }

    /**
     * Start listening to connection request from the client.
     *
     * @param serviceUuid The Uuid which the accept task is listening on.
     * @return {@code true} if listening is started successfully
     */
    boolean startListening(@NonNull UUID serviceUuid) {
        logd(TAG, "Start socket to listening to incoming connection request.");
        if (mConnectedTask != null) {
            mConnectedTask.cancel();
            mConnectedTask = null;
        }

        // Start the task to listen on a BluetoothServerSocket
        if (mAcceptTask != null) {
            mAcceptTask.cancel();
        }
        mAcceptTask = new AcceptTask(mAdapter, mIsSecure, serviceUuid, mAcceptTaskListener);
        if (!mAcceptTask.startListening()) {
            // TODO(b/159376003): Handle listening error.
            mAcceptTask.cancel();
            mAcceptTask = null;
            return false;
        }
        synchronized (mLock) {
            mState = ConnectionState.LISTEN;
        }
        mConnectionExecutor.execute(mAcceptTask);
        return true;
    }

    /**
     * Send data to remote connected bluetooth device.
     *
     * @param data the raw data that wait to be sent
     * @return {@code true} if the message is sent to client successfully.
     */
    boolean write(@NonNull byte[] data) {
        ConnectedTask connectedTask;
        // Synchronize a copy of the ConnectedTask
        synchronized (mLock) {
            if (mState != ConnectionState.CONNECTED) {
                loge(TAG, "Try to send data when device is disconnected");
                return false;
            }
            connectedTask = mConnectedTask;
        }
        byte[] dataReadyToSend = SppPayloadStream.wrapWithArrayLength(data);
        if (dataReadyToSend == null) {
            loge(TAG, "Wrapping data with array length failed.");
            return false;
        }
        connectedTask.write(dataReadyToSend);
        return true;
    }

    /**
     * Cleans up the registered listeners.
     */
    void cleanup() {
        // Clears all registered listeners. IHU only supports single connection.
        mReceivedListeners.clear();
    }

    /**
     * Start the ConnectedTask to begin and maintain a RFCOMM channel.
     *
     * @param socket   The BluetoothSocket on which the connection was made
     * @param device   The BluetoothDevice that has been connected
     * @param isSecure The type of current established channel
     */
    @GuardedBy("mLock")
    private void startConnectionLocked(BluetoothSocket socket, BluetoothDevice device,
            boolean isSecure) {
        logd(TAG, "Get accepted bluetooth socket, start listening to incoming messages.");

        // Cancel any task currently running a connection
        if (mConnectedTask != null) {
            mConnectedTask.cancel();
            mConnectedTask = null;
        }

        // Cancel the accept task because we only want to connect to one device
        if (mAcceptTask != null) {
            mAcceptTask.cancel();
            mAcceptTask = null;
        }
        logd(TAG, "Create ConnectedTask: is secure? " + isSecure);
        InputStream inputStream;
        OutputStream outputStream;
        mDevice = device;

        // Get the BluetoothSocket input and output streams
        try {
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
        } catch (IOException e) {
            loge(TAG, "Can not get stream from BluetoothSocket. Connection failed.", e);
            return;
        }
        mState = ConnectionState.CONNECTED;
        mCallbacks.invoke(callback -> callback.onRemoteDeviceConnected(device));

        // Start the task to manage the connection and perform transmissions
        mConnectedTask = new ConnectedTask(inputStream, outputStream, socket,
                mConnectedTaskCallback);
        mConnectionExecutor.execute(mConnectedTask);
    }

    private void onMessageCompleted(@NonNull byte[] message) {
        mReceivedListeners.invoke(listener -> listener.onMessageReceived(mDevice, message));
    }

    @VisibleForTesting
    final AcceptTask.OnTaskCompletedListener mAcceptTaskListener =
            new AcceptTask.OnTaskCompletedListener() {
                @Override
                public void onTaskCompleted(BluetoothSocket socket, boolean isSecure) {
                    if (socket == null) {
                        loge(TAG, "AcceptTask failed getting the socket");
                        return;
                    }
                    // Connection accepted
                    synchronized (mLock) {
                        switch (mState) {
                            case LISTEN:
                            case CONNECTING:
                                startConnectionLocked(socket, socket.getRemoteDevice(), isSecure);
                                break;
                            case NONE:
                                loge(TAG, "AcceptTask is done while in NONE state.");
                                break;
                            case CONNECTED:
                                // Already connected. Terminate new socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    loge(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                            case DISCONNECTED:
                                loge(TAG, "AcceptTask is done while in DISCONNECTED state.");
                                break;
                        }
                    }
                }
            };

    @VisibleForTesting
    final ConnectedTask.Callback mConnectedTaskCallback = new ConnectedTask.Callback() {
        @Override
        public void onMessageReceived(byte[] message) {
            synchronized (mLock) {
                try {
                    mPayloadStream.write(message);
                } catch (IOException e) {
                    loge(TAG, "Error writes message to spp payload stream: " + e.getMessage());
                }
            }
        }

        @Override
        public void onDisconnected() {
            synchronized (mLock) {
                mState = ConnectionState.DISCONNECTED;
                mCallbacks.invoke(callback -> callback.onRemoteDeviceDisconnected(mDevice));
            }
        }
    };

    /**
     * Interface to be notified of various events within the {@link SppManager}.
     */
    interface ConnectionCallback {

        /**
         * Triggered when a bluetooth device connected.
         *
         * @param device Remote device that connected on Spp.
         */
        void onRemoteDeviceConnected(@NonNull BluetoothDevice device);

        /**
         * Triggered when a bluetooth device disconnected.
         *
         * @param device Remote device that disconnected on Spp.
         */
        void onRemoteDeviceDisconnected(@NonNull BluetoothDevice device);
    }

    /**
     * An interface for classes that wish to be notified of incoming messages.
     */
    interface OnMessageReceivedListener {
        /**
         * Triggered when this SppManager receives a write request from a remote device.
         *
         * @param device The bluetooth device that sending the message.
         * @param value  The value that was written.
         */
        void onMessageReceived(@NonNull BluetoothDevice device, @NonNull byte[] value);
    }

}
