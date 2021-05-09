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

package com.android.remoteprovisioner.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.security.IGenerateRkpKeyService;
import android.security.remoteprovisioning.AttestationPoolStatus;
import android.security.remoteprovisioning.IRemoteProvisioning;
import android.util.Log;

import com.android.remoteprovisioner.Provisioner;

/**
 * Provides the implementation for IGenerateKeyService.aidl
 */
public class GenerateRkpKeyService extends Service {
    private static final String SERVICE = "android.security.remoteprovisioning";
    private static final String TAG = "RemoteProvisioningService";

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IGenerateRkpKeyService.Stub mBinder = new IGenerateRkpKeyService.Stub() {
        @Override
        public void generateKey(int securityLevel) {
            try {
                IRemoteProvisioning binder =
                        IRemoteProvisioning.Stub.asInterface(ServiceManager.getService(SERVICE));
                // Iterate through each security level backend
                checkAndFillPool(binder, securityLevel);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote Exception: ", e);
            }
        }

        @Override
        public void notifyKeyGenerated(int securityLevel) {
            try {
                IRemoteProvisioning binder =
                        IRemoteProvisioning.Stub.asInterface(ServiceManager.getService(SERVICE));
                // Iterate through each security level backend
                checkAndFillPool(binder, securityLevel);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote Exception: ", e);
            }
        }

        private void checkAndFillPool(IRemoteProvisioning binder, int secLevel)
                throws RemoteException {
            AttestationPoolStatus pool =
                    binder.getPoolStatus(System.currentTimeMillis(), secLevel);
            if (pool.unassigned == 0) {
                binder.generateKeyPair(false /* isTestMode */, secLevel);
                Provisioner.provisionCerts(1 /* numCsr */, secLevel, binder);
            } else {
                Log.e(TAG, "generateKey() called, but signed certs are available.");
            }
        }
    };
}
