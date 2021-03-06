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

package com.android.nn.benchmark.app;

import android.test.suitebuilder.annotation.LargeTest;

import com.android.nn.benchmark.core.TestModels;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tests that ensure stability of NNAPI by running inference for a
 * prolonged period of time.
 */
@RunWith(Parameterized.class)
public class NNInferenceStressTest extends BenchmarkTestBase {
    private static final String TAG = NNInferenceStressTest.class.getSimpleName();

    private static final float WARMUP_SECONDS = 0; // No warmup.
    private static final float RUNTIME_SECONDS = Duration.ofHours(1).getSeconds();
    private static final long LONG_STRESS_TEST_DURATION_SECONDS = Duration.ofHours(4).getSeconds();

    public NNInferenceStressTest(TestModels.TestModelEntry model) {
        super(model);
    }

    @Parameters(name = "{0}")
    public static List<TestModels.TestModelEntry> modelsList() {
        return TestModels.modelsList().stream()
                .map(TestModels.TestModelEntry::withDisabledEvaluation)
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        Collections::unmodifiableList));
    }

    @Test
    @LargeTest
    public void stressTestNNAPI() throws IOException {
        waitUntilCharged();
        setUseNNApi(true);
        setCompleteInputSet(false);
        // Will sample results for very long tests in order to avoid the results to saturate
        // available memory.
        final boolean shouldSampleResults = RUNTIME_SECONDS >= LONG_STRESS_TEST_DURATION_SECONDS;
        TestAction ta = new TestAction(mModel, WARMUP_SECONDS, RUNTIME_SECONDS,
                shouldSampleResults);
        runTest(ta, mModel.getTestName());
    }
}
