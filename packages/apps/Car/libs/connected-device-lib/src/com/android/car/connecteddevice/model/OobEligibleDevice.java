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

package com.android.car.connecteddevice.model;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.util.Objects;

/** Device that may be used for an out-of-band channel. */
public class OobEligibleDevice {

    @Retention(SOURCE)
    @IntDef(value = { OOB_TYPE_BLUETOOTH })
    public @interface OobType {}
    public static final int OOB_TYPE_BLUETOOTH = 0;

    private final String mDeviceAddress;

    @OobType
    private final int mOobType;

    public OobEligibleDevice(@NonNull String deviceAddress, @OobType int oobType) {
        mDeviceAddress = deviceAddress;
        mOobType = oobType;
    }

    @NonNull
    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    @OobType
    public int getOobType() {
        return mOobType;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof OobEligibleDevice)) {
            return false;
        }
        OobEligibleDevice device = (OobEligibleDevice) obj;
        return Objects.equals(device.mDeviceAddress, mDeviceAddress)
                && device.mOobType == mOobType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceAddress, mOobType);
    }
}
