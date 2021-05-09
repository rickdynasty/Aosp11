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

package com.android.car.connecteddevice;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.connecteddevice.connection.CarBluetoothManager;
import com.android.car.connecteddevice.connection.DeviceMessage;
import com.android.car.connecteddevice.model.AssociatedDevice;
import com.android.car.connecteddevice.model.ConnectedDevice;
import com.android.car.connecteddevice.model.OobEligibleDevice;
import com.android.car.connecteddevice.oob.BluetoothRfcommChannel;
import com.android.car.connecteddevice.oob.OobChannel;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage.AssociatedDeviceCallback;
import com.android.car.connecteddevice.util.ByteUtils;
import com.android.car.connecteddevice.util.EventLog;
import com.android.car.connecteddevice.util.ThreadSafeCallbacks;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Manager of devices connected to the car. */
public class ConnectedDeviceManager {

    private static final String TAG = "ConnectedDeviceManager";

    // Device name length is limited by available bytes in BLE advertisement data packet.
    //
    // BLE advertisement limits data packet length to 31
    // Currently we send:
    // - 18 bytes for 16 chars UUID: 16 bytes + 2 bytes for header;
    // - 3 bytes for advertisement being connectable;
    // which leaves 10 bytes.
    // Subtracting 2 bytes used by header, we have 8 bytes for device name.
    private static final int DEVICE_NAME_LENGTH_LIMIT = 8;

    private final ConnectedDeviceStorage mStorage;

    private final CarBluetoothManager mCarBluetoothManager;

    private final ThreadSafeCallbacks<DeviceAssociationCallback> mDeviceAssociationCallbacks =
            new ThreadSafeCallbacks<>();

    private final ThreadSafeCallbacks<ConnectionCallback> mActiveUserConnectionCallbacks =
            new ThreadSafeCallbacks<>();

    private final ThreadSafeCallbacks<ConnectionCallback> mAllUserConnectionCallbacks =
            new ThreadSafeCallbacks<>();

    // deviceId -> (recipientId -> callbacks)
    private final Map<String, Map<UUID, ThreadSafeCallbacks<DeviceCallback>>> mDeviceCallbacks =
            new ConcurrentHashMap<>();

    // deviceId -> device
    private final Map<String, ConnectedDevice> mConnectedDevices =
            new ConcurrentHashMap<>();

    // recipientId -> (deviceId -> message bytes)
    private final Map<UUID, Map<String, List<byte[]>>> mRecipientMissedMessages =
            new ConcurrentHashMap<>();

    // Recipient ids that received multiple callback registrations indicate that the recipient id
    // has been compromised. Another party now has access the messages intended for that recipient.
    // As a safeguard, that recipient id will be added to this list and blocked from further
    // callback notifications.
    private final Set<UUID> mBlacklistedRecipients = new CopyOnWriteArraySet<>();

    private final AtomicBoolean mIsConnectingToUserDevice = new AtomicBoolean(false);

    private final AtomicBoolean mHasStarted = new AtomicBoolean(false);

    private String mNameForAssociation;

    private AssociationCallback mAssociationCallback;

    private MessageDeliveryDelegate mMessageDeliveryDelegate;

    private OobChannel mOobChannel;

    @Retention(SOURCE)
    @IntDef({
            DEVICE_ERROR_INVALID_HANDSHAKE,
            DEVICE_ERROR_INVALID_MSG,
            DEVICE_ERROR_INVALID_DEVICE_ID,
            DEVICE_ERROR_INVALID_VERIFICATION,
            DEVICE_ERROR_INVALID_CHANNEL_STATE,
            DEVICE_ERROR_INVALID_ENCRYPTION_KEY,
            DEVICE_ERROR_STORAGE_FAILURE,
            DEVICE_ERROR_INVALID_SECURITY_KEY,
            DEVICE_ERROR_INSECURE_RECIPIENT_ID_DETECTED,
            DEVICE_ERROR_UNEXPECTED_DISCONNECTION
    })
    public @interface DeviceError {}

