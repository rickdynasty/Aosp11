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

import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class SppPayloadStreamTest {
    private SppPayloadStream mSppPayloadStream;
    private MockitoSession mMockitoSession;
    private final byte[] mTestData = "testData".getBytes();
    private byte[] mCompletedMessage = SppPayloadStream.wrapWithArrayLength(mTestData);
    ;
    private byte[] mCompletedMessageSplit1;
    private byte[] mCompletedMessageSplit2;
    @Mock
    private SppPayloadStream.OnMessageCompletedListener mMockListener;


    @Before
    public void setUp() {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .startMocking();
        mSppPayloadStream = new SppPayloadStream();
        mSppPayloadStream.setMessageCompletedListener(mMockListener);
        int length = mCompletedMessage.length;
        mCompletedMessageSplit1 = Arrays.copyOfRange(mCompletedMessage, 0, (length + 1) / 2);
        mCompletedMessageSplit2 = Arrays.copyOfRange(mCompletedMessage, (length + 1) / 2, length);
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testWriteCompletedMessage_InformListener() throws IOException {
        mSppPayloadStream.write(mCompletedMessage);
        verify(mMockListener).onMessageCompleted(mTestData);
    }

    @Test
    public void testWriteIncompleteMessage_DoNotInformListener() throws IOException {
        mSppPayloadStream.write(mCompletedMessageSplit1);
        verify(mMockListener, never()).onMessageCompleted(mTestData);
    }

    @Test
    public void testWriteTwoMessage_InformListenerCompletedMessage() throws IOException {
        mSppPayloadStream.write(mCompletedMessageSplit1);
        mSppPayloadStream.write(mCompletedMessageSplit2);

        verify(mMockListener).onMessageCompleted(mTestData);
    }
}
