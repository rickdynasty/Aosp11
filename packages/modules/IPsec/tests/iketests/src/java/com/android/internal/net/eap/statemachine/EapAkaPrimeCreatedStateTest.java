/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.net.eap.test.statemachine;

import static android.net.eap.test.EapSessionConfig.EapMethodConfig.EAP_TYPE_AKA_PRIME;

import static com.android.internal.net.eap.test.message.EapMessage.EAP_CODE_REQUEST;
import static com.android.internal.net.eap.test.message.EapTestMessageDefinitions.ID_INT;
import static com.android.internal.net.eap.test.message.simaka.EapAkaTypeData.EAP_AKA_CHALLENGE;
import static com.android.internal.net.eap.test.message.simaka.EapAkaTypeData.EAP_AKA_IDENTITY;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.android.internal.net.eap.test.message.EapData;
import com.android.internal.net.eap.test.message.EapMessage;
import com.android.internal.net.eap.test.message.simaka.EapAkaPrimeTypeData;
import com.android.internal.net.eap.test.message.simaka.EapAkaTypeData;
import com.android.internal.net.eap.test.message.simaka.EapSimAkaTypeData.DecodeResult;
import com.android.internal.net.eap.test.statemachine.EapAkaPrimeMethodStateMachine.ChallengeState;

import org.junit.Test;

import java.util.ArrayList;

public class EapAkaPrimeCreatedStateTest extends EapAkaPrimeStateTest {
    @Test
    public void testProcessTransitionToIdentityState() throws Exception {
        EapData eapData = new EapData(EAP_TYPE_AKA_PRIME, DUMMY_EAP_TYPE_DATA);
        EapMessage eapMessage = new EapMessage(EAP_CODE_REQUEST, ID_INT, eapData);

        // Don't actually need any attributes in the attributeMap, since we only care about the
        // state transition here.
        DecodeResult<EapAkaTypeData> decodeResult =
                new DecodeResult<>(new EapAkaPrimeTypeData(EAP_AKA_IDENTITY, new ArrayList<>()));
        doReturn(decodeResult).when(mMockTypeDataDecoder).decode(eq(DUMMY_EAP_TYPE_DATA));

        mStateMachine.process(eapMessage);

        assertTrue(mStateMachine.getState() instanceof EapAkaMethodStateMachine.IdentityState);

        // decoded in CreatedState and IdentityState
        verify(mMockTypeDataDecoder, times(2)).decode(eq(DUMMY_EAP_TYPE_DATA));
        verifyNoMoreInteractions(mMockTelephonyManager, mMockTypeDataDecoder);
    }

    @Test
    public void testProcessTransitionToChallengeState() throws Exception {
        EapData eapData = new EapData(EAP_TYPE_AKA_PRIME, DUMMY_EAP_TYPE_DATA);
        EapMessage eapMessage = new EapMessage(EAP_CODE_REQUEST, ID_INT, eapData);

        // Don't actually need any attributes in the attributeMap, since we only care about the
        // state transition here.
        DecodeResult<EapAkaTypeData> decodeResult =
                new DecodeResult<>(new EapAkaPrimeTypeData(EAP_AKA_CHALLENGE, new ArrayList<>()));
        doReturn(decodeResult).when(mMockTypeDataDecoder).decode(eq(DUMMY_EAP_TYPE_DATA));

        mStateMachine.process(eapMessage);

        ChallengeState challengeState = (ChallengeState) mStateMachine.getState();
        assertArrayEquals(EAP_IDENTITY_BYTES, challengeState.mIdentity);

        // decoded in CreatedState and ChallengeState
        verify(mMockTypeDataDecoder, times(2)).decode(DUMMY_EAP_TYPE_DATA);
        verifyNoMoreInteractions(mMockTelephonyManager, mMockTypeDataDecoder);
    }
}
