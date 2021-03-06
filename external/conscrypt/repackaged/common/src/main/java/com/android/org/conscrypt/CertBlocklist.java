/* GENERATED SOURCE. DO NOT MODIFY. */
/*
 * Copyright 2018 The Android Open Source Project
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

import java.math.BigInteger;
import java.security.PublicKey;

/**
 * A set of certificates that are blacklisted from trust.
 * @hide This class is not part of the Android public SDK API
 */
public interface CertBlocklist {

    /**
     * Returns whether the given public key is in the blacklist.
     */
    boolean isPublicKeyBlockListed(PublicKey publicKey);

    /**
     * Returns whether the given serial number is in the blacklist.
     */
    boolean isSerialNumberBlockListed(BigInteger serial);
}