    public static final int DEVICE_ERROR_INVALID_HANDSHAKE = 0;
    public static final int DEVICE_ERROR_INVALID_MSG = 1;
    public static final int DEVICE_ERROR_INVALID_DEVICE_ID = 2;
    public static final int DEVICE_ERROR_INVALID_VERIFICATION = 3;
    public static final int DEVICE_ERROR_INVALID_CHANNEL_STATE = 4;
    public static final int DEVICE_ERROR_INVALID_ENCRYPTION_KEY = 5;
    public static final int DEVICE_ERROR_STORAGE_FAILURE = 6;
    public static final int DEVICE_ERROR_INVALID_SECURITY_KEY = 7;
    public static final int DEVICE_ERROR_INSECURE_RECIPIENT_ID_DETECTED = 8;
    public static final int DEVICE_ERROR_UNEXPECTED_DISCONNECTION = 9;

    public ConnectedDeviceManager(@NonNull CarBluetoothManager carBluetoothManager,
            @NonNull ConnectedDeviceStorage storage) {
        Executor callbackExecutor = Executors.newSingleThreadExecutor();
        mStorage = storage;
        mCarBluetoothManager = carBluetoothManager;
        mCarBluetoothManager.registerCallback(generateCarBleCallback(carBluetoothManager),
                callbackExecutor);
        mStorage.setAssociatedDeviceCallback(mAssociatedDeviceCallback);
        logd(TAG, "ConnectedDeviceManager created successfully.");
    }

    /**
     * Start internal processes and begin discovering devices. Must be called before any
     * connections can be made using {@link #connectToActiveUserDevice()}.
     */
    public void start() {
        if (mHasStarted.getAndSet(true)) {
            reset();
        } else {
            logd(TAG, "Starting ConnectedDeviceManager.");
            EventLog.onConnectedDeviceManagerStarted();
        }
        mCarBluetoothManager.start();
        connectToActiveUserDevice();
    }

    /** Reset internal processes and disconnect any active connections. */
    public void reset() {
        logd(TAG, "Resetting ConnectedDeviceManager.");
        for (ConnectedDevice device : mConnectedDevices.values()) {
            removeConnectedDevice(device.getDeviceId());
        }
        mCarBluetoothManager.stop();
        mIsConnectingToUserDevice.set(false);
        if (mOobChannel != null) {
            mOobChannel.interrupt();
            mOobChannel = null;
        }
        mAssociationCallback = null;
    }

    /** Returns {@link List<ConnectedDevice>} of devices currently connected. */
    @NonNull
    public List<ConnectedDevice> getActiveUserConnectedDevices() {
        List<ConnectedDevice> activeUserConnectedDevices = new ArrayList<>();
        for (ConnectedDevice device : mConnectedDevices.values()) {
            if (device.isAssociatedWithActiveUser()) {
                activeUserConnectedDevices.add(device);
            }
        }
        logd(TAG, "Returned " + activeUserConnectedDevices.size() + " active user devices.");
        return activeUserConnectedDevices;
    }

    /**
     * Register a callback for triggered associated device related events.
     *
     * @param callback {@link DeviceAssociationCallback} to register.
     * @param executor {@link Executor} to execute triggers on.
     */
    public void registerDeviceAssociationCallback(@NonNull DeviceAssociationCallback callback,
            @NonNull Executor executor) {
        mDeviceAssociationCallbacks.add(callback, executor);
    }

    /**
     * Unregister a device association callback.
     *
     * @param callback {@link DeviceAssociationCallback} to unregister.
     */
    public void unregisterDeviceAssociationCallback(@NonNull DeviceAssociationCallback callback) {
        mDeviceAssociationCallbacks.remove(callback);
    }

    /**
     * Register a callback for manager triggered connection events for only the currently active
     * user's devices.
     *
     * @param callback {@link ConnectionCallback} to register.
     * @param executor {@link Executor} to execute triggers on.
     */
    public void registerActiveUserConnectionCallback(@NonNull ConnectionCallback callback,
            @NonNull Executor executor) {
        mActiveUserConnectionCallbacks.add(callback, executor);
    }

    /**
     * Unregister a connection callback from manager.
     *
     * @param callback {@link ConnectionCallback} to unregister.
     */
    public void unregisterConnectionCallback(ConnectionCallback callback) {
        mActiveUserConnectionCallbacks.remove(callback);
        mAllUserConnectionCallbacks.remove(callback);
    }

