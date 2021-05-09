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

package com.android.ims.rcs.uce.presence.publish;

import android.content.Context;
import android.net.Uri;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.rcs.uce.util.UceUtils;

/**
 * The util class of publishing device's capabilities.
 */
class PublishUtils {
    private static final String LOG_TAG = UceUtils.getLogPrefix() + "PublishUtils";

    private static final String SCHEME_SIP = "sip";
    private static final String SCHEME_TEL = "tel";
    private static final String DOMAIN_SEPARATOR = "@";

    public static Uri getDeviceContactUri(Context context, int subId) {
        TelephonyManager telephonyManager = getTelephonyManager(context, subId);
        if (telephonyManager == null) {
            Log.w(LOG_TAG, "getDeviceContactUri: TelephonyManager is null");
            return null;
        }

        // Get the contact uri from ISIM.
        Uri contactUri = getContactUriFromIsim(telephonyManager);
        if (contactUri != null) {
            Log.d(LOG_TAG, "getDeviceContactUri: impu");
            return contactUri;
        } else {
            Log.d(LOG_TAG, "getDeviceContactUri: line number");
            return getContactUriFromLine1Number(telephonyManager);
        }
    }

    private static Uri getContactUriFromIsim(TelephonyManager telephonyManager) {
        // Get the home network domain and the array of the public user identities
        String domain = telephonyManager.getIsimDomain();
        String[] impus = telephonyManager.getIsimImpu();

        if (TextUtils.isEmpty(domain) || impus == null) {
            return null;
        }

        for (String impu : impus) {
            if (TextUtils.isEmpty(impu)) continue;
            Uri impuUri = Uri.parse(impu);
            if (SCHEME_SIP.equals(impuUri.getScheme()) &&
                    impuUri.getSchemeSpecificPart().endsWith(domain)) {
                return impuUri;
            }
        }
        return null;
    }

    private static Uri getContactUriFromLine1Number(TelephonyManager telephonyManager) {
        String phoneNumber = formatPhoneNumber(telephonyManager.getLine1Number());
        if (TextUtils.isEmpty(phoneNumber)) {
            Log.w(LOG_TAG, "Cannot get the phone number");
            return null;
        }

        String domain = telephonyManager.getIsimDomain();
        if (!TextUtils.isEmpty(domain)) {
            return Uri.fromParts(SCHEME_SIP, phoneNumber + DOMAIN_SEPARATOR + domain, null);
        } else {
            return Uri.fromParts(SCHEME_TEL, phoneNumber, null);
        }
    }

    private static String formatPhoneNumber(final String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            Log.w(LOG_TAG, "formatPhoneNumber: phone number is empty");
            return null;
        }
        String number = PhoneNumberUtils.stripSeparators(phoneNumber);
        return PhoneNumberUtils.normalizeNumber(number);
    }

    private static TelephonyManager getTelephonyManager(Context context, int subId) {
        TelephonyManager telephonyManager = context.getSystemService(TelephonyManager.class);
        if (telephonyManager == null) {
            return null;
        } else {
            return telephonyManager.createForSubscriptionId(subId);
        }
    }
}
