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

import static com.android.car.connecteddevice.StreamProtos.OperationProto.OperationType.CLIENT_MESSAGE;
import static com.android.car.connecteddevice.StreamProtos.OperationProto.OperationType.ENCRYPTION_HANDSHAKE;
import static com.android.car.connecteddevice.connection.SecureChannel.CHANNEL_ERROR_INVALID_HANDSHAKE;
import static com.android.car.connecteddevice.connection.SecureChannel.Callback;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.car.encryptionrunner.EncryptionRunnerFactory;
import android.car.encryptionrunner.FakeEncryptionRunner;
import android.car.encryptionrunner.HandshakeException;
import android.car.encryptionrunner.Key;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.connecteddevice.connection.ble.BleDeviceMessageStream;
import com.android.car.connecteddevice.util.ByteUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class SecureChannelTest {

    @Mock private BleDeviceMessageStream mMockStream;

    @Mock private Key mKey = spy(new Key() {
        @Override
        public byte[] asBytes() {
            return new byte[0];
        }

        @Override
        public byte[] encryptData(byte[] data) {
            return data;
        }

        @Override
        public byte[] decryptData(byte[] encryptedData) throws SignatureException {
            return encryptedData;
        }

        @Override
        public byte[] getUniqueSession() throws NoSuchAlgorithmException {
            return new byte[0];
        }
    });

    private MockitoSession mMockitoSession;

    private SecureChannel mSecureChannel;

    @Before
    public void setUp() throws SignatureException {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();

        mSecureChannel = new SecureChannel(mMockStream,
                EncryptionRunnerFactory.newFakeRunner()) {
            @Override
            void processHandshake(byte[] message) { }
        };
        mSecureChannel.setEncryptionKey(mKey);
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void processMessage_doesNothingForUnencryptedMessage() throws SignatureException {
        byte[] payload = ByteUtils.randomBytes(10);
        DeviceMessage message = new DeviceMessage(UUID.randomUUID(), /* isEncrypted= */ false,
                payload);
        mSecureChannel.processMessage(message);
        assertThat(message.getMessage()).isEqualTo(payload);
        verify(mKey, times(0)).decryptData(any());
    }

    @Test
    public void processMessage_decryptsEncryptedMessage() throws SignatureException {
        byte[] payload = ByteUtils.randomBytes(10);
        DeviceMessage message = new DeviceMessage(UUID.randomUUID(), /* isEncrypted= */ true,
                payload);
        mSecureChannel.processMessage(message);
        verify(mKey).decryptData(any());
    }

    @Test
    public void processMessage_onMessageReceivedErrorForEncryptedMessageWithNoKey()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        DeviceMessage message = new DeviceMessage(UUID.randomUUID(), /* isEncrypted= */ true,
                ByteUtils.randomBytes(10));

        mSecureChannel.setEncryptionKey(null);
        mSecureChannel.registerCallback(new Callback() {
            @Override
            public void onMessageReceivedError(Exception exception) {
                semaphore.release();
            }
        });
        mSecureChannel.processMessage(message);
        assertThat(tryAcquire(semaphore)).isTrue();
        assertThat(message.getMessage()).isNull();
    }

    @Test
    public void onMessageReceived_onEstablishSecureChannelFailureBadHandshakeMessage()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        DeviceMessage message = new DeviceMessage(UUID.randomUUID(), /* isEncrypted= */ true,
                ByteUtils.randomBytes(10));

        mSecureChannel.setEncryptionKey(null);
        mSecureChannel.registerCallback(new Callback() {
            @Override
            public void onEstablishSecureChannelFailure(int error) {
                assertThat(error).isEqualTo(CHANNEL_ERROR_INVALID_HANDSHAKE);
                semaphore.release();
            }
        });
        mSecureChannel.onMessageReceived(message, ENCRYPTION_HANDSHAKE);
        assertThat(tryAcquire(semaphore)).isTrue();
    }

    @Test
    public void onMessageReceived_onMessageReceivedNotIssuedForNullMessage()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        DeviceMessage message = new DeviceMessage(UUID.randomUUID(), /* isEncrypted= */ false,
                /* message= */ null);

        mSecureChannel.registerCallback(new Callback() {
            @Override
            public void onMessageReceived(DeviceMessage message) {
                semaphore.release();
            }
        });
        mSecureChannel.onMessageReceived(message, CLIENT_MESSAGE);
        assertThat(tryAcquire(semaphore)).isFalse();
    }

    @Test
    public void onMessageReceived_processHandshakeExceptionIssuesSecureChannelFailureCallback()
            throws InterruptedException {
        SecureChannel secureChannel = new SecureChannel(mMockStream,
                EncryptionRunnerFactory.newFakeRunner()) {
            @Override
            void processHandshake(byte[] message) throws HandshakeException {
                FakeEncryptionRunner.throwHandshakeException("test");
            }
        };
        Semaphore semaphore = new Semaphore(0);
        secureChannel.registerCallback(new Callback() {
            @Override
            public void onEstablishSecureChannelFailure(int error) {
                semaphore.release();
            }
        });
        DeviceMessage message = new DeviceMessage(UUID.randomUUID(), /* isEncrypted= */ true,
                /* message= */ ByteUtils.randomBytes(10));

        secureChannel.onMessageReceived(message, ENCRYPTION_HANDSHAKE);
        assertThat(tryAcquire(semaphore)).isTrue();
    }

    private boolean tryAcquire(Semaphore semaphore) throws InterruptedException {
        return semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
    }
}
