/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.remoteprovisioner.unittest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.remoteprovisioner.CborUtils;
import com.android.remoteprovisioner.GeekResponse;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CborUtilsTest {

    private ByteArrayOutputStream mBaos;

    @Before
    public void setUp() throws Exception {
        mBaos = new ByteArrayOutputStream();
    }

    @Presubmit
    @Test
    public void testParseSignedCertificatesFakeData() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add(new byte[] {0x01, 0x02, 0x03})
                    .addArray()
                        .add(new byte[] {0x04, 0x05, 0x06})
                        .add(new byte[] {0x07, 0x08, 0x09})
                        .end()
                    .end()
                .build());
        byte[] encodedBytes = mBaos.toByteArray();
        ArrayList<byte[]> certChains =
                new ArrayList<byte[]>(CborUtils.parseSignedCertificates(encodedBytes));
        assertArrayEquals(new byte[] {0x04, 0x05, 0x06, 0x01, 0x02, 0x03}, certChains.get(0));
        assertArrayEquals(new byte[] {0x07, 0x08, 0x09, 0x01, 0x02, 0x03}, certChains.get(1));
    }

    @Test
    public void testParseSignedCertificatesWrongSize() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add(1)
                    .end()
                .build());
        assertNull(CborUtils.parseSignedCertificates(mBaos.toByteArray()));
    }

    @Test
    public void testParseSignedCertificatesWrongTypeSharedCerts() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add("Should be a bstr")
                    .addArray()
                        .add(new byte[] {0x04, 0x05, 0x06})
                        .add(new byte[] {0x07, 0x08, 0x09})
                        .end()
                    .end()
                .build());
        assertNull(CborUtils.parseSignedCertificates(mBaos.toByteArray()));
    }

    @Test
    public void testParseSignedCertificatesWrongTypeUniqueCerts() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add(new byte[] {0x01, 0x02, 0x03})
                    .addArray()
                        .add(new byte[] {0x04, 0x05, 0x06})
                        .add("Every entry should be a bstr")
                        .add(new byte[] {0x07, 0x08, 0x09})
                        .end()
                    .end()
                .build());
        assertNull(CborUtils.parseSignedCertificates(mBaos.toByteArray()));
    }

    @Presubmit
    @Test
    public void testParseGeekResponseFakeData() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .addArray()                                       // GEEK Chain
                        .add(new byte[] {0x01, 0x02, 0x03})
                        .add(new byte[] {0x04, 0x05, 0x06})
                        .add(new byte[] {0x07, 0x08, 0x09})
                        .end()
                    .add(new byte[] {0x0a, 0x0b, 0x0c})               // Challenge
                    .end()
                .build());
        GeekResponse resp = CborUtils.parseGeekResponse(mBaos.toByteArray());
        mBaos.reset();
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add(new byte[] {0x01, 0x02, 0x03})
                    .add(new byte[] {0x04, 0x05, 0x06})
                    .add(new byte[] {0x07, 0x08, 0x09})
                    .end()
                .build());
        byte[] expectedGeek = mBaos.toByteArray();
        assertArrayEquals(expectedGeek, resp.geek);
        assertArrayEquals(new byte[] {0x0a, 0x0b, 0x0c}, resp.challenge);
    }

    @Test
    public void testParseGeekResponseWrongSize() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .addArray()
                        .add(new byte[] {0x01, 0x02, 0x03})
                        .add(new byte[] {0x04, 0x05, 0x06})
                        .add(new byte[] {0x07, 0x08, 0x09})
                        .end()
                    .add(new byte[] {0x0a, 0x0b, 0x0c})
                    .add("One more entry than there should be")
                    .end()
                .build());
        assertNull(CborUtils.parseGeekResponse(mBaos.toByteArray()));
    }

    @Test
    public void testCreateCertificateRequest() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addMap()
                    .put("a", "b")
                    .put("cool", "yeah")
                    .put("testing", "123")
                    .put("str", "str")
                    .end()
                .build());
        byte[] deviceInfo = mBaos.toByteArray();
        mBaos.reset();
        byte[] challenge = new byte[] {0x01, 0x02, 0x03};
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add("protected header")
                    .add("unprotected header")
                    .add("super secret payload")
                    .add("super not secret recipient")
                    .end()
                .build());
        byte[] protectedDataPayload = mBaos.toByteArray();
        mBaos.reset();
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add("protected header")
                    .add("unprotected header")
                    .add("super not secret payload")
                    .add("mac tag")
                    .end()
                .build());
        byte[] macedKeysToSign = mBaos.toByteArray();
        byte[] certReq =
                CborUtils.buildCertificateRequest(deviceInfo,
                                                  challenge,
                                                  protectedDataPayload,
                                                  macedKeysToSign);
        ByteArrayInputStream bais = new ByteArrayInputStream(certReq);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertEquals(1, dataItems.size());
        assertEquals(MajorType.ARRAY, dataItems.get(0).getMajorType());
        dataItems = ((Array) dataItems.get(0)).getDataItems();
        assertEquals(4, dataItems.size());
        assertEquals(MajorType.MAP, dataItems.get(0).getMajorType());
        assertEquals(MajorType.BYTE_STRING, dataItems.get(1).getMajorType());
        assertEquals(MajorType.ARRAY, dataItems.get(2).getMajorType());
        assertEquals(MajorType.ARRAY, dataItems.get(3).getMajorType());
    }
}
