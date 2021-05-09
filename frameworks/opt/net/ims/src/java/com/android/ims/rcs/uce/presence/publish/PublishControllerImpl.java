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

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RcsContactUceCapability.CapabilityMechanism;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.RcsUceAdapter.PublishState;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IRcsUcePublishStateCallback;
import android.telephony.ims.feature.RcsFeature.RcsImsCapabilities;
import android.telephony.ims.feature.RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag;
import android.util.LocalLog;
import android.util.Log;

import com.android.ims.RcsFeatureManager;
import com.android.ims.rcs.uce.UceController.UceControllerCallback;
import com.android.ims.rcs.uce.util.UceUtils;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The implementation of PublishController.
 */
public class PublishControllerImpl implements PublishController {

    private static final String LOG_TAG = UceUtils.getLogPrefix() + "PublishController";

    /**
     * Used to inject PublishProcessor instances for testing.
     */
    @VisibleForTesting
    public interface PublishProcessorFactory {
        PublishProcessor createPublishProcessor(Context context, int subId,
                DeviceCapabilityInfo capabilityInfo, PublishControllerCallback callback);
    }

    /**
     * Used to inject DeviceCapabilityListener instances for testing.
     */
    @VisibleForTesting
    public interface DeviceCapListenerFactory {
        DeviceCapabilityListener createDeviceCapListener(Context context, int subId,
                DeviceCapabilityInfo capInfo, PublishControllerCallback callback);
    }

    private final int mSubId;
    private final Context mContext;
    private final LocalLog mLocalLog = new LocalLog(UceUtils.LOG_SIZE);
    private PublishHandler mPublishHandler;
    private volatile boolean mIsDestroyedFlag;
    private volatile boolean mReceivePublishFromService;
    private volatile RcsFeatureManager mRcsFeatureManager;
    private final UceControllerCallback mUceCtrlCallback;

    // The device publish state
    private @PublishState int mPublishState;
    // The timestamp of updating the publish state
    private Instant mPublishStateUpdatedTime = Instant.now();
    // The last PIDF XML used in the publish
    private String mPidfXml;

    // The callbacks to notify publish state changed.
    private RemoteCallbackList<IRcsUcePublishStateCallback> mPublishStateCallbacks;

    private final Object mPublishStateLock = new Object();

    // The information of the device's capabilities.
    private DeviceCapabilityInfo mDeviceCapabilityInfo;

    // The processor of publishing device's capabilities.
    private PublishProcessor mPublishProcessor;
    private PublishProcessorFactory mPublishProcessorFactory = (context, subId, capInfo, callback)
            -> new PublishProcessor(context, subId, capInfo, callback);

    // The listener to listen to the device's capabilities changed.
    private DeviceCapabilityListener mDeviceCapListener;
    private DeviceCapListenerFactory mDeviceCapListenerFactory = (context, subId, capInfo, callback)
            -> new DeviceCapabilityListener(context, subId, capInfo, callback);

    // Listen to the RCS availability status changed.
    private final IImsCapabilityCallback mRcsCapabilitiesCallback =
            new IImsCapabilityCallback.Stub() {
        @Override
        public void onQueryCapabilityConfiguration(
                int resultCapability, int resultRadioTech, boolean enabled) {
        }
        @Override
        public void onCapabilitiesStatusChanged(@RcsImsCapabilityFlag int capabilities) {
            logd("onCapabilitiesStatusChanged: " + capabilities);
            mPublishHandler.sendRcsCapabilitiesStatusChangedMsg(capabilities);
        }
        @Override
        public void onChangeCapabilityConfigurationError(int capability, int radioTech,
                int reason) {
        }
    };

    public PublishControllerImpl(Context context, int subId, UceControllerCallback callback,
            Looper looper) {
        mSubId = subId;
        mContext = context;
        mUceCtrlCallback = callback;
        logi("create");
        initPublishController(looper);
    }

