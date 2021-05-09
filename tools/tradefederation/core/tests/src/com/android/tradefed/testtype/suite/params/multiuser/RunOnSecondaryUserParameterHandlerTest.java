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

package com.android.tradefed.testtype.suite.params.multiuser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.targetprep.RunOnSecondaryUserTargetPreparer;
import com.android.tradefed.testtype.suite.params.TestFilterable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Set;

@RunWith(JUnit4.class)
public class RunOnSecondaryUserParameterHandlerTest {

    private static final String REQUIRE_RUN_ON_SECONDARY_USER_NAME =
            "com.android.bedstead.harrier.annotations.RequireRunOnSecondaryUser";
    private static final String EXISTING_ANNOTATION_FILTER = "existing.annotation.filter";

    private RunOnSecondaryUserParameterHandler mHandler;
    private IConfiguration mModuleConfig;

    @Before
    public void setUp() {
        mHandler = new RunOnSecondaryUserParameterHandler();
        mModuleConfig = new Configuration("test", "test");
    }

    @Test
    public void applySetup_replacesIncludeAnnotationsWithRequireRunOnSecondaryUser() {
        TestFilterable test = new TestFilterable();
        test.addIncludeAnnotation(EXISTING_ANNOTATION_FILTER);
        mModuleConfig.setTest(test);

        mHandler.applySetup(mModuleConfig);

        assertEquals(1, test.getIncludeAnnotations().size());
        assertEquals(
                REQUIRE_RUN_ON_SECONDARY_USER_NAME, test.getIncludeAnnotations().iterator().next());
    }

    @Test
    public void applySetup_removesRequireRunOnSecondaryUserFromExcludeFilters() {
        TestFilterable test = new TestFilterable();
        test.addAllExcludeAnnotation(
                Set.of(EXISTING_ANNOTATION_FILTER, REQUIRE_RUN_ON_SECONDARY_USER_NAME));
        mModuleConfig.setTest(test);

        mHandler.applySetup(mModuleConfig);

        assertEquals(1, test.getExcludeAnnotations().size());
        assertEquals(EXISTING_ANNOTATION_FILTER, test.getExcludeAnnotations().iterator().next());
    }

    @Test
    public void addParameterSpecificConfig_addsRunOnSecondaryUserTargetPreparer() {
        mHandler.addParameterSpecificConfig(mModuleConfig);

        assertTrue(
                mModuleConfig.getTargetPreparers().get(0)
                        instanceof RunOnSecondaryUserTargetPreparer);
    }
}
