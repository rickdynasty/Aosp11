/*
 * Copyright (c) 2021 The Android Open Source Project
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

package com.android.ims.rcs.uce.request;

import static android.telephony.ims.stub.RcsCapabilityExchangeImplBase.COMMAND_CODE_GENERIC_FAILURE;

import android.net.Uri;
import android.os.RemoteException;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.aidl.IRcsUceControllerCallback;

import com.android.ims.rcs.uce.presence.pidfparser.PidfParserUtils;
import com.android.ims.rcs.uce.request.UceRequestManager.RequestManagerCallback;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Responsible for the communication and interaction between SubscribeRequests and triggering
 * the callback to notify the result of the capabilities request.
 */
public class SubscribeRequestCoordinator extends UceRequestCoordinator {
    /**
     * The builder of the SubscribeRequestCoordinator.
     */
    public static final class Builder {
        private SubscribeRequestCoordinator mRequestCoordinator;

        /**
         * The builder of the SubscribeRequestCoordinator class.
         */
        public Builder(int subId, Collection<UceRequest> requests, RequestManagerCallback c) {
            mRequestCoordinator = new SubscribeRequestCoordinator(subId, requests, c);
        }

        /**
         * Set the callback to receive the request updated.
         */
        public Builder setCapabilitiesCallback(IRcsUceControllerCallback callback) {
            mRequestCoordinator.setCapabilitiesCallback(callback);
            return this;
        }

        /**
         * Get the SubscribeRequestCoordinator instance.
         */
        public SubscribeRequestCoordinator build() {
            return mRequestCoordinator;
        }
    }

    /**
     * Different request updated events will create different {@link RequestResult}. Define the
     * interface to get the {@link RequestResult} instance according to the given task ID and
     * {@link CapabilityRequestResponse}.
     */
    @FunctionalInterface
    private interface RequestResultCreator {
        RequestResult createRequestResult(long taskId, CapabilityRequestResponse response);
    }

    // The RequestResult creator of the request error.
    private static final RequestResultCreator sRequestErrorCreator = (taskId, response) -> {
        int errorCode = response.getRequestInternalError().orElse(DEFAULT_ERROR_CODE);
        long retryAfter = response.getRetryAfterMillis();
        return RequestResult.createFailedResult(taskId, errorCode, retryAfter);
    };

    // The RequestResult creator of the command error.
    private static final RequestResultCreator sCommandErrorCreator = (taskId, response) -> {
        int cmdError = response.getCommandError().orElse(COMMAND_CODE_GENERIC_FAILURE);
        int errorCode = CapabilityRequestResponse.getCapabilityErrorFromCommandError(cmdError);
        long retryAfter = response.getRetryAfterMillis();
        return RequestResult.createFailedResult(taskId, errorCode, retryAfter);
    };

    // The RequestResult creator of the network response error.
    private static final RequestResultCreator sNetworkRespErrorCreator = (taskId, response) -> {
        int errorCode = CapabilityRequestResponse.getCapabilityErrorFromSipCode(response);
        long retryAfter = response.getRetryAfterMillis();
        return RequestResult.createFailedResult(taskId, errorCode, retryAfter);
    };

    // The RequestResult creator of the request terminated.
    private static final RequestResultCreator sTerminatedCreator = (taskId, response) -> {
        long retryAfterMillis = response.getRetryAfterMillis();
        int errorCode = CapabilityRequestResponse.getCapabilityErrorFromSipCode(response);
        // If the network response is failed or the retryAfter is not zero, this request is failed.
        if (!response.isNetworkResponseOK() || retryAfterMillis > 0L) {
            return RequestResult.createFailedResult(taskId, errorCode, retryAfterMillis);
        } else {
            return RequestResult.createSuccessResult(taskId);
        }
    };

    // The RequestResult creator for does not need to request from the network.
    private static final RequestResultCreator sNotNeedRequestFromNetworkCreator =
            (taskId, response) -> RequestResult.createSuccessResult(taskId);

    // The callback to notify the result of the capabilities request.
    private volatile IRcsUceControllerCallback mCapabilitiesCallback;