    @VisibleForTesting
    public PublishControllerImpl(Context context, int subId, UceControllerCallback c,
            Looper looper, DeviceCapListenerFactory deviceCapFactory,
            PublishProcessorFactory processorFactory) {
        mSubId = subId;
        mContext = context;
        mUceCtrlCallback = c;
        mDeviceCapListenerFactory = deviceCapFactory;
        mPublishProcessorFactory = processorFactory;
        initPublishController(looper);
    }

    private void initPublishController(Looper looper) {
        mPublishState = RcsUceAdapter.PUBLISH_STATE_NOT_PUBLISHED;
        mPublishStateCallbacks = new RemoteCallbackList<>();
        mPublishHandler = new PublishHandler(this, looper);

        String[] serviceDescFeatureTagMap = getCarrierServiceDescriptionFeatureTagMap();
        mDeviceCapabilityInfo = new DeviceCapabilityInfo(mSubId, serviceDescFeatureTagMap);

        initPublishProcessor();
        initDeviceCapabilitiesListener();

        // Turn on the listener to listen to the device changes.
        mDeviceCapListener.initialize();
    }

    private void initPublishProcessor() {
        mPublishProcessor = mPublishProcessorFactory.createPublishProcessor(mContext, mSubId,
                mDeviceCapabilityInfo, mPublishControllerCallback);
    }

    private void initDeviceCapabilitiesListener() {
        mDeviceCapListener = mDeviceCapListenerFactory.createDeviceCapListener(mContext, mSubId,
                mDeviceCapabilityInfo, mPublishControllerCallback);
    }

    @Override
    public void onRcsConnected(RcsFeatureManager manager) {
        logd("onRcsConnected");
        mPublishHandler.sendRcsConnectedMsg(manager);
    }

    @Override
    public void onRcsDisconnected() {
        logd("onRcsDisconnected");
        mPublishHandler.sendRcsDisconnectedMsg();
    }

    @Override
    public void onDestroy() {
        logi("onDestroy");
        mPublishHandler.sendDestroyedMsg();
    }

    @Override
    public void onCarrierConfigChanged() {
        logi("onCarrierConfigChanged");
        mPublishHandler.sendCarrierConfigChangedMsg();
    }

    @Override
    public int getUcePublishState() {
        synchronized (mPublishStateLock) {
            return (!mIsDestroyedFlag) ? mPublishState : RcsUceAdapter.PUBLISH_STATE_OTHER_ERROR;
        }
    }

    @Override
    public RcsContactUceCapability addRegistrationOverrideCapabilities(Set<String> featureTags) {
        if (mDeviceCapabilityInfo.addRegistrationOverrideCapabilities(featureTags)) {
            mPublishHandler.sendPublishMessage(PublishController.PUBLISH_TRIGGER_OVERRIDE_CAPS);
        }
        return mDeviceCapabilityInfo.getDeviceCapabilities(
                RcsContactUceCapability.CAPABILITY_MECHANISM_PRESENCE, mContext);
    }

    @Override
    public RcsContactUceCapability removeRegistrationOverrideCapabilities(Set<String> featureTags) {
        if (mDeviceCapabilityInfo.removeRegistrationOverrideCapabilities(featureTags)) {
            mPublishHandler.sendPublishMessage(PublishController.PUBLISH_TRIGGER_OVERRIDE_CAPS);
        }
        return mDeviceCapabilityInfo.getDeviceCapabilities(
                RcsContactUceCapability.CAPABILITY_MECHANISM_PRESENCE, mContext);
    }

    @Override
    public RcsContactUceCapability clearRegistrationOverrideCapabilities() {
        if (mDeviceCapabilityInfo.clearRegistrationOverrideCapabilities()) {
            mPublishHandler.sendPublishMessage(PublishController.PUBLISH_TRIGGER_OVERRIDE_CAPS);
        }
        return mDeviceCapabilityInfo.getDeviceCapabilities(
                RcsContactUceCapability.CAPABILITY_MECHANISM_PRESENCE, mContext);
    }

    @Override
    public RcsContactUceCapability getLatestRcsContactUceCapability() {
        return mDeviceCapabilityInfo.getDeviceCapabilities(
                RcsContactUceCapability.CAPABILITY_MECHANISM_PRESENCE, mContext);
    }

