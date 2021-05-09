/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.task.wifi;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;

import com.android.internal.annotations.VisibleForTesting;
import com.android.managedprovisioning.common.ProvisionLogger;
import com.android.managedprovisioning.common.Utils;

/**
 * Monitor the state of the data network. Invoke a callback when the network is connected.
 */
public class NetworkMonitor {

    /** State notification callback. Expect some duplicate notifications. */
    public interface NetworkConnectedCallback {
        void onNetworkConnected();
    }

    private final Context mContext;

    private NetworkConnectedCallback mCallback = null;

    /**
     * Start watching the network. Immediately invokes the callback method to report the
     * current default network if any, and then invokes callback methods over time as the default
     * network changes.
     *
     * @param context to use for intent observers and such
     */
    public NetworkMonitor(Context context) {
        mContext = checkNotNull(context);
    }

    /**
     * Start listening for connectivity changes.
     * @param callback Callback to inform about those changes.
     */
    public synchronized void startListening(NetworkConnectedCallback callback) {
        mCallback = checkNotNull(callback);
        // TODO: this code has always kept track of the default network, but it should probably
        // instead use registerNetworkCallback or even requestNetwork with a specific
        // NetworkRequest.Builder#addTransportType transport depending on the caller. For example,
        // ConnectMobileNetworkTask should file a request for TRANSPORT_CELLULAR instead of waiting
        // for any network to be available. When requestNetwork is not used, cellular data is also
        // not guaranteed to connect after being enabled if another network is already available.
        mContext.getSystemService(ConnectivityManager.class)
                .registerDefaultNetworkCallback(mConnectivityCallback);
    }

    /**
     * Stop listening for connectivity changes.
     */
    public synchronized void stopListening() {
        if (mCallback == null) {
            return;
        }

        mCallback = null;
        mContext.getSystemService(ConnectivityManager.class)
                .unregisterNetworkCallback(mConnectivityCallback);
    }

    private final NetworkCallback mConnectivityCallback = new NetworkCallback() {
        // onBlockedStatusChanged is called immediately after onAvailable, and receiving
        // blocked=false is the equivalent to legacy behavior that would verify
        // getActiveNetworkInfo().isConnected()
        @Override
        public void onBlockedStatusChanged(Network network, boolean blocked) {
            ProvisionLogger.logd("NetworkMonitor.onBlockedStatusChanged: " + network
                    + " blocked=" + blocked);
            if (blocked) {
                return;
            }
            synchronized (NetworkMonitor.this) {
                if (mCallback != null) {
                    mCallback.onNetworkConnected();
                }
            }
        }
    };
}
