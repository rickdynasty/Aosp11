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

package com.android.car.connecteddevice.connection;

import static android.car.encryptionrunner.HandshakeMessage.HandshakeState;

import static com.android.car.connecteddevice.util.SafeLog.loge;

import android.car.encryptionrunner.EncryptionRunner;
import android.car.encryptionrunner.EncryptionRunnerFactory;
import android.car.encryptionrunner.HandshakeException;
import android.car.encryptionrunner.HandshakeMessage;

import androidx.annotation.NonNull;

import com.android.car.connecteddevice.oob.OobConnectionManager;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;

/**
 * A secure channel established with the association flow with an out-of-band verification.
 */
public class OobAssociationSecureChannel extends AssociationSecureChannel {

    private static final String TAG = "OobAssociationSecureChannel";

    private final OobConnectionManager mOobConnectionManager;

    private byte[] mOobCode;

    public OobAssociationSecureChannel(
            DeviceMessageStream stream,
            ConnectedDeviceStorage storage,
            OobConnectionManager oobConnectionManager) {
        this(stream, storage, oobConnectionManager, EncryptionRunnerFactory.newRunner(
                EncryptionRunnerFactory.EncryptionRunnerType.OOB_UKEY2));
    }

    OobAssociationSecureChannel(
            DeviceMessageStream stream,
            ConnectedDeviceStorage storage,
            OobConnectionManager oobConnectionManager,
            EncryptionRunner encryptionRunner) {
        super(stream, storage, encryptionRunner);
        mOobConnectionManager = oobConnectionManager;
    }

    @Override
    void processHandshake(@NonNull byte[] message) throws HandshakeException {
        switch (getState()) {
            case HandshakeState.IN_PROGRESS:
                processHandshakeInProgress(message);
                break;
            case HandshakeState.OOB_VERIFICATION_NEEDED:
                processHandshakeOobVerificationNeeded(message);
                break;
            default:
                super.processHandshake(message);
        }
    }

    private void processHandshakeInProgress(@NonNull byte[] message) throws HandshakeException {
        HandshakeMessage handshakeMessage = getEncryptionRunner().continueHandshake(message);
        setState(handshakeMessage.getHandshakeState());
        int state = getState();
        if (state != HandshakeState.OOB_VERIFICATION_NEEDED) {
            loge(TAG, "processHandshakeInProgress: Encountered unexpected handshake state: "
                    + state + ".");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_STATE);
            return;
        }

        mOobCode = handshakeMessage.getOobVerificationCode();
        if (mOobCode == null) {
            loge(TAG, "Unable to get out of band verification code.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_VERIFICATION);
            return;
        }

        byte[] encryptedCode;
        try {
            encryptedCode = mOobConnectionManager.encryptVerificationCode(mOobCode);
        } catch (InvalidKeyException | InvalidAlgorithmParameterException
                | IllegalBlockSizeException | BadPaddingException e) {
            loge(TAG, "Encryption failed for verification code exchange.", e);
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_HANDSHAKE);
            return;
        }

        sendHandshakeMessage(encryptedCode, /* isEncrypted= */ false);
    }

    private void processHandshakeOobVerificationNeeded(@NonNull byte[] message) {
        byte[] decryptedCode;
        try {
            decryptedCode = mOobConnectionManager.decryptVerificationCode(message);
        } catch (InvalidKeyException | InvalidAlgorithmParameterException
                | IllegalBlockSizeException | BadPaddingException e) {
            loge(TAG, "Decryption failed for verification code exchange", e);
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_HANDSHAKE);
            return;
        }

        if (!Arrays.equals(mOobCode, decryptedCode)) {
            loge(TAG, "Exchanged verification codes do not match. Aborting secure channel.");
            notifySecureChannelFailure(CHANNEL_ERROR_INVALID_VERIFICATION);
            return;
        }

        notifyOutOfBandAccepted();
    }
}
