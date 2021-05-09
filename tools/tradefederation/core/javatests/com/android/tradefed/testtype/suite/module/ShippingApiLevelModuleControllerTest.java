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

import static org.junit.Assert.assertEquals;

import com.android.ddmlib.IDevice;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.testtype.suite.module.IModuleController.RunStrategy;
import com.android.tradefed.testtype.suite.ModuleDefinition;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ShippingApiLevelModuleController}. */
@RunWith(JUnit4.class)
public class ShippingApiLevelModuleControllerTest {
    private ShippingApiLevelModuleController mController;
    private IInvocationContext mContext;
    private ITestDevice mMockDevice;
    private IDevice mMockIDevice;

    private static final String SYSTEM_SHIPPING_API_LEVEL_PROP = "ro.product.first_api_level";
    private static final String VENDOR_SHIPPING_API_LEVEL_PROP = "ro.board.first_api_level";
    private static final String VENDOR_API_LEVEL_PROP = "ro.board.api_level";
    private static final long API_LEVEL_CURRENT = 10000;

    @Before
    public void setUp() {
        mController = new ShippingApiLevelModuleController();
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        mContext = new InvocationContext();
        mContext.addAllocatedDevice(ConfigurationDef.DEFAULT_DEVICE_NAME, mMockDevice);
        mContext.addInvocationAttribute(ModuleDefinition.MODULE_ABI, "arm64-v8a");
        mContext.addInvocationAttribute(ModuleDefinition.MODULE_NAME, "module1");
        mMockIDevice = EasyMock.createMock(IDevice.class);
    }

    /**
     * min-api-level > ro.product.first_api_level. No need to check vendor api levels. The test will
     * be skipped.
     */
    @Test
    public void testMinApiLevelHigherThanProductFirstApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        EasyMock.expect(mMockDevice.getIDevice()).andReturn(mMockIDevice);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(28L);
        EasyMock.replay(mMockDevice);
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "29");
        assertEquals(RunStrategy.FULL_MODULE_BYPASS, mController.shouldRunModule(mContext));
        EasyMock.verify(mMockDevice);
    }

    /**
     * min-api-level == ro.product.first_api_level. min-api-level > ro.board.api_level. The test
     * will be skipped.
     */
    @Test
    public void testMinApiLevelHigherThanBoardFirstApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        EasyMock.expect(mMockDevice.getIDevice()).andReturn(mMockIDevice);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(31L);
        EasyMock.expect(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(30L);
        EasyMock.expect(mMockDevice.getProperty(VENDOR_API_LEVEL_PROP)).andReturn("30");
        EasyMock.replay(mMockDevice);
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "31");
        assertEquals(RunStrategy.FULL_MODULE_BYPASS, mController.shouldRunModule(mContext));
        EasyMock.verify(mMockDevice);
    }

    /**
     * min-api-level < ro.product.first_api_level. Vendor api levels are not defined. The test will
     * run.
     */
    @Test
    public void testBoardApiLevelsNotFound()
            throws DeviceNotAvailableException, ConfigurationException {
        EasyMock.expect(mMockDevice.getIDevice()).andReturn(mMockIDevice);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(28L);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(API_LEVEL_CURRENT);
        EasyMock.expect(mMockDevice.getProperty(VENDOR_API_LEVEL_PROP)).andReturn(null);
        EasyMock.replay(mMockDevice);
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "27");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        EasyMock.verify(mMockDevice);
    }

    /**
     * min-api-level < ro.product.first_api_level. min-api-level > ro.board.first_api_level.
     * ro.board.api_level is not defined. The test will be skipped.
     */
    @Test
    public void testMinApiLevelHigherThanBoardFirstApiLevel2()
            throws DeviceNotAvailableException, ConfigurationException {
        EasyMock.expect(mMockDevice.getIDevice()).andReturn(mMockIDevice);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(32L);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(30L);
        EasyMock.expect(mMockDevice.getProperty(VENDOR_API_LEVEL_PROP)).andReturn(null);
        EasyMock.replay(mMockDevice);
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "31");
        assertEquals(RunStrategy.FULL_MODULE_BYPASS, mController.shouldRunModule(mContext));
        EasyMock.verify(mMockDevice);
    }

    /**
     * min-api-level == ro.product.first_api_level. min-api-level == ro.board.api_level. The test
     * will run.
     */
    @Test
    public void testMinApiLevelEqualToBoardApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        EasyMock.expect(mMockDevice.getIDevice()).andReturn(mMockIDevice);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(31L);
        EasyMock.expect(mMockDevice.getIntProperty(VENDOR_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(31L);
        EasyMock.expect(mMockDevice.getProperty(VENDOR_API_LEVEL_PROP)).andReturn("31");
        EasyMock.replay(mMockDevice);
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "31");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        EasyMock.verify(mMockDevice);
    }

    /**
     * min-api-level == ro.product.first_api_level. min-api-level == ro.board.first_api_level.
     * ro.board.api_level is not defined. The test will run.
     */
    @Test
    public void testMinApiLevelEqualToBoardFirstApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        EasyMock.expect(mMockDevice.getIDevice()).andReturn(mMockIDevice);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(31L);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(31L);
        EasyMock.expect(mMockDevice.getProperty(VENDOR_API_LEVEL_PROP)).andReturn(null);
        EasyMock.replay(mMockDevice);
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "31");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        EasyMock.verify(mMockDevice);
    }

    /**
     * min-api-level == ro.product.first_api_level. min-api-level < ro.board.first_api_level.
     * ro.board.api_level is not defined. The test will run.
     */
    @Test
    public void testMinApiLevelLessThanBoardFirstApiLevel()
            throws DeviceNotAvailableException, ConfigurationException {
        EasyMock.expect(mMockDevice.getIDevice()).andReturn(mMockIDevice);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(31L);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(32L);
        EasyMock.expect(mMockDevice.getProperty(VENDOR_API_LEVEL_PROP)).andReturn(null);
        EasyMock.replay(mMockDevice);
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "31");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        EasyMock.verify(mMockDevice);
    }

    /** No properties are defined. The test will run. */
    @Test
    public void testDeviceApiLevelNotFound()
            throws DeviceNotAvailableException, ConfigurationException {
        EasyMock.expect(mMockDevice.getIDevice()).andReturn(mMockIDevice);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                SYSTEM_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(API_LEVEL_CURRENT);
        EasyMock.expect(
                        mMockDevice.getIntProperty(
                                VENDOR_SHIPPING_API_LEVEL_PROP, API_LEVEL_CURRENT))
                .andReturn(API_LEVEL_CURRENT);
        EasyMock.expect(mMockDevice.getProperty(VENDOR_API_LEVEL_PROP)).andReturn(null);
        EasyMock.replay(mMockDevice);
        OptionSetter setter = new OptionSetter(mController);
        setter.setOptionValue("min-api-level", "27");
        assertEquals(RunStrategy.RUN, mController.shouldRunModule(mContext));
        EasyMock.verify(mMockDevice);
    }
}
