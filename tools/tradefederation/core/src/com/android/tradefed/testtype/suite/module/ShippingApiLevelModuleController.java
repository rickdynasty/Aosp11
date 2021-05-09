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
package com.android.tradefed.testtype.suite.module;

import com.android.tradefed.config.Option;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.StubDevice;
import com.android.tradefed.log.LogUtil.CLog;

/**
 * Run tests if the device meets both of the following conditions:
 *
 * <ul>
 *   <li>The device shipped with the {@code min-api-level} or later.
 *   <li>The vendor image implemented the features for the {@code min-api-level} or later.
 * </ul>
 */
public class ShippingApiLevelModuleController extends BaseModuleController {

    private static final String SYSTEM_SHIPPING_API_LEVEL_PROP = "ro.product.first_api_level";
    private static final String VENDOR_SHIPPING_API_LEVEL_PROP = "ro.board.first_api_level";
    private static final String VENDOR_API_LEVEL_PROP = "ro.board.api_level";
    private static final long API_LEVEL_CURRENT = 10000;

    @Option(name = "min-api-level", description = "The minimum api-level on which tests will run.")
    private Integer mMinApiLevel = 0;

    /**
     * Compares the API level from the {@code min-api-level} and decide if the test should run or
     * not.
     *
     * @param device the {@link ITestDevice}.
     * @param prop the name of a property that has the API level to compare with the {@code
     *     min-api-level}.
     * @return {@code true} if the api level is equal to or greater than the {@code min-api-level}.
     *     Otherwise, {@code false}.
     * @throws DeviceNotAvailableException
     */
    private boolean shouldRunTestWithApiLevel(ITestDevice device, String prop)
            throws DeviceNotAvailableException {
        long apiLevel = device.getIntProperty(prop, API_LEVEL_CURRENT);
        if (apiLevel < mMinApiLevel) {
            CLog.d(
                    "Skipping module %s because API Level %d from %s is less than %d.",
                    getModuleName(), apiLevel, prop, mMinApiLevel);
            return false;
        }
        return true;
    }

    /**
     * Use VENDOR_API_LEVEL_PROP. If empty, use VENDOR_SHIPPING_API_LEVEL_PROP, instead.
     *
     * @param device the {@link ITestDevice}.
     * @return {@code String}: the property name for the vendor api level
     * @throws DeviceNotAvailableException
     */
    private String getVendorApiLevelProp(ITestDevice device) throws DeviceNotAvailableException {
        if (device.getProperty(VENDOR_API_LEVEL_PROP) != null) {
            return VENDOR_API_LEVEL_PROP;
        }
        return VENDOR_SHIPPING_API_LEVEL_PROP;
    }

    /**
     * Method to decide if the module should run or not.
     *
     * @param context the {@link IInvocationContext} of the module
     * @return {@link RunStrategy#RUN} if the module should run, {@link
     *     RunStrategy#FULL_MODULE_BYPASS} otherwise.
     * @throws RuntimeException if device is not available
     */
    @Override
    public RunStrategy shouldRun(IInvocationContext context) {
        for (ITestDevice device : context.getDevices()) {
            if (device.getIDevice() instanceof StubDevice) {
                continue;
            }
            try {
                // check system shipping api level
                if (!shouldRunTestWithApiLevel(device, SYSTEM_SHIPPING_API_LEVEL_PROP)) {
                    return RunStrategy.FULL_MODULE_BYPASS;
                }
                // check vendor api level
                if (!shouldRunTestWithApiLevel(device, getVendorApiLevelProp(device))) {
                    return RunStrategy.FULL_MODULE_BYPASS;
                }
            } catch (DeviceNotAvailableException e) {
                CLog.e("Couldn't check API Levels on %s", device.getSerialNumber());
                CLog.e(e);
                throw new RuntimeException(e);
            }
        }
        return RunStrategy.RUN;
    }
}
