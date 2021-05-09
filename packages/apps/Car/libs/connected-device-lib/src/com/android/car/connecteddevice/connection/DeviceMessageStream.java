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

package com.android.car.connecteddevice.connection;

import static com.android.car.connecteddevice.util.SafeLog.logd;
import static com.android.car.connecteddevice.util.SafeLog.loge;
import static com.android.car.connecteddevice.util.SafeLog.logw;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.connecteddevice.StreamProtos.DeviceMessageProto.Message;
import com.android.car.connecteddevice.StreamProtos.OperationProto.OperationType;
import com.android.car.connecteddevice.StreamProtos.PacketProto.Packet;
import com.android.car.connecteddevice.StreamProtos.VersionExchangeProto.VersionExchange;
import com.android.car.connecteddevice.util.ByteUtils;
import com.android.car.protobuf.ByteString;
import com.android.car.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract class which includes common logic of different types of {@link DeviceMessageStream}.
 */
public abstract class DeviceMessageStream {

    private static final String TAG = "DeviceMessageStream";

    // Only version 2 of the messaging and version 2 of the security supported.
    @VisibleForTesting
    public static final int MESSAGING_VERSION = 2;
    @VisibleForTesting
    public static final int SECURITY_VERSION = 2;

    private final ArrayDeque<Packet> mPacketQueue = new ArrayDeque<>();

    private final Map<Integer, ByteArrayOutputStream> mPendingData = new HashMap<>();

    // messageId -> nextExpectedPacketNumber
    private final Map<Integer, Integer> mPendingPacketNumber = new HashMap<>();

    private final MessageIdGenerator mMessageIdGenerator = new MessageIdGenerator();

    private final AtomicBoolean mIsVersionExchanged = new AtomicBoolean(false);

    private final AtomicBoolean mIsSendingInProgress = new AtomicBoolean(false);

    private int mMaxWriteSize;

    /** Listener which will be notified when there is new {@link DeviceMessage} received. */
    private MessageReceivedListener mMessageReceivedListener;

    /** Listener which will be notified when there is error parsing the received message. */
    private MessageReceivedErrorListener mMessageReceivedErrorListener;

    public DeviceMessageStream(int defaultMaxWriteSize) {
        mMaxWriteSize = defaultMaxWriteSize;
    }

    /**
     * Send data to the connected device. Note: {@link #sendCompleted()} must be called when the
     * bytes have successfully been sent to indicate the stream is ready to send more data.
     */
    protected abstract void send(byte[] data);

    /**
     * Set the given listener to be notified when a new message was received from the client. If
     * listener is {@code null}, clear.
     */
    public final void setMessageReceivedListener(@Nullable MessageReceivedListener listener) {
        mMessageReceivedListener = listener;
    }

    /**
     * Set the given listener to be notified when there was an error during receiving message from
     * the client. If listener is {@code null}, clear.
     */
    public final void setMessageReceivedErrorListener(
            @Nullable MessageReceivedErrorListener listener) {
        mMessageReceivedErrorListener = listener;
    }

    /**
     * Notify the {@code mMessageReceivedListener} about the message received if it is  not {@code
     * null}.
     *
     * @param deviceMessage The message received.
     * @param operationType The operation type of the message.
     */
    protected final void notifyMessageReceivedListener(@NonNull DeviceMessage deviceMessage,
            OperationType operationType) {
        if (mMessageReceivedListener != null) {
            mMessageReceivedListener.onMessageReceived(deviceMessage, operationType);
        }
    }

    /**
     * Notify the {@code mMessageReceivedErrorListener} about the message received if it is  not
     * {@code null}.
     *
     * @param e The exception happened when parsing the received message.
     */
    protected final void notifyMessageReceivedErrorListener(Exception e) {
        if (mMessageReceivedErrorListener != null) {
            mMessageReceivedErrorListener.onMessageReceivedError(e);
        }
    }

