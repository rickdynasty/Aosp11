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
package com.android.tradefed.testtype.rust;

import com.android.ddmlib.IShellOutputReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestFilterReceiver;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Base class of RustBinaryHostTest and RustBinaryTest */
@OptionClass(alias = "rust-test")
public abstract class RustTestBase implements IRemoteTest, ITestFilterReceiver {

    @Option(
            name = "test-options",
            description = "Option string to be passed to the binary when running")
    protected List<String> mTestOptions = new ArrayList<>();

    @Option(
            name = "test-timeout",
            description = "Timeout for a single test file to terminate.",
            isTimeVal = true)
    protected long mTestTimeout = 60 * 1000L; // milliseconds

    @Option(
            name = "is-benchmark",
            description =
                    "Set to true if module is a benchmark. Module is treated as test by default.")
    protected boolean mIsBenchmark = false;

    @Option(name = "include-filter", description = "A substr filter of test case names to run.")
    private Set<String> mIncludeFilters = new LinkedHashSet<>();

    @Option(name = "exclude-filter", description = "A substr filter of test case names to skip.")
    private Set<String> mExcludeFilters = new LinkedHashSet<>();

    // A wrapper that can be redefined in unit tests to create a (mocked) result parser.
    @VisibleForTesting
    IShellOutputReceiver createParser(
            ITestInvocationListener listener, String runName, boolean isBenchmark) {
        if (!isBenchmark) {
            return new RustTestResultParser(listener, runName);
        } else {
            return new RustBenchmarkResultParser(listener, runName);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void addIncludeFilter(String filter) {
        mIncludeFilters.add(filter);
    }

    /** {@inheritDoc} */
    @Override
    public void addExcludeFilter(String filter) {
        mExcludeFilters.add(filter);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllIncludeFilters(Set<String> filters) {
        mIncludeFilters.addAll(filters);
    }

    /** {@inheritDoc} */
    @Override
    public void addAllExcludeFilters(Set<String> filters) {
        mExcludeFilters.addAll(filters);
    }

    /** {@inheritDoc} */
    @Override
    public void clearIncludeFilters() {
        mIncludeFilters.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void clearExcludeFilters() {
        mExcludeFilters.clear();
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getIncludeFilters() {
        return mIncludeFilters;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getExcludeFilters() {
        return mExcludeFilters;
    }

    /** Find test case names in testList and add them into foundTests. */
    protected static void collectTestLines(
            String[] testList, Set<String> foundTests, boolean isBenchmark) {
        // Rust test --list returns "testName: test" for each test.
        // In case of criterion benchmarks it's "benchName: bench".
        final String tag = isBenchmark ? ": bench" : ": test";
        int counter = 0;
        for (String line : testList) {
            if (line.endsWith(tag)) {
                counter++;
                foundTests.add(line);
            }
        }
        CLog.d("Found %d tests", counter);
    }

    /** Convert TestBinaryName#TestCaseName to TestCaseName for Rust Test. */
    protected String cleanFilter(String filter) {
        return filter.replaceFirst(".*#", "");
    }

    protected List<String> getListOfIncludeFilters() {
        if (mIncludeFilters.isEmpty()) {
            // Run test only once without any include filter.
            return new ArrayList<String>(Arrays.asList(""));
        }
        return new ArrayList<String>(mIncludeFilters);
    }

    protected void addFiltersToArgs(List<String> args, String filter) {
        if (!"".equals(filter)) {
            args.add(cleanFilter(filter));
        }
        for (String s : mExcludeFilters) {
            args.add("--skip");
            args.add(cleanFilter(s));
        }
    }

    protected String addFiltersToCommand(String cmd, String filter) {
        List<String> args = new ArrayList<>();
        addFiltersToArgs(args, filter);
        if (args.isEmpty()) {
            return cmd;
        }
        return cmd + " " + String.join(" ", args);
    }
}