    private SubscribeRequestCoordinator(int subId, Collection<UceRequest> requests,
            RequestManagerCallback requestMgrCallback) {
        super(subId, requests, requestMgrCallback);
        logd("SubscribeRequestCoordinator: created");
    }

    private void setCapabilitiesCallback(IRcsUceControllerCallback callback) {
        mCapabilitiesCallback = callback;
    }

    @Override
    public void onFinish() {
        logd("SubscribeRequestCoordinator: onFinish");
        mCapabilitiesCallback = null;
        super.onFinish();
    }

    @Override
    public void onRequestUpdated(long taskId, @UceRequestUpdate int event) {
        if (mIsFinished) return;
        SubscribeRequest request = (SubscribeRequest) getUceRequest(taskId);
        if (request == null) {
            logw("onRequestUpdated: Cannot find SubscribeRequest taskId=" + taskId);
            return;
        }

        logd("onRequestUpdated: taskId=" + taskId + ", event=" + REQUEST_EVENT_DESC.get(event));
        switch (event) {
            case REQUEST_UPDATE_ERROR:
                handleRequestError(request);
                break;
            case REQUEST_UPDATE_COMMAND_ERROR:
                handleCommandError(request);
                break;
            case REQUEST_UPDATE_NETWORK_RESPONSE:
                handleNetworkResponse(request);
                break;
            case REQUEST_UPDATE_CAPABILITY_UPDATE:
                handleCapabilitiesUpdated(request);
                break;
            case REQUEST_UPDATE_RESOURCE_TERMINATED:
                handleResourceTerminated(request);
                break;
            case REQUEST_UPDATE_CACHED_CAPABILITY_UPDATE:
                handleCachedCapabilityUpdated(request);
                break;
            case REQUEST_UPDATE_TERMINATED:
                handleTerminated(request);
                break;
            case REQUEST_UPDATE_NO_NEED_REQUEST_FROM_NETWORK:
                handleNoNeedRequestFromNetwork(request);
                break;
            default:
                logw("onRequestUpdated: invalid event " + event);
                break;
        }

        // End this instance if all the UceRequests in the coordinator are finished.
        checkAndFinishRequestCoordinator();
    }

    /**
     * Finish the SubscribeRequest because it has encountered error.
     */
    private void handleRequestError(SubscribeRequest request) {
        CapabilityRequestResponse response = request.getRequestResponse();
        logd("handleRequestError: " + request.toString());

        // Finish this request.
        request.onFinish();

        // Remove this request from the activated collection and notify RequestManager.
        Long taskId = request.getTaskId();
        RequestResult requestResult = sRequestErrorCreator.createRequestResult(taskId, response);
        moveRequestToFinishedCollection(taskId, requestResult);
    }

    /**
     * This method is called when the given SubscribeRequest received the onCommandError callback
     * from the ImsService.
     */
    private void handleCommandError(SubscribeRequest request) {
        CapabilityRequestResponse response = request.getRequestResponse();
        logd("handleCommandError: " + request.toString());

        // Finish this request.
        request.onFinish();

        // Remove this request from the activated collection and notify RequestManager.
        Long taskId = request.getTaskId();
        RequestResult requestResult = sCommandErrorCreator.createRequestResult(taskId, response);
        moveRequestToFinishedCollection(taskId, requestResult);
    }

    /**
     * This method is called when the given SubscribeRequest received the onNetworkResponse
     * callback from the ImsService.
     */
    private void handleNetworkResponse(SubscribeRequest request) {
        CapabilityRequestResponse response = request.getRequestResponse();
        logd("handleNetworkResponse: " + response.toString());

        // When the network response is unsuccessful, there is no subsequent callback for this
        // request. Check the forbidden state and finish this request. Otherwise, keep waiting for
        // the subsequent callback of this request.
        if (!response.isNetworkResponseOK()) {
            Long taskId = request.getTaskId();
            RequestResult requestResult = sNetworkRespErrorCreator.createRequestResult(taskId,
                    response);

            // handle forbidden and not found case.
            handleNetworkResponseFailed(request, requestResult);

            // Trigger capabilities updated callback if there is any.
            List<RcsContactUceCapability> updatedCapList = response.getUpdatedContactCapability();
            if (!updatedCapList.isEmpty()) {
                mRequestManagerCallback.saveCapabilities(updatedCapList);
                triggerCapabilitiesReceivedCallback(updatedCapList);
                response.removeUpdatedCapabilities(updatedCapList);
            }

            // Finish this request.
            request.onFinish();

            // Remove this request from the activated collection and notify RequestManager.
            moveRequestToFinishedCollection(taskId, requestResult);
        }
    }

