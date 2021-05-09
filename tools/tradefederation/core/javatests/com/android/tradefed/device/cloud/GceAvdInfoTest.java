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
package com.android.tradefed.device.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.invoker.logger.InvocationMetricLogger;
import com.android.tradefed.invoker.logger.InvocationMetricLogger.InvocationMetricKey;
import com.android.tradefed.result.error.InfraErrorIdentifier;
import com.android.tradefed.targetprep.TargetSetupError;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.Map;

/** Unit tests for {@link GceAvdInfo} */
@RunWith(JUnit4.class)
public class GceAvdInfoTest {

    @Test
    public void testValidGceJsonParsing() throws Exception {
        String valid =
                " {\n"
                        + "    \"data\": {\n"
                        + "      \"devices\": [\n"
                        + "        {\n"
                        + "          \"ip\": \"104.154.62.236\",\n"
                        + "          \"instance_name\": \"gce-x86-phone-userdebug-2299773-22cf\"\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    \"errors\": [],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"SUCCESS\"\n"
                        + "  }";
        GceAvdInfo avd = GceAvdInfo.parseGceInfoFromString(valid, null, 5555);
        assertNotNull(avd);
        assertEquals(avd.hostAndPort().getHost(), "104.154.62.236");
        assertEquals(avd.instanceName(), "gce-x86-phone-userdebug-2299773-22cf");
        assertTrue(avd.getBuildVars().isEmpty());
    }

    @Test
    public void testValidGceJsonParsingWithBuildVars() throws Exception {
        String valid =
                " {\n"
                        + "    \"data\": {\n"
                        + "      \"devices\": [\n"
                        + "        {\n"
                        + "          \"ip\": \"104.154.62.236\",\n"
                        + "          \"branch\": \"git_main\",\n"
                        + "          \"build_id\": \"5230832\",\n"
                        + "          \"build_target\": \"cf_x86_phone-userdebug\",\n"
                        + "          \"instance_name\": \"gce-x86-phone-userdebug-2299773-22cf\"\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    \"errors\": [],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"SUCCESS\"\n"
                        + "  }";
        GceAvdInfo avd = GceAvdInfo.parseGceInfoFromString(valid, null, 5555);
        assertNotNull(avd);
        assertEquals(avd.hostAndPort().getHost(), "104.154.62.236");
        assertEquals(avd.instanceName(), "gce-x86-phone-userdebug-2299773-22cf");
        assertEquals(avd.getBuildVars().get("branch"), "git_main");
        assertEquals(avd.getBuildVars().get("build_id"), "5230832");
        assertEquals(avd.getBuildVars().get("build_target"), "cf_x86_phone-userdebug");
    }

    @Test
    public void testDualAvdsJsonParsingWithBuildVars() throws Exception {
        String json1 =
                " {\n"
                        + "    \"data\": {\n"
                        + "      \"devices\": [\n"
                        + "        {\n"
                        + "          \"ip\": \"1.1.1.1\",\n"
                        + "          \"branch\": \"git_main\",\n"
                        + "          \"build_id\": \"1111111\",\n"
                        + "          \"build_target\": \"cf_x86_phone-userdebug\",\n"
                        + "          \"instance_name\": \"gce-x86-phone-userdebug-1111111-22cf\"\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    \"errors\": [],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"SUCCESS\"\n"
                        + "  }";
        String json2 =
                " {\n"
                        + "    \"data\": {\n"
                        + "      \"devices\": [\n"
                        + "        {\n"
                        + "          \"ip\": \"2.2.2.2\",\n"
                        + "          \"branch\": \"git_main-release\",\n"
                        + "          \"build_id\": \"2222222\",\n"
                        + "          \"build_target\": \"cf_x86_phone-userdebug\",\n"
                        + "          \"instance_name\": \"gce-x86-phone-userdebug-2222222-22cf\"\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    \"errors\": [],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"SUCCESS\"\n"
                        + "  }";
        GceAvdInfo avd1 = GceAvdInfo.parseGceInfoFromString(json1, null, 1111);
        GceAvdInfo avd2 = GceAvdInfo.parseGceInfoFromString(json2, null, 2222);
        assertNotNull(avd1);
        assertEquals(avd1.hostAndPort().getHost(), "1.1.1.1");
        assertEquals(avd1.instanceName(), "gce-x86-phone-userdebug-1111111-22cf");
        assertEquals(avd1.getBuildVars().get("branch"), "git_main");
        assertEquals(avd1.getBuildVars().get("build_id"), "1111111");
        assertEquals(avd1.getBuildVars().get("build_target"), "cf_x86_phone-userdebug");
        assertNotNull(avd2);
        assertEquals(avd2.hostAndPort().getHost(), "2.2.2.2");
        assertEquals(avd2.instanceName(), "gce-x86-phone-userdebug-2222222-22cf");
        assertEquals(avd2.getBuildVars().get("branch"), "git_main-release");
        assertEquals(avd2.getBuildVars().get("build_id"), "2222222");
        assertEquals(avd2.getBuildVars().get("build_target"), "cf_x86_phone-userdebug");
    }