    /** Connect to a device for the active user if available. */
    @VisibleForTesting
    void connectToActiveUserDevice() {
        Executors.defaultThreadFactory().newThread(() -> {
            logd(TAG, "Received request to connect to active user's device.");
            connectToActiveUserDeviceInternal();
        }).start();
    }

    private void connectToActiveUserDeviceInternal() {
        try {
            boolean isLockAcquired = mIsConnectingToUserDevice.compareAndSet(false, true);
            if (!isLockAcquired) {
                logd(TAG, "A request has already been made to connect to this user's device. "
                        + "Ignoring redundant request.");
                return;
            }
            List<AssociatedDevice> userDevices = mStorage.getActiveUserAssociatedDevices();
            if (userDevices.isEmpty()) {
                logw(TAG, "No devices associated with active user. Ignoring.");
                mIsConnectingToUserDevice.set(false);
                return;
            }

            // Only currently support one device per user for fast association, so take the
            // first one.
            AssociatedDevice userDevice = userDevices.get(0);
            if (!userDevice.isConnectionEnabled()) {
                logd(TAG, "Connection is disabled on device " + userDevice + ".");
                mIsConnectingToUserDevice.set(false);
                return;
            }
            if (mConnectedDevices.containsKey(userDevice.getDeviceId())) {
                logd(TAG, "Device has already been connected. No need to attempt connection "
                        + "again.");
                mIsConnectingToUserDevice.set(false);
                return;
            }
            EventLog.onStartDeviceSearchStarted();
            mCarBluetoothManager.connectToDevice(UUID.fromString(userDevice.getDeviceId()));
        } catch (Exception e) {
            loge(TAG, "Exception while attempting connection with active user's device.", e);
        }
    }

    /**
     * Start the association with a new device.
     *
     * @param callback Callback for association events.
     */
    public void startAssociation(@NonNull AssociationCallback callback) {
        mAssociationCallback = callback;
        Executors.defaultThreadFactory().newThread(() -> {
            logd(TAG, "Received request to start association.");
            mCarBluetoothManager.startAssociation(getNameForAssociation(),
                    mInternalAssociationCallback);
        }).start();
    }

    /**
     * Start association with an out of band device.
     *
     * @param device   The out of band eligible device.
     * @param callback Callback for association events.
     */
    public void startOutOfBandAssociation(@NonNull OobEligibleDevice device,
            @NonNull AssociationCallback callback) {
        logd(TAG, "Received request to start out of band association.");
        mAssociationCallback = callback;
        mOobChannel = new BluetoothRfcommChannel();
        mOobChannel.completeOobDataExchange(device, new OobChannel.Callback() {
            @Override
            public void onOobExchangeSuccess() {
                logd(TAG, "Out of band exchange succeeded. Proceeding to association with device.");
                Executors.defaultThreadFactory().newThread(() -> {
                    mCarBluetoothManager.startOutOfBandAssociation(
                            getNameForAssociation(),
                            mOobChannel, mInternalAssociationCallback);
                }).start();
            }

            @Override
            public void onOobExchangeFailure() {
                loge(TAG, "Out of band exchange failed.");
                mInternalAssociationCallback.onAssociationError(
                        DEVICE_ERROR_INVALID_ENCRYPTION_KEY);
                mOobChannel = null;
                mAssociationCallback = null;
            }
        });
    }

    /** Stop the association with any device. */
    public void stopAssociation(@NonNull AssociationCallback callback) {
        if (mAssociationCallback != callback) {
            logd(TAG, "Stop association called with unrecognized callback. Ignoring.");
            return;
        }
        logd(TAG, "Stopping association.");
        mAssociationCallback = null;
        mCarBluetoothManager.stopAssociation();
        if (mOobChannel != null) {
            mOobChannel.interrupt();
        }
        mOobChannel = null;
    }

    /**
     * Get a list of associated devices for the given user.
     *
     * @return Associated device list.
     */
    @NonNull
    public List<AssociatedDevice> getActiveUserAssociatedDevices() {
        return mStorage.getActiveUserAssociatedDevices();
    }