    /**
     * Writes the given message to the write characteristic of this stream with operation type
     * {@code CLIENT_MESSAGE}.
     *
     * This method will handle the chunking of messages based on the max write size.
     *
     * @param deviceMessage The data object contains recipient, isPayloadEncrypted and message.
     */
    public final void writeMessage(@NonNull DeviceMessage deviceMessage) {
        writeMessage(deviceMessage, OperationType.CLIENT_MESSAGE);
    }

    /**
     * Send {@link DeviceMessage} to remote connected devices.
     *
     * @param deviceMessage The message which need to be sent
     * @param operationType The operation type of current message
     */
    public final void writeMessage(@NonNull DeviceMessage deviceMessage,
            OperationType operationType) {
        Message.Builder builder = Message.newBuilder()
                .setOperation(operationType)
                .setIsPayloadEncrypted(deviceMessage.isMessageEncrypted())
                .setPayload(ByteString.copyFrom(deviceMessage.getMessage()));

        UUID recipient = deviceMessage.getRecipient();
        if (recipient != null) {
            builder.setRecipient(ByteString.copyFrom(ByteUtils.uuidToBytes(recipient)));
        }

        Message message = builder.build();
        byte[] rawBytes = message.toByteArray();
        List<Packet> packets;
        try {
            packets = PacketFactory.makePackets(rawBytes, mMessageIdGenerator.next(),
                    mMaxWriteSize);
        } catch (PacketFactoryException e) {
            loge(TAG, "Error while creating message packets.", e);
            return;
        }
        mPacketQueue.addAll(packets);
        writeNextMessageInQueue();
    }

    private void writeNextMessageInQueue() {
        if (mPacketQueue.isEmpty()) {
            logd(TAG, "No more packets to send.");
            return;
        }
        boolean isLockAcquired = mIsSendingInProgress.compareAndSet(false, true);
        if (!isLockAcquired) {
            logd(TAG, "Unable to send packet at this time.");
            return;
        }

        Packet packet = mPacketQueue.remove();
        logd(TAG, "Writing packet " + packet.getPacketNumber() + " of "
                + packet.getTotalPackets() + " for " + packet.getMessageId() + ".");
        send(packet.toByteArray());
    }

    /** Process incoming data from stream. */
    protected final void onDataReceived(byte[] data) {
        if (!hasVersionBeenExchanged()) {
            processVersionExchange(data);
            return;
        }

        Packet packet;
        try {
            packet = Packet.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            loge(TAG, "Can not parse packet from client.", e);
            notifyMessageReceivedErrorListener(e);
            return;
        }
        processPacket(packet);
    }

    protected final void processPacket(@NonNull Packet packet) {
        int messageId = packet.getMessageId();
        int packetNumber = packet.getPacketNumber();
        int expectedPacket = mPendingPacketNumber.getOrDefault(messageId, 1);
        if (packetNumber == expectedPacket - 1) {
            logw(TAG, "Received duplicate packet " + packet.getPacketNumber() + " for message "
                    + messageId + ". Ignoring.");
            return;
        }
        if (packetNumber != expectedPacket) {
            loge(TAG, "Received unexpected packet " + packetNumber + " for message "
                    + messageId + ".");
            notifyMessageReceivedErrorListener(
                    new IllegalStateException("Packet received out of order."));
            return;
        }
        mPendingPacketNumber.put(messageId, packetNumber + 1);

        ByteArrayOutputStream currentPayloadStream =
                mPendingData.getOrDefault(messageId, new ByteArrayOutputStream());
        mPendingData.putIfAbsent(messageId, currentPayloadStream);

        byte[] payload = packet.getPayload().toByteArray();
        try {
            currentPayloadStream.write(payload);
        } catch (IOException e) {
            loge(TAG, "Error writing packet to stream.", e);
            notifyMessageReceivedErrorListener(e);
            return;
        }
        logd(TAG, "Parsed packet " + packet.getPacketNumber() + " of "
                + packet.getTotalPackets() + " for message " + messageId + ". Writing "
                + payload.length + ".");

        if (packet.getPacketNumber() != packet.getTotalPackets()) {
            return;
        }

        byte[] messageBytes = currentPayloadStream.toByteArray();
        mPendingData.remove(messageId);

        logd(TAG, "Received complete device message " + messageId + " of " + messageBytes.length
                + " bytes.");
        Message message;
        try {
            message = Message.parseFrom(messageBytes);
        } catch (InvalidProtocolBufferException e) {
            loge(TAG, "Cannot parse device message from client.", e);
            notifyMessageReceivedErrorListener(e);
            return;
        }

        DeviceMessage deviceMessage = new DeviceMessage(
                ByteUtils.bytesToUUID(message.getRecipient().toByteArray()),
                message.getIsPayloadEncrypted(), message.getPayload().toByteArray());
        notifyMessageReceivedListener(deviceMessage, message.getOperation());
    }

