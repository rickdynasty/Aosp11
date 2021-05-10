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

package android.net.cts.util;

import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_FAILED;
import static android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_STARTED;
import static android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_STOPPED;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Network;
import android.net.TetheredClient;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.TetheringManager.TetheringInterfaceRegexps;
import android.net.TetheringManager.TetheringRequest;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.SoftApCallback;
import android.os.ConditionVariable;

import androidx.annotation.NonNull;

import com.android.compatibility.common.util.SystemUtil;
import com.android.net.module.util.ArrayTrackRecord;

import java.util.Collection;
import java.util.List;

public final class CtsTetheringUtils {
    private TetheringManager mTm;
    private WifiManager mWm;
    private Context mContext;

    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    public CtsTetheringUtils(Context ctx) {
        mContext = ctx;
        mTm = mContext.getSystemService(TetheringManager.class);
        mWm = mContext.getSystemService(WifiManager.class);
    }

    public static class StartTetheringCallback implements TetheringManager.StartTetheringCallback {
        private static int TIMEOUT_MS = 30_000;
        public static class CallbackValue {
            public final int error;

            private CallbackValue(final int e) {
                error = e;
            }

            public static class OnTetheringStarted extends CallbackValue {
                OnTetheringStarted() { super(TETHER_ERROR_NO_ERROR); }
            }

            public static class OnTetheringFailed extends CallbackValue {
                OnTetheringFailed(final int error) { super(error); }
            }

            @Override
            public String toString() {
                return String.format("%s(%d)", getClass().getSimpleName(), error);
            }
        }

        private final ArrayTrackRecord<CallbackValue>.ReadHead mHistory =
                new ArrayTrackRecord<CallbackValue>().newReadHead();

        @Override
        public void onTetheringStarted() {
            mHistory.add(new CallbackValue.OnTetheringStarted());
        }

        @Override
        public void onTetheringFailed(final int error) {
            mHistory.add(new CallbackValue.OnTetheringFailed(error));
        }

        public void verifyTetheringStarted() {
            final CallbackValue cv = mHistory.poll(TIMEOUT_MS, c -> true);
            assertNotNull("No onTetheringStarted after " + TIMEOUT_MS + " ms", cv);
            assertTrue("Fail start tethering:" + cv,
                    cv instanceof CallbackValue.OnTetheringStarted);
        }

        public void expectTetheringFailed(final int expected) throws InterruptedException {
            final CallbackValue cv = mHistory.poll(TIMEOUT_MS, c -> true);
            assertNotNull("No onTetheringFailed after " + TIMEOUT_MS + " ms", cv);
            assertTrue("Expect fail with error code " + expected + ", but received: " + cv,
                    (cv instanceof CallbackValue.OnTetheringFailed) && (cv.error == expected));
        }
    }

    public static boolean isIfaceMatch(final List<String> ifaceRegexs, final List<String> ifaces) {
        return isIfaceMatch(ifaceRegexs.toArray(new String[0]), ifaces);
    }