    /** Notify that the user has accepted a pairing code or any out-of-band confirmation. */
    public void notifyOutOfBandAccepted() {
        mCarBluetoothManager.notifyOutOfBandAccepted();
    }

    /**
     * Remove the associated device with the given device identifier for the current user.
     *
     * @param deviceId Device identifier.
     */
    public void removeActiveUserAssociatedDevice(@NonNull String deviceId) {
        mStorage.removeAssociatedDeviceForActiveUser(deviceId);
        disconnectDevice(deviceId);
    }

    /**
     * Enable connection on an associated device.
     *
     * @param deviceId Device identifier.
     */
    public void enableAssociatedDeviceConnection(@NonNull String deviceId) {
        logd(TAG, "enableAssociatedDeviceConnection() called on " + deviceId);
        mStorage.updateAssociatedDeviceConnectionEnabled(deviceId,
                /* isConnectionEnabled= */ true);

        connectToActiveUserDevice();
    }

    /**
     * Disable connection on an associated device.
     *
     * @param deviceId Device identifier.
     */
    public void disableAssociatedDeviceConnection(@NonNull String deviceId) {
        logd(TAG, "disableAssociatedDeviceConnection() called on " + deviceId);
        mStorage.updateAssociatedDeviceConnectionEnabled(deviceId,
                /* isConnectionEnabled= */ false);
        disconnectDevice(deviceId);
    }

    private void disconnectDevice(String deviceId) {
        ConnectedDevice device = mConnectedDevices.get(deviceId);
        if (device != null) {
            mCarBluetoothManager.disconnectDevice(deviceId);
            removeConnectedDevice(deviceId);
        }
    }

    /**
     * Register a callback for a specific device and recipient.
     *
     * @param device      {@link ConnectedDevice} to register triggers on.
     * @param recipientId {@link UUID} to register as recipient of.
     * @param callback    {@link DeviceCallback} to register.
     * @param executor    {@link Executor} on which to execute callback.
     */
    public void registerDeviceCallback(@NonNull ConnectedDevice device, @NonNull UUID recipientId,
            @NonNull DeviceCallback callback, @NonNull Executor executor) {
        if (isRecipientBlacklisted(recipientId)) {
            notifyOfBlacklisting(device, recipientId, callback, executor);
            return;
        }
        logd(TAG, "New callback registered on device " + device.getDeviceId() + " for recipient "
                + recipientId);
        String deviceId = device.getDeviceId();
        Map<UUID, ThreadSafeCallbacks<DeviceCallback>> recipientCallbacks =
                mDeviceCallbacks.computeIfAbsent(deviceId, key -> new ConcurrentHashMap<>());

        // Device already has a callback registered with this recipient UUID. For the
        // protection of the user, this UUID is now blacklisted from future subscriptions
        // and the original subscription is notified and removed.
        if (recipientCallbacks.containsKey(recipientId)) {
            blacklistRecipient(deviceId, recipientId);
            notifyOfBlacklisting(device, recipientId, callback, executor);
            return;
        }

        ThreadSafeCallbacks<DeviceCallback> newCallbacks = new ThreadSafeCallbacks<>();
        newCallbacks.add(callback, executor);
        recipientCallbacks.put(recipientId, newCallbacks);

        List<byte[]> messages = popMissedMessages(recipientId, device.getDeviceId());
        if (messages != null) {
            for (byte[] message : messages) {
                newCallbacks.invoke(deviceCallback ->
                        deviceCallback.onMessageReceived(device, message));
            }
        }
    }

    /**
     * Set the delegate for message delivery operations.
     *
     * @param delegate The {@link MessageDeliveryDelegate} to set. {@code null} to unset.
     */
    public void setMessageDeliveryDelegate(@Nullable MessageDeliveryDelegate delegate) {
        mMessageDeliveryDelegate = delegate;
    }

    private void notifyOfBlacklisting(@NonNull ConnectedDevice device, @NonNull UUID recipientId,
            @NonNull DeviceCallback callback, @NonNull Executor executor) {
        loge(TAG, "Multiple callbacks registered for recipient " + recipientId + "! Your "
                + "recipient id is no longer secure and has been blocked from future use.");
        executor.execute(() ->
                callback.onDeviceError(device, DEVICE_ERROR_INSECURE_RECIPIENT_ID_DETECTED));
    }