    /** The maximum amount of bytes that can be written in a single packet. */
    public final void setMaxWriteSize(@IntRange(from = 1) int maxWriteSize) {
        if (maxWriteSize <= 0) {
            return;
        }
        mMaxWriteSize = maxWriteSize;
    }

    private boolean hasVersionBeenExchanged() {
        return mIsVersionExchanged.get();
    }

    /** Indicate current send operation has completed. */
    @VisibleForTesting
    public final void sendCompleted() {
        mIsSendingInProgress.set(false);
        writeNextMessageInQueue();
    }

    private void processVersionExchange(@NonNull byte[] value) {
        VersionExchange versionExchange;
        try {
            versionExchange = VersionExchange.parseFrom(value);
        } catch (InvalidProtocolBufferException e) {
            loge(TAG, "Could not parse version exchange message", e);
            notifyMessageReceivedErrorListener(e);

            return;
        }
        int minMessagingVersion = versionExchange.getMinSupportedMessagingVersion();
        int maxMessagingVersion = versionExchange.getMaxSupportedMessagingVersion();
        int minSecurityVersion = versionExchange.getMinSupportedSecurityVersion();
        int maxSecurityVersion = versionExchange.getMaxSupportedSecurityVersion();
        if (minMessagingVersion > MESSAGING_VERSION || maxMessagingVersion < MESSAGING_VERSION
                || minSecurityVersion > SECURITY_VERSION || maxSecurityVersion < SECURITY_VERSION) {
            loge(TAG, "Unsupported message version for min " + minMessagingVersion + " and max "
                    + maxMessagingVersion + " or security version for " + minSecurityVersion
                    + " and max " + maxSecurityVersion + ".");
            notifyMessageReceivedErrorListener(new IllegalStateException("Unsupported version."));
            return;
        }

        VersionExchange headunitVersion = VersionExchange.newBuilder()
                .setMinSupportedMessagingVersion(MESSAGING_VERSION)
                .setMaxSupportedMessagingVersion(MESSAGING_VERSION)
                .setMinSupportedSecurityVersion(SECURITY_VERSION)
                .setMaxSupportedSecurityVersion(SECURITY_VERSION)
                .build();

        send(headunitVersion.toByteArray());
        mIsVersionExchanged.set(true);
        logd(TAG, "Sent supported version to the phone.");
    }


    /** A generator of unique IDs for messages. */
    private static class MessageIdGenerator {
        private final AtomicInteger mMessageId = new AtomicInteger(0);

        int next() {
            int current = mMessageId.getAndIncrement();
            mMessageId.compareAndSet(Integer.MAX_VALUE, 0);
            return current;
        }
    }

    /**
     * Listener to be invoked when a complete message is received from the client.
     */
    public interface MessageReceivedListener {

        /**
         * Called when a complete message is received from the client.
         *
         * @param deviceMessage The message received from the client.
         * @param operationType The {@link OperationType} of the received message.
         */
        void onMessageReceived(@NonNull DeviceMessage deviceMessage,
                OperationType operationType);
    }

    /**
     * Listener to be invoked when there was an error during receiving message from the client.
     */
    public interface MessageReceivedErrorListener {
        /**
         * Called when there was an error during receiving message from the client.
         *
         * @param exception The error.
         */
        void onMessageReceivedError(@NonNull Exception exception);
    }
}
