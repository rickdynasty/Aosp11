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

import static org.junit.Assert.assertNotNull;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;

import androidx.test.filters.SmallTest;

import com.android.managedprovisioning.common.Utils;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link NetworkMonitor}.
 */
@SmallTest
public class NetworkMonitorTest {

    @Mock private Context mContext;
    @Mock private ConnectivityManager mConnManager;
    @Mock private NetworkMonitor.NetworkConnectedCallback mCallback;
    private NetworkMonitor mNetworkMonitor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mConnManager).when(mContext).getSystemService(Context.CONNECTIVITY_SERVICE);
        doReturn(Context.CONNECTIVITY_SERVICE).when(mContext).getSystemServiceName(
                ConnectivityManager.class);

        mNetworkMonitor = new NetworkMonitor(mContext);
    }

    @Test
    public void testStartListening() {
        // WHEN starting to listen for connectivity changes
        mNetworkMonitor.startListening(mCallback);

        // THEN a callback should be registered
        final ArgumentCaptor<NetworkCallback> cbCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mConnManager).registerDefaultNetworkCallback(cbCaptor.capture());

        // WHEN the network connects and a callback is received
        cbCaptor.getValue().onBlockedStatusChanged(mock(Network.class), false);

        // THEN a callback should be given
        verify(mCallback).onNetworkConnected();
    }

    @Test
    public void testStopListening() {
        // WHEN starting and stopping to listen for connectivity changes
        mNetworkMonitor.startListening(mCallback);
        mNetworkMonitor.stopListening();

        // THEN a callback should be registered and later unregistered
        final ArgumentCaptor<NetworkCallback> cbCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mConnManager).registerDefaultNetworkCallback(cbCaptor.capture());
        verify(mConnManager).unregisterNetworkCallback(cbCaptor.getValue());

        // Even if an unexpected callback is received after unregistering
        cbCaptor.getValue().onBlockedStatusChanged(mock(Network.class), false);

        // THEN no callback should be given
        verifyZeroInteractions(mCallback);
    }
}
