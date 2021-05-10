/*
 * Copyright (C) 2019 The Android Open Source Project
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
package org.chromium.net.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CronetApiTest {
    private static final String TAG = CronetApiTest.class.getSimpleName();
    static final String TEST_DOMAIN = "www.google.com";
    static final String HTTPS_PREFIX = "https://";
    static final int TIMEOUT_MS = 12_000;

    @NonNull
    private CronetEngine mCronetEngine;
    @NonNull
    private ConnectivityManager mCm;
    @NonNull
    private Executor mExecutor;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mCm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        CronetEngine.Builder builder = new CronetEngine.Builder(context);
        builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 100 * 1024)
                .enableHttp2(true)
                //.enableBrotli(true)
                .enableQuic(true);
        mCronetEngine = builder.build();
        mExecutor = new Handler(Looper.getMainLooper())::post;
    }

    static private void assertGreaterThan(String msg, int first, int second) {
        assertTrue(msg + " Excepted " + first + " to be greater than " + second, first > second);
    }

    private void assertHasTestableNetworks() {
        assertNotNull("This test requires a working Internet connection",
            mCm.getActiveNetwork());
    }

    class VerifyUrlRequestCallback extends UrlRequest.Callback {
        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final String mUrl;

        VerifyUrlRequestCallback(@NonNull String url) {
            this.mUrl = url;
        }

        public boolean waitForAnswer() throws InterruptedException {
            return mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onRedirectReceived(
                UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
            request.followRedirect();
        }

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
            request.read(ByteBuffer.allocateDirect(32 * 1024));
        }

        @Override
        public void onReadCompleted(
            UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) {
            byteBuffer.clear();
            request.read(byteBuffer);
        }


        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            assertEquals("Unexpected http status code", info.getHttpStatusCode(), 200);
            assertGreaterThan("Received byte is 0", (int)info.getReceivedByteCount(), 0);
            mLatch.countDown();
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error) {
            fail(mUrl + error.getMessage());
        }
    }

    @Test
    public void testUrlGet() throws Exception {
        assertHasTestableNetworks();
        String url = HTTPS_PREFIX + TEST_DOMAIN;
        VerifyUrlRequestCallback callback = new VerifyUrlRequestCallback(url);
        UrlRequest.Builder builder = mCronetEngine.newUrlRequestBuilder(url, callback, mExecutor);
        builder.build().start();
        assertTrue(url + " but not complete after " + TIMEOUT_MS + "ms.",
                callback.waitForAnswer());
    }

}