    @Test
    public void testNullStringJsonParsing() throws Exception {
        GceAvdInfo avd = GceAvdInfo.parseGceInfoFromString(null, null, 5555);
        assertNull(avd);
    }

    @Test
    public void testEmptyStringJsonParsing() throws Exception {
        assertNull(GceAvdInfo.parseGceInfoFromString(new String(), null, 5555));
    }

    @Test
    public void testMultipleGceJsonParsing() throws Exception {
        String multipleInstances =
                " {\n"
                        + "    \"data\": {\n"
                        + "      \"devices\": [\n"
                        + "        {\n"
                        + "          \"ip\": \"104.154.62.236\",\n"
                        + "          \"instance_name\": \"gce-x86-phone-userdebug-2299773-22ecc\"\n"
                        + "        },\n"
                        + "       {\n"
                        + "          \"ip\": \"104.154.62.236\",\n"
                        + "          \"instance_name\": \"gce-x86-phone-userdebug-2299773-22ecc\"\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    \"errors\": [],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"SUCCESS\"\n"
                        + "  }";
        try {
            GceAvdInfo.parseGceInfoFromString(multipleInstances, null, 5555);
            fail("A TargetSetupError should have been thrown.");
        } catch (TargetSetupError e) {
            // expected
        }
    }

    @Test
    public void testInvalidJsonParsing() throws Exception {
        String invalidJson = "bad_json";
        try {
            GceAvdInfo.parseGceInfoFromString(invalidJson, null, 5555);
            fail("A TargetSetupError should have been thrown.");
        } catch (TargetSetupError e) {
            // expected
        }
    }

    @Test
    public void testMissingGceJsonParsing() throws Exception {
        String missingInstance =
                " {\n"
                        + "    \"data\": {\n"
                        + "      \"devices\": [\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    \"errors\": [],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"SUCCESS\"\n"
                        + "  }";
        try {
            GceAvdInfo.parseGceInfoFromString(missingInstance, null, 5555);
            fail("A TargetSetupError should have been thrown.");
        } catch (TargetSetupError e) {
            // expected
        }
    }

    /**
     * In case of failure to boot in expected time, we need to parse the error to get the instance
     * name and stop it.
     *
     * @throws Exception
     */
    @Test
    public void testValidGceJsonParsingFail() throws Exception {
        String validFail =
                " {\n"
                        + "    \"data\": {\n"
                        + "      \"devices_failing_boot\": [\n"
                        + "        {\n"
                        + "          \"ip\": \"104.154.62.236\",\n"
                        + "          \"instance_name\": \"gce-x86-phone-userdebug-2299773-22ecc\"\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    \"errors\": [],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"FAIL\"\n"
                        + "  }";
        GceAvdInfo avd = GceAvdInfo.parseGceInfoFromString(validFail, null, 5555);
        assertNotNull(avd);
        assertEquals(avd.hostAndPort().getHost(), "104.154.62.236");
        assertEquals(avd.instanceName(), "gce-x86-phone-userdebug-2299773-22ecc");
    }

    /**
     * On a quota error No GceAvd information is created because the instance was not created.
     *
     * @throws Exception
     */
    @Test
    public void testValidGceJsonParsingFailQuota() throws Exception {
        String validError =
                " {\n"
                        + "    \"data\": {},\n"
                        + "    \"errors\": [\n"
                        + "\"Get operation state failed, errors: [{u'message': u\\\"Quota 'CPUS' "
                        + "exceeded.  Limit: 500.0\\\", u'code': u'QUOTA_EXCEEDED'}]\"\n"
                        + "],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"FAIL\"\n"
                        + "  }";
        try {
            GceAvdInfo.parseGceInfoFromString(validError, null, 5555);
            fail("A TargetSetupError should have been thrown.");
        } catch (TargetSetupError e) {
            // expected
        }
    }