    @Override
    public String getLastPidfXml() {
        return mPidfXml;
    }

    /**
     * Register a {@link PublishStateCallback} to listen to the published state changed.
     */
    @Override
    public void registerPublishStateCallback(@NonNull IRcsUcePublishStateCallback c) {
        synchronized (mPublishStateLock) {
            if (mIsDestroyedFlag) return;
            mPublishStateCallbacks.register(c);
            logd("registerPublishStateCallback: size="
                    + mPublishStateCallbacks.getRegisteredCallbackCount());
        }
        // Notify the current publish state
        mPublishHandler.sendNotifyCurrentPublishStateMessage(c);
    }

    /**
     * Removes an existing {@link PublishStateCallback}.
     */
    @Override
    public void unregisterPublishStateCallback(@NonNull IRcsUcePublishStateCallback c) {
        synchronized (mPublishStateLock) {
            if (mIsDestroyedFlag) return;
            mPublishStateCallbacks.unregister(c);
        }
    }

    // Clear all the publish state callbacks since the publish controller instance is destroyed.
    private void clearPublishStateCallbacks() {
        synchronized (mPublishStateLock) {
            logd("clearPublishStateCallbacks");
            final int lastIndex = mPublishStateCallbacks.getRegisteredCallbackCount() - 1;
            for (int index = lastIndex; index >= 0; index--) {
                IRcsUcePublishStateCallback callback =
                        mPublishStateCallbacks.getRegisteredCallbackItem(index);
                mPublishStateCallbacks.unregister(callback);
            }
        }
    }

    /**
     * Notify that the device's capabilities has been unpublished from the network.
     */
    @Override
    public void onUnpublish() {
        logd("onUnpublish");
        if (mIsDestroyedFlag) return;
        mPublishHandler.sendPublishStateChangedMessage(RcsUceAdapter.PUBLISH_STATE_NOT_PUBLISHED,
                Instant.now(), null /*pidfXml*/);
    }

    @Override
    public RcsContactUceCapability getDeviceCapabilities(@CapabilityMechanism int mechanism) {
        return mDeviceCapabilityInfo.getDeviceCapabilities(mechanism, mContext);
    }

    // The local publish request from the sub-components which interact with PublishController.
    private final PublishControllerCallback mPublishControllerCallback =
            new PublishControllerCallback() {
                @Override
                public void requestPublishFromInternal(@PublishTriggerType int type) {
                    logd("requestPublishFromInternal: type=" + type);
                    mPublishHandler.sendPublishMessage(type);
                }

                @Override
                public void onRequestCommandError(PublishRequestResponse requestResponse) {
                    logd("onRequestCommandError: taskId=" + requestResponse.getTaskId()
                            + ", time=" + requestResponse.getResponseTimestamp());
                    mPublishHandler.sendRequestCommandErrorMessage(requestResponse);
                }

                @Override
                public void onRequestNetworkResp(PublishRequestResponse requestResponse) {
                    logd("onRequestNetworkResp: taskId=" + requestResponse.getTaskId()
                            + ", time=" + requestResponse.getResponseTimestamp());
                    mPublishHandler.sendRequestNetworkRespMessage(requestResponse);
                }

                @Override
                public void setupRequestCanceledTimer(long taskId, long delay) {
                    logd("setupRequestCanceledTimer: taskId=" + taskId + ", delay=" + delay);
                    mPublishHandler.sendRequestCanceledTimerMessage(taskId, delay);
                }

                @Override
                public void clearRequestCanceledTimer() {
                    logd("clearRequestCanceledTimer");
                    mPublishHandler.clearRequestCanceledTimer();
                }

                @Override
                public void updatePublishRequestResult(@PublishState int state,
                        Instant updatedTime, String pidfXml) {
                    logd("updatePublishRequestResult: " + state + ", time=" + updatedTime);
                    mPublishHandler.sendPublishStateChangedMessage(state, updatedTime, pidfXml);
                }

                @Override
                public void updatePublishThrottle(int value) {
                    logd("updatePublishThrottle: value=" + value);
                    mPublishProcessor.updatePublishThrottle(value);
                }
            };

