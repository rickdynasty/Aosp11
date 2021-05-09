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

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IDeviceConfiguration;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.RunOnWorkProfileTargetPreparer;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.ITestAnnotationFilterReceiver;
import com.android.tradefed.testtype.suite.params.IModuleParameter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RunOnWorkProfileParameterHandler implements IModuleParameter {

    private static final String REQUIRE_RUN_ON_WORK_PROFILE_NAME =
            "com.android.bedstead.harrier.annotations.RequireRunOnWorkProfile";

    @Override
    public String getParameterIdentifier() {
        return "run-on-work-profile";
    }

    /** {@inheritDoc} */
    @Override
    public void addParameterSpecificConfig(IConfiguration moduleConfiguration) {
        for (IDeviceConfiguration deviceConfig : moduleConfiguration.getDeviceConfig()) {
            List<ITargetPreparer> preparers = deviceConfig.getTargetPreparers();
            // The first thing the module will do is run on a work profile
            preparers.add(0, new RunOnWorkProfileTargetPreparer());
        }
    }

    @Override
    public void applySetup(IConfiguration moduleConfiguration) {
        // Add filter to include @RequireRunOnWorkProfile
        for (IRemoteTest test : moduleConfiguration.getTests()) {
            if (test instanceof ITestAnnotationFilterReceiver) {
                ITestAnnotationFilterReceiver filterTest = (ITestAnnotationFilterReceiver) test;
                filterTest.clearIncludeAnnotations();
                filterTest.addIncludeAnnotation(REQUIRE_RUN_ON_WORK_PROFILE_NAME);

                Set<String> excludeAnnotations = new HashSet<>(filterTest.getExcludeAnnotations());
                excludeAnnotations.remove(REQUIRE_RUN_ON_WORK_PROFILE_NAME);
                filterTest.clearExcludeAnnotations();
                filterTest.addAllExcludeAnnotation(excludeAnnotations);
            }
        }
    }
}