    private void saveMissedMessage(@NonNull String deviceId, @NonNull UUID recipientId,
            @NonNull byte[] message) {
        // Store last message in case recipient registers callbacks in the future.
        logd(TAG, "No recipient registered for device " + deviceId + " and recipient "
                + recipientId + " combination. Saving message.");
        mRecipientMissedMessages.computeIfAbsent(recipientId, __ -> new HashMap<>())
                .computeIfAbsent(deviceId, __ -> new ArrayList<>()).add(message);
    }

    /**
     * Remove the last message sent for this device prior to a {@link DeviceCallback} being
     * registered.
     *
     * @param recipientId Recipient's id
     * @param deviceId    Device id
     * @return The missed {@code byte[]} messages, or {@code null} if no messages were
     * missed.
     */
    @Nullable
    private List<byte[]> popMissedMessages(@NonNull UUID recipientId, @NonNull String deviceId) {
        Map<String, List<byte[]>> missedMessages = mRecipientMissedMessages.get(recipientId);
        if (missedMessages == null) {
            return null;
        }

        return missedMessages.remove(deviceId);
    }

    /**
     * Unregister callback from device events.
     *
     * @param device      {@link ConnectedDevice} callback was registered on.
     * @param recipientId {@link UUID} callback was registered under.
     * @param callback    {@link DeviceCallback} to unregister.
     */
    public void unregisterDeviceCallback(@NonNull ConnectedDevice device,
            @NonNull UUID recipientId, @NonNull DeviceCallback callback) {
        logd(TAG, "Device callback unregistered on device " + device.getDeviceId() + " for "
                + "recipient " + recipientId + ".");

        Map<UUID, ThreadSafeCallbacks<DeviceCallback>> recipientCallbacks =
                mDeviceCallbacks.get(device.getDeviceId());
        if (recipientCallbacks == null) {
            return;
        }
        ThreadSafeCallbacks<DeviceCallback> callbacks = recipientCallbacks.get(recipientId);
        if (callbacks == null) {
            return;
        }

        callbacks.remove(callback);
        if (callbacks.size() == 0) {
            recipientCallbacks.remove(recipientId);
        }
    }

    /**
     * Securely send message to a device.
     *
     * @param device      {@link ConnectedDevice} to send the message to.
     * @param recipientId Recipient {@link UUID}.
     * @param message     Message to send.
     * @throws IllegalStateException Secure channel has not been established.
     */
    public void sendMessageSecurely(@NonNull ConnectedDevice device, @NonNull UUID recipientId,
            @NonNull byte[] message) throws IllegalStateException {
        sendMessage(device, recipientId, message, /* isEncrypted= */ true);
    }

    /**
     * Send an unencrypted message to a device.
     *
     * @param device      {@link ConnectedDevice} to send the message to.
     * @param recipientId Recipient {@link UUID}.
     * @param message     Message to send.
     */
    public void sendMessageUnsecurely(@NonNull ConnectedDevice device, @NonNull UUID recipientId,
            @NonNull byte[] message) {
        sendMessage(device, recipientId, message, /* isEncrypted= */ false);
    }

    private void sendMessage(@NonNull ConnectedDevice device, @NonNull UUID recipientId,
            @NonNull byte[] message, boolean isEncrypted) throws IllegalStateException {
        String deviceId = device.getDeviceId();
        logd(TAG, "Sending new message to device " + deviceId + " for " + recipientId
                + " containing " + message.length + ". Message will be sent securely: "
                + isEncrypted + ".");

        ConnectedDevice connectedDevice = mConnectedDevices.get(deviceId);
        if (connectedDevice == null) {
            loge(TAG, "Attempted to send message to unknown device " + deviceId + ". Ignoring.");
            return;
        }

        if (isEncrypted && !connectedDevice.hasSecureChannel()) {
            throw new IllegalStateException("Cannot send a message securely to device that has not "
                    + "established a secure channel.");
        }

        mCarBluetoothManager.sendMessage(deviceId, new DeviceMessage(recipientId, isEncrypted,
                message));
    }

