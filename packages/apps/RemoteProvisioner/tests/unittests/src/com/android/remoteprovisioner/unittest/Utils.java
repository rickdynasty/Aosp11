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

import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * Utility class for unit testing.
 */
public class Utils {

    public static PublicKey getP256PubKeyFromBytes(byte[] xPub, byte[] yPub) throws Exception {
        BigInteger x = new BigInteger(1, xPub);
        BigInteger y = new BigInteger(1, yPub);
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecParameters = parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(x, y);
        ECPublicKeySpec keySpec = new ECPublicKeySpec(point, ecParameters);
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        return keyFactory.generatePublic(keySpec);
    }

    public static KeyPair generateEcdsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        ECGenParameterSpec params = new ECGenParameterSpec("secp256r1");
        generator.initialize(params);
        return generator.generateKeyPair();
    }

    public static X509Certificate signPublicKey(KeyPair issuerKeyPair, PublicKey publicKeyToSign)
            throws Exception {
        X500Principal issuer = new X500Principal("CN=TEE");
        BigInteger serial = BigInteger.ONE;
        X500Principal subject = new X500Principal("CN=TEE");

        Instant now = Instant.now();
        X509V3CertificateGenerator certificateBuilder = new X509V3CertificateGenerator();
        certificateBuilder.setIssuerDN(issuer);
        certificateBuilder.setSerialNumber(serial);
        certificateBuilder.setNotBefore(Date.from(now));
        certificateBuilder.setNotAfter(Date.from(now.plus(Duration.ofDays(1))));
        certificateBuilder.setSignatureAlgorithm("SHA256WITHECDSA");
        certificateBuilder.setSubjectDN(subject);
        certificateBuilder.setPublicKey(publicKeyToSign);
        certificateBuilder.addExtension(
                Extension.basicConstraints, /*isCritical=*/ true, new BasicConstraints(true));
        certificateBuilder.addExtension(
                Extension.keyUsage, /*isCritical=*/ true, new KeyUsage(KeyUsage.keyCertSign));
        return certificateBuilder.generate(issuerKeyPair.getPrivate());
    }
}