    /**
     * Publish the device's capabilities to the network. This method is triggered by ImsService.
     */
    @Override
    public void requestPublishCapabilitiesFromService(int triggerType) {
        logi("Receive the publish request from service: service trigger type=" + triggerType);
        mPublishHandler.sendPublishMessage(PublishController.PUBLISH_TRIGGER_SERVICE);
    }

    private static class PublishHandler extends Handler {
        private static final int MSG_RCS_CONNECTED = 1;
        private static final int MSG_RCS_DISCONNECTED = 2;
        private static final int MSG_DESTROYED = 3;
        private static final int MSG_CARRIER_CONFIG_CHANGED = 4;
        private static final int MSG_RCS_CAPABILITIES_CHANGED = 5;
        private static final int MSG_PUBLISH_STATE_CHANGED = 6;
        private static final int MSG_NOTIFY_CURRENT_PUBLISH_STATE = 7;
        private static final int MSG_REQUEST_PUBLISH = 8;
        private static final int MSG_REQUEST_CMD_ERROR = 9;
        private static final int MSG_REQUEST_NETWORK_RESPONSE = 10;
        private static final int MSG_REQUEST_CANCELED = 11;

        private final WeakReference<PublishControllerImpl> mPublishControllerRef;

        public PublishHandler(PublishControllerImpl publishController, Looper looper) {
            super(looper);
            mPublishControllerRef = new WeakReference<>(publishController);
        }

        @Override
        public void handleMessage(Message message) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            publishCtrl.logd("handleMessage: " + EVENT_DESCRIPTION.get(message.what));
            switch (message.what) {
                case MSG_RCS_CONNECTED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    RcsFeatureManager manager = (RcsFeatureManager) args.arg1;
                    args.recycle();
                    publishCtrl.handleRcsConnectedMessage(manager);
                    break;
                }
                case MSG_RCS_DISCONNECTED:
                    publishCtrl.handleRcsDisconnectedMessage();
                    break;

                case MSG_DESTROYED:
                    publishCtrl.handleDestroyedMessage();
                    break;

                case MSG_CARRIER_CONFIG_CHANGED:
                    publishCtrl.handleCarrierConfigChangedMessage();
                    break;

                case MSG_RCS_CAPABILITIES_CHANGED:
                    int RcsCapabilities = message.arg1;
                    publishCtrl.handleRcsCapabilitiesChangedMessage(RcsCapabilities);
                    break;

                case MSG_PUBLISH_STATE_CHANGED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    int newPublishState = (Integer) args.arg1;
                    Instant updatedTimestamp = (Instant) args.arg2;
                    String pidfXml = (String) args.arg3;
                    args.recycle();
                    publishCtrl.handlePublishStateChangedMessage(newPublishState, updatedTimestamp,
                            pidfXml);
                    break;
                }
                case MSG_NOTIFY_CURRENT_PUBLISH_STATE:
                    IRcsUcePublishStateCallback c = (IRcsUcePublishStateCallback) message.obj;
                    publishCtrl.handleNotifyCurrentPublishStateMessage(c);
                    break;

                case MSG_REQUEST_PUBLISH:
                    int type = message.arg1;
                    publishCtrl.handleRequestPublishMessage(type);
                    break;

                case MSG_REQUEST_CMD_ERROR:
                    PublishRequestResponse cmdErrorResponse = (PublishRequestResponse) message.obj;
                    publishCtrl.mPublishProcessor.onCommandError(cmdErrorResponse);
                    break;

                case MSG_REQUEST_NETWORK_RESPONSE:
                    PublishRequestResponse networkResponse = (PublishRequestResponse) message.obj;
                    publishCtrl.mPublishProcessor.onNetworkResponse(networkResponse);
                    break;

