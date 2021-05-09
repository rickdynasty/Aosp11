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

import androidx.annotation.NonNull;

import com.android.car.connecteddevice.model.OobEligibleDevice;

/**
 * An interface for handling out of band data exchange. This interface should be implemented for
 * every out of band channel that is supported in device association.
 *
 * Usage is:
 * <pre>
 *     1. Define success and failure responses in {@link Callback}
 *     2. Call {@link OobChannel#completeOobDataExchange(OobEligibleDevice, Callback)}
 * </pre>
 */
public interface OobChannel {
    /**
     * Exchange out of band data with a remote device. This must be done prior to the start of the
     * association with that device.
     *
     * @param device The remote device to exchange out of band data with
     */
    void completeOobDataExchange(@NonNull OobEligibleDevice device, @NonNull Callback callback);

    /**
     * Send raw data over the out of band channel
     *
     * @param oobData to be sent
     */
    void sendOobData(@NonNull byte[] oobData);

    /** Interrupt the current data exchange and prevent callbacks from being issued. */
    void interrupt();

    /**
     * Callbacks for {@link OobChannel#completeOobDataExchange(OobEligibleDevice, Callback)}
     */
    interface Callback {
        /**
         * Called when {@link OobChannel#completeOobDataExchange(OobEligibleDevice, Callback)}
         * finishes successfully.
         */
        void onOobExchangeSuccess();

        /**
         * Called when {@link OobChannel#completeOobDataExchange(OobEligibleDevice, Callback)}
         * fails.
         */
        void onOobExchangeFailure();
    }
}