    private void handleNetworkResponseFailed(SubscribeRequest request, RequestResult result) {
        CapabilityRequestResponse response = request.getRequestResponse();

        // Update the forbidden state when the sip code is forbidden
        if (response.isRequestForbidden()) {
            Long retryMillis =  result.getRetryMillis().orElse(0L);
            int errorCode = result.getErrorCode().orElse(DEFAULT_ERROR_CODE);
            mRequestManagerCallback.onRequestForbidden(true, errorCode, retryMillis);
        }

        if (response.isNotFound()) {
            List<Uri> uriList = request.getContactUri();
            List<RcsContactUceCapability> capabilityList = uriList.stream().map(uri ->
                    PidfParserUtils.getNotFoundContactCapabilities(uri))
                    .collect(Collectors.toList());
            response.addUpdatedCapabilities(capabilityList);
        }
    }

    /**
     * This method is called when the given SubscribeRequest received the onNotifyCapabilitiesUpdate
     * callback from the ImsService.
     */
    private void handleCapabilitiesUpdated(SubscribeRequest request) {
        CapabilityRequestResponse response = request.getRequestResponse();
        Long taskId = request.getTaskId();
        List<RcsContactUceCapability> updatedCapList = response.getUpdatedContactCapability();
        logd("handleCapabilitiesUpdated: taskId=" + taskId + ", size=" + updatedCapList.size());

        if (updatedCapList.isEmpty()) {
            return;
        }

        // Save the updated capabilities to the cache.
        mRequestManagerCallback.saveCapabilities(updatedCapList);

        // Trigger the capabilities updated callback and remove the given capabilities that have
        // executed the callback onCapabilitiesReceived.
        triggerCapabilitiesReceivedCallback(updatedCapList);
        response.removeUpdatedCapabilities(updatedCapList);
    }

    /**
     * This method is called when the given SubscribeRequest received the onResourceTerminated
     * callback from the ImsService.
     */
    private void handleResourceTerminated(SubscribeRequest request) {
        CapabilityRequestResponse response = request.getRequestResponse();
        Long taskId = request.getTaskId();
        List<RcsContactUceCapability> terminatedResources = response.getTerminatedResources();
        logd("handleResourceTerminated: taskId=" + taskId + ", size=" + terminatedResources.size());

        if (terminatedResources.isEmpty()) {
            return;
        }

        // Save the terminated capabilities to the cache.
        mRequestManagerCallback.saveCapabilities(terminatedResources);

        // Trigger the capabilities updated callback and remove the given capabilities from the
        // resource terminated list.
        triggerCapabilitiesReceivedCallback(terminatedResources);
        response.removeTerminatedResources(terminatedResources);
    }

    /**
     * This method is called when the given SubscribeRequest retrieve the cached capabilities.
     */
    private void handleCachedCapabilityUpdated(SubscribeRequest request) {
        CapabilityRequestResponse response = request.getRequestResponse();
        Long taskId = request.getTaskId();
        List<RcsContactUceCapability> cachedCapList = response.getCachedContactCapability();
        logd("handleCachedCapabilityUpdated: taskId=" + taskId + ", size=" + cachedCapList.size());

        if (cachedCapList.isEmpty()) {
            return;
        }

        // Trigger the capabilities updated callback.
        triggerCapabilitiesReceivedCallback(cachedCapList);
        response.removeCachedContactCapabilities();
    }

