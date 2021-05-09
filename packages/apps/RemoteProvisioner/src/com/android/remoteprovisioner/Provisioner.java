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

import android.annotation.NonNull;
import android.hardware.security.keymint.DeviceInfo;
import android.hardware.security.keymint.ProtectedData;
import android.security.remoteprovisioning.IRemoteProvisioning;
import android.util.Log;

import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

/**
 * Provides an easy package to run the provisioning process from start to finish, interfacing
 * with the remote provisioning system service and the server backend in order to provision
 * attestation certificates to the device.
 */
public class Provisioner {
    private static final String TAG = "RemoteProvisioningService";

    /**
     * Drives the process of provisioning certs. The method first contacts the provided backend
     * server to retrieve an Endpoing Encryption Key with an accompanying certificate chain and a
     * challenge. It passes this data and the requested number of keys to the remote provisioning
     * system backend, which then works with KeyMint in order to get a CSR bundle generated, along
     * with an encrypted package containing metadata that the server needs in order to make
     * decisions about provisioning.
     *
     * This method then passes that bundle back out to the server backend, waits for the response,
     * and, if successful, passes the certificate chains back to the remote provisioning service to
     * be stored and later assigned to apps requesting a key attestation.
     *
     * @param numKeys The number of keys to be signed. The service will do a best-effort to
     *                     provision the number requested, but if the number requested is larger
     *                     than the number of unsigned attestation key pairs available, it will
     *                     only sign the number that is available at time of calling.
     *
     * @param secLevel Which KM instance should be used to provision certs.
     * @param binder The IRemoteProvisioning binder interface needed by the method to handle talking
     *                     to the remote provisioning system component.
     *
     * @return True if certificates were successfully provisioned for the signing keys.
     */
    public static boolean provisionCerts(int numKeys, int secLevel,
            @NonNull IRemoteProvisioning binder) {
        if (numKeys < 1) {
            Log.e(TAG, "Request at least 1 key to be signed. Num requested: " + numKeys);
            return false;
        }
        GeekResponse geek = ServerInterface.fetchGeek();
        if (geek == null) {
            Log.e(TAG, "The geek is null");
            return false;
        }
        DeviceInfo deviceInfo = new DeviceInfo();
        ProtectedData protectedData = new ProtectedData();
        byte[] macedKeysToSign =
                SystemInterface.generateCsr(false /* testMode */, numKeys, secLevel, geek,
                                            protectedData, deviceInfo, binder);
        if (macedKeysToSign == null || protectedData.protectedData == null
                || deviceInfo.deviceInfo == null) {
            Log.e(TAG, "Keystore failed to generate a payload");
            return false;
        }
        byte[] certificateRequest =
                CborUtils.buildCertificateRequest(deviceInfo.deviceInfo,
                                                  geek.challenge,
                                                  protectedData.protectedData,
                                                  macedKeysToSign);
        ArrayList<byte[]> certChains =
                new ArrayList<byte[]>(ServerInterface.requestSignedCertificates(
                        certificateRequest, geek.challenge));
        for (byte[] certChain : certChains) {
            // DER encoding specifies leaf to root ordering. Pull the public key and expiration
            // date from the leaf.
            X509Certificate cert;
            try {
                cert = X509Utils.formatX509Certs(certChain)[0];
            } catch (CertificateException e) {
                Log.e(TAG, "Failed to interpret DER encoded certificate chain", e);
                return false;
            }
            // getTime returns the time in *milliseconds* since the epoch.
            long expirationDate = cert.getNotAfter().getTime();
            byte[] rawPublicKey = X509Utils.getAndFormatRawPublicKey(cert);
            try {
                return SystemInterface.provisionCertChain(rawPublicKey, cert.getEncoded(),
                                                          certChain, expirationDate, secLevel,
                                                          binder);
            } catch (CertificateEncodingException e) {
                Log.e(TAG, "Somehow can't re-encode the decoded batch cert...", e);
                return false;
            }
        }
        Log.d(TAG, "Reaching this return statement implies the server returned 0 signed certs.");
        return false;
    }
}
