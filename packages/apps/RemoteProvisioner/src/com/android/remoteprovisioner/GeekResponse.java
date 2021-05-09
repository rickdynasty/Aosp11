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

/**
 * Convenience class for packaging up the values returned by the server when initially requesting
 * an Endpoint Encryption Key for remote provisioning. Those values are described by the following
 * CDDL Schema:
 *    GeekResponse = [
 *        EekChain,
 *        challenge : bstr,
 *    ]
 *
 * The CDDL that defines EekChain is defined in the RemoteProvisioning HAL, but this app does not
 * require any semantic understanding of the format to perform its function.
 */
public class GeekResponse {
    public byte[] challenge;
    public byte[] geek;

    public GeekResponse(byte[] geek, byte[] challenge) {
        this.geek = geek;
        this.challenge = challenge;
    }
}
