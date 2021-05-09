/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.config.remote;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.gcs.GCSDownloaderHelper;
import com.android.tradefed.config.DynamicRemoteFileResolver;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import javax.annotation.Nonnull;

/** Implementation of {@link IRemoteFileResolver} that allows downloading from a GCS bucket. */
public class GcsRemoteFileResolver implements IRemoteFileResolver {

    public static final String PROTOCOL = "gs";

    private static final long SLEEP_INTERVAL_MS = 5 * 1000;
    private static final String RETRY_TIMEOUT_MS_ARG = "retry_timeout_ms";

    private GCSDownloaderHelper mHelper = null;

    @Override
    public ResolvedFile resolveRemoteFile(RemoteFileResolverArgs args) throws BuildRetrievalError {
        File consideredFile = args.getConsideredFile();
        // Don't use absolute path as it would not start with gs:
        String path = consideredFile.getPath();
        try {
            // We need to download the file from the bucket
            File downloadedFile = fetchResourceWithRetry(path, args.getQueryArgs());
            // Unzip it if required
            return new ResolvedFile(
                    DynamicRemoteFileResolver.unzipIfRequired(downloadedFile, args.getQueryArgs()));
        } catch (IOException e) {
            CLog.e(e);
            throw new BuildRetrievalError(
                    String.format("Failed to download %s due to: %s", path, e.getMessage()),
                    e,
                    InfraErrorIdentifier.GCS_ERROR);
        }
    }

    @Override
    public @Nonnull String getSupportedProtocol() {
        return PROTOCOL;
    }

    @VisibleForTesting
    protected GCSDownloaderHelper getDownloader() {
        if (mHelper == null) {
            mHelper = new GCSDownloaderHelper();
        }
        return mHelper;
    }

    @VisibleForTesting
    void sleep() {
        RunUtil.getDefault().sleep(SLEEP_INTERVAL_MS);
    }

    /** If the retry arg is set, we retry downloading until timeout is reached */
    private File fetchResourceWithRetry(String path, Map<String, String> queryArgs)
            throws BuildRetrievalError {
        String timeoutStringValue = queryArgs.get(RETRY_TIMEOUT_MS_ARG);
        if (timeoutStringValue == null) {
            return getDownloader().fetchTestResource(path);
        }
        long timeout = System.currentTimeMillis() + Long.parseLong(timeoutStringValue);
        BuildRetrievalError error = null;
        while (System.currentTimeMillis() < timeout) {
            try {
                return getDownloader().fetchTestResource(path);
            } catch (BuildRetrievalError e) {
                error = e;
            }
            sleep();
        }
        throw error;
    }
}
