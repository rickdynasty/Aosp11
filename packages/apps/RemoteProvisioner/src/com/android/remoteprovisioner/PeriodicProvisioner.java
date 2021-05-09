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

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.security.remoteprovisioning.AttestationPoolStatus;
import android.security.remoteprovisioning.IRemoteProvisioning;
import android.util.Log;

/**
 * A class that extends JobService in order to be scheduled to check the status of the attestation
 * key pool at regular intervals. If the job determines that more keys need to be generated and
 * signed, it drives that process.
 */
public class PeriodicProvisioner extends JobService {
    //TODO(b/176249146): Replace default values here with values fetched from the server.
    private static int sTotalSignedKeys = 0;
    // Check for expiring certs in the next 3 days
    private static int sExpiringBy = 1000 * 60 * 60 * 24 * 3;

    private static final String SERVICE = "android.security.remoteprovisioning";
    private static final String TAG = "RemoteProvisioningService";
    private ProvisionerThread mProvisionerThread;

    /**
     * Starts the periodic provisioning job, which will occasionally check the attestation key pool
     * and provision it as necessary.
     */
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "Starting provisioning job");
        mProvisionerThread = new ProvisionerThread(params);
        mProvisionerThread.start();
        return true;
    }

    /**
     * Allows the job to be stopped if need be.
     */
    public boolean onStopJob(JobParameters params) {
        mProvisionerThread.stop();
        return false;
    }

    private class ProvisionerThread extends Thread {
        private JobParameters mParams;

        ProvisionerThread(JobParameters params) {
            mParams = params;
        }

        public void run() {
            try {
                IRemoteProvisioning binder =
                        IRemoteProvisioning.Stub.asInterface(ServiceManager.getService(SERVICE));
                if (binder == null) {
                    Log.e(TAG, "Binder returned null pointer to RemoteProvisioning service.");
                    jobFinished(mParams, true /* wantsReschedule */);
                    return;
                }
                int[] securityLevels = binder.getSecurityLevels();
                if (securityLevels == null) {
                    Log.e(TAG, "No instances of IRemotelyProvisionedComponent registered in "
                               + SERVICE);
                    jobFinished(mParams, true /* wantsReschedule */);
                    return;
                }
                for (int i = 0; i < securityLevels.length; i++) {
                    // TODO(b/176249146): Replace expiration date parameter with value fetched from
                    //                    server
                    checkAndProvision(binder, System.currentTimeMillis() + sExpiringBy,
                            securityLevels[i]);
                }
                jobFinished(mParams, false /* wantsReschedule */);
            } catch (RemoteException e) {
                jobFinished(mParams, true /* wantsReschedule */);
                Log.e(TAG, "Error on the binder side during provisioning.", e);
            } catch (InterruptedException e) {
                jobFinished(mParams, true /* wantsReschedule */);
                Log.e(TAG, "Provisioner thread interrupted.", e);
            }
        }

        private void checkAndProvision(IRemoteProvisioning binder, long expiringBy, int secLevel)
                throws InterruptedException, RemoteException {
            AttestationPoolStatus pool = binder.getPoolStatus(expiringBy, secLevel);
            int generated = 0;
            while (generated + pool.total - pool.expiring < sTotalSignedKeys) {
                generated++;
                binder.generateKeyPair(false /* isTestMode */, secLevel);
                Thread.sleep(5000);
            }
            if (generated > 0) {
                Log.d(TAG, "Keys generated, moving to provisioning process.");
                Provisioner.provisionCerts(generated, secLevel, binder);
            }
        }
    }
}
