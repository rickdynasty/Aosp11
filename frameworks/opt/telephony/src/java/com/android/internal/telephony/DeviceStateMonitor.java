/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.telephony;

import static android.hardware.radio.V1_0.DeviceStateType.CHARGING_STATE;
import static android.hardware.radio.V1_0.DeviceStateType.LOW_DATA_EXPECTED;
import static android.hardware.radio.V1_0.DeviceStateType.POWER_SAVE_MODE;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.hardware.radio.V1_5.IndicationFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TetheringManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.RegistrantList;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.CarrierConfigManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.SignalThresholdInfo;
import android.util.LocalLog;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * The device state monitor monitors the device state such as charging state, power saving sate,
 * and then passes down the information to the radio modem for the modem to perform its own
 * proprietary power saving strategy. Device state monitor also turns off the unsolicited
 * response from the modem when the device does not need to receive it, for example, device's
 * screen is off and does not have activities like tethering, remote display, etc...This effectively
 * prevents the CPU from waking up by those unnecessary unsolicited responses such as signal
 * strength update.
 */
public class DeviceStateMonitor extends Handler {
    protected static final boolean DBG = false;      /* STOPSHIP if true */
    protected static final String TAG = DeviceStateMonitor.class.getSimpleName();

    static final int EVENT_RIL_CONNECTED                = 0;
    static final int EVENT_CAR_MODE_CHANGED             = 1;
    @VisibleForTesting
    static final int EVENT_SCREEN_STATE_CHANGED         = 2;
    static final int EVENT_POWER_SAVE_MODE_CHANGED      = 3;
    @VisibleForTesting
    static final int EVENT_CHARGING_STATE_CHANGED       = 4;
    static final int EVENT_TETHERING_STATE_CHANGED      = 5;
    static final int EVENT_RADIO_AVAILABLE              = 6;
    @VisibleForTesting
    static final int EVENT_WIFI_CONNECTION_CHANGED      = 7;
    static final int EVENT_UPDATE_ALWAYS_REPORT_SIGNAL_STRENGTH = 8;

    private static final int WIFI_UNAVAILABLE = 0;
    private static final int WIFI_AVAILABLE = 1;

    private static final int NR_NSA_TRACKING_INDICATIONS_OFF = 0;
    private static final int NR_NSA_TRACKING_INDICATIONS_EXTENDED = 1;
    private static final int NR_NSA_TRACKING_INDICATIONS_ALWAYS_ON = 2;

    private final Phone mPhone;

    private final LocalLog mLocalLog = new LocalLog(100);

    private final RegistrantList mPhysicalChannelConfigRegistrants = new RegistrantList();

    private final NetworkRequest mWifiNetworkRequest =
            new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build();

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        Set<Network> mWifiNetworks = new HashSet<>();

        @Override
        public void onAvailable(Network network) {
            synchronized (mWifiNetworks) {
                if (mWifiNetworks.size() == 0) {
                    // We just connected to Wifi, so send an update.
                    obtainMessage(EVENT_WIFI_CONNECTION_CHANGED, WIFI_AVAILABLE, 0).sendToTarget();
                    log("Wifi (default) connected", true);
                }
                mWifiNetworks.add(network);
            }
        }