    /**
     * In case of failure to boot in expected time, we need to parse the error to get the instance
     * name and stop it.
     *
     * @throws Exception
     */
    @Test
    public void testParseJson_Boot_Fail() throws Exception {
        String validFail =
                " {\n"
                        + "    \"data\": {\n"
                        + "      \"devices_failing_boot\": [\n"
                        + "        {\n"
                        + "          \"ip\": \"104.154.62.236\",\n"
                        + "          \"instance_name\": \"gce-x86-phone-userdebug-2299773-22ec\"\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    \"errors\": [\"device did not boot\"],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"BOOT_FAIL\"\n"
                        + "  }";
        GceAvdInfo avd = GceAvdInfo.parseGceInfoFromString(validFail, null, 5555);
        assertNotNull(avd);
        assertEquals(avd.hostAndPort().getHost(), "104.154.62.236");
        assertEquals(avd.instanceName(), "gce-x86-phone-userdebug-2299773-22ec");
        assertEquals(GceAvdInfo.GceStatus.BOOT_FAIL, avd.getStatus());
    }

    /**
     * In case of failure to start the instance if no 'devices_failing_boot' is available avoid
     * parsing the instance.
     */
    @Test
    public void testParseJson_fail_error() throws Exception {
        String validFail =
                " {\n"
                        + "    \"data\": {},\n"
                        + "    \"errors\": [\"HttpError 403 when requesting\"],\n"
                        + "    \"command\": \"create\",\n"
                        + "    \"status\": \"FAIL\"\n"
                        + "  }";
        try {
            GceAvdInfo.parseGceInfoFromString(validFail, null, 5555);
            fail("A TargetSetupError should have been thrown.");
        } catch (TargetSetupError e) {
            // expected
        }
    }

    /** Test CF start time metrics are added. */
    @Test
    public void testCfStartTimeMetricsAdded() throws Exception {
        String cuttlefish =
                " {\n"
                        + "    \"command\": \"create_cf\",\n"
                        + "    \"data\": {\n"
                        + "      \"devices\": [\n"
                        + "        {\n"
                        + "          \"ip\": \"34.71.83.182\",\n"
                        + "          \"instance_name\": \"ins-cf-x86-phone-userdebug\",\n"
                        + "          \"fetch_artifact_time\": 63.22,\n"
                        + "          \"gce_create_time\": 23.5,\n"
                        + "          \"launch_cvd_time\": 226.5\n"
                        + "        },\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    \"errors\": [],\n"
                        + "    \"status\": \"SUCCESS\"\n"
                        + "  }";
        JSONObject res = new JSONObject(cuttlefish);
        JSONArray devices = res.getJSONObject("data").getJSONArray("devices");
        GceAvdInfo.addCfStartTimeMetrics((JSONObject) devices.get(0));
        Map<String, String> metrics = InvocationMetricLogger.getInvocationMetrics();
        assertEquals("63220", metrics.get(InvocationMetricKey.CF_FETCH_ARTIFACT_TIME.toString()));
        assertEquals("23500", metrics.get(InvocationMetricKey.CF_GCE_CREATE_TIME.toString()));
        assertEquals("226500", metrics.get(InvocationMetricKey.CF_LAUNCH_CVD_TIME.toString()));
    }

    /** Test parsing valid json with error_type field defined. */
    @Test
    public void testValidGceJsonParsing_acloud_error_type() throws Exception {
        String acloudError =
                " {\n"
                    + "    \"data\": {\n"
                    + "      \"devices_failing_boot\": [\n"
                    + "        {\n"
                    + "          \"ip\": \"10.2.0.205\",\n"
                    + "          \"branch\": \"git_main\",\n"
                    + "          \"build_id\": \"P17712100\",\n"
                    + "          \"build_target\": \"cf_x86_phone-userdebug\",\n"
                    + "          \"instance_name\":"
                    + " \"ins-ae428ce9-p17712100-cf-x86-phone-userdebug\",\n"
                    + "          \"fetch_artifact_time\": \"89.86\",\n"
                    + "          \"gce_create_time\": \"22.31\",\n"
                    + "          \"launch_cvd_time\": \"540.01\"\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"launch_cvd_command\": \"./bin/launch_cvd -daemon -x_res=720"
                    + " -y_res=1280 -dpi=320 -memory_mb=4096 -cpus 4"
                    + " -undefok=report_anonymous_usage_stats -report_anonymous_usage_stats=y\",\n"
                    + "      \"version\": \"2020-10-19_6914176\",\n"
                    + "      \"zone\": \"us-west1-b\"\n"
                    + "    },\n"
                    + "    \"error_type\": \"ACLOUD_INIT_ERROR\",\n"
                    + "    \"errors\": [\n"
                    + "\"Device ins-ae428ce9-p17712100-cf-x86-phone-userdebug did not finish on"
                    + " boot within timeout (540 secs)\"\n"
                    + "],\n"
                    + "    \"command\": \"create_cf\",\n"
                    + "    \"status\": \"BOOT_FAIL\"\n"
                    + "  }";

        GceAvdInfo avd = GceAvdInfo.parseGceInfoFromString(acloudError, null, 5555);
        assertNotNull(avd);
        assertEquals(avd.hostAndPort().getHost(), "10.2.0.205");
        assertEquals(avd.instanceName(), "ins-ae428ce9-p17712100-cf-x86-phone-userdebug");
        assertEquals(avd.getBuildVars().get("branch"), "git_main");
        assertEquals(avd.getBuildVars().get("build_id"), "P17712100");
        assertEquals(avd.getBuildVars().get("build_target"), "cf_x86_phone-userdebug");
        assertEquals(avd.getErrorType(), InfraErrorIdentifier.ACLOUD_INIT_ERROR);
    }