    private boolean isRecipientBlacklisted(UUID recipientId) {
        return mBlacklistedRecipients.contains(recipientId);
    }

    private void blacklistRecipient(@NonNull String deviceId, @NonNull UUID recipientId) {
        Map<UUID, ThreadSafeCallbacks<DeviceCallback>> recipientCallbacks =
                mDeviceCallbacks.get(deviceId);
        if (recipientCallbacks == null) {
            // Should never happen, but null-safety check.
            return;
        }

        ThreadSafeCallbacks<DeviceCallback> existingCallback = recipientCallbacks.get(recipientId);
        if (existingCallback == null) {
            // Should never happen, but null-safety check.
            return;
        }

        ConnectedDevice connectedDevice = mConnectedDevices.get(deviceId);
        if (connectedDevice != null) {
            recipientCallbacks.get(recipientId).invoke(
                    callback ->
                            callback.onDeviceError(connectedDevice,
                                    DEVICE_ERROR_INSECURE_RECIPIENT_ID_DETECTED)
            );
        }

        recipientCallbacks.remove(recipientId);
        mBlacklistedRecipients.add(recipientId);
    }

    @VisibleForTesting
    void addConnectedDevice(@NonNull String deviceId) {
        if (mConnectedDevices.containsKey(deviceId)) {
            // Device already connected. No-op until secure channel established.
            return;
        }
        logd(TAG, "New device with id " + deviceId + " connected.");
        ConnectedDevice connectedDevice = new ConnectedDevice(
                deviceId,
                /* deviceName= */ null,
                mStorage.getActiveUserAssociatedDeviceIds().contains(deviceId),
                /* hasSecureChannel= */ false
        );

        mConnectedDevices.put(deviceId, connectedDevice);
        invokeConnectionCallbacks(connectedDevice.isAssociatedWithActiveUser(),
                callback -> callback.onDeviceConnected(connectedDevice));
    }

    @VisibleForTesting
    void removeConnectedDevice(@NonNull String deviceId) {
        logd(TAG, "Device " + deviceId + " disconnected.");
        ConnectedDevice connectedDevice = mConnectedDevices.get(deviceId);
        mIsConnectingToUserDevice.set(false);
        if (connectedDevice == null) {
            return;
        }

        mConnectedDevices.remove(deviceId);
        boolean isAssociated = connectedDevice.isAssociatedWithActiveUser();
        invokeConnectionCallbacks(isAssociated,
                callback -> callback.onDeviceDisconnected(connectedDevice));

        if (isAssociated || mConnectedDevices.isEmpty()) {
            // Try to regain connection to active user's device.
            connectToActiveUserDevice();
        }
    }

    @VisibleForTesting
    void onSecureChannelEstablished(@NonNull String deviceId) {
        if (mConnectedDevices.get(deviceId) == null) {
            loge(TAG, "Secure channel established on unknown device " + deviceId + ".");
            return;
        }
        ConnectedDevice connectedDevice = mConnectedDevices.get(deviceId);
        ConnectedDevice updatedConnectedDevice = new ConnectedDevice(connectedDevice.getDeviceId(),
                connectedDevice.getDeviceName(), connectedDevice.isAssociatedWithActiveUser(),
                /* hasSecureChannel= */ true);

        boolean notifyCallbacks = mConnectedDevices.get(deviceId) != null;

        // TODO (b/143088482) Implement interrupt
        // Ignore if central already holds the active device connection and interrupt the
        // connection.

        mConnectedDevices.put(deviceId, updatedConnectedDevice);
        logd(TAG, "Secure channel established to " + deviceId + " . Notifying callbacks: "
                + notifyCallbacks + ".");
        if (notifyCallbacks) {
            notifyAllDeviceCallbacks(deviceId,
                    callback -> callback.onSecureChannelEstablished(updatedConnectedDevice));
        }
    }