    /**
     * This method is called when the given SubscribeRequest received the onTerminated callback
     * from the ImsService.
     */
    private void handleTerminated(SubscribeRequest request) {
        CapabilityRequestResponse response = request.getRequestResponse();
        logd("handleTerminated: " + response.toString());

        // Finish this request.
        request.onFinish();

        // Remove this request from the activated collection and notify RequestManager.
        Long taskId = request.getTaskId();
        RequestResult requestResult = sTerminatedCreator.createRequestResult(taskId, response);
        moveRequestToFinishedCollection(taskId, requestResult);
    }

    /**
     * This method is called when all the capabilities can be retrieved from the cached and it does
     * not need to request capabilities from the network.
     */
    private void handleNoNeedRequestFromNetwork(SubscribeRequest request) {
        CapabilityRequestResponse response = request.getRequestResponse();
        logd("handleNoNeedRequestFromNetwork: " + response.toString());

        // Finish this request.
        request.onFinish();

        // Remove this request from the activated collection and notify RequestManager.
        long taskId = request.getTaskId();
        RequestResult requestResult = sNotNeedRequestFromNetworkCreator.createRequestResult(taskId,
                response);
        moveRequestToFinishedCollection(taskId, requestResult);
    }

    private void checkAndFinishRequestCoordinator() {
        synchronized (mCollectionLock) {
            // Return because there are requests running.
            if (!mActivatedRequests.isEmpty()) {
                return;
            }

            // All the requests has finished, find the request which has the max retryAfter time.
            // If the result is empty, it means all the request are success.
            Optional<RequestResult> optRequestResult =
                    mFinishedRequests.values().stream()
                        .filter(result -> !result.isRequestSuccess())
                        .max(Comparator.comparingLong(result ->
                                result.getRetryMillis().orElse(-1L)));

            // Trigger the callback
            if (optRequestResult.isPresent()) {
                RequestResult result = optRequestResult.get();
                int errorCode = result.getErrorCode().orElse(DEFAULT_ERROR_CODE);
                long retryAfter = result.getRetryMillis().orElse(0L);
                triggerErrorCallback(errorCode, retryAfter);
            } else {
                triggerCompletedCallback();
            }

            // Notify UceRequestManager to remove this instance from the collection.
            mRequestManagerCallback.notifyRequestCoordinatorFinished(mCoordinatorId);

            logd("checkAndFinishRequestCoordinator: id=" + mCoordinatorId);
        }
    }

    /**
     * Trigger the capabilities updated callback.
     */
    private void triggerCapabilitiesReceivedCallback(List<RcsContactUceCapability> capList) {
        try {
            logd("triggerCapabilitiesCallback: size=" + capList.size());
            mCapabilitiesCallback.onCapabilitiesReceived(capList);
        } catch (RemoteException e) {
            logw("triggerCapabilitiesCallback exception: " + e);
        } finally {
            logd("triggerCapabilitiesCallback: done");
        }
    }

    /**
     * Trigger the onComplete callback to notify the request is completed.
     */
    private void triggerCompletedCallback() {
        try {
            logd("triggerCompletedCallback");
            mCapabilitiesCallback.onComplete();
        } catch (RemoteException e) {
            logw("triggerCompletedCallback exception: " + e);
        } finally {
            logd("triggerCompletedCallback: done");
        }
    }

    /**
     * Trigger the onError callback to notify the request is failed.
     */
    private void triggerErrorCallback(int errorCode, long retryAfterMillis) {
        try {
            logd("triggerErrorCallback: errorCode=" + errorCode + ", retry=" + retryAfterMillis);
            mCapabilitiesCallback.onError(errorCode, retryAfterMillis);
        } catch (RemoteException e) {
            logw("triggerErrorCallback exception: " + e);
        } finally {
            logd("triggerErrorCallback: done");
        }
    }

    @VisibleForTesting
    public Collection<UceRequest> getActivatedRequest() {
        return mActivatedRequests.values();
    }

    @VisibleForTesting
    public Collection<RequestResult> getFinishedRequest() {
        return mFinishedRequests.values();
    }
}
