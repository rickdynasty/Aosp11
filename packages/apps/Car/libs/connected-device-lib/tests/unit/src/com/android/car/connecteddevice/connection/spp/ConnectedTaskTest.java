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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class ConnectedTaskTest {

    private final byte[] mTestData = "testData".getBytes();
    private ConnectedTask mConnectedTask;
    private InputStream mInputStream = new ByteArrayInputStream(mTestData);
    private OutputStream mOutputStream = new ByteArrayOutputStream();
    private ConnectedTask.Callback mCallback;
    private Executor mExecutor = Executors.newSingleThreadExecutor();
    private Semaphore mSemaphore = new Semaphore(0);


    @Before
    public void setUp() {
        mCallback = spy(new ConnectedTask.Callback() {
            @Override
            public void onMessageReceived(byte[] message) {
                mSemaphore.release();
            }

            @Override
            public void onDisconnected() {

            }
        });
        mConnectedTask = new ConnectedTask(mInputStream, mOutputStream, null, mCallback);
    }

    @Test
    public void testTaskRun_InformCallback() throws InterruptedException {
        mExecutor.execute(mConnectedTask);
        assertThat(tryAcquire(mSemaphore)).isTrue();
        verify(mCallback).onMessageReceived(mTestData);
    }

    @Test
    public void testWrite_WriteToOutputStream() {
        mConnectedTask.write(mTestData);
        assertThat(mOutputStream.toString()).isEqualTo(new String(mTestData));
    }

    private boolean tryAcquire(Semaphore semaphore) throws InterruptedException {
        return semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
    }
}
