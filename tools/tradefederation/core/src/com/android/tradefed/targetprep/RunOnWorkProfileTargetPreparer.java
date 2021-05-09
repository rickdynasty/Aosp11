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

package com.android.tradefed.targetprep;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.UserInfo;
import com.android.tradefed.invoker.TestInformation;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An {@link ITargetPreparer} that creates a work profile in setup, and marks that tests should be
 * run in that user.
 *
 * <p>In teardown, the work profile is removed.
 *
 * <p>If a work profile already exists, it will be used rather than creating a new one, and it will
 * not be removed in teardown.
 *
 * <p>If the device does not have the managed_users feature, or does not have capacity to create a
 * new user when one is required, then the instrumentation argument skip-tests-reason will be set,
 * and the user will not be changed. Tests running on the device can read this argument to respond
 * to this state.
 */
@OptionClass(alias = "run-on-work-profile")
public class RunOnWorkProfileTargetPreparer extends BaseTargetPreparer
        implements IConfigurationReceiver {

    @VisibleForTesting static final String RUN_TESTS_AS_USER_KEY = "RUN_TESTS_AS_USER";

    @VisibleForTesting static final String TEST_PACKAGE_NAME_OPTION = "test-package-name";

    @VisibleForTesting static final String SKIP_TESTS_REASON_KEY = "skip-tests-reason";

    private IConfiguration mConfiguration;

    @Option(
            name = TEST_PACKAGE_NAME_OPTION,
            description =
                    "the name of a package to be installed on the work profile. "
                            + "This must already be installed on the device.",
            importance = Option.Importance.IF_UNSET)
    private List<String> mTestPackages = new ArrayList<>();

    @Override
    public void setConfiguration(IConfiguration configuration) {
        if (configuration == null) {
            throw new NullPointerException("configuration must not be null");
        }
        mConfiguration = configuration;
    }

    @Override
    public void setUp(TestInformation testInfo)
            throws TargetSetupError, DeviceNotAvailableException {
        if (!requireFeatures(testInfo.getDevice(), "android.software.managed_users")) {
            return;
        }

        int workProfileId = getWorkProfileId(testInfo.getDevice());

        if (workProfileId != -1) {
            // There is already a work profile - so we don't want to remove it
            setDisableTearDown(true);
        } else {
            if (!assumeTrue(
                    canCreateAdditionalUsers(testInfo.getDevice(), 1),
                    "Device cannot support additional users",
                    testInfo.getDevice())) {
                return;
            }

            workProfileId = createWorkProfile(testInfo.getDevice());
        }

        for (String pkg : mTestPackages) {
            testInfo.getDevice()
                    .executeShellCommand("pm install-existing --user " + workProfileId + " " + pkg);
        }

        testInfo.properties().put(RUN_TESTS_AS_USER_KEY, Integer.toString(workProfileId));
    }

    /** Get the id of a work profile currently on the device. -1 if there is none */
    private static int getWorkProfileId(ITestDevice device) throws DeviceNotAvailableException {
        for (Map.Entry<Integer, UserInfo> userInfo : device.getUserInfos().entrySet()) {
            if (userInfo.getValue().isManagedProfile()) {
                return userInfo.getKey();
            }
        }
        return -1;
    }

    /** Creates a work profile and returns the new user ID. */
    private static int createWorkProfile(ITestDevice device) throws DeviceNotAvailableException {
        int parentProfile = device.getCurrentUser();
        final String createUserOutput =
                device.executeShellCommand(
                        "pm create-user --profileOf " + parentProfile + " --managed work");
        final int profileId = Integer.parseInt(createUserOutput.split(" id ")[1].trim());
        device.executeShellCommand("am start-user -w " + profileId);
        return profileId;
    }

    @Override
    public void tearDown(TestInformation testInfo, Throwable e) throws DeviceNotAvailableException {
        int workProfileId = Integer.parseInt(testInfo.properties().get(RUN_TESTS_AS_USER_KEY));
        testInfo.properties().remove(RUN_TESTS_AS_USER_KEY);
        testInfo.getDevice().removeUser(workProfileId);
    }

    private boolean requireFeatures(ITestDevice device, String... features)
            throws TargetSetupError, DeviceNotAvailableException {
        for (String feature : features) {
            if (!assumeTrue(
                    device.hasFeature(feature),
                    "Device does not have feature " + feature,
                    device)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Disable teardown and set the {@link #SKIP_TESTS_REASON_KEY} if {@code value} isn't true.
     *
     * <p>This will return {@code value} and, if it is not true, setup should be skipped.
     */
    private boolean assumeTrue(boolean value, String reason, ITestDevice device)
            throws TargetSetupError {
        if (!value) {
            setDisableTearDown(true);
            try {
                mConfiguration.injectOptionValue(
                        "instrumentation-arg", SKIP_TESTS_REASON_KEY, reason.replace(" ", "\\ "));
            } catch (ConfigurationException e) {
                throw new TargetSetupError(
                        "Error setting skip-tests-reason", device.getDeviceDescriptor());
            }
        }

        return value;
    }

    /** Checks whether it is possible to create the desired number of users. */
    protected boolean canCreateAdditionalUsers(ITestDevice device, int numberOfUsers)
            throws DeviceNotAvailableException {
        return device.listUsers().size() + numberOfUsers <= device.getMaxNumberOfUsersSupported();
    }
}
