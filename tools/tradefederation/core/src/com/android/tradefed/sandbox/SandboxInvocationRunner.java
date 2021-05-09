/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.sandbox;

import com.android.tradefed.config.Configuration;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.PrettyPrintDelimiter;

/** Run the tests associated with the invocation in the sandbox. */
public class SandboxInvocationRunner {

    /**
     * Do setup and run the tests.
     *
     * @return True if the invocation is successful. False otherwise.
     */
    public static boolean prepareAndRun(
            TestInformation info, IConfiguration config, ITestInvocationListener listener)
            throws Throwable {
        // TODO: refactor TestInvocation to be more modular in the sandbox handling
        ISandbox sandbox =
                (ISandbox) config.getConfigurationObject(Configuration.SANDBOX_TYPE_NAME);
        if (sandbox == null) {
            throw new RuntimeException("Couldn't find the sandbox object.");
        }
        PrettyPrintDelimiter.printStageDelimiter("Starting Sandbox Environment Setup");
        Exception res = sandbox.prepareEnvironment(info.getContext(), config, listener);
        if (res != null) {
            CLog.w("Sandbox prepareEnvironment threw an Exception.");
            sandbox.tearDown();
            throw res;
        }
        PrettyPrintDelimiter.printStageDelimiter("Done with Sandbox Environment Setup");
        try {
            CommandResult result = sandbox.run(config, listener);
            return CommandStatus.SUCCESS.equals(result.getStatus());
        } finally {
            sandbox.tearDown();
        }
    }
}
