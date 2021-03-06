/* GENERATED SOURCE. DO NOT MODIFY. */
/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.org.conscrypt;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.KeyAgreementSpi;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

/**
 * Generic base classe for Diffie-Hellman style key agreement backed by the OpenSSL engine.
 * @hide This class is not part of the Android public SDK API
 */
@Internal
public abstract class OpenSSLBaseDHKeyAgreement<T> extends KeyAgreementSpi {

    /** OpenSSL handle of the private key. Only available after the engine has been initialized. */
    private T mPrivateKey;

    /**
     * Expected length (in bytes) of the agreed key ({@link #mResult}). Only available after the
     * engine has been initialized.
     */
    private int mExpectedResultLength;

    /** Agreed key. Only available after {@link #engineDoPhase(Key, boolean)} completes. */
    private byte[] mResult;

    protected OpenSSLBaseDHKeyAgreement() {}

    @Override
    public Key engineDoPhase(Key key, boolean lastPhase) throws InvalidKeyException {
        if (mPrivateKey == null) {
            throw new IllegalStateException("Not initialized");
        }
        if (!lastPhase) {
            throw new IllegalStateException("DH only has one phase");
        }

        if (key == null) {
            throw new InvalidKeyException("key == null");
        }
        if (!(key instanceof PublicKey)) {
            throw new InvalidKeyException("Not a public key: " + key.getClass());
        }
        T openSslPublicKey = convertPublicKey((PublicKey) key);

        byte[] buffer = new byte[mExpectedResultLength];
        int actualResultLength = computeKey(buffer, openSslPublicKey, mPrivateKey);
        byte[] result;
        if (actualResultLength == -1) {
            throw new RuntimeException("Engine returned -1");
        } else if (actualResultLength == mExpectedResultLength) {
            // The output is as long as expected -- use the whole buffer
            result = buffer;
        } else if (actualResultLength < mExpectedResultLength) {
            // The output is shorter than expected -- use only what's produced by the engine
            result = new byte[actualResultLength];
            System.arraycopy(buffer, 0, mResult, 0, mResult.length);
        } else {
            // The output is longer than expected
            throw new RuntimeException("Engine produced a longer than expected result. Expected: "
                + mExpectedResultLength + ", actual: " + actualResultLength);
        }
        mResult = result;

        return null; // No intermediate key
    }

    protected abstract T convertPublicKey(PublicKey key) throws InvalidKeyException;

    protected abstract T convertPrivateKey(PrivateKey key) throws InvalidKeyException;

    /**
     * Given the public key {@code theirPublicKey} and the private key {@code ourPrivateKey} write the shared secret
     * to {@code buffer} and return the actual output size.
     */
    protected abstract int computeKey(byte[] buffer, T theirPublicKey, T ourPrivateKey) throws InvalidKeyException;

    @Override
    protected int engineGenerateSecret(byte[] sharedSecret, int offset)
            throws ShortBufferException {
        checkCompleted();
        int available = sharedSecret.length - offset;
        if (mResult.length > available) {
            throw new ShortBufferWithoutStackTraceException(
                    "Needed: " + mResult.length + ", available: " + available);
        }

        System.arraycopy(mResult, 0, sharedSecret, offset, mResult.length);
        return mResult.length;
    }

    @Override
    protected byte[] engineGenerateSecret() {
        checkCompleted();
        return mResult;
    }

    @Override
    protected SecretKey engineGenerateSecret(String algorithm) {
        checkCompleted();
        return new SecretKeySpec(engineGenerateSecret(), algorithm);
    }

    @Override
    protected void engineInit(Key key, SecureRandom random) throws InvalidKeyException {
        if (key == null) {
            throw new InvalidKeyException("key == null");
        }
        if (!(key instanceof PrivateKey)) {
            throw new InvalidKeyException("Not a private key: " + key.getClass());
        }

        T privateKey = convertPrivateKey((PrivateKey) key);
        mExpectedResultLength = getOutputSize(privateKey);
        mPrivateKey = privateKey;
    }

    /** Returns the expected result length for the given {@code key}. */
    protected abstract int getOutputSize(T key);

    @Override
    protected void engineInit(Key key, AlgorithmParameterSpec params,
            SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
        // ECDH doesn't need an AlgorithmParameterSpec
        if (params != null) {
          throw new InvalidAlgorithmParameterException("No algorithm parameters supported");
        }
        engineInit(key, random);
    }

    private void checkCompleted() {
        if (mResult == null) {
            throw new IllegalStateException("Key agreement not completed");
        }
    }
}
