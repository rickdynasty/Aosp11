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

package com.android.ims.rcs.uce.util;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.BlockedNumberContract;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ProvisioningManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.telephony.Rlog;

public class UceUtils {

    public static final int LOG_SIZE = 20;
    private static final String LOG_PREFIX = "RcsUce.";
    private static final String LOG_TAG = LOG_PREFIX + "UceUtils";

    private static final int DEFAULT_RCL_MAX_NUM_ENTRIES = 100;
    private static final long DEFAULT_RCS_PUBLISH_SOURCE_THROTTLE_MS = 60000L;

    // The task ID of the UCE request
    private static long TASK_ID = 0L;

    // The request coordinator ID
    private static long REQUEST_COORDINATOR_ID = 0;

    /**
     * Get the log prefix of RCS UCE
     */
    public static String getLogPrefix() {
        return LOG_PREFIX;
    }

    /**
     * Generate the unique UCE request task id.
     */
    public static synchronized long generateTaskId() {
        return ++TASK_ID;
    }

    /**
     * Generate the unique request coordinator id.
     */
    public static synchronized long generateRequestCoordinatorId() {
        return ++REQUEST_COORDINATOR_ID;
    }

    public static boolean isEabProvisioned(Context context, int subId) {
        boolean isProvisioned = false;
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            Log.w(LOG_TAG, "isEabProvisioned: invalid subscriptionId " + subId);
            return false;
        }
        CarrierConfigManager configManager = (CarrierConfigManager)
                context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager != null) {
            PersistableBundle config = configManager.getConfigForSubId(subId);
            if (config != null && !config.getBoolean(
                    CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONED_BOOL)) {
                return true;
            }
        }
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(subId);
            isProvisioned = manager.getProvisioningIntValue(
                    ProvisioningManager.KEY_EAB_PROVISIONING_STATUS)
                    == ProvisioningManager.PROVISIONING_VALUE_ENABLED;
        } catch (Exception e) {
            Log.w(LOG_TAG, "isEabProvisioned: exception=" + e.getMessage());
        }
        return isProvisioned;
    }

    /**
     * Check whether or not this carrier supports the exchange of phone numbers with the carrier's
     * presence server.
     */
    public static boolean isPresenceCapExchangeEnabled(Context context, int subId) {
        CarrierConfigManager configManager = context.getSystemService(CarrierConfigManager.class);
        if (configManager == null) {
            return false;
        }
        PersistableBundle config = configManager.getConfigForSubId(subId);
        if (config == null) {
            return false;
        }
        return config.getBoolean(
                CarrierConfigManager.Ims.KEY_ENABLE_PRESENCE_CAPABILITY_EXCHANGE_BOOL);
    }

    /**
     * Check if Presence is supported by the carrier.
     */
    public static boolean isPresenceSupported(Context context, int subId) {
        CarrierConfigManager configManager = context.getSystemService(CarrierConfigManager.class);
        if (configManager == null) {
            return false;
        }
        PersistableBundle config = configManager.getConfigForSubId(subId);
        if (config == null) {
            return false;
        }
        return config.getBoolean(CarrierConfigManager.Ims.KEY_ENABLE_PRESENCE_PUBLISH_BOOL);
    }

    /**
     * Check if SIP OPTIONS is supported by the carrier.
     */
    public static boolean isSipOptionsSupported(Context context, int subId) {
        CarrierConfigManager configManager = context.getSystemService(CarrierConfigManager.class);
        if (configManager == null) {
            return false;
        }
        PersistableBundle config = configManager.getConfigForSubId(subId);
        if (config == null) {
            return false;
        }
        return config.getBoolean(CarrierConfigManager.KEY_USE_RCS_SIP_OPTIONS_BOOL);
    }

    /**
     * Check whether the PRESENCE group subscribe is enabled or not.
     *
     * @return true when the Presence group subscribe is enabled, false otherwise.
     */
    public static boolean isPresenceGroupSubscribeEnabled(Context context, int subId) {
        CarrierConfigManager configManager = context.getSystemService(CarrierConfigManager.class);
        if (configManager == null) {
            return false;
        }
        PersistableBundle config = configManager.getConfigForSubId(subId);
        if (config == null) {
            return false;
        }
        return config.getBoolean(CarrierConfigManager.Ims.KEY_ENABLE_PRESENCE_GROUP_SUBSCRIBE_BOOL);
    }

    /**
     *  Returns {@code true} if {@code phoneNumber} is blocked.
     *
     * @param context the context of the caller.
     * @param phoneNumber the number to check.
     * @return true if the number is blocked, false otherwise.
     */
    public static boolean isNumberBlocked(Context context, String phoneNumber) {
        int blockStatus;
        try {
            blockStatus = BlockedNumberContract.SystemContract.shouldSystemBlockNumber(
                    context, phoneNumber, null /*extras*/);
        } catch (Exception e) {
            return false;
        }
        return blockStatus != BlockedNumberContract.STATUS_NOT_BLOCKED;
    }

    /**
     * Get the minimum time that allow two PUBLISH requests can be executed continuously.
     *
     * @param subId The subscribe ID
     * @return The milliseconds that allowed two consecutive publish request.
     */
    public static long getRcsPublishThrottle(int subId) {
        long throttle = DEFAULT_RCS_PUBLISH_SOURCE_THROTTLE_MS;
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(subId);
            long provisioningValue = manager.getProvisioningIntValue(
                    ProvisioningManager.KEY_RCS_PUBLISH_SOURCE_THROTTLE_MS);
            if (provisioningValue > 0) {
                throttle = provisioningValue;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "getRcsPublishThrottle: exception=" + e.getMessage());
        }
        return throttle;
    }

    /**
     * Retrieve the maximum number of contacts that is in one Request Contained List(RCL)
     *
     * @param subId The subscribe ID
     * @return The maximum number of contacts.
     */
    public static int getRclMaxNumberEntries(int subId) {
        int maxNumEntries = DEFAULT_RCL_MAX_NUM_ENTRIES;
        try {
            ProvisioningManager manager = ProvisioningManager.createForSubscriptionId(subId);
            int provisioningValue = manager.getProvisioningIntValue(
                    ProvisioningManager.KEY_RCS_MAX_NUM_ENTRIES_IN_RCL);
            if (provisioningValue > 0) {
                maxNumEntries = provisioningValue;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "getRclMaxNumberEntries: exception=" + e.getMessage());
        }
        return maxNumEntries;
    }
}
