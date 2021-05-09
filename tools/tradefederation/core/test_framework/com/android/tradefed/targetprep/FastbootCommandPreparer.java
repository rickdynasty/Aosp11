/*
 * Copyright (C) 2018 Google Inc.
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

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;

import java.util.ArrayList;
import java.util.List;

/**
 * Target preparer that triggers fastboot and sends fastboot commands.
 *
 * <p>TODO(b/122592575): Add tests for this preparer.
 */
@OptionClass(alias = "fastboot-command-preparer")
public final class FastbootCommandPreparer extends BaseTargetPreparer {

    private enum FastbootMode {
        BOOTLOADER,
        FASTBOOTD,
    }

    @Option(
            name = "fastboot-mode",
            description = "True to boot the device into bootloader mode, false for fastbootd mode.")
    private FastbootMode mFastbootMode = FastbootMode.BOOTLOADER;

    @Option(
            name = "stay-fastboot",
            description =
                    "True to keep the device in bootloader or fastbootd mode after the commands"
                            + " executed.")
    private boolean mStayFastboot = false;

    @Option(
            name = "command",
            description =
                    "Fastboot commands to run in setup. Device will be rebooted after the commands"
                            + " executed.")
    private List<String> mFastbootCommands = new ArrayList<String>();

    @Option(
            name = "teardown-command",
            description =
                    "Fastboot commands to run in teardown. Device will be rebooted after the"
                            + " commands executed.")
    private List<String> mFastbootTearDownCommands = new ArrayList<String>();

    @Override
    public void setUp(TestInformation testInformation)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        if (!mFastbootCommands.isEmpty()) {
            executeFastbootCommands(mFastbootCommands, testInformation.getDevice());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(TestInformation testInformation, Throwable e)
            throws DeviceNotAvailableException {
        if (!mFastbootTearDownCommands.isEmpty()) {
            executeFastbootCommands(mFastbootTearDownCommands, testInformation.getDevice());
        }
    }

    private void executeFastbootCommands(List<String> commands, ITestDevice device)
            throws DeviceNotAvailableException {
        if (mFastbootMode == FastbootMode.BOOTLOADER) {
            device.rebootIntoBootloader();
        } else {
            device.rebootIntoFastbootd();
        }

        for (String cmd : commands) {
            device.executeFastbootCommand(cmd.split("\\s+"));
        }

        if (!mStayFastboot) {
            device.reboot();
        }
    }
}
