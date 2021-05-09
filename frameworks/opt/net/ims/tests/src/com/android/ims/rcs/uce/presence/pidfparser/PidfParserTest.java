/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.ims.rcs.uce.presence.pidfparser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.telephony.ims.RcsContactPresenceTuple;
import android.telephony.ims.RcsContactPresenceTuple.ServiceCapabilities;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RcsContactUceCapability.PresenceBuilder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.ims.ImsTestBase;

import java.time.Instant;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class PidfParserTest extends ImsTestBase {

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testConvertToPidf() throws Exception {
        RcsContactUceCapability capability = getRcsContactUceCapability();

        String pidfResult = PidfParser.convertToPidf(capability);

        String contact = "<contact>sip:test</contact>";
        String audioSupported = "<caps:audio>true</caps:audio>";
        String videoSupported = "<caps:video>true</caps:video>";
        String description = "<op:version>1.0</op:version>";
        assertTrue(pidfResult.contains(contact));
        assertTrue(pidfResult.contains(audioSupported));
        assertTrue(pidfResult.contains(videoSupported));
        assertTrue(pidfResult.contains(description));
    }

    @Test
    @SmallTest
    public void testConvertFromPidfToRcsContactUceCapability() throws Exception {
        final String contact = "sip:+11234567890@test";
        final String serviceId = "org.3gpp.urn:urn-7:3gpp-service.ims.icsi.mmtel";
        final String serviceDescription = "MMTEL feature service";
        final boolean isAudioSupported = true;
        final boolean isVideoSupported = false;

        // Create the first PIDF data
        String pidfData = getPidfData(contact, serviceId, serviceDescription, isAudioSupported,
                isVideoSupported);

        // Convert to the class RcsContactUceCapability
        RcsContactUceCapability capabilities = PidfParser.getRcsContactUceCapability(pidfData);
        assertNotNull(capabilities);
        assertEquals(Uri.parse(contact), capabilities.getContactUri());
        assertEquals(RcsContactUceCapability.SOURCE_TYPE_NETWORK, capabilities.getSourceType());
        assertEquals(RcsContactUceCapability.CAPABILITY_MECHANISM_PRESENCE,
                capabilities.getCapabilityMechanism());

        List<RcsContactPresenceTuple> presenceTupleList = capabilities.getCapabilityTuples();
        assertNotNull(presenceTupleList);
        assertEquals(1, presenceTupleList.size());

        RcsContactPresenceTuple presenceTuple1 = presenceTupleList.get(0);
        assertEquals(serviceId, presenceTuple1.getServiceId());
        assertEquals("1.0", presenceTuple1.getServiceVersion());
        assertEquals(serviceDescription, presenceTuple1.getServiceDescription());
        assertEquals(Uri.parse(contact), presenceTuple1.getContactUri());
        assertEquals("2001-01-01T01:00:00Z", presenceTuple1.getTime().toString());
        assertTrue(presenceTuple1.getServiceCapabilities().isAudioCapable());
        assertFalse(presenceTuple1.getServiceCapabilities().isVideoCapable());
    }

    @Test
    @SmallTest
    public void testConvertToRcsContactUceCapabilityForMultipleTuples() throws Exception {
        final String contact = "sip:+11234567890@test";
        final String serviceId1 = "org.3gpp.urn:urn-7:3gpp-application.ims.iari.rcse.dp";
        final String serviceDescription1 = "capabilities discovery";
        final String serviceId2 = "org.3gpp.urn:urn-7:3gpp-service.ims.icsi.mmtel";
        final String serviceDescription2 = "MMTEL feature service";
        final boolean isAudioSupported = true;
        final boolean isVideoSupported = false;

        // Create the PIDF data
        String pidfData = getPidfDataWithMultiTuples(contact, serviceId1, serviceDescription1,
                serviceId2, serviceDescription2, isAudioSupported, isVideoSupported);

        // Convert to the class RcsContactUceCapability
        RcsContactUceCapability capabilities = PidfParser.getRcsContactUceCapability(pidfData);

        assertNotNull(capabilities);
        assertEquals(Uri.parse(contact), capabilities.getContactUri());

        List<RcsContactPresenceTuple> presenceTupleList = capabilities.getCapabilityTuples();
        assertNotNull(presenceTupleList);
        assertEquals(2, presenceTupleList.size());

        // Verify the first tuple information
        RcsContactPresenceTuple presenceTuple1 = presenceTupleList.get(0);
        assertEquals(serviceId1, presenceTuple1.getServiceId());
        assertEquals("1.0", presenceTuple1.getServiceVersion());
        assertEquals(serviceDescription1, presenceTuple1.getServiceDescription());
        assertEquals(Uri.parse(contact), presenceTuple1.getContactUri());
        assertEquals("2001-01-01T01:00:00Z", presenceTuple1.getTime().toString());
        assertNull(presenceTuple1.getServiceCapabilities());

        // Verify the second tuple information
        RcsContactPresenceTuple presenceTuple2 = presenceTupleList.get(1);
        assertEquals(serviceId2, presenceTuple2.getServiceId());
        assertEquals("1.0", presenceTuple2.getServiceVersion());
        assertTrue(presenceTuple2.getServiceCapabilities().isAudioCapable());
        assertFalse(presenceTuple2.getServiceCapabilities().isVideoCapable());
        assertEquals(serviceDescription2, presenceTuple2.getServiceDescription());
        assertEquals(Uri.parse(contact), presenceTuple2.getContactUri());
        assertEquals("2001-02-02T01:00:00Z", presenceTuple2.getTime().toString());
        assertNotNull(presenceTuple2.getServiceCapabilities());
        assertEquals(isAudioSupported, presenceTuple2.getServiceCapabilities().isAudioCapable());
        assertEquals(isVideoSupported, presenceTuple2.getServiceCapabilities().isVideoCapable());
    }

    @Test
    @SmallTest
    public void testConversionAndRestoration() throws Exception {
        // Create the capability
        final RcsContactUceCapability capability = getRcsContactUceCapability();

        // Convert the capability to the pidf
        final String pidf = PidfParser.convertToPidf(capability);

        // Restore to the RcsContactUceCapability from the pidf
        final RcsContactUceCapability restoredCapability =
                PidfParser.getRcsContactUceCapability(pidf);

        assertEquals(capability.getContactUri(), restoredCapability.getContactUri());
        assertEquals(capability.getCapabilityMechanism(),
                restoredCapability.getCapabilityMechanism());
        assertEquals(capability.getSourceType(), restoredCapability.getSourceType());

        // Assert all the tuples are equal
        List<RcsContactPresenceTuple> originalTuples = capability.getCapabilityTuples();
        List<RcsContactPresenceTuple> restoredTuples = restoredCapability.getCapabilityTuples();

        assertNotNull(restoredTuples);
        assertEquals(originalTuples.size(), restoredTuples.size());

        for (int i = 0; i < originalTuples.size(); i++) {
            RcsContactPresenceTuple tuple = originalTuples.get(i);
            RcsContactPresenceTuple restoredTuple = restoredTuples.get(i);

            assertEquals(tuple.getContactUri(), restoredTuple.getContactUri());
            assertEquals(tuple.getStatus(), restoredTuple.getStatus());
            assertEquals(tuple.getServiceId(), restoredTuple.getServiceId());
            assertEquals(tuple.getServiceVersion(), restoredTuple.getServiceVersion());
            assertEquals(tuple.getServiceDescription(), restoredTuple.getServiceDescription());

            boolean isAudioCapable = false;
            boolean isVideoCapable = false;
            boolean isRestoredAudioCapable = false;
            boolean isRestoredVideoCapable = false;

            ServiceCapabilities servCaps = tuple.getServiceCapabilities();
            if (servCaps != null) {
                isAudioCapable = servCaps.isAudioCapable();
                isVideoCapable = servCaps.isVideoCapable();
            }

            ServiceCapabilities restoredServCaps = restoredTuple.getServiceCapabilities();
            if (restoredServCaps != null) {
                isRestoredAudioCapable = restoredServCaps.isAudioCapable();
                isRestoredVideoCapable = restoredServCaps.isVideoCapable();
            }

            assertEquals(isAudioCapable, isRestoredAudioCapable);
            assertEquals(isVideoCapable, isRestoredVideoCapable);
        }
     }

    private String getPidfData(String contact, String serviceId, String serviceDescription,
            boolean isAudioSupported, boolean isVideoSupported) {
        StringBuilder pidfBuilder = new StringBuilder();
        pidfBuilder.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>")
                .append("<presence entity=\"" + contact + "\"")
                .append(" xmlns=\"urn:ietf:params:xml:ns:pidf\"")
                .append(" xmlns:op=\"urn:oma:xml:prs:pidf:oma-pres\"")
                .append(" xmlns:op=\"urn:oma:xml:prs:pidf:oma-pres\"")
                .append(" xmlns:caps=\"urn:ietf:params:xml:ns:pidf:caps\">")
                // tuple data
                .append("<tuple id=\"tid0\">")
                .append("<status><basic>open</basic></status>")
                .append("<op:service-description>")
                .append("<op:service-id>").append(serviceId).append("</op:service-id>")
                .append("<op:version>1.0</op:version>")
                .append("<op:description>").append(serviceDescription).append("</op:description>")
                .append("</op:service-description>")
                // is audio supported
                .append("<caps:servcaps>")
                .append("<caps:audio>").append(isAudioSupported).append("</caps:audio>")
                // is video supported
                .append("<caps:video>").append(isVideoSupported).append("</caps:video>")
                .append("</caps:servcaps>")
                .append("<contact>").append(contact).append("</contact>")
                .append("<timestamp>2001-01-01T01:00:00.00Z</timestamp>")
                .append("</tuple></presence>");
        return pidfBuilder.toString();
    }

    private String getPidfDataWithMultiTuples(String contact, String serviceId1,
            String serviceDescription1, String serviceId2, String serviceDescription2,
            boolean audioSupported, boolean videoSupported) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<presence xmlns=\"urn:ietf:params:xml:ns:pidf\""
                + " xmlns:op=\"urn:oma:xml:prs:pidf:oma-pres\""
                + " xmlns:caps=\"urn:ietf:params:xml:ns:pidf:caps\""
                + " entity=\"" + contact + "\">"
                // tuple 1
                + "<tuple id=\"a0\">"
                + "<status><basic>open</basic></status>"
                + "<op:service-description>"
                + "<op:service-id>" + serviceId1 + "</op:service-id>"
                + "<op:version>1.0</op:version>"
                + "<op:description>" + serviceDescription1 + "</op:description>"
                + "</op:service-description>"
                + "<contact>" + contact + "</contact>"
                + "<timestamp>2001-01-01T01:00:00.00Z</timestamp>"
                + "</tuple>"
                // tuple 2
                + "<tuple id=\"a1\">"
                + "<status><basic>open</basic></status>"
                + "<op:service-description>"
                + "<op:service-id>" + serviceId2 + "</op:service-id>"
                + "<op:version>1.0</op:version>"
                + "<op:description>" + serviceDescription2 + "</op:description>"
                + "</op:service-description>"
                + "<caps:servcaps>"
                + "<caps:audio>" + audioSupported + "</caps:audio>"
                + "<caps:duplex>"
                + "<caps:supported><caps:full></caps:full></caps:supported>"
                + "</caps:duplex>"
                + "<caps:video>" + videoSupported + "</caps:video>"
                + "</caps:servcaps>"
                + "<contact>" + contact + "</contact>"
                + "<timestamp>2001-02-02T01:00:00.00Z</timestamp>"
                + "</tuple>"
                + "</presence>";
    }

    private RcsContactUceCapability getRcsContactUceCapability() {
        final Uri contact = Uri.fromParts("sip", "test", null);
        final boolean isAudioCapable = true;
        final boolean isVideoCapable = true;
        final String duplexMode = ServiceCapabilities.DUPLEX_MODE_FULL;
        final String basicStatus = RcsContactPresenceTuple.TUPLE_BASIC_STATUS_OPEN;
        final String version = "1.0";
        final String description = "description test";
        final Instant nowTime = Instant.now();

        // init the capabilities
        ServiceCapabilities.Builder servCapsBuilder =
                new ServiceCapabilities.Builder(isAudioCapable, isVideoCapable);
        servCapsBuilder.addSupportedDuplexMode(duplexMode);

        // init the presence tuple
        RcsContactPresenceTuple.Builder tupleBuilder = new RcsContactPresenceTuple.Builder(
                basicStatus, RcsContactPresenceTuple.SERVICE_ID_MMTEL, version);
        tupleBuilder.setContactUri(contact)
                .setServiceDescription(description)
                .setTime(nowTime)
                .setServiceCapabilities(servCapsBuilder.build());

        PresenceBuilder presenceBuilder = new PresenceBuilder(contact,
                RcsContactUceCapability.SOURCE_TYPE_NETWORK,
                RcsContactUceCapability.REQUEST_RESULT_FOUND);
        presenceBuilder.addCapabilityTuple(tupleBuilder.build());

        return presenceBuilder.build();
    }
}
