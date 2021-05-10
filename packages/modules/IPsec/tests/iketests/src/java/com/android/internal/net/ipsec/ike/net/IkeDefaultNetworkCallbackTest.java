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

package com.android.internal.net.ipsec.test.ike.net;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;

public class IkeDefaultNetworkCallbackTest {
    // Addresses in the IPv4 Documentation Address Blocks (RFC 5737 Section 3)
    private static final String CURR_ADDRESS = "192.0.2.0";
    private static final String UPDATED_ADDRESS = "192.0.2.1";

    private Network mMockNetwork;
    private IkeNetworkUpdater mMockIkeNetworkUpdater;

    private InetAddress mCurrAddress;
    private IkeDefaultNetworkCallback mNetworkCallback;

    @Before
    public void setUp() throws Exception {
        mMockNetwork = mock(Network.class);
        mMockIkeNetworkUpdater = mock(IkeNetworkUpdater.class);

        mCurrAddress = InetAddress.getByName(CURR_ADDRESS);
        mNetworkCallback =
                new IkeDefaultNetworkCallback(mMockIkeNetworkUpdater, mMockNetwork, mCurrAddress);
    }

    @Test
    public void testOnAvailable() {
        Network updatedNetwork = mock(Network.class);

        mNetworkCallback.onAvailable(updatedNetwork);

        verify(mMockIkeNetworkUpdater).onUnderlyingNetworkUpdated(eq(updatedNetwork));
    }

    @Test
    public void testOnAvailableCurrentNetwork() {
        mNetworkCallback.onAvailable(mMockNetwork);

        verify(mMockIkeNetworkUpdater, never()).onUnderlyingNetworkUpdated(any());
    }

    @Test
    public void testOnLost() {
        mNetworkCallback.onLost(mMockNetwork);

        verify(mMockIkeNetworkUpdater).onUnderlyingNetworkDied();
    }

    @Test
    public void testOnLostWrongNetwork() {
        mNetworkCallback.onLost(mock(Network.class));

        verify(mMockIkeNetworkUpdater, never()).onUnderlyingNetworkDied();
    }

    @Test
    public void testOnLinkPropertiesChanged() throws Exception {
        mNetworkCallback.onLinkPropertiesChanged(
                mMockNetwork, getLinkPropertiesWithAddresses(UPDATED_ADDRESS));

        verify(mMockIkeNetworkUpdater).onUnderlyingNetworkUpdated(eq(mMockNetwork));
    }

    @Test
    public void testOnLinkPropertiesChangedWrongNetwork() throws Exception {
        mNetworkCallback.onLinkPropertiesChanged(
                mock(Network.class), getLinkPropertiesWithAddresses(UPDATED_ADDRESS));

        verify(mMockIkeNetworkUpdater, never()).onUnderlyingNetworkUpdated(any());
    }

    @Test
    public void testOnLinkPropertiesChangedNoAddressChange() throws Exception {
        mNetworkCallback.onLinkPropertiesChanged(
                mMockNetwork, getLinkPropertiesWithAddresses(CURR_ADDRESS));

        verify(mMockIkeNetworkUpdater, never()).onUnderlyingNetworkUpdated(any());
    }

    private LinkProperties getLinkPropertiesWithAddresses(String... addresses) throws Exception {
        LinkProperties linkProperties = new LinkProperties();
        for (String address : addresses) {
            linkProperties.addLinkAddress(
                    new LinkAddress(InetAddress.getByName(address), 32 /* prefixLength */));
        }
        return linkProperties;
    }
}
