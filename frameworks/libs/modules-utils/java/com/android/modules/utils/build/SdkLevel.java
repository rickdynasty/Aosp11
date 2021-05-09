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

package com.android.modules.utils.build;

import android.os.Build;

import androidx.annotation.ChecksSdkIntAtLeast;

/**
 * Utility class to check SDK level.
 *
 * @hide
 */
public class SdkLevel {

    private SdkLevel() {}

    /** Return true iff the running Android SDK is at least "R". */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    public static boolean isAtLeastR() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * Returns true iff the running Android SDK is pre-release "S" or "T", built based on "R" SDK.
     *
     * If new SDK versions are added > R, then this method needs to be updated to recognise them
     * (e.g. if we add SDK version for R-QPR,  the current implementation will not recognise
     * pre-release "S" versions built on that).
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.CUR_DEVELOPMENT)
    public static boolean isAtLeastS() {
        // TODO(b/170831689) This should check SDK_INT >= S once S sdk finalised. Note that removing the
        // current conditions may lead to issues in mainlinefood (and possibly public beta?).

        // While in development, builds will have R SDK_INT and "S" or "T" codename.
        // We don't accept SDK_INT > R for now, since R and S may have non-consecutive values.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R
            && ("S".equals(Build.VERSION.CODENAME) || "T".equals(Build.VERSION.CODENAME))) {
            return true;
        }

        return false;
    }
}
