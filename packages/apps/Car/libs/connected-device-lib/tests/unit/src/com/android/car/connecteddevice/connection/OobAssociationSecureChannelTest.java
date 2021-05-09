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

import static com.android.car.connecteddevice.StreamProtos.OperationProto.OperationType;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.encryptionrunner.EncryptionRunnerFactory;
import android.car.encryptionrunner.FakeEncryptionRunner;

import com.android.car.connecteddevice.connection.ble.BleDeviceMessageStream;
import com.android.car.connecteddevice.oob.OobConnectionManager;
import com.android.car.connecteddevice.storage.ConnectedDeviceStorage;
import com.android.car.connecteddevice.util.ByteUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;

public class OobAssociationSecureChannelTest {
    private static final UUID CLIENT_DEVICE_ID =
            UUID.fromString("a5645523-3280-410a-90c1-582a6c6f4969");

    private static final UUID SERVER_DEVICE_ID =
            UUID.fromString("a29f0c74-2014-4b14-ac02-be6ed15b545a");

    private static final byte[] CLIENT_SECRET = ByteUtils.randomBytes(32);

    @Mock
    private BleDeviceMessageStream mStreamMock;

    @Mock
    private ConnectedDeviceStorage mStorageMock;

    @Mock
    private OobConnectionManager mOobConnectionManagerMock;

    private OobAssociationSecureChannel mChannel;

    private BleDeviceMessageStream.MessageReceivedListener mMessageReceivedListener;

    private MockitoSession mMockitoSession;

    @Before
    public void setUp() {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();
        when(mStorageMock.getUniqueId()).thenReturn(SERVER_DEVICE_ID);
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testEncryptionHandshake_oobAssociation() throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        ChannelCallback
                callbackSpy = spy(new ChannelCallback(semaphore));
        setupOobAssociationSecureChannel(callbackSpy);
        ArgumentCaptor<String> deviceIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DeviceMessage> messageCaptor =
                ArgumentCaptor.forClass(DeviceMessage.class);

        initHandshakeMessage();
        verify(mStreamMock).writeMessage(messageCaptor.capture(), any());
        byte[] response = messageCaptor.getValue().getMessage();
        assertThat(response).isEqualTo(FakeEncryptionRunner.INIT_RESPONSE.getBytes());
        reset(mStreamMock);
        respondToContinueMessage();
        verify(mStreamMock).writeMessage(messageCaptor.capture(), any());
        byte[] oobCodeResponse = messageCaptor.getValue().getMessage();
        assertThat(oobCodeResponse).isEqualTo(FakeEncryptionRunner.VERIFICATION_CODE.getBytes());
        respondToOobCode();
        sendDeviceId();
        assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue();
        verify(callbackSpy).onDeviceIdReceived(deviceIdCaptor.capture());
        verify(mStreamMock, times(2)).writeMessage(messageCaptor.capture(), any());
        byte[] deviceIdMessage = messageCaptor.getValue().getMessage();
        assertThat(deviceIdMessage).isEqualTo(ByteUtils.uuidToBytes(SERVER_DEVICE_ID));
        assertThat(deviceIdCaptor.getValue()).isEqualTo(CLIENT_DEVICE_ID.toString());
        verify(mStorageMock).saveEncryptionKey(eq(CLIENT_DEVICE_ID.toString()), any());
        verify(mStorageMock).saveChallengeSecret(CLIENT_DEVICE_ID.toString(), CLIENT_SECRET);

        assertThat(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)).isTrue();
        verify(callbackSpy).onSecureChannelEstablished();
    }

    private void setupOobAssociationSecureChannel(ChannelCallback callback) {
        mChannel = new OobAssociationSecureChannel(mStreamMock, mStorageMock,
                mOobConnectionManagerMock, EncryptionRunnerFactory.newOobFakeRunner());
        mChannel.registerCallback(callback);
        ArgumentCaptor<BleDeviceMessageStream.MessageReceivedListener> listenerCaptor =
                ArgumentCaptor.forClass(BleDeviceMessageStream.MessageReceivedListener.class);
        verify(mStreamMock).setMessageReceivedListener(listenerCaptor.capture());
        mMessageReceivedListener = listenerCaptor.getValue();
        try {
            when(mOobConnectionManagerMock.encryptVerificationCode(any()))
                    .thenReturn(FakeEncryptionRunner.VERIFICATION_CODE.getBytes());
        } catch (InvalidAlgorithmParameterException | BadPaddingException | InvalidKeyException
                | IllegalBlockSizeException e) {
        }
        try {
            when(mOobConnectionManagerMock.decryptVerificationCode(any()))
                    .thenReturn(FakeEncryptionRunner.VERIFICATION_CODE.getBytes());
        } catch (InvalidAlgorithmParameterException | BadPaddingException | InvalidKeyException
                | IllegalBlockSizeException e) {
        }
    }

    private void sendDeviceId() {
        DeviceMessage message = new DeviceMessage(
                /* recipient= */ null,
                /* isMessageEncrypted= */ true,
                ByteUtils.concatByteArrays(ByteUtils.uuidToBytes(CLIENT_DEVICE_ID), CLIENT_SECRET)
        );
        mMessageReceivedListener.onMessageReceived(message, OperationType.ENCRYPTION_HANDSHAKE);
    }

    private void initHandshakeMessage() {
        DeviceMessage message = new DeviceMessage(
                /* recipient= */ null,
                /* isMessageEncrypted= */ false,
                FakeEncryptionRunner.INIT.getBytes()
        );
        mMessageReceivedListener.onMessageReceived(message, OperationType.ENCRYPTION_HANDSHAKE);
    }

    private void respondToContinueMessage() {
        DeviceMessage message = new DeviceMessage(
                /* recipient= */ null,
                /* isMessageEncrypted= */ false,
                FakeEncryptionRunner.CLIENT_RESPONSE.getBytes()
        );
        mMessageReceivedListener.onMessageReceived(message, OperationType.ENCRYPTION_HANDSHAKE);
    }

    private void respondToOobCode() {
        DeviceMessage message = new DeviceMessage(
                /* recipient= */ null,
                /* isMessageEncrypted= */ false,
                FakeEncryptionRunner.VERIFICATION_CODE.getBytes()
        );
        mMessageReceivedListener.onMessageReceived(message, OperationType.ENCRYPTION_HANDSHAKE);
    }

    /**
     * Add the thread control logic into {@link SecureChannel.Callback} only for spy purpose.
     *
     * <p>The callback will release the semaphore which hold by one test when this callback
     * is called, telling the test that it can verify certain behaviors which will only occurred
     * after the callback is notified. This is needed mainly because of the callback is notified
     * in a different thread.
     */
    private static class ChannelCallback implements SecureChannel.Callback {
        private final Semaphore mSemaphore;

        ChannelCallback(Semaphore semaphore) {
            mSemaphore = semaphore;
        }

        @Override
        public void onSecureChannelEstablished() {
            mSemaphore.release();
        }

        @Override
        public void onEstablishSecureChannelFailure(int error) {
            mSemaphore.release();
        }

        @Override
        public void onMessageReceived(DeviceMessage deviceMessage) {
            mSemaphore.release();
        }

        @Override
        public void onMessageReceivedError(Exception exception) {
            mSemaphore.release();
        }

        @Override
        public void onDeviceIdReceived(String deviceId) {
            mSemaphore.release();
        }
    }
}
