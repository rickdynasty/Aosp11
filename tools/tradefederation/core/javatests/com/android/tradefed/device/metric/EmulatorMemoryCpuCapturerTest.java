/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tradefed.device.metric;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class EmulatorMemoryCpuCapturerTest {

    private EmulatorMemoryCpuCapturer mEmulatorMemoryCpuCapturer;

    @Before
    public void setUp() {
        // just capture the current process'es id
        mEmulatorMemoryCpuCapturer = new EmulatorMemoryCpuCapturer(ProcessHandle.current().pid());
    }

    @Test
    public void parsePssMemory() {
        String sampleContent =
                "MMUPageSize:           4 kB\n"
                        + "Pss:                   4 kB\n"
                        + "Rss:                   4 kB\n"
                        + "Pss:                   2 kB\n";

        long result = EmulatorMemoryCpuCapturer.parsePssMemory(sampleContent);
        assertThat(result).isEqualTo(6);
    }

    @Test
    public void parseCpuUsage() {
        String sampleContent = "%CPU\n25.4";

        float result = EmulatorMemoryCpuCapturer.parseCpuUsage(sampleContent);
        assertThat(result).isEqualTo(25.4f);
    }

    /** functional test for getting pss memory using the current java processes' pid. */
    @Test
    public void getPssMemory() {
        long memory = mEmulatorMemoryCpuCapturer.getPssMemory();
        // arbitrarily check bounds to make sure returned value is reasonable
        assertThat(memory).isGreaterThan(100000);
        // ensure less than 2 GB memory
        assertThat(memory).isLessThan(2 * 1000 * 1000 * 1000);
    }

    /** functional test for getting cpu usage using the current java processes' pid. */
    @Test
    public void getCpuUsage() {
        float cpu = mEmulatorMemoryCpuCapturer.getCpuUsage();
        // arbitrarily check bounds to make sure returned value is reasonable
        assertThat(cpu).isGreaterThan(1);
        // ensure less than 2 GB memory
        assertThat(cpu).isLessThan(1000);
    }
}