    @VisibleForTesting
    void onMessageReceived(@NonNull String deviceId, @NonNull DeviceMessage message) {
        logd(TAG, "New message received from device " + deviceId + " intended for "
                + message.getRecipient() + " containing " + message.getMessage().length
                + " bytes.");

        ConnectedDevice connectedDevice = mConnectedDevices.get(deviceId);
        if (connectedDevice == null) {
            logw(TAG, "Received message from unknown device " + deviceId + "or to unknown "
                    + "recipient " + message.getRecipient() + ".");
            return;
        }

        if (mMessageDeliveryDelegate != null
                && !mMessageDeliveryDelegate.shouldDeliverMessageForDevice(connectedDevice)) {
            logw(TAG, "The message delegate has rejected this message. It will not be "
                    + "delivered to the intended recipient.");
            return;
        }

        UUID recipientId = message.getRecipient();
        Map<UUID, ThreadSafeCallbacks<DeviceCallback>> deviceCallbacks =
                mDeviceCallbacks.get(deviceId);
        if (deviceCallbacks == null) {
            saveMissedMessage(deviceId, recipientId, message.getMessage());
            return;
        }
        ThreadSafeCallbacks<DeviceCallback> recipientCallbacks =
                deviceCallbacks.get(recipientId);
        if (recipientCallbacks == null) {
            saveMissedMessage(deviceId, recipientId, message.getMessage());
            return;
        }

        recipientCallbacks.invoke(
                callback -> callback.onMessageReceived(connectedDevice, message.getMessage()));
    }

    @VisibleForTesting
    void deviceErrorOccurred(@NonNull String deviceId) {
        ConnectedDevice connectedDevice = mConnectedDevices.get(deviceId);
        if (connectedDevice == null) {
            logw(TAG, "Failed to establish secure channel on unknown device " + deviceId + ".");
            return;
        }

        notifyAllDeviceCallbacks(deviceId, callback -> callback.onDeviceError(connectedDevice,
                        DEVICE_ERROR_INVALID_SECURITY_KEY));
    }

    @VisibleForTesting
    void onAssociationCompleted(@NonNull String deviceId) {
        ConnectedDevice connectedDevice = mConnectedDevices.get(deviceId);
        if (connectedDevice == null) {
            return;
        }

        // The previous device is now obsolete and should be replaced with a new one properly
        // reflecting the state of belonging to the active user and notify features.
        if (connectedDevice.isAssociatedWithActiveUser()) {
            // Device was already marked as belonging to active user. No need to reissue callbacks.
            return;
        }
        removeConnectedDevice(deviceId);
        addConnectedDevice(deviceId);
    }

    @NonNull
    private List<String> getActiveUserDeviceIds() {
        return mStorage.getActiveUserAssociatedDeviceIds();
    }

    private void invokeConnectionCallbacks(boolean belongsToActiveUser,
            @NonNull Consumer<ConnectionCallback> notification) {
        logd(TAG, "Notifying connection callbacks for device belonging to active user "
                + belongsToActiveUser + ".");
        if (belongsToActiveUser) {
            mActiveUserConnectionCallbacks.invoke(notification);
        }
        mAllUserConnectionCallbacks.invoke(notification);
    }

    private void notifyAllDeviceCallbacks(@NonNull String deviceId,
            @NonNull Consumer<DeviceCallback> notification) {
        logd(TAG, "Notifying all device callbacks for device " + deviceId + ".");
        Map<UUID, ThreadSafeCallbacks<DeviceCallback>> deviceCallbacks =
                mDeviceCallbacks.get(deviceId);
        if (deviceCallbacks == null) {
            return;
        }

        for (ThreadSafeCallbacks<DeviceCallback> callbacks : deviceCallbacks.values()) {
            callbacks.invoke(notification);
        }
    }

    /**
     * Returns the name that should be used for the device during enrollment of a trusted device.
     *
     * <p>The returned name will be a combination of a prefix sysprop and randomized digits.
     */
    @NonNull
    private String getNameForAssociation() {
        if (mNameForAssociation == null) {
            mNameForAssociation = ByteUtils.generateRandomNumberString(DEVICE_NAME_LENGTH_LIMIT);
        }
        return mNameForAssociation;
    }

