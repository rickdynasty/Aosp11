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

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * A receiver class that listens for boot to be completed and then starts a recurring job that will
 * monitor the status of the attestation key pool on device, purging old certificates and requesting
 * new ones as needed.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "RemoteProvisioningBootReceiver";
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Caught boot intent, waking up.");
        JobInfo info = new JobInfo
                .Builder(1, new ComponentName(context, PeriodicProvisioner.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(1000, 1000)
                .setPeriodic(1000 * 60 * 60 * 24)
                .build();
        if (((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(info)
                != JobScheduler.RESULT_SUCCESS) {
            Log.e(TAG, "Could not start the job scheduler for provisioning");
        }
    }

}
