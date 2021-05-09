/**
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

package com.android.remoteprovisioner;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;

public class CborUtils {
    private static final int RESPONSE_CERT_ARRAY_INDEX = 0;
    private static final int RESPONSE_ARRAY_SIZE = 1;

    private static final int SHARED_CERTIFICATES_INDEX = 0;
    private static final int UNIQUE_CERTIFICATES_INDEX = 1;
    private static final int CERT_ARRAY_ENTRIES = 2;

    private static final int EEK_INDEX = 0;
    private static final int CHALLENGE_INDEX = 1;
    private static final int EEK_ARRAY_ENTRIES = 2;

    private static final String TAG = "RemoteProvisioningService";

    /**
     * Parses the signed certificate chains returned by the server. In order to reduce data use over
     * the wire, shared certificate chain prefixes are separated from the remaining unique portions
     * of each individual certificate chain. This method first parses the shared prefix certificates
     * and then prepends them to each unique certificate chain. Each PEM-encoded certificate chain
     * is returned in a byte array.
     *
     * @param serverResp The CBOR blob received from the server which contains all signed
     *                      certificate chains.
     *
     * @return A List object where each byte[] entry is an entire DER-encoded certificate chain.
     */
    public static List<byte[]> parseSignedCertificates(byte[] serverResp) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(serverResp);
            List<DataItem> dataItems = new CborDecoder(bais).decode();
            if (dataItems.size() != RESPONSE_ARRAY_SIZE
                    || dataItems.get(RESPONSE_CERT_ARRAY_INDEX).getMajorType() != MajorType.ARRAY) {
                Log.e(TAG, "Improper formatting of CBOR response. Expected size 1. Actual: "
                            + dataItems.size() + "\nExpected major type: Array. Actual: "
                            + dataItems.get(0).getMajorType().name());
                return null;
            }
            dataItems = ((Array) dataItems.get(RESPONSE_CERT_ARRAY_INDEX)).getDataItems();
            if (dataItems.size() != CERT_ARRAY_ENTRIES) {
                Log.e(TAG, "Incorrect number of certificate array entries. Expected: 2. Actual: "
                            + dataItems.size());
                return null;
            }
            if (dataItems.get(SHARED_CERTIFICATES_INDEX).getMajorType() != MajorType.BYTE_STRING
                    || dataItems.get(UNIQUE_CERTIFICATES_INDEX).getMajorType() != MajorType.ARRAY) {
                Log.e(TAG, "Incorrect CBOR types. Expected 'Byte String' and 'Array'. Got: "
                            + dataItems.get(SHARED_CERTIFICATES_INDEX).getMajorType().name()
                            + " and "
                            + dataItems.get(UNIQUE_CERTIFICATES_INDEX).getMajorType().name());
                return null;
            }
            byte[] sharedCertificates =
                    ((ByteString) dataItems.get(SHARED_CERTIFICATES_INDEX)).getBytes();
            Array uniqueCertificates = (Array) dataItems.get(UNIQUE_CERTIFICATES_INDEX);
            List<byte[]> uniqueCertificateChains = new ArrayList<byte[]>();
            for (DataItem entry : uniqueCertificates.getDataItems()) {
                if (entry.getMajorType() != MajorType.BYTE_STRING) {
                    Log.e(TAG, "Incorrect CBOR type. Expected: 'Byte String'. Actual:"
                                + entry.getMajorType().name());
                    return null;
                }
                ByteArrayOutputStream concat = new ByteArrayOutputStream();
                // DER encoding specifies certificate chains ordered from leaf to root.
                concat.write(((ByteString) entry).getBytes());
                concat.write(sharedCertificates);
                uniqueCertificateChains.add(concat.toByteArray());
            }
            return uniqueCertificateChains;
        } catch (CborException e) {
            Log.e(TAG, "CBOR decoding failed.", e);
        } catch (IOException e) {
            Log.e(TAG, "Writing bytes failed.", e);
        }
        return null;
    }

    /**
     * Parses the Google Endpoint Encryption Key response provided by the server which contains a
     * Google signed EEK and a challenge for use by the underlying IRemotelyProvisionedComponent HAL
     */
    public static GeekResponse parseGeekResponse(byte[] serverResp) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(serverResp);
            List<DataItem> dataItems = new CborDecoder(bais).decode();
            if (dataItems.size() != RESPONSE_ARRAY_SIZE
                    || dataItems.get(RESPONSE_CERT_ARRAY_INDEX).getMajorType() != MajorType.ARRAY) {
                Log.e(TAG, "Improper formatting of CBOR response. Expected size 1. Actual: "
                            + dataItems.size() + "\nExpected major type: Array. Actual: "
                            + dataItems.get(0).getMajorType().name());
                return null;
            }
            dataItems = ((Array) dataItems.get(RESPONSE_CERT_ARRAY_INDEX)).getDataItems();
            if (dataItems.size() != EEK_ARRAY_ENTRIES) {
                Log.e(TAG, "Incorrect number of certificate array entries. Expected: 2. Actual: "
                            + dataItems.size());
                return null;
            }
            if (dataItems.get(EEK_INDEX).getMajorType() != MajorType.ARRAY
                    || dataItems.get(CHALLENGE_INDEX).getMajorType() != MajorType.BYTE_STRING) {
                Log.e(TAG, "Incorrect CBOR types. Expected 'Array' and 'Byte String'. Got: "
                            + dataItems.get(EEK_INDEX).getMajorType().name()
                            + " and "
                            + dataItems.get(CHALLENGE_INDEX).getMajorType().name());
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(dataItems.get(EEK_INDEX));
            return new GeekResponse(baos.toByteArray(),
                                    ((ByteString) dataItems.get(CHALLENGE_INDEX)).getBytes());
        } catch (CborException e) {
            Log.e(TAG, "CBOR parsing/serializing failed.", e);
            return null;
        }
    }

    /**
     * Takes the various fields fetched from the server and the remote provisioning service and
     * formats them in the CBOR blob the server is expecting as defined by the
     * IRemotelyProvisionedComponent HAL AIDL files.
     */
    public static byte[] buildCertificateRequest(byte[] deviceInfo, byte[] challenge,
                                                 byte[] protectedData, byte[] macedKeysToSign) {
            // This CBOR library doesn't support adding already serialized CBOR structures into a
            // CBOR builder. Because of this, we have to first deserialize the provided parameters
            // back into the library's CBOR object types, and then reserialize them into the
            // desired structure.
        try {
            // Deserialize the protectedData blob
            ByteArrayInputStream bais = new ByteArrayInputStream(protectedData);
            List<DataItem> dataItems = new CborDecoder(bais).decode();
            if (dataItems.size() != 1 || dataItems.get(0).getMajorType() != MajorType.ARRAY) {
                Log.e(TAG, "protectedData is carrying unexpected data.");
                return null;
            }
            Array protectedDataArray = (Array) dataItems.get(0);

            // Deserialize macedKeysToSign
            bais = new ByteArrayInputStream(macedKeysToSign);
            dataItems = new CborDecoder(bais).decode();
            if (dataItems.size() != 1 || dataItems.get(0).getMajorType() != MajorType.ARRAY) {
                Log.e(TAG, "macedKeysToSign is carrying unexpected data.");
                return null;
            }
            Array macedKeysToSignArray = (Array) dataItems.get(0);

            // Deserialize deviceInfo
            bais = new ByteArrayInputStream(deviceInfo);
            dataItems = new CborDecoder(bais).decode();
            if (dataItems.size() != 1 || dataItems.get(0).getMajorType() != MajorType.MAP) {
                Log.e(TAG, "macedKeysToSign is carrying unexpected data.");
                return null;
            }
            Map deviceInfoMap = (Map) dataItems.get(0);

            // Serialize the actual CertificateSigningRequest structure
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(new CborBuilder()
                    .addArray()
                        .add(deviceInfoMap)
                        .add(challenge)
                        .add(protectedDataArray)
                        .add(macedKeysToSignArray)
                        .end()
                    .build());
            return baos.toByteArray();
        } catch (CborException e) {
            Log.e(TAG, "Malformed CBOR", e);
            return null;
        }
    }
}
