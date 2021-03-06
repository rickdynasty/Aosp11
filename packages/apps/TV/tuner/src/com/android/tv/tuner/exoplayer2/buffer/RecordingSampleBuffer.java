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

package com.android.tv.tuner.exoplayer2.buffer;

import android.os.ConditionVariable;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.util.Log;

import com.android.tv.tuner.exoplayer2.MpegTsPlayerV2;
import com.android.tv.tuner.exoplayer2.SampleExtractor;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.util.Assertions;
import com.google.auto.factory.AutoFactory;
import com.google.auto.factory.Provided;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles I/O between {@link SampleExtractor} and {@link BufferManager}.Reads & writes samples
 * from/to {@link SampleChunk} which is backed by physical storage.
 */
public class RecordingSampleBuffer
        implements BufferManager.SampleBuffer, BufferManager.ChunkEvictedListener {
    private static final String TAG = "RecordingSampleBuffer";

    @IntDef({BUFFER_REASON_LIVE_PLAYBACK, BUFFER_REASON_RECORDED_PLAYBACK, BUFFER_REASON_RECORDING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface BufferReason {}

    /** A buffer reason for live-stream playback. */
    public static final int BUFFER_REASON_LIVE_PLAYBACK = 0;

    /** A buffer reason for playback of a recorded program. */
    public static final int BUFFER_REASON_RECORDED_PLAYBACK = 1;

    /** A buffer reason for recording a program. */
    public static final int BUFFER_REASON_RECORDING = 2;

    /** The minimum duration to support seek in Trickplay. */
    static final long MIN_SEEK_DURATION_US = TimeUnit.MILLISECONDS.toMicros(500);

    /** The duration of a {@link SampleChunk} for recordings. */
    static final long RECORDING_CHUNK_DURATION_US = MIN_SEEK_DURATION_US * 1200; // 10 minutes

    private static final long BUFFER_WRITE_TIMEOUT_MS = 10 * 1000; // 10 seconds
    private static final long BUFFER_NEEDED_US =
            1000L * Math.max(MpegTsPlayerV2.MIN_BUFFER_MS, MpegTsPlayerV2.MIN_REBUFFER_MS);

    private final BufferManager mBufferManager;
    private final PlaybackBufferListener mBufferListener;
    private final @BufferReason int mBufferReason;
    private final SampleChunkIoHelper.Factory mSampleChunkIoHelperFactory;

    private int mTrackCount;
    private boolean[] mTrackSelected;
    private List<SampleQueue> mReadSampleQueues;
    private final InputBufferPool mInputBufferPool = new InputBufferPool();
    private long mCurrentPlaybackPositionUs = 0;

    // An error in I/O thread of {@link SampleChunkIoHelper} will be notified.
    private volatile boolean mError;

    // Eos was reached in I/O thread of {@link SampleChunkIoHelper}.
    private volatile boolean mEos;
    private SampleChunkIoHelper mSampleChunkIoHelper;
    private final SampleChunkIoHelper.IoCallback mIoCallback =
            new SampleChunkIoHelper.IoCallback() {
                @Override
                public void onIoReachedEos() {
                    mEos = true;
                }

                @Override
                public void onIoError() {
                    mError = true;
                }
            };

    /**
     * Factory for {@link RecordingSampleBuffer}.
     *
     * <p>This wrapper class keeps other classes from needing to reference the {@link AutoFactory}
     * generated class.
     */
    public interface Factory {
        RecordingSampleBuffer create(
                BufferManager bufferManager,
                PlaybackBufferListener bufferListener,
                boolean enableTrickplay,
                @BufferReason int bufferReason);
    }

    /**
     * Creates {@link BufferManager.SampleBuffer} with cached I/O backed by physical storage (e.g.
     * trickplay,recording,recorded-playback).
     *
     * @param bufferManager the manager of {@link SampleChunk}
     * @param bufferListener the listener for buffer I/O event
     * @param enableTrickplay {@code true} when trickplay should be enabled
     * @param bufferReason the reason for caching samples {@link BufferReason}
     */
    @AutoFactory(implementing = Factory.class)
    public RecordingSampleBuffer(
            BufferManager bufferManager,
            PlaybackBufferListener bufferListener,
            boolean enableTrickplay,
            @BufferReason int bufferReason,
            @Provided SampleChunkIoHelper.Factory sampleChunkIoHelperFactory) {
        mBufferManager = bufferManager;
        mBufferListener = bufferListener;
        if (bufferListener != null) {
            bufferListener.onBufferStateChanged(enableTrickplay);
        }
        mBufferReason = bufferReason;
        mSampleChunkIoHelperFactory = sampleChunkIoHelperFactory;
    }

    @Override
    public void init(@NonNull List<String> ids, @NonNull List<Format> formats)
            throws IOException {
        mTrackCount = ids.size();
        if (mTrackCount <= 0) {
            throw new IOException("No tracks to initialize");
        }
        mTrackSelected = new boolean[mTrackCount];
        mReadSampleQueues = new ArrayList<>();
        mSampleChunkIoHelper =
                mSampleChunkIoHelperFactory.create(
                        ids, formats, mBufferReason, mBufferManager, mInputBufferPool, mIoCallback);
        for (int i = 0; i < mTrackCount; ++i) {
            mReadSampleQueues.add(i, new SampleQueue(mInputBufferPool));
        }
        mSampleChunkIoHelper.init();
        for (int i = 0; i < mTrackCount; ++i) {
            mBufferManager.registerChunkEvictedListener(ids.get(i), RecordingSampleBuffer.this);
        }
    }

    @Override
    public void selectTrack(int index) {
        if (!mTrackSelected[index]) {
            mTrackSelected[index] = true;
            mReadSampleQueues.get(index).clear();
            mSampleChunkIoHelper.openRead(index, mCurrentPlaybackPositionUs);
        }
    }

    @Override
    public void deselectTrack(int index) {
        if (mTrackSelected[index]) {
            mTrackSelected[index] = false;
            mReadSampleQueues.get(index).clear();
            mSampleChunkIoHelper.closeRead(index);
        }
    }

    @Override
    public void writeSample(
            int index,
            DecoderInputBuffer sample,
            ConditionVariable conditionVariable) throws IOException {
        mSampleChunkIoHelper.writeSample(index, sample, conditionVariable);

        if (!conditionVariable.block(BUFFER_WRITE_TIMEOUT_MS)) {
            Log.e(TAG, "Error: Serious delay on writing buffer");
            conditionVariable.block();
        }
    }

    @Override
    public boolean isWriteSpeedSlow(int sampleSize, long writeDurationNs) {
        if (mBufferReason == BUFFER_REASON_RECORDED_PLAYBACK) {
            return false;
        }
        mBufferManager.addWriteStat(sampleSize, writeDurationNs);
        return mBufferManager.isWriteSlow();
    }

    @Override
    public void handleWriteSpeedSlow() {
        if (mBufferReason == BUFFER_REASON_RECORDING) {
            // Recording does not need to stop because I/O speed is slow temporarily.
            // If fixed size buffer of TsStreamer overflows, TsDataSource will reach EoS.
            // Reaching EoS will stop recording eventually.
            Log.w(
                    TAG,
                    "Disk I/O speed is slow for recording temporarily: "
                            + mBufferManager.getWriteBandwidth()
                            + "MBps");
            return;
        }
        // Disables buffering samples afterwards, and notifies the disk speed is slow.
        Log.w(TAG, "Disk is too slow for trickplay");
        mBufferListener.onDiskTooSlow();
    }

    @Override
    public void setEos() {
        mSampleChunkIoHelper.closeWrite();
    }

    private boolean maybeReadSample(SampleQueue queue, int index) {
        if (queue.getLastQueuedPositionUs() != null
                && queue.getLastQueuedPositionUs() > mCurrentPlaybackPositionUs + BUFFER_NEEDED_US
                && queue.isDurationGreaterThan(MIN_SEEK_DURATION_US)) {
            // The speed of queuing samples can be higher than the playback speed.
            // If the duration of the samples in the queue is not limited,
            // samples can be accumulated and there can be out-of-memory issues.
            // But, the throttling should provide enough samples for the player to
            // finish the buffering state.
            return false;
        }
        DecoderInputBuffer sample = mSampleChunkIoHelper.readSample(index);
        if (sample != null) {
            queue.queueSample(sample);
            return true;
        }
        return false;
    }

    @Override
    public int readSample(int track, DecoderInputBuffer outSample) {
        Assertions.checkState(mTrackSelected[track]);
        maybeReadSample(mReadSampleQueues.get(track), track);
        int result = mReadSampleQueues.get(track).dequeueSample(outSample);
        if ((result != C.RESULT_BUFFER_READ && mEos) || mError) {
            return C.RESULT_END_OF_INPUT;
        }
        return result;
    }

    @Override
    public void seekTo(long positionUs) {
        for (int i = 0; i < mTrackCount; ++i) {
            if (mTrackSelected[i]) {
                mReadSampleQueues.get(i).clear();
                mSampleChunkIoHelper.openRead(i, positionUs);
            }
        }
    }

    @Override
    public boolean continueLoading(long positionUs) {
        mCurrentPlaybackPositionUs = positionUs;
        for (int i = 0; i < mTrackCount; ++i) {
            if (!mTrackSelected[i]) {
                continue;
            }
            SampleQueue queue = mReadSampleQueues.get(i);
            maybeReadSample(queue, i);
            if (queue.getLastQueuedPositionUs() == null
                    || positionUs > queue.getLastQueuedPositionUs()) {
                // No more buffered data.
                return false;
            }
        }
        return true;
    }

    @Override
    public void release() throws IOException {
        if (mTrackCount <= 0) {
            return;
        }
        if (mSampleChunkIoHelper != null) {
            mSampleChunkIoHelper.release();
        }
    }

    // onChunkEvictedListener
    @Override
    public void onChunkEvicted(long createdTimeMs) {
        if (mBufferListener != null) {
            mBufferListener.onBufferStartTimeChanged(
                    createdTimeMs + TimeUnit.MICROSECONDS.toMillis(MIN_SEEK_DURATION_US));
        }
    }
}
