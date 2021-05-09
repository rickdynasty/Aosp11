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

import static com.android.car.connecteddevice.util.SafeLog.loge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * For spp stream will be segmented to several parts, a completed message length need to be prepend
 * to any message sent. This class will take care of the decode and encode of the incoming and
 * outgoing message.
 */
class SppPayloadStream {
    private static final String TAG = "SppPayloadStream";
    // An int will take 4 bytes.
    private static final int LENGTH_BYTES_SIZE = 4;
    private final ByteArrayOutputStream mPendingStream = new ByteArrayOutputStream();
    private OnMessageCompletedListener mOnMessageCompletedListener;
    private int mCurrentMessageTotalLength;

    /**
     * Writes data to the {@code pendingStream}, inform the {@code messageCompletedListener} when
     * the message is completed, otherwise store the data into {@code pendingStream} and waiting for
     * the following parts.
     *
     * @param data Received byte array
     * @throws IOException If there are some errors writing data to {@code pendingStream}
     */
    public void write(@NonNull byte[] data) throws IOException {
        if (mPendingStream.size() == 0) {
            int currentLength = data.length;
            // Arbitrarily choose a byte order, need to use the same byte order for server and
            // client.
            mCurrentMessageTotalLength = ByteBuffer.wrap(
                    Arrays.copyOf(data, LENGTH_BYTES_SIZE)).order(
                    ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] payload = Arrays.copyOfRange(data, LENGTH_BYTES_SIZE, currentLength);
            mPendingStream.write(payload);
        } else {
            mPendingStream.write(data);
        }

        if (mPendingStream.size() > mCurrentMessageTotalLength) {
            // TODO(b/159712861): Handle this situation, e.g. disconnect.
            loge(TAG, "Received invalid message: " + mPendingStream.toByteArray());
            return;
        }

        if (mPendingStream.size() < mCurrentMessageTotalLength) {
            return;
        }
        if (mOnMessageCompletedListener != null) {
            mOnMessageCompletedListener.onMessageCompleted(mPendingStream.toByteArray());
        }
        mPendingStream.reset();

    }

    /**
     * Register the given listener to be notified when a completed message is received.
     *
     * @param listener The listener to be notified
     */
    public void setMessageCompletedListener(@Nullable OnMessageCompletedListener listener) {
        mOnMessageCompletedListener = listener;
    }

    /**
     * Wrap the raw byte array with array length.
     * <p>
     * Should be called every time when server wants to send a message to client.
     *
     * @param rawData Original data
     * @return The wrapped data
     * @throws IOException If there are some errors writing data to {@code outputStream}
     */
    @Nullable
    public static byte[] wrapWithArrayLength(@NonNull byte[] rawData) {
        int length = rawData.length;
        byte[] lengthBytes = ByteBuffer.allocate(LENGTH_BYTES_SIZE).order(
                ByteOrder.LITTLE_ENDIAN).putInt(length).array();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write(lengthBytes);
            outputStream.write(rawData);
        } catch (IOException e) {
            loge(TAG, "Error wrap data with array length");
            return null;
        }
        return outputStream.toByteArray();
    }

    /**
     * Interface to be notified when a completed message has been received.
     */
    interface OnMessageCompletedListener {
        void onMessageCompleted(@NonNull byte[] message);
    }
}
