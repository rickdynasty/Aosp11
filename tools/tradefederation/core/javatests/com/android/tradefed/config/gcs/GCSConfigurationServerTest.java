/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tradefed.config.gcs;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.util.StreamUtil;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** Unit test for {@link com.android.tradefed.config.gcs.GCSConfigurationServer}; */
@RunWith(JUnit4.class)
public class GCSConfigurationServerTest {

    private static final String CONFIG =
            "<configuration>"
                    + "<device_manager class=\"com.android.tradefed.device.DeviceManager\" />\n"
                    + "</configuration>";
    private static final String HOST_CONFIG_MAPPING =
            "\n"
                    + "\n"
                    + "# comment\n"
                    + "\n"
                    + "[cluster]\n"
                    + "\n"
                    + "hostname,,host-config.xml,\n"
                    + "[presubmit]\n"
                    + "\n"
                    + "presubmit,,presubmit-host-config.xml,\n"
                    + "presubmit_vts,,presubmit-vts-host-config.xml,\n";

    private GCSConfigurationServer mConfigServer;
    private String mHostname;

    @Before
    public void setUp() {
        mHostname = "hostname.mtv.com";
        mConfigServer =
                new GCSConfigurationServer() {
                    @Override
                    InputStream downloadFileToInputStream(String name)
                            throws ConfigurationException {
                        String content = null;
                        if (name.equals("host-config.xml")) {
                            content = CONFIG;
                        } else if (name.equals("presubmit-host-config.xml")) {
                            content = CONFIG;
                        } else if (name.equals("host-config.txt")) {
                            content = HOST_CONFIG_MAPPING;
                        }
                        if (content != null) {
                            return new ByteArrayInputStream(content.getBytes());
                        }
                        throw new ConfigurationException(
                                String.format("Config %s doesn't exist.", name));
                    }

                    @Override
                    String currentHostname() throws ConfigurationException {
                        return mHostname;
                    }
                };
    }

    @Test
    public void testGetConfig() throws Exception {
        InputStream input = mConfigServer.getConfig("host-config.xml");
        Assert.assertEquals(CONFIG, StreamUtil.getStringFromStream(input));
    }

    @Test
    public void testGetConfig_notExist() throws Exception {
        try {
            mConfigServer.getConfig("not-exist-host-config.xml");
            Assert.fail("Should throw ConfigurationException.");
        } catch (ConfigurationException e) {
            // Expect to throw ConfigurationException.
            Assert.assertEquals("Config not-exist-host-config.xml doesn't exist.", e.getMessage());
        }
    }

    @Test
    public void testGetCurrentHostConfig() throws Exception {
        String configName = mConfigServer.getCurrentHostConfig();
        Assert.assertEquals("host-config.xml", configName);
    }

    @Test
    public void testGetCurrentHostConfig_cluster() throws Exception {
        OptionSetter setter = new OptionSetter(mConfigServer);
        setter.setOptionValue("cluster", "presubmit_vts");
        String configName = mConfigServer.getCurrentHostConfig();
        Assert.assertEquals("presubmit-vts-host-config.xml", configName);
    }

    @Test
    public void testGetCurrentHostConfig_configPath() throws Exception {
        OptionSetter setter = new OptionSetter(mConfigServer);
        setter.setOptionValue("config-path", "/config/path.xml");
        String configName = mConfigServer.getCurrentHostConfig();
        Assert.assertEquals("/config/path.xml", configName);
    }

    @Test
    public void testGetCurrentHostConfig_noHostConfig() throws Exception {
        mHostname = "invalid_hostname";
        try {
            mConfigServer.getCurrentHostConfig();
            Assert.fail("Should throw ConfigurationException.");
        } catch (ConfigurationException e) {
            // Expect to throw ConfigurationException.
            Assert.assertEquals("There is no config for invalid_hostname.", e.getMessage());
        }
    }

    @Test
    public void testSameHostname() {
        Assert.assertTrue(
                mConfigServer.sameHost("hostname.mtv.google.com", "hostname.mtv.google.com"));
    }

    @Test
    public void testSameHostname_shortHostname() {
        Assert.assertTrue(mConfigServer.sameHost("hostname.mtv.google.com", "hostname"));
    }

    @Test
    public void testSameHostname_differentShortHostname() {
        Assert.assertFalse(mConfigServer.sameHost("hostname.mtv.google.com", "host"));
    }
}