        @Override
        public void onLost(Network network) {
            synchronized (mWifiNetworks) {
                mWifiNetworks.remove(network);
                if (mWifiNetworks.size() == 0) {
                    // We just disconnected from the last connected wifi, so send an update.
                    obtainMessage(
                            EVENT_WIFI_CONNECTION_CHANGED, WIFI_UNAVAILABLE, 0).sendToTarget();
                    log("Wifi (default) disconnected", true);
                }
            }
        }
    };

    /**
     * Flag for wifi/usb/bluetooth tethering turned on or not
     */
    private boolean mIsTetheringOn;

    /**
     * Screen state provided by Display Manager. True indicates one of the screen is on, otherwise
     * all off.
     */
    private boolean mIsScreenOn;

    /**
     * Indicating the device is plugged in and is supplying sufficient power that the battery level
     * is going up (or the battery is fully charged). See BatteryManager.isCharging() for the
     * details
     */
    private boolean mIsCharging;

    /**
     * Flag for device power save mode. See PowerManager.isPowerSaveMode() for the details.
     * Note that it is not possible both mIsCharging and mIsPowerSaveOn are true at the same time.
     * The system will automatically end power save mode when the device starts charging.
     */
    private boolean mIsPowerSaveOn;

    /**
     * Low data expected mode. True indicates low data traffic is expected, for example, when the
     * device is idle (e.g. screen is off and not doing tethering in the background). Note this
     * doesn't mean no data is expected.
     */
    private boolean mIsLowDataExpected;

    /**
     * Wifi is connected. True means both that cellular is likely to be asleep when the screen is
     * on and that in most cases the device location is relatively close to the WiFi AP. This means
     * that fewer location updates should be provided by cellular.
     */
    private boolean mIsWifiConnected;

    /**
     * Car mode is on. True means the device is currently connected to Android Auto. This should be
     * handled by mIsScreenOn, but the Android Auto display is private and not accessible by
     * DeviceStateMonitor from DisplayMonitor.
     */
    private boolean mIsCarModeOn;

    /**
     * True indicates we should always enable the signal strength reporting from radio.
     */
    private boolean mIsAlwaysSignalStrengthReportingEnabled;

    @VisibleForTesting
    static final int CELL_INFO_INTERVAL_SHORT_MS = 2000;
    @VisibleForTesting
    static final int CELL_INFO_INTERVAL_LONG_MS = 10000;

    /** The minimum required wait time between cell info requests to the modem */
    private int mCellInfoMinInterval = CELL_INFO_INTERVAL_SHORT_MS;

    /**
     * The unsolicited response filter. See IndicationFilter defined in types.hal for the definition
     * of each bit.
     */
    private int mUnsolicitedResponseFilter = IndicationFilter.ALL;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) { }

                @Override
                public void onDisplayRemoved(int displayId) { }

                @Override
                public void onDisplayChanged(int displayId) {
                    boolean screenOn = isScreenOn();
                    Message msg = obtainMessage(EVENT_SCREEN_STATE_CHANGED);
                    msg.arg1 = screenOn ? 1 : 0;
                    sendMessage(msg);
                }
            };

    /**
     * Device state broadcast receiver
     */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("received: " + intent, true);

            Message msg;
            switch (intent.getAction()) {
                case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                    msg = obtainMessage(EVENT_POWER_SAVE_MODE_CHANGED);
                    msg.arg1 = isPowerSaveModeOn() ? 1 : 0;
                    log("Power Save mode " + ((msg.arg1 == 1) ? "on" : "off"), true);
                    break;
                case BatteryManager.ACTION_CHARGING:
                    msg = obtainMessage(EVENT_CHARGING_STATE_CHANGED);
                    msg.arg1 = 1;   // charging
                    break;
                case BatteryManager.ACTION_DISCHARGING:
                    msg = obtainMessage(EVENT_CHARGING_STATE_CHANGED);
                    msg.arg1 = 0;   // not charging
                    break;
                case TetheringManager.ACTION_TETHER_STATE_CHANGED:
                    ArrayList<String> activeTetherIfaces = intent.getStringArrayListExtra(
                            TetheringManager.EXTRA_ACTIVE_TETHER);

                    boolean isTetheringOn = activeTetherIfaces != null
                            && activeTetherIfaces.size() > 0;
                    log("Tethering " + (isTetheringOn ? "on" : "off"), true);
                    msg = obtainMessage(EVENT_TETHERING_STATE_CHANGED);
                    msg.arg1 = isTetheringOn ? 1 : 0;
                    break;
                case UiModeManager.ACTION_ENTER_CAR_MODE_PRIORITIZED:
                    msg = obtainMessage(EVENT_CAR_MODE_CHANGED);
                    msg.arg1 = 1; // car mode on
                    break;
                case UiModeManager.ACTION_EXIT_CAR_MODE_PRIORITIZED:
                    msg = obtainMessage(EVENT_CAR_MODE_CHANGED);
                    msg.arg1 = 0; // car mode off
                    break;
                default:
                    log("Unexpected broadcast intent: " + intent, false);
                    return;
            }
            sendMessage(msg);
        }
    };

    /**
     * Device state monitor constructor. Note that each phone object should have its own device
     * state monitor, meaning there will be two device monitors on the multi-sim device.
     *
     * @param phone Phone object
     */
    public DeviceStateMonitor(Phone phone) {
        mPhone = phone;
        DisplayManager dm = (DisplayManager) phone.getContext().getSystemService(
                Context.DISPLAY_SERVICE);
        dm.registerDisplayListener(mDisplayListener, null);

        mIsPowerSaveOn = isPowerSaveModeOn();
        mIsCharging = isDeviceCharging();
        mIsScreenOn = isScreenOn();
        mIsCarModeOn = isCarModeOn();
        // Assuming tethering is always off after boot up.
        mIsTetheringOn = false;
        mIsLowDataExpected = false;

        log("DeviceStateMonitor mIsTetheringOn=" + mIsTetheringOn
                + ", mIsScreenOn=" + mIsScreenOn
                + ", mIsCharging=" + mIsCharging
                + ", mIsPowerSaveOn=" + mIsPowerSaveOn
                + ", mIsLowDataExpected=" + mIsLowDataExpected
                + ", mIsCarModeOn=" + mIsCarModeOn
                + ", mIsWifiConnected=" + mIsWifiConnected
                + ", mIsAlwaysSignalStrengthReportingEnabled="
                + mIsAlwaysSignalStrengthReportingEnabled, false);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(BatteryManager.ACTION_CHARGING);
        filter.addAction(BatteryManager.ACTION_DISCHARGING);
        filter.addAction(TetheringManager.ACTION_TETHER_STATE_CHANGED);
        filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE_PRIORITIZED);
        filter.addAction(UiModeManager.ACTION_EXIT_CAR_MODE_PRIORITIZED);
        mPhone.getContext().registerReceiver(mBroadcastReceiver, filter, null, mPhone);

        mPhone.mCi.registerForRilConnected(this, EVENT_RIL_CONNECTED, null);
        mPhone.mCi.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);

        ConnectivityManager cm = (ConnectivityManager) phone.getContext().getSystemService(
                Context.CONNECTIVITY_SERVICE);
        cm.registerNetworkCallback(mWifiNetworkRequest, mNetworkCallback);
    }

    /**
     * @return True if low data is expected
     */
    private boolean isLowDataExpected() {
        return !mIsCharging && !mIsTetheringOn && !mIsScreenOn;
    }

    /**
     * @return The minimum period between CellInfo requests to the modem
     */
    @VisibleForTesting
    public int computeCellInfoMinInterval() {
        // The screen is on and we're either on cellular or charging. Screen on + Charging is
        // a likely vehicular scenario, even if there is a nomadic AP.
        if (mIsScreenOn && !mIsWifiConnected) {
            // Screen on without WiFi - We are in a high power likely mobile situation.
            return CELL_INFO_INTERVAL_SHORT_MS;
        } else if (mIsScreenOn && mIsCharging) {
            // Screen is on and we're charging, so we favor accuracy over power.
            return CELL_INFO_INTERVAL_SHORT_MS;
        } else {
            // If the screen is off, apps should not need cellular location at rapid intervals.
            // If the screen is on but we are on wifi and not charging then cellular location
            // accuracy is not crucial, so favor modem power saving over high accuracy.
            return CELL_INFO_INTERVAL_LONG_MS;
        }
    }

    /**
     * @return True if signal strength update should be enabled. See details in
     *         android.hardware.radio@1.2::IndicationFilter::SIGNAL_STRENGTH.
     */
    private boolean shouldEnableSignalStrengthReports() {
        // We should enable signal strength update if one of the following condition is true.
        // 1. The device is charging.
        // 2. When the screen is on.
        // 3. Any of system services is registrating to always listen to signal strength changes
        return mIsAlwaysSignalStrengthReportingEnabled || mIsCharging || mIsScreenOn;
    }

    /**
     * @return True if full network state update should be enabled. When off, only significant
     *         changes will trigger the network update unsolicited response. See details in
     *         android.hardware.radio@1.2::IndicationFilter::FULL_NETWORK_STATE.
     */
    private boolean shouldEnableFullNetworkStateReports() {
        return shouldEnableNrTrackingIndications();
    }

    /**
     * @return True if data call dormancy changed update should be enabled. See details in
     *         android.hardware.radio@1.2::IndicationFilter::DATA_CALL_DORMANCY_CHANGED.
     */
    private boolean shouldEnableDataCallDormancyChangedReports() {
        return shouldEnableNrTrackingIndications();
    }

    /**
     * @return True if link capacity estimate update should be enabled. See details in
     *         android.hardware.radio@1.2::IndicationFilter::LINK_CAPACITY_ESTIMATE.
     */
    private boolean shouldEnableLinkCapacityEstimateReports() {
        return shouldEnableHighPowerConsumptionIndications();
    }

    /**
     * @return True if physical channel config update should be enabled. See details in
     *         android.hardware.radio@1.2::IndicationFilter::PHYSICAL_CHANNEL_CONFIG.
     */
    private boolean shouldEnablePhysicalChannelConfigReports() {
        return shouldEnableNrTrackingIndications();
    }

    /**
     * @return True if barring info update should be enabled. See details in
     *         android.hardware.radio@1.5::IndicationFilter::BARRING_INFO.
     */
    private boolean shouldEnableBarringInfoReports() {
        return shouldEnableHighPowerConsumptionIndications();
    }

    /**
     * A common policy to determine if we should enable the necessary indications update,
     * for power consumption's sake.
     *
     * @return True if the response update should be enabled.
     */
    public boolean shouldEnableHighPowerConsumptionIndications() {
        // We should enable indications reports if one of the following condition is true.
        // 1. The device is charging.
        // 2. When the screen is on.
        // 3. When the tethering is on.
        // 4. When car mode (Android Auto) is on.
        return mIsCharging || mIsScreenOn || mIsTetheringOn || mIsCarModeOn;
    }

    /**
     * For 5G NSA devices, a policy to determine if we should enable NR tracking indications.
     *
     * @return True if the response update should be enabled.
     */
    private boolean shouldEnableNrTrackingIndications() {
        int trackingMode = Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                Settings.Global.NR_NSA_TRACKING_SCREEN_OFF_MODE, NR_NSA_TRACKING_INDICATIONS_OFF);
        switch (trackingMode) {
            case NR_NSA_TRACKING_INDICATIONS_ALWAYS_ON:
                return true;
            case NR_NSA_TRACKING_INDICATIONS_EXTENDED:
                if (mPhone.getServiceState().getNrState()
                        == NetworkRegistrationInfo.NR_STATE_CONNECTED) {
                    return true;
                }
                // fallthrough
            case NR_NSA_TRACKING_INDICATIONS_OFF:
                return shouldEnableHighPowerConsumptionIndications();
            default:
                return shouldEnableHighPowerConsumptionIndications();
        }
    }

    /**
     * Set if Telephony need always report signal strength.
     *
     * @param isEnable
     */
    public void setAlwaysReportSignalStrength(boolean isEnable) {
        Message msg = obtainMessage(EVENT_UPDATE_ALWAYS_REPORT_SIGNAL_STRENGTH);
        msg.arg1 = isEnable ? 1 : 0;
        sendMessage(msg);
    }

    /**
     * Message handler
     *
     * @param msg The message
     */
    @Override
    public void handleMessage(Message msg) {
        log("handleMessage msg=" + msg, false);
        switch (msg.what) {
            case EVENT_RIL_CONNECTED:
            case EVENT_RADIO_AVAILABLE:
                onReset();
                break;
            case EVENT_SCREEN_STATE_CHANGED:
            case EVENT_POWER_SAVE_MODE_CHANGED:
            case EVENT_CHARGING_STATE_CHANGED:
            case EVENT_TETHERING_STATE_CHANGED:
            case EVENT_UPDATE_ALWAYS_REPORT_SIGNAL_STRENGTH:
            case EVENT_CAR_MODE_CHANGED:
                onUpdateDeviceState(msg.what, msg.arg1 != 0);
                break;
            case EVENT_WIFI_CONNECTION_CHANGED:
                onUpdateDeviceState(msg.what, msg.arg1 != WIFI_UNAVAILABLE);
                break;
            default:
                throw new IllegalStateException("Unexpected message arrives. msg = " + msg.what);
        }
    }

    /**
     * Update the device and send the information to the modem.
     *
     * @param eventType Device state event type
     * @param state True if enabled/on, otherwise disabled/off.
     */
    private void onUpdateDeviceState(int eventType, boolean state) {
        final boolean shouldEnableBarringInfoReportsOld = shouldEnableBarringInfoReports();
        final boolean wasHighPowerEnabled = shouldEnableHighPowerConsumptionIndications();
        switch (eventType) {
            case EVENT_SCREEN_STATE_CHANGED:
                if (mIsScreenOn == state) return;
                mIsScreenOn = state;
                break;
            case EVENT_CHARGING_STATE_CHANGED:
                if (mIsCharging == state) return;
                mIsCharging = state;
                sendDeviceState(CHARGING_STATE, mIsCharging);
                break;
            case EVENT_TETHERING_STATE_CHANGED:
                if (mIsTetheringOn == state) return;
                mIsTetheringOn = state;
                break;
            case EVENT_POWER_SAVE_MODE_CHANGED:
                if (mIsPowerSaveOn == state) return;
                mIsPowerSaveOn = state;
                sendDeviceState(POWER_SAVE_MODE, mIsPowerSaveOn);
                break;
            case EVENT_WIFI_CONNECTION_CHANGED:
                if (mIsWifiConnected == state) return;
                mIsWifiConnected = state;
                break;
            case EVENT_UPDATE_ALWAYS_REPORT_SIGNAL_STRENGTH:
                if (mIsAlwaysSignalStrengthReportingEnabled == state) return;
                mIsAlwaysSignalStrengthReportingEnabled = state;
                break;
            case EVENT_CAR_MODE_CHANGED:
                if (mIsCarModeOn == state) return;
                mIsCarModeOn = state;
                break;
            default:
                return;
        }

        final boolean isHighPowerEnabled = shouldEnableHighPowerConsumptionIndications();
        if (wasHighPowerEnabled != isHighPowerEnabled) {
            mPhone.notifyDeviceIdleStateChanged(!isHighPowerEnabled /*isIdle*/);
        }

        final int newCellInfoMinInterval = computeCellInfoMinInterval();
        if (mCellInfoMinInterval != newCellInfoMinInterval) {
            mCellInfoMinInterval = newCellInfoMinInterval;
            setCellInfoMinInterval(mCellInfoMinInterval);
            log("CellInfo Min Interval Updated to " + newCellInfoMinInterval, true);
        }

        if (mIsLowDataExpected != isLowDataExpected()) {
            mIsLowDataExpected = !mIsLowDataExpected;
            sendDeviceState(LOW_DATA_EXPECTED, mIsLowDataExpected);
        }

        // Registration Failure is always reported.
        int newFilter = IndicationFilter.REGISTRATION_FAILURE;

        if (shouldEnableSignalStrengthReports()) {
            newFilter |= IndicationFilter.SIGNAL_STRENGTH;
        }

        if (shouldEnableFullNetworkStateReports()) {
            newFilter |= IndicationFilter.FULL_NETWORK_STATE;
        }

        if (shouldEnableDataCallDormancyChangedReports()) {
            newFilter |= IndicationFilter.DATA_CALL_DORMANCY_CHANGED;
        }

        if (shouldEnableLinkCapacityEstimateReports()) {
            newFilter |= IndicationFilter.LINK_CAPACITY_ESTIMATE;
        }

        if (shouldEnablePhysicalChannelConfigReports()) {
            newFilter |= IndicationFilter.PHYSICAL_CHANNEL_CONFIG;
        }

        final boolean shouldEnableBarringInfoReports = shouldEnableBarringInfoReports();
        if (shouldEnableBarringInfoReports) {
            newFilter |= IndicationFilter.BARRING_INFO;
        }

        // notify PhysicalChannelConfig registrants if state changes
        if ((newFilter & IndicationFilter.PHYSICAL_CHANNEL_CONFIG)
                != (mUnsolicitedResponseFilter & IndicationFilter.PHYSICAL_CHANNEL_CONFIG)) {
            mPhysicalChannelConfigRegistrants.notifyResult(
                    (newFilter & IndicationFilter.PHYSICAL_CHANNEL_CONFIG) != 0);
        }

        setUnsolResponseFilter(newFilter, false);

        // Pull barring info AFTER setting filter, the order matters
        if (shouldEnableBarringInfoReports && !shouldEnableBarringInfoReportsOld) {
            if (DBG) log("Manually pull barring info...", true);
            // use a null message since we don't care of receiving response
            mPhone.mCi.getBarringInfo(null);
        }
    }

    /**
     * Called when RIL is connected during boot up or radio becomes available after modem restart.
     *
     * When modem crashes, if the user turns the screen off before RIL reconnects, device
     * state and filter cannot be sent to modem. Resend the state here so that modem
     * has the correct state (to stop signal strength reporting, etc).
     */
    private void onReset() {
        log("onReset.", true);
        sendDeviceState(CHARGING_STATE, mIsCharging);
        sendDeviceState(LOW_DATA_EXPECTED, mIsLowDataExpected);
        sendDeviceState(POWER_SAVE_MODE, mIsPowerSaveOn);
        setUnsolResponseFilter(mUnsolicitedResponseFilter, true);
        setSignalStrengthReportingCriteria();
        setLinkCapacityReportingCriteria();
        setCellInfoMinInterval(mCellInfoMinInterval);
    }

    /**
     * Convert the device state type into string
     *
     * @param type Device state type
     * @return The converted string
     */
    private String deviceTypeToString(int type) {
        switch (type) {
            case CHARGING_STATE: return "CHARGING_STATE";
            case LOW_DATA_EXPECTED: return "LOW_DATA_EXPECTED";
            case POWER_SAVE_MODE: return "POWER_SAVE_MODE";
            default: return "UNKNOWN";
        }
    }

    /**
     * Send the device state to the modem.
     *
     * @param type Device state type. See DeviceStateType defined in types.hal.
     * @param state True if enabled/on, otherwise disabled/off
     */
    private void sendDeviceState(int type, boolean state) {
        log("send type: " + deviceTypeToString(type) + ", state=" + state, true);
        mPhone.mCi.sendDeviceState(type, state, null);
    }

    /**
     * Turn on/off the unsolicited response from the modem.
     *
     * @param newFilter See UnsolicitedResponseFilter in types.hal for the definition of each bit.
     * @param force Always set the filter when true.
     */
    private void setUnsolResponseFilter(int newFilter, boolean force) {
        if (force || newFilter != mUnsolicitedResponseFilter) {
            log("old filter: " + mUnsolicitedResponseFilter + ", new filter: " + newFilter, true);
            mPhone.mCi.setUnsolResponseFilter(newFilter, null);
            mUnsolicitedResponseFilter = newFilter;
        }
    }

    private void setSignalStrengthReportingCriteria() {
        mPhone.setSignalStrengthReportingCriteria(SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                AccessNetworkThresholds.GERAN, AccessNetworkType.GERAN, true);
        mPhone.setSignalStrengthReportingCriteria(SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSCP,
                AccessNetworkThresholds.UTRAN, AccessNetworkType.UTRAN, true);
        mPhone.setSignalStrengthReportingCriteria(SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                AccessNetworkThresholds.EUTRAN_RSRP, AccessNetworkType.EUTRAN, true);
        mPhone.setSignalStrengthReportingCriteria(SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                AccessNetworkThresholds.CDMA2000, AccessNetworkType.CDMA2000, true);
        if (mPhone.getHalVersion().greaterOrEqual(RIL.RADIO_HAL_VERSION_1_5)) {
            mPhone.setSignalStrengthReportingCriteria(
                    SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ,
                    AccessNetworkThresholds.EUTRAN_RSRQ, AccessNetworkType.EUTRAN, false);
            mPhone.setSignalStrengthReportingCriteria(
                    SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR,
                    AccessNetworkThresholds.EUTRAN_RSSNR, AccessNetworkType.EUTRAN, true);

            // Defaultly we only need SSRSRP for NGRAN signal criteria reporting
            mPhone.setSignalStrengthReportingCriteria(
                    SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRP,
                    AccessNetworkThresholds.NGRAN_RSRSRP, AccessNetworkType.NGRAN, true);
            mPhone.setSignalStrengthReportingCriteria(
                    SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRQ,
                    AccessNetworkThresholds.NGRAN_RSRSRQ, AccessNetworkType.NGRAN, false);
            mPhone.setSignalStrengthReportingCriteria(
                    SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSSINR,
                    AccessNetworkThresholds.NGRAN_SSSINR, AccessNetworkType.NGRAN, false);
        }
    }

    private void setLinkCapacityReportingCriteria() {
        mPhone.setLinkCapacityReportingCriteria(LINK_CAPACITY_DOWNLINK_THRESHOLDS,
                LINK_CAPACITY_UPLINK_THRESHOLDS, AccessNetworkType.GERAN);
        mPhone.setLinkCapacityReportingCriteria(LINK_CAPACITY_DOWNLINK_THRESHOLDS,
                LINK_CAPACITY_UPLINK_THRESHOLDS, AccessNetworkType.UTRAN);
        mPhone.setLinkCapacityReportingCriteria(LINK_CAPACITY_DOWNLINK_THRESHOLDS,
                LINK_CAPACITY_UPLINK_THRESHOLDS, AccessNetworkType.EUTRAN);
        mPhone.setLinkCapacityReportingCriteria(LINK_CAPACITY_DOWNLINK_THRESHOLDS,
                LINK_CAPACITY_UPLINK_THRESHOLDS, AccessNetworkType.CDMA2000);
        if (mPhone.getHalVersion().greaterOrEqual(RIL.RADIO_HAL_VERSION_1_5)) {
            mPhone.setLinkCapacityReportingCriteria(LINK_CAPACITY_DOWNLINK_THRESHOLDS,
                    LINK_CAPACITY_UPLINK_THRESHOLDS, AccessNetworkType.NGRAN);
        }
    }

    private void setCellInfoMinInterval(int rate) {
        mPhone.setCellInfoMinInterval(rate);
    }

    /**
     * @return True if the device is currently in power save mode.
     * See {@link android.os.BatteryManager#isPowerSaveMode BatteryManager.isPowerSaveMode()}.
     */
    private boolean isPowerSaveModeOn() {
        final PowerManager pm = (PowerManager) mPhone.getContext().getSystemService(
                Context.POWER_SERVICE);
        boolean retval = pm.isPowerSaveMode();
        log("isPowerSaveModeOn=" + retval, true);
        return retval;
    }

    /**
     * @return Return true if the battery is currently considered to be charging. This means that
     * the device is plugged in and is supplying sufficient power that the battery level is
     * going up (or the battery is fully charged).
     * See {@link android.os.BatteryManager#isCharging BatteryManager.isCharging()}.
     */
    private boolean isDeviceCharging() {
        final BatteryManager bm = (BatteryManager) mPhone.getContext().getSystemService(
                Context.BATTERY_SERVICE);
        boolean retval = bm.isCharging();
        log("isDeviceCharging=" + retval, true);
        return retval;
    }

    /**
     * @return True if one the device's screen (e.g. main screen, wifi display, HDMI display etc...)
     * is on.
     */
    private boolean isScreenOn() {
        // Note that we don't listen to Intent.SCREEN_ON and Intent.SCREEN_OFF because they are no
        // longer adequate for monitoring the screen state since they are not sent in cases where
        // the screen is turned off transiently such as due to the proximity sensor.
        final DisplayManager dm = (DisplayManager) mPhone.getContext().getSystemService(
                Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays();

        if (displays != null) {
            for (Display display : displays) {
                // Anything other than STATE_ON is treated as screen off, such as STATE_DOZE,
                // STATE_DOZE_SUSPEND, etc...
                if (display.getState() == Display.STATE_ON) {
                    log("Screen on for display=" + display, true);
                    return true;
                }
            }
            log("Screens all off", true);
            return false;
        }

        log("No displays found", true);
        return false;
    }

    /**
     * @return True if car mode (Android Auto) is on.
     */
    private boolean isCarModeOn() {
        final UiModeManager umm = (UiModeManager) mPhone.getContext().getSystemService(
                Context.UI_MODE_SERVICE);
        if (umm == null) return false;
        boolean retval = umm.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
        log("isCarModeOn=" + retval, true);
        return retval;
    }

    /**
     * Register for PhysicalChannelConfig notifications changed. On change, msg.obj will be an
     * AsyncResult with a boolean result. AsyncResult.result is true if notifications are enabled
     * and false if they are disabled.
     *
     * @param h Handler to notify
     * @param what msg.what when the message is delivered
     * @param obj AsyncResult.userObj when the message is delivered
     */
    public void registerForPhysicalChannelConfigNotifChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mPhysicalChannelConfigRegistrants.add(r);
    }

    /**
     * Unregister for PhysicalChannelConfig notifications changed.
     * @param h Handler to notify
     */
    public void unregisterForPhysicalChannelConfigNotifChanged(Handler h) {
        mPhysicalChannelConfigRegistrants.remove(h);
    }

    /**
     * @param msg Debug message
     * @param logIntoLocalLog True if log into the local log
     */
    private void log(String msg, boolean logIntoLocalLog) {
        if (DBG) Rlog.d(TAG, msg);
        if (logIntoLocalLog) {
            mLocalLog.log(msg);
        }
    }

    /**
     * Print the DeviceStateMonitor into the given stream.
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param pw A PrintWriter to which the dump is to be set.
     * @param args Additional arguments to the dump request.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.increaseIndent();
        ipw.println("mIsTetheringOn=" + mIsTetheringOn);
        ipw.println("mIsScreenOn=" + mIsScreenOn);
        ipw.println("mIsCharging=" + mIsCharging);
        ipw.println("mIsPowerSaveOn=" + mIsPowerSaveOn);
        ipw.println("mIsLowDataExpected=" + mIsLowDataExpected);
        ipw.println("mIsCarModeOn=" + mIsCarModeOn);
        ipw.println("mUnsolicitedResponseFilter=" + mUnsolicitedResponseFilter);
        ipw.println("mIsWifiConnected=" + mIsWifiConnected);
        ipw.println("mIsAlwaysSignalStrengthReportingEnabled="
                + mIsAlwaysSignalStrengthReportingEnabled);
        ipw.println("Local logs:");
        ipw.increaseIndent();
        mLocalLog.dump(fd, ipw, args);
        ipw.decreaseIndent();
        ipw.decreaseIndent();
        ipw.flush();
    }

    /**
     * dBm thresholds that correspond to changes in signal strength indications.
     */
    private static final class AccessNetworkThresholds {

        /**
         * List of dBm thresholds for GERAN {@link AccessNetworkType}.
         *
         * Calculated from GSM asu level thresholds - TS 27.007 Sec 8.5
         */
        public static final int[] GERAN = new int[] {
            -109,
            -103,
            -97,
            -89,
        };

        /**
         * List of default dBm thresholds for UTRAN {@link AccessNetworkType}.
         *
         * These thresholds are taken from the WCDMA RSCP defaults in {@link CarrierConfigManager}.
         * See TS 27.007 Sec 8.69.
         */
        public static final int[] UTRAN = new int[] {
            -114, /* SIGNAL_STRENGTH_POOR */
            -104, /* SIGNAL_STRENGTH_MODERATE */
            -94,  /* SIGNAL_STRENGTH_GOOD */
            -84   /* SIGNAL_STRENGTH_GREAT */
        };

        /**
         * List of default dBm RSRP thresholds for EUTRAN {@link AccessNetworkType}.
         *
         * These thresholds are taken from the LTE RSRP defaults in {@link CarrierConfigManager}.
         */
        public static final int[] EUTRAN_RSRP = new int[] {
            -128, /* SIGNAL_STRENGTH_POOR */
            -118, /* SIGNAL_STRENGTH_MODERATE */
            -108, /* SIGNAL_STRENGTH_GOOD */
            -98,  /* SIGNAL_STRENGTH_GREAT */
        };

        /**
         * List of default dB RSRQ thresholds for EUTRAN {@link AccessNetworkType}.
         *
         * These thresholds are taken from the LTE RSRQ defaults in {@link CarrierConfigManager}.
         */
        public static final int[] EUTRAN_RSRQ = new int[] {
            -20,  /* SIGNAL_STRENGTH_POOR */
            -17,  /* SIGNAL_STRENGTH_MODERATE */
            -14,  /* SIGNAL_STRENGTH_GOOD */
            -11   /* SIGNAL_STRENGTH_GREAT */
        };

        /**
         * List of default dB RSSNR thresholds for EUTRAN {@link AccessNetworkType}.
         *
         * These thresholds are taken from the LTE RSSNR defaults in {@link CarrierConfigManager}.
         */
        public static final int[] EUTRAN_RSSNR = new int[] {
            -3,  /* SIGNAL_STRENGTH_POOR */
            1,   /* SIGNAL_STRENGTH_MODERATE */
            5,   /* SIGNAL_STRENGTH_GOOD */
            13   /* SIGNAL_STRENGTH_GREAT */
        };

        /**
         * List of dBm thresholds for CDMA2000 {@link AccessNetworkType}.
         *
         * These correspond to EVDO level thresholds.
         */
        public static final int[] CDMA2000 = new int[] {
            -105,
            -90,
            -75,
            -65
        };

        /**
         * List of dB thresholds for NGRAN {@link AccessNetworkType} RSRSRP
         */
        public static final int[] NGRAN_RSRSRP = new int[] {
            -110, /* SIGNAL_STRENGTH_POOR */
            -90, /* SIGNAL_STRENGTH_MODERATE */
            -80, /* SIGNAL_STRENGTH_GOOD */
            -65,  /* SIGNAL_STRENGTH_GREAT */
        };

        /**
         * List of dB thresholds for NGRAN {@link AccessNetworkType} RSRSRP
         */
        public static final int[] NGRAN_RSRSRQ = new int[] {
            -31, /* SIGNAL_STRENGTH_POOR */
            -19, /* SIGNAL_STRENGTH_MODERATE */
            -7, /* SIGNAL_STRENGTH_GOOD */
            6  /* SIGNAL_STRENGTH_GREAT */
        };

        /**
         * List of dB thresholds for NGRAN {@link AccessNetworkType} SSSINR
         */
        public static final int[] NGRAN_SSSINR = new int[] {
            -5, /* SIGNAL_STRENGTH_POOR */
            5, /* SIGNAL_STRENGTH_MODERATE */
            15, /* SIGNAL_STRENGTH_GOOD */
            30  /* SIGNAL_STRENGTH_GREAT */
        };
    }

    /**
     * Downlink reporting thresholds in kbps
     *
     * <p>Threshold values taken from FCC Speed Guide when available
     * (https://www.fcc.gov/reports-research/guides/broadband-speed-guide) and Android WiFi speed
     * labels (https://support.google.com/pixelphone/answer/2819519#strength_speed).
     *
     */
    private static final int[] LINK_CAPACITY_DOWNLINK_THRESHOLDS = new int[] {
            100,    // VoIP
            500,    // Web browsing
            1000,   // SD video streaming
            5000,   // HD video streaming
            10000,  // file downloading
            20000,  // 4K video streaming
            50000,  // LTE-Advanced speeds
            75000,
            100000,
            200000, // 5G speeds
            500000,
            1000000,
            1500000,
            2000000
    };

    /** Uplink reporting thresholds in kbps */
    private static final int[] LINK_CAPACITY_UPLINK_THRESHOLDS = new int[] {
            100,    // VoIP calls
            500,
            1000,   // SD video calling
            5000,   // HD video calling
            10000,  // file uploading
            20000,  // 4K video calling
            50000,
            75000,
            100000,
            200000,
            500000
    };
}
