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

package com.android.car.connecteddevice.connection.spp;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class SppManagerTest {
    private static final UUID TEST_SERVICE_UUID = UUID.randomUUID();
    private final boolean mIsSecureRfcommChannel = true;
    private final byte[] mTestData = "testData".getBytes();
    private SppManager mSppManager;
    private Executor mCallbackExecutor = Executors.newSingleThreadExecutor();
    private byte[] mCompletedMessage = SppPayloadStream.wrapWithArrayLength(mTestData);
    @Mock
    private ConnectedTask mMockConnectedTask;

    @Mock
    private ExecutorService mMockExecutorService;
    private MockitoSession mMockitoSession;


    @Before
    public void setUp() throws IOException {
        mSppManager = new SppManager(mIsSecureRfcommChannel);
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testStartListen_StartAcceptTask() {
        mSppManager.mConnectionExecutor = mMockExecutorService;
        mSppManager.startListening(TEST_SERVICE_UUID);
        assertThat(mSppManager.mAcceptTask).isNotNull();
        verify(mMockExecutorService).execute(mSppManager.mAcceptTask);
    }

    @Test
    public void testWrite_CallConnectedTaskToWrite() {
        mSppManager.mConnectedTask = mMockConnectedTask;
        mSppManager.mState = SppManager.ConnectionState.CONNECTED;
        mSppManager.write(mTestData);
        verify(mMockConnectedTask).write(SppPayloadStream.wrapWithArrayLength(mTestData));
    }

    @Test
    public void testConnectedTaskCallback_onMessageReceived_CallOnMessageReceivedListener()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        SppManager.OnMessageReceivedListener listener = createOnMessageReceivedListener(semaphore);
        mSppManager.addOnMessageReceivedListener(listener, mCallbackExecutor);
        mSppManager.mConnectedTaskCallback.onMessageReceived(mCompletedMessage);
        assertThat(tryAcquire(semaphore)).isTrue();
        verify(listener).onMessageReceived(any(), eq(mTestData));
    }

    @Test
    public void testConnectedTaskCallback_onDisconnected_CallOnRemoteDeviceDisconnected()
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);
        SppManager.ConnectionCallback callback = createConnectionCallback(semaphore);
        mSppManager.registerCallback(callback, mCallbackExecutor);
        mSppManager.mConnectedTaskCallback.onDisconnected();
        assertThat(tryAcquire(semaphore)).isTrue();
        verify(callback).onRemoteDeviceDisconnected(any());
    }

    @NonNull
    private SppManager.ConnectionCallback createConnectionCallback(
            @NonNull final Semaphore semaphore) {
        return spy(new SppManager.ConnectionCallback() {

            @Override
            public void onRemoteDeviceConnected(BluetoothDevice device) {
                semaphore.release();
            }

            @Override
            public void onRemoteDeviceDisconnected(BluetoothDevice device) {
                semaphore.release();
            }
        });
    }

    @NonNull
    private SppManager.OnMessageReceivedListener createOnMessageReceivedListener(
            @NonNull final Semaphore semaphore) {
        return spy((device, value) -> semaphore.release());
    }

    private boolean tryAcquire(Semaphore semaphore) throws InterruptedException {
        return semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
    }
}