    @NonNull
    private CarBluetoothManager.Callback generateCarBleCallback(
            @NonNull CarBluetoothManager carBluetoothManager) {
        return new CarBluetoothManager.Callback() {
            @Override
            public void onDeviceConnected(String deviceId) {
                EventLog.onDeviceIdReceived();
                addConnectedDevice(deviceId);
            }

            @Override
            public void onDeviceDisconnected(String deviceId) {
                removeConnectedDevice(deviceId);
            }

            @Override
            public void onSecureChannelEstablished(String deviceId) {
                EventLog.onSecureChannelEstablished();
                ConnectedDeviceManager.this.onSecureChannelEstablished(deviceId);
            }

            @Override
            public void onMessageReceived(String deviceId, DeviceMessage message) {
                ConnectedDeviceManager.this.onMessageReceived(deviceId, message);
            }

            @Override
            public void onSecureChannelError(String deviceId) {
                deviceErrorOccurred(deviceId);
            }
        };
    }

    private final AssociationCallback mInternalAssociationCallback = new AssociationCallback() {
        @Override
        public void onAssociationStartSuccess(String deviceName) {
            if (mAssociationCallback != null) {
                mAssociationCallback.onAssociationStartSuccess(deviceName);
            }
        }

        @Override
        public void onAssociationStartFailure() {
            if (mAssociationCallback != null) {
                mAssociationCallback.onAssociationStartFailure();
            }
        }

        @Override
        public void onAssociationError(int error) {
            if (mAssociationCallback != null) {
                mAssociationCallback.onAssociationError(error);
            }
        }

        @Override
        public void onVerificationCodeAvailable(String code) {
            if (mAssociationCallback != null) {
                mAssociationCallback.onVerificationCodeAvailable(code);
            }
        }

        @Override
        public void onAssociationCompleted(String deviceId) {
            if (mAssociationCallback != null) {
                mAssociationCallback.onAssociationCompleted(deviceId);
            }
            ConnectedDeviceManager.this.onAssociationCompleted(deviceId);
        }
    };

    private final AssociatedDeviceCallback mAssociatedDeviceCallback =
            new AssociatedDeviceCallback() {
                @Override
                public void onAssociatedDeviceAdded(AssociatedDevice device) {
                    mDeviceAssociationCallbacks.invoke(callback ->
                            callback.onAssociatedDeviceAdded(device));
                }

                @Override
                public void onAssociatedDeviceRemoved(AssociatedDevice device) {
                    mDeviceAssociationCallbacks.invoke(callback ->
                            callback.onAssociatedDeviceRemoved(device));
                    logd(TAG, "Successfully removed associated device " + device + ".");
                }

                @Override
                public void onAssociatedDeviceUpdated(AssociatedDevice device) {
                    mDeviceAssociationCallbacks.invoke(callback ->
                            callback.onAssociatedDeviceUpdated(device));
                }
            };

    /** Callback for triggered connection events from {@link ConnectedDeviceManager}. */
    public interface ConnectionCallback {
        /** Triggered when a new device has connected. */
        void onDeviceConnected(@NonNull ConnectedDevice device);

        /** Triggered when a device has disconnected. */
        void onDeviceDisconnected(@NonNull ConnectedDevice device);
    }

    /** Triggered device events for a connected device from {@link ConnectedDeviceManager}. */
    public interface DeviceCallback {
        /**
         * Triggered when secure channel has been established on a device. Encrypted messaging now
         * available.
         */
        void onSecureChannelEstablished(@NonNull ConnectedDevice device);

        /** Triggered when a new message is received from a device. */
        void onMessageReceived(@NonNull ConnectedDevice device, @NonNull byte[] message);

        /** Triggered when an error has occurred for a device. */
        void onDeviceError(@NonNull ConnectedDevice device, @DeviceError int error);
    }

    /** Callback for association device related events. */
    public interface DeviceAssociationCallback {

        /** Triggered when an associated device has been added. */
        void onAssociatedDeviceAdded(@NonNull AssociatedDevice device);

        /** Triggered when an associated device has been removed. */
        void onAssociatedDeviceRemoved(@NonNull AssociatedDevice device);

        /** Triggered when the name of an associated device has been updated. */
        void onAssociatedDeviceUpdated(@NonNull AssociatedDevice device);
    }

    /** Delegate for message delivery operations. */
    public interface MessageDeliveryDelegate {

        /** Indicate whether a message should be delivered for the specified device. */
        boolean shouldDeliverMessageForDevice(@NonNull ConnectedDevice device);
    }
}