    /** Test parsing invalid json with error_type field defined and devices arbitrarily removed. */
    @Test
    public void testInvalidGceJsonParsing_acloud_errors_and_missing_devices() throws Exception {
        String acloudErrorAndMissingDevices =
                " {\n"
                        + "    \"data\": {\n"
                        /*
                        + "      \"devices_failing_boot\": [\n"
                        + "        {\n"
                        + "          \"ip\": \"10.2.0.205\",\n"
                        + "          \"branch\": \"git_main\",\n"
                        + "          \"build_id\": \"P17712100\",\n"
                        + "          \"build_target\": \"cf_x86_phone-userdebug\",\n"
                        + "          \"instance_name\": \"ins-ae428ce9-p17712100-cf-x86-phone-userdebug\",\n"
                        + "          \"fetch_artifact_time\": \"89.86\",\n"
                        + "          \"gce_create_time\": \"22.31\",\n"
                        + "          \"launch_cvd_time\": \"540.01\"\n"
                        + "        }\n"
                        + "      ],\n"
                        */
                        + "      \"launch_cvd_command\": \"./bin/launch_cvd -daemon -x_res=720"
                        + " -y_res=1280 -dpi=320 -memory_mb=4096 -cpus 4"
                        + " -undefok=report_anonymous_usage_stats"
                        + " -report_anonymous_usage_stats=y\",\n"
                        + "      \"version\": \"2020-10-19_6914176\",\n"
                        + "      \"zone\": \"us-west1-b\"\n"
                        + "    },\n"
                        + "    \"error_type\": \"ACLOUD_INIT_ERROR\",\n"
                        + "    \"errors\": [\n"
                        + "\"Device ins-ae428ce9-p17712100-cf-x86-phone-userdebug did not finish"
                        + " on boot within timeout (540 secs)\"\n"
                        + "],\n"
                        + "    \"command\": \"create_cf\",\n"
                        + "    \"status\": \"BOOT_FAIL\"\n"
                        + "  }";

        try {
            GceAvdInfo.parseGceInfoFromString(acloudErrorAndMissingDevices, null, 5555);
            fail("A TargetSetupError should have been thrown.");
        } catch (TargetSetupError e) {
            assertEquals(e.getErrorId(), InfraErrorIdentifier.ACLOUD_INIT_ERROR);
        }
    }

    @Test
    public void testDetermineAcloudErrorType() {
        assertEquals(GceAvdInfo.determineAcloudErrorType(null), null);
        assertEquals(GceAvdInfo.determineAcloudErrorType(""), null);
        assertEquals(
                GceAvdInfo.determineAcloudErrorType("invalid error type"),
                InfraErrorIdentifier.ACLOUD_UNRECOGNIZED_ERROR_TYPE);
        assertEquals(
                GceAvdInfo.determineAcloudErrorType("ACLOUD_INIT_ERROR"),
                InfraErrorIdentifier.ACLOUD_INIT_ERROR);
        assertEquals(
                GceAvdInfo.determineAcloudErrorType("ACLOUD_CREATE_GCE_ERROR"),
                InfraErrorIdentifier.ACLOUD_CREATE_GCE_ERROR);
        assertEquals(
                GceAvdInfo.determineAcloudErrorType("ACLOUD_DOWNLOAD_ARTIFACT_ERROR"),
                InfraErrorIdentifier.ACLOUD_DOWNLOAD_ARTIFACT_ERROR);
        assertEquals(
                GceAvdInfo.determineAcloudErrorType("ACLOUD_BOOT_UP_ERROR"),
                InfraErrorIdentifier.ACLOUD_BOOT_UP_ERROR);
        assertEquals(
                GceAvdInfo.determineAcloudErrorType("GCE_QUOTA_ERROR"),
                InfraErrorIdentifier.GCE_QUOTA_ERROR);
    }
}