                case MSG_REQUEST_CANCELED:
                    long taskId = (Long) message.obj;
                    publishCtrl.handleRequestCanceledMessage(taskId);
                    break;
            }
        }

        /**
         * Remove all the messages from the handler.
         */
        public void onDestroy() {
            removeCallbacksAndMessages(null);
        }

        public void sendRcsConnectedMsg(RcsFeatureManager manager) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) return;
            if (publishCtrl.mIsDestroyedFlag) return;

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = manager;
            Message message = obtainMessage();
            message.what = MSG_RCS_CONNECTED;
            message.obj = args;
            sendMessage(message);
        }

        public void sendRcsDisconnectedMsg() {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) return;
            if (publishCtrl.mIsDestroyedFlag) return;

            Message message = obtainMessage();
            message.what = MSG_RCS_DISCONNECTED;
            sendMessage(message);
        }

        public void sendDestroyedMsg() {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) return;
            if (publishCtrl.mIsDestroyedFlag) return;

            Message message = obtainMessage();
            message.what = MSG_DESTROYED;
            sendMessage(message);
        }

        public void sendCarrierConfigChangedMsg() {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) return;
            if (publishCtrl.mIsDestroyedFlag) return;

            Message message = obtainMessage();
            message.what = MSG_CARRIER_CONFIG_CHANGED;
            sendMessage(message);
        }

        public void sendRcsCapabilitiesStatusChangedMsg(@RcsImsCapabilityFlag int capabilities) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) return;
            if (publishCtrl.mIsDestroyedFlag) return;

            Message message = obtainMessage();
            message.what = MSG_RCS_CAPABILITIES_CHANGED;
            message.arg1 = capabilities;
            sendMessage(message);
        }

        /**
         * Send the message to notify the publish state is changed.
         */
        public void sendPublishStateChangedMessage(@PublishState int publishState,
                @NonNull Instant updatedTimestamp, String pidfXml) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) return;
            if (publishCtrl.mIsDestroyedFlag) return;

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = publishState;
            args.arg2 = updatedTimestamp;
            args.arg3 = pidfXml;
            Message message = obtainMessage();
            message.what = MSG_PUBLISH_STATE_CHANGED;
            message.obj = args;
            sendMessage(message);
        }

        /**
         * Send the message to notify the new added callback of the latest publish state.
         */
        public void sendNotifyCurrentPublishStateMessage(
                IRcsUcePublishStateCallback callback) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) return;
            if (publishCtrl.mIsDestroyedFlag) return;

            Message message = obtainMessage();
            message.what = MSG_NOTIFY_CURRENT_PUBLISH_STATE;
            message.obj = callback;
            sendMessage(message);
        }

        public void sendPublishMessage(@PublishTriggerType int type) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) return;
            if (publishCtrl.mIsDestroyedFlag) return;

            Message message = obtainMessage();
            message.what = MSG_REQUEST_PUBLISH;
            message.arg1 = type;
            sendMessage(message);
        }

        public void sendPublishMessage(@PublishTriggerType int type, long delay) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) return;
            if (publishCtrl.mIsDestroyedFlag) return;

            Message message = obtainMessage();
            message.what = MSG_REQUEST_PUBLISH;
            message.arg1 = type;
            sendMessageDelayed(message, delay);
        }

        public void sendRequestCommandErrorMessage(PublishRequestResponse response) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            Message message = obtainMessage();
            message.what = MSG_REQUEST_CMD_ERROR;
            message.obj = response;
            sendMessage(message);
        }

        public void sendRequestNetworkRespMessage(PublishRequestResponse response) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            Message message = obtainMessage();
            message.what = MSG_REQUEST_NETWORK_RESPONSE;
            message.obj = response;
            sendMessage(message);
        }

        public void sendRequestCanceledTimerMessage(long taskId, long delay) {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            removeMessages(MSG_REQUEST_CANCELED, (Long) taskId);

            Message message = obtainMessage();
            message.what = MSG_REQUEST_CANCELED;
            message.obj = (Long) taskId;
            sendMessageDelayed(message, delay);
        }

        public void clearRequestCanceledTimer() {
            PublishControllerImpl publishCtrl = mPublishControllerRef.get();
            if (publishCtrl == null) {
                return;
            }
            if (publishCtrl.mIsDestroyedFlag) return;
            removeMessages(MSG_REQUEST_CANCELED);
        }

        private static Map<Integer, String> EVENT_DESCRIPTION = new HashMap<>();
        static {
            EVENT_DESCRIPTION.put(MSG_RCS_CONNECTED, "RCS_CONNECTED");
            EVENT_DESCRIPTION.put(MSG_RCS_DISCONNECTED, "RCS_DISCONNECTED");
            EVENT_DESCRIPTION.put(MSG_DESTROYED, "DESTROYED");
            EVENT_DESCRIPTION.put(MSG_CARRIER_CONFIG_CHANGED, "CARRIER_CONFIG_CHANGED");
            EVENT_DESCRIPTION.put(MSG_RCS_CAPABILITIES_CHANGED, "RCS_CAPABILITIES_CHANGED");
            EVENT_DESCRIPTION.put(MSG_PUBLISH_STATE_CHANGED, "PUBLISH_STATE_CHANGED");
            EVENT_DESCRIPTION.put(MSG_NOTIFY_CURRENT_PUBLISH_STATE, "NOTIFY_PUBLISH_STATE");
            EVENT_DESCRIPTION.put(MSG_REQUEST_PUBLISH, "REQUEST_PUBLISH");
            EVENT_DESCRIPTION.put(MSG_REQUEST_CMD_ERROR, "REQUEST_CMD_ERROR");
            EVENT_DESCRIPTION.put(MSG_REQUEST_NETWORK_RESPONSE, "REQUEST_NETWORK_RESPONSE");
            EVENT_DESCRIPTION.put(MSG_REQUEST_CANCELED, "REQUEST_CANCELED");
        }
    }

    /**
     * Check if the PUBLISH request is allowed.
     */
    private boolean isPublishRequestAllowed() {
        // The PUBLISH request requires that the RCS PRESENCE is capable.
        if (!mDeviceCapabilityInfo.isPresenceCapable()) {
            logd("isPublishRequestAllowed: capability presence uce is not enabled.");
            return false;
        }
        // The first PUBLISH request is required to be triggered from the service.
        if (!mReceivePublishFromService) {
            logd("isPublishRequestAllowed: Have not received the first PUBLISH from the service.");
            return false;
        }
        // Check whether there is already a publish request running or not. When the running
        // request is finished and there is a pending request, it will send a new request.
        if (mPublishProcessor.isPublishingNow()) {
            logd("isPublishRequestAllowed: There is already a publish request running now.");
            return false;
        }
        return true;
    }

    private void handleRcsConnectedMessage(RcsFeatureManager manager) {
        if (mIsDestroyedFlag) return;
        mRcsFeatureManager = manager;
        mDeviceCapListener.onRcsConnected();
        mPublishProcessor.onRcsConnected(manager);
        registerRcsAvailabilityChanged(manager);
    }

    private void handleRcsDisconnectedMessage() {
        if (mIsDestroyedFlag) return;
        mRcsFeatureManager = null;
        onUnpublish();
        mDeviceCapabilityInfo.updatePresenceCapable(false);
        mDeviceCapListener.onRcsDisconnected();
        mPublishProcessor.onRcsDisconnected();
    }

    private void handleDestroyedMessage() {
        mIsDestroyedFlag = true;
        mDeviceCapabilityInfo.updatePresenceCapable(false);
        unregisterRcsAvailabilityChanged();
        mDeviceCapListener.onDestroy();   // It will turn off the listener automatically.
        mPublishHandler.onDestroy();
        mPublishProcessor.onDestroy();
        synchronized (mPublishStateLock) {
            clearPublishStateCallbacks();
        }
    }

    /*
     * Register the availability callback to receive the RCS capabilities change. This method is
     * called when the RCS is connected.
     */
    private void registerRcsAvailabilityChanged(RcsFeatureManager manager) {
        try {
            manager.registerRcsAvailabilityCallback(mSubId, mRcsCapabilitiesCallback);
        } catch (ImsException e) {
            logw("registerRcsAvailabilityChanged exception " + e);
        }
    }

    /*
     * Unregister the availability callback. This method is called when the PublishController
     * instance is destroyed.
     */
    private void unregisterRcsAvailabilityChanged() {
        RcsFeatureManager manager = mRcsFeatureManager;
        if (manager == null) return;
        try {
            manager.unregisterRcsAvailabilityCallback(mSubId, mRcsCapabilitiesCallback);
        } catch (Exception e) {
            // Do not handle the exception
        }
    }

    private void handleCarrierConfigChangedMessage() {
        if (mIsDestroyedFlag) return;
        String[] newMap = getCarrierServiceDescriptionFeatureTagMap();
        if (mDeviceCapabilityInfo.updateCapabilityRegistrationTrackerMap(newMap)) {
            mPublishHandler.sendPublishMessage(
                    PublishController.PUBLISH_TRIGGER_CARRIER_CONFIG_CHANGED);
        }
    }

    private String[] getCarrierServiceDescriptionFeatureTagMap() {
        CarrierConfigManager manager = mContext.getSystemService(CarrierConfigManager.class);
        PersistableBundle bundle = manager != null ? manager.getConfigForSubId(mSubId) :
                CarrierConfigManager.getDefaultConfig();
        return bundle.getStringArray(CarrierConfigManager.Ims.
                KEY_PUBLISH_SERVICE_DESC_FEATURE_TAG_MAP_OVERRIDE_STRING_ARRAY);
    }

    private void handleRcsCapabilitiesChangedMessage(int capabilities) {
        logd("handleRcsCapabilitiesChangedMessage: " + capabilities);
        if (mIsDestroyedFlag) return;
        RcsImsCapabilities RcsImsCapabilities = new RcsImsCapabilities(capabilities);
        mDeviceCapabilityInfo.updatePresenceCapable(
                RcsImsCapabilities.isCapable(RcsUceAdapter.CAPABILITY_TYPE_PRESENCE_UCE));
        // Trigger a publish request if the RCS capabilities presence is enabled.
        if (mDeviceCapabilityInfo.isPresenceCapable()) {
            mPublishProcessor.checkAndSendPendingRequest();
        }
    }

    /**
     * Update the publish state and notify the publish state callback if the new state is different
     * from original state.
     */
    private void handlePublishStateChangedMessage(@PublishState int newPublishState,
            Instant updatedTimestamp, String pidfXml) {
        synchronized (mPublishStateLock) {
            if (mIsDestroyedFlag) return;
            // Check if the time of the given publish state is not earlier than existing time.
            if (updatedTimestamp == null || !updatedTimestamp.isAfter(mPublishStateUpdatedTime)) {
                logd("handlePublishStateChangedMessage: updatedTimestamp is not allowed: "
                        + mPublishStateUpdatedTime + " to " + updatedTimestamp
                        + ", publishState=" + newPublishState);
                return;
            }
            logd("publish state changes from " + mPublishState + " to " + newPublishState +
                    ", time=" + updatedTimestamp);
            if (mPublishState == newPublishState) return;
            mPublishState = newPublishState;
            mPublishStateUpdatedTime = updatedTimestamp;
            mPidfXml = pidfXml;
        }

        // Trigger the publish state changed in handler thread since it may take time.
        logd("Notify publish state changed: " + mPublishState);
        mPublishStateCallbacks.broadcast(c -> {
            try {
                c.onPublishStateChanged(mPublishState);
            } catch (RemoteException e) {
                logw("Notify publish state changed error: " + e);
            }
        });
        logd("Notify publish state changed: completed");
    }

    private void handleNotifyCurrentPublishStateMessage(IRcsUcePublishStateCallback callback) {
        if (mIsDestroyedFlag || callback == null) return;
        try {
            callback.onPublishStateChanged(getUcePublishState());
        } catch (RemoteException e) {
            logw("handleCurrentPublishStateUpdateMessage exception: " + e);
        }
    }

    private void handleRequestPublishMessage(@PublishTriggerType int type) {
        if (mIsDestroyedFlag) return;
        if (mUceCtrlCallback.isRequestForbiddenByNetwork()) {
            logd("handleRequestPublishMessage: The network forbids UCE requests: type=" + type);
            return;
        }
        logd("handleRequestPublishMessage: type=" + type);

        // Set the flag if the publish is triggered by the ImsService.
        if (type == PublishController.PUBLISH_TRIGGER_SERVICE && !mReceivePublishFromService) {
            mReceivePublishFromService = true;
        }

        // Set the pending flag and return if the request is not allowed.
        if (!isPublishRequestAllowed()) {
            logd("handleRequestPublishMessage: SKIP. The request is not allowed. type=" + type);
            mPublishProcessor.setPendingRequest(type);
            return;
        }

        // Update the latest PUBLISH allowed time according to the given trigger type.
        mPublishProcessor.updatePublishingAllowedTime(type);

        // Get the publish request delay time. If the delay is not present, the first
        // PUBLISH is not allowed to be executed; If the delay time is 0, it means that
        // this request can be executed immediately.
        Optional<Long> delay = mPublishProcessor.getPublishingDelayTime();
        if (!delay.isPresent()) {
            logd("handleRequestPublishMessage: SKIP. The delay is empty. type=" + type);
            mPublishProcessor.setPendingRequest(type);
            return;
        }

        logd("handleRequestPublishMessage: " + type + ", delay=" + delay.get());
        if (delay.get() == 0L) {
            mPublishProcessor.doPublish(type);
        } else {
            mPublishHandler.sendPublishMessage(type, delay.get());
        }
    }

    private void handleRequestCanceledMessage(long taskId) {
        if (mIsDestroyedFlag) return;
        mPublishProcessor.cancelPublishRequest(taskId);
    }

    @VisibleForTesting
    public void setPublishStateCallback(RemoteCallbackList<IRcsUcePublishStateCallback> list) {
        mPublishStateCallbacks = list;
    }

    @VisibleForTesting
    public PublishHandler getPublishHandler() {
        return mPublishHandler;
    }

    @VisibleForTesting
    public IImsCapabilityCallback getRcsCapabilitiesCallback() {
        return mRcsCapabilitiesCallback;
    }

    @VisibleForTesting
    public PublishControllerCallback getPublishControllerCallback() {
        return mPublishControllerCallback;
    }

    private void logd(String log) {
        Log.d(LOG_TAG, getLogPrefix().append(log).toString());
        mLocalLog.log("[D] " + log);
    }

    private void logi(String log) {
        Log.i(LOG_TAG, getLogPrefix().append(log).toString());
        mLocalLog.log("[I] " + log);
    }

    private void logw(String log) {
        Log.w(LOG_TAG, getLogPrefix().append(log).toString());
        mLocalLog.log("[W] " + log);
    }

    private StringBuilder getLogPrefix() {
        StringBuilder builder = new StringBuilder("[");
        builder.append(mSubId);
        builder.append("] ");
        return builder;
    }

    @Override
    public void dump(PrintWriter printWriter) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        pw.println("PublishControllerImpl" + "[subId: " + mSubId + "]:");
        pw.increaseIndent();

        pw.print("isPresenceCapable=");
        pw.println(mDeviceCapabilityInfo.isPresenceCapable());
        pw.print("mPublishState=");
        pw.print(mPublishState);
        pw.print(" at time ");
        pw.println(mPublishStateUpdatedTime);
        pw.println("Last PIDF XML:");
        pw.increaseIndent();
        pw.println(mPidfXml);
        pw.decreaseIndent();

        if (mPublishProcessor != null) {
            mPublishProcessor.dump(pw);
        } else {
            pw.println("mPublishProcessor is null");
        }

        pw.println();
        mDeviceCapListener.dump(pw);

        pw.println("Log:");
        pw.increaseIndent();
        mLocalLog.dump(pw);
        pw.decreaseIndent();
        pw.println("---");

        pw.decreaseIndent();
    }
}
