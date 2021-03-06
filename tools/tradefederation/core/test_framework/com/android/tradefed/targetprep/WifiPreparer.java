/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.tradefed.targetprep;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;

/**
 * A {@link ITargetPreparer} that configures wifi on the device if necessary.
 *
 * <p>Unlike {@link DeviceSetup}, this preparer works when adb is not root aka user builds.
 */
@OptionClass(alias = "wifi")
public class WifiPreparer extends BaseTargetPreparer {

    @Option(name="wifi-network", description="the name of wifi network to connect to.")
    private String mWifiNetwork = null;

    @Option(name="wifi-psk", description="WPA-PSK passphrase of wifi network to connect to.")
    private String mWifiPsk = null;

    @Option(name = "disconnect-wifi-after-test", description =
            "disconnect from wifi network after test completes.")
    private boolean mDisconnectWifiAfterTest = true;

    @Option(name = "monitor-network", description =
            "monitor network connectivity during test.")
    private boolean mMonitorNetwork = true;

    @Option(name = "skip", description = "skip the connectivity check and wifi setup")
    private boolean mSkip = false;

    @Option(name = "verify-only", description = "Skip setup and verify a wifi connection.")
    private boolean mVerifyOnly = false;

    /** {@inheritDoc} */
    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        if (mSkip) {
            return;
        }
        ITestDevice device = testInfo.getDevice();
        if (mVerifyOnly) {
            if (!device.isWifiEnabled()) {
                throw new TargetSetupError(
                        "The device does not have wifi enabled.",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.NO_WIFI);
            } else if (!device.checkConnectivity()) {
                throw new TargetSetupError(
                        "The device has no wifi connection.",
                        device.getDeviceDescriptor(),
                        InfraErrorIdentifier.NO_WIFI);
            }
            return;
        }

        if (mWifiNetwork == null) {
            throw new TargetSetupError("wifi-network not specified", device.getDeviceDescriptor());
        }

        InvocationMetricLogger.addInvocationMetrics(InvocationMetricKey.WIFI_AP_NAME, mWifiNetwork);
        if (!device.connectToWifiNetworkIfNeeded(mWifiNetwork, mWifiPsk)) {
            throw new TargetSetupError(
                    String.format(
                            "Failed to connect to wifi network %s on %s",
                            mWifiNetwork, device.getSerialNumber()),
                    device.getDeviceDescriptor(),
                    InfraErrorIdentifier.WIFI_FAILED_CONNECT);
        }
        if (mMonitorNetwork) {
            device.enableNetworkMonitor();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        if (mSkip || mVerifyOnly) {
            return;
        }
        ITestDevice device = testInfo.getDevice();
        if (e instanceof DeviceFailedToBootError) {
            CLog.d("boot failure: skipping wifi teardown");
            return;
        }

        if (mMonitorNetwork) {
            device.disableNetworkMonitor();
        }

        if (mWifiNetwork != null && mDisconnectWifiAfterTest && device.isWifiEnabled()) {
            if (!device.disconnectFromWifi()) {
                CLog.w("Failed to disconnect from wifi network on %s", device.getSerialNumber());
                return;
            }
            CLog.i("Successfully disconnected from wifi network on %s", device.getSerialNumber());
        }
    }
}