    public static boolean isIfaceMatch(final String[] ifaceRegexs, final List<String> ifaces) {
        if (ifaceRegexs == null) fail("ifaceRegexs should not be null");

        if (ifaces == null) return false;

        for (String s : ifaces) {
            for (String regex : ifaceRegexs) {
                if (s.matches(regex)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Must poll the callback before looking at the member.
    public static class TestTetheringEventCallback implements TetheringEventCallback {
        private static final int TIMEOUT_MS = 30_000;

        public enum CallbackType {
            ON_SUPPORTED,
            ON_UPSTREAM,
            ON_TETHERABLE_REGEX,
            ON_TETHERABLE_IFACES,
            ON_TETHERED_IFACES,
            ON_ERROR,
            ON_CLIENTS,
            ON_OFFLOAD_STATUS,
        };

        public static class CallbackValue {
            public final CallbackType callbackType;
            public final Object callbackParam;
            public final int callbackParam2;

            private CallbackValue(final CallbackType type, final Object param, final int param2) {
                this.callbackType = type;
                this.callbackParam = param;
                this.callbackParam2 = param2;
            }
        }

        private final ArrayTrackRecord<CallbackValue> mHistory =
                new ArrayTrackRecord<CallbackValue>();

        private final ArrayTrackRecord<CallbackValue>.ReadHead mCurrent =
                mHistory.newReadHead();

        private TetheringInterfaceRegexps mTetherableRegex;
        private List<String> mTetherableIfaces;
        private List<String> mTetheredIfaces;

        @Override
        public void onTetheringSupported(boolean supported) {
            mHistory.add(new CallbackValue(CallbackType.ON_SUPPORTED, null, (supported ? 1 : 0)));
        }

        @Override
        public void onUpstreamChanged(Network network) {
            mHistory.add(new CallbackValue(CallbackType.ON_UPSTREAM, network, 0));
        }

        @Override
        public void onTetherableInterfaceRegexpsChanged(TetheringInterfaceRegexps reg) {
            mTetherableRegex = reg;
            mHistory.add(new CallbackValue(CallbackType.ON_TETHERABLE_REGEX, reg, 0));
        }

        @Override
        public void onTetherableInterfacesChanged(List<String> interfaces) {
            mTetherableIfaces = interfaces;
            mHistory.add(new CallbackValue(CallbackType.ON_TETHERABLE_IFACES, interfaces, 0));
        }

        @Override
        public void onTetheredInterfacesChanged(List<String> interfaces) {
            mTetheredIfaces = interfaces;
            mHistory.add(new CallbackValue(CallbackType.ON_TETHERED_IFACES, interfaces, 0));
        }

        @Override
        public void onError(String ifName, int error) {
            mHistory.add(new CallbackValue(CallbackType.ON_ERROR, ifName, error));
        }

        @Override
        public void onClientsChanged(Collection<TetheredClient> clients) {
            mHistory.add(new CallbackValue(CallbackType.ON_CLIENTS, clients, 0));
        }

        @Override
        public void onOffloadStatusChanged(int status) {
            mHistory.add(new CallbackValue(CallbackType.ON_OFFLOAD_STATUS, status, 0));
        }

        public void expectTetherableInterfacesChanged(@NonNull List<String> regexs) {
            assertNotNull("No expected tetherable ifaces callback", mCurrent.poll(TIMEOUT_MS,
                (cv) -> {
                    if (cv.callbackType != CallbackType.ON_TETHERABLE_IFACES) return false;
                    final List<String> interfaces = (List<String>) cv.callbackParam;
                    return isIfaceMatch(regexs, interfaces);
                }));
        }

        public void expectTetheredInterfacesChanged(@NonNull List<String> regexs) {
            assertNotNull("No expected tethered ifaces callback", mCurrent.poll(TIMEOUT_MS,
                (cv) -> {
                    if (cv.callbackType != CallbackType.ON_TETHERED_IFACES) return false;

                    final List<String> interfaces = (List<String>) cv.callbackParam;

                    // Null regexs means no active tethering.
                    if (regexs == null) return interfaces.isEmpty();

                    return isIfaceMatch(regexs, interfaces);
                }));
        }

        public void expectCallbackStarted() {
            int receivedBitMap = 0;
            // The each bit represent a type from CallbackType.ON_*.
            // Expect all of callbacks except for ON_ERROR.
            final int expectedBitMap = 0xff ^ (1 << CallbackType.ON_ERROR.ordinal());
            // Receive ON_ERROR on started callback is not matter. It just means tethering is
            // failed last time, should able to continue the test this time.
            while ((receivedBitMap & expectedBitMap) != expectedBitMap) {
                final CallbackValue cv = mCurrent.poll(TIMEOUT_MS, c -> true);
                if (cv == null) {
                    fail("No expected callbacks, " + "expected bitmap: "
                            + expectedBitMap + ", actual: " + receivedBitMap);
                }

                receivedBitMap |= (1 << cv.callbackType.ordinal());
            }
        }

        public void expectOneOfOffloadStatusChanged(int... offloadStatuses) {
            assertNotNull("No offload status changed", mCurrent.poll(TIMEOUT_MS, (cv) -> {
                if (cv.callbackType != CallbackType.ON_OFFLOAD_STATUS) return false;

                final int status = (int) cv.callbackParam;
                for (int offloadStatus : offloadStatuses) {
                    if (offloadStatus == status) return true;
                }

                return false;
            }));
        }

        public void expectErrorOrTethered(final String iface) {
            assertNotNull("No expected callback", mCurrent.poll(TIMEOUT_MS, (cv) -> {
                if (cv.callbackType == CallbackType.ON_ERROR
                        && iface.equals((String) cv.callbackParam)) {
                    return true;
                }
                if (cv.callbackType == CallbackType.ON_TETHERED_IFACES
                        && ((List<String>) cv.callbackParam).contains(iface)) {
                    return true;
                }

                return false;
            }));
        }

        public Network getCurrentValidUpstream() {
            final CallbackValue result = mCurrent.poll(TIMEOUT_MS, (cv) -> {
                return (cv.callbackType == CallbackType.ON_UPSTREAM)
                        && cv.callbackParam != null;
            });

            assertNotNull("No valid upstream", result);
            return (Network) result.callbackParam;
        }

        public void assumeTetheringSupported() {
            final ArrayTrackRecord<CallbackValue>.ReadHead history =
                    mHistory.newReadHead();
            assertNotNull("No onSupported callback", history.poll(TIMEOUT_MS, (cv) -> {
                if (cv.callbackType != CallbackType.ON_SUPPORTED) return false;

                assumeTrue(cv.callbackParam2 == 1 /* supported */);
                return true;
            }));
        }

        public void assumeWifiTetheringSupported(final Context ctx) throws Exception {
            assumeTetheringSupported();

            assumeTrue(!getTetheringInterfaceRegexps().getTetherableWifiRegexs().isEmpty());

            final PackageManager pm = ctx.getPackageManager();
            assumeTrue(pm.hasSystemFeature(PackageManager.FEATURE_WIFI));

            WifiManager wm = ctx.getSystemService(WifiManager.class);
            // Wifi feature flags only work when wifi is on.
            final boolean previousWifiEnabledState = wm.isWifiEnabled();
            try {
                if (!previousWifiEnabledState) SystemUtil.runShellCommand("svc wifi enable");
                waitForWifiEnabled(ctx);
                assumeTrue(wm.isPortableHotspotSupported());
            } finally {
                if (!previousWifiEnabledState) SystemUtil.runShellCommand("svc wifi disable");
            }
        }

        public TetheringInterfaceRegexps getTetheringInterfaceRegexps() {
            return mTetherableRegex;
        }

        public List<String> getTetherableInterfaces() {
            return mTetherableIfaces;
        }

        public List<String> getTetheredInterfaces() {
            return mTetheredIfaces;
        }
    }

    private static void waitForWifiEnabled(final Context ctx) throws Exception {
        WifiManager wm = ctx.getSystemService(WifiManager.class);
        if (wm.isWifiEnabled()) return;

        final ConditionVariable mWaiting = new ConditionVariable();
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    if (wm.isWifiEnabled()) mWaiting.open();
                }
            }
        };
        try {
            ctx.registerReceiver(receiver, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
            if (!mWaiting.block(DEFAULT_TIMEOUT_MS)) {
                assertTrue("Wifi did not become enabled after " + DEFAULT_TIMEOUT_MS + "ms",
                        wm.isWifiEnabled());
            }
        } finally {
            ctx.unregisterReceiver(receiver);
        }
    }

    public TestTetheringEventCallback registerTetheringEventCallback() {
        final TestTetheringEventCallback tetherEventCallback =
                new TestTetheringEventCallback();

        mTm.registerTetheringEventCallback(c -> c.run() /* executor */, tetherEventCallback);
        tetherEventCallback.expectCallbackStarted();

        return tetherEventCallback;
    }

    public void unregisterTetheringEventCallback(final TestTetheringEventCallback callback) {
        mTm.unregisterTetheringEventCallback(callback);
    }

    private static List<String> getWifiTetherableInterfaceRegexps(
            final TestTetheringEventCallback callback) {
        return callback.getTetheringInterfaceRegexps().getTetherableWifiRegexs();
    }

    public static boolean isWifiTetheringSupported(final TestTetheringEventCallback callback) {
        return !getWifiTetherableInterfaceRegexps(callback).isEmpty();
    }

    public void startWifiTethering(final TestTetheringEventCallback callback)
            throws InterruptedException {
        final List<String> wifiRegexs = getWifiTetherableInterfaceRegexps(callback);
        assertFalse(isIfaceMatch(wifiRegexs, callback.getTetheredInterfaces()));

        final StartTetheringCallback startTetheringCallback = new StartTetheringCallback();
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI)
                .setShouldShowEntitlementUi(false).build();
        mTm.startTethering(request, c -> c.run() /* executor */, startTetheringCallback);
        startTetheringCallback.verifyTetheringStarted();

        callback.expectTetheredInterfacesChanged(wifiRegexs);

        callback.expectOneOfOffloadStatusChanged(
                TETHER_HARDWARE_OFFLOAD_STARTED,
                TETHER_HARDWARE_OFFLOAD_FAILED);
    }

    private static class StopSoftApCallback implements SoftApCallback {
        private final ConditionVariable mWaiting = new ConditionVariable();
        @Override
        public void onStateChanged(int state, int failureReason) {
            if (state == WifiManager.WIFI_AP_STATE_DISABLED) mWaiting.open();
        }

        @Override
        public void onConnectedClientsChanged(List<WifiClient> clients) { }

        public void waitForSoftApStopped() {
            if (!mWaiting.block(DEFAULT_TIMEOUT_MS)) {
                fail("stopSoftAp Timeout");
            }
        }
    }

    // Wait for softAp to be disabled. This is necessary on devices where stopping softAp
    // deletes the interface. On these devices, tethering immediately stops when the softAp
    // interface is removed, but softAp is not yet fully disabled. Wait for softAp to be
    // fully disabled, because otherwise the next test might fail because it attempts to
    // start softAp before it's fully stopped.
    public void expectSoftApDisabled() {
        final StopSoftApCallback callback = new StopSoftApCallback();
        try {
            mWm.registerSoftApCallback(c -> c.run(), callback);
            // registerSoftApCallback will immediately call the callback with the current state, so
            // this callback will fire even if softAp is already disabled.
            callback.waitForSoftApStopped();
        } finally {
            mWm.unregisterSoftApCallback(callback);
        }
    }

    public void stopWifiTethering(final TestTetheringEventCallback callback) {
        mTm.stopTethering(TETHERING_WIFI);
        expectSoftApDisabled();
        callback.expectTetheredInterfacesChanged(null);
        callback.expectOneOfOffloadStatusChanged(TETHER_HARDWARE_OFFLOAD_STOPPED);
    }
}
