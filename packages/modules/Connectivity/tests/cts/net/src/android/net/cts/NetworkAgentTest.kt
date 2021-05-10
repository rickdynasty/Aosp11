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
package android.net.cts

import android.app.Instrumentation
import android.content.Context
import android.net.ConnectivityManager
import android.net.INetworkAgent
import android.net.INetworkAgentRegistry
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.KeepalivePacketData
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NattKeepalivePacketData
import android.net.Network
import android.net.NetworkAgent
import android.net.NetworkAgent.INVALID_NETWORK
import android.net.NetworkAgent.VALID_NETWORK
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkInfo
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.RouteInfo
import android.net.SocketKeepalive
import android.net.Uri
import android.net.VpnManager
import android.net.VpnTransportInfo
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnAddKeepalivePacketFilter
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnAutomaticReconnectDisabled
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnBandwidthUpdateRequested
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnNetworkCreated
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnNetworkDestroyed
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnNetworkUnwanted
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnRemoveKeepalivePacketFilter
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnSaveAcceptUnvalidated
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnSignalStrengthThresholdsUpdated
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnStartSocketKeepalive
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnStopSocketKeepalive
import android.net.cts.NetworkAgentTest.TestableNetworkAgent.CallbackEntry.OnValidationStatus
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.DebugUtils.valueToString
import androidx.test.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.CompatUtil
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.TestableNetworkCallback
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import java.time.Duration
import java.util.Arrays
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// This test doesn't really have a constraint on how fast the methods should return. If it's
// going to fail, it will simply wait forever, so setting a high timeout lowers the flake ratio
// without affecting the run time of successful runs. Thus, set a very high timeout.
private const val DEFAULT_TIMEOUT_MS = 5000L
// When waiting for a NetworkCallback to determine there was no timeout, waiting is the
// only possible thing (the relevant handler is the one in the real ConnectivityService,
// and then there is the Binder call), so have a short timeout for this as it will be
// exhausted every time.
private const val NO_CALLBACK_TIMEOUT = 200L
// Any legal score (0~99) for the test network would do, as it is going to be kept up by the
// requests filed by the test and should never match normal internet requests. 70 is the default
// score of Ethernet networks, it's as good a value as any other.
private const val TEST_NETWORK_SCORE = 70
private const val BETTER_NETWORK_SCORE = 75
private const val FAKE_NET_ID = 1098
private val instrumentation: Instrumentation
    get() = InstrumentationRegistry.getInstrumentation()
private val realContext: Context
    get() = InstrumentationRegistry.getContext()
private fun Message(what: Int, arg1: Int, arg2: Int, obj: Any?) = Message.obtain().also {
    it.what = what
    it.arg1 = arg1
    it.arg2 = arg2
    it.obj = obj
}

@RunWith(DevSdkIgnoreRunner::class)
// NetworkAgent is not updatable in R-, so this test does not need to be compatible with older
// versions. NetworkAgent was also based on AsyncChannel before S so cannot be tested the same way.
@IgnoreUpTo(Build.VERSION_CODES.R)
class NetworkAgentTest {
    private val LOCAL_IPV4_ADDRESS = InetAddresses.parseNumericAddress("192.0.2.1")
    private val REMOTE_IPV4_ADDRESS = InetAddresses.parseNumericAddress("192.0.2.2")

    private val mCM = realContext.getSystemService(ConnectivityManager::class.java)
    private val mHandlerThread = HandlerThread("${javaClass.simpleName} handler thread")
    private val mFakeConnectivityService = FakeConnectivityService()

    private class Provider(context: Context, looper: Looper) :
            NetworkProvider(context, looper, "NetworkAgentTest NetworkProvider")

    private val agentsToCleanUp = mutableListOf<NetworkAgent>()
    private val callbacksToCleanUp = mutableListOf<TestableNetworkCallback>()

    @Before
    fun setUp() {
        instrumentation.getUiAutomation().adoptShellPermissionIdentity()
        mHandlerThread.start()
    }

    @After
    fun tearDown() {
        agentsToCleanUp.forEach { it.unregister() }
        callbacksToCleanUp.forEach { mCM.unregisterNetworkCallback(it) }
        mHandlerThread.quitSafely()
        instrumentation.getUiAutomation().dropShellPermissionIdentity()
    }

    /**
     * A fake that helps simulating ConnectivityService talking to a harnessed agent.
     * This fake only supports speaking to one harnessed agent at a time because it
     * only keeps track of one async channel.
     */
    private class FakeConnectivityService {
        val mockRegistry = mock(INetworkAgentRegistry::class.java)
        private var agentField: INetworkAgent? = null
        private val registry = object : INetworkAgentRegistry.Stub(),
                INetworkAgentRegistry by mockRegistry {
            // asBinder has implementations in both INetworkAgentRegistry.Stub and mockRegistry, so
            // it needs to be disambiguated. Just fail the test as it should be unused here.
            // asBinder is used when sending the registry in binder transactions, so not in this
            // test (the test just uses in-process direct calls). If it were used across processes,
            // using the Stub super.asBinder() implementation would allow sending the registry in
            // binder transactions, while recording incoming calls on the other mockito-generated
            // methods.
            override fun asBinder() = fail("asBinder should be unused in this test")
        }

        val agent: INetworkAgent
            get() = agentField ?: fail("No INetworkAgent")

        fun connect(agent: INetworkAgent) {
            this.agentField = agent
            agent.onRegistered(registry)
        }

        fun disconnect() = agent.onDisconnected()
    }

    private open class TestableNetworkAgent(
        context: Context,
        looper: Looper,
        val nc: NetworkCapabilities,
        val lp: LinkProperties,
        conf: NetworkAgentConfig
    ) : NetworkAgent(context, looper, TestableNetworkAgent::class.java.simpleName /* tag */,
            nc, lp, TEST_NETWORK_SCORE, conf, Provider(context, looper)) {
        private val history = ArrayTrackRecord<CallbackEntry>().newReadHead()

        sealed class CallbackEntry {
            object OnBandwidthUpdateRequested : CallbackEntry()
            object OnNetworkUnwanted : CallbackEntry()
            data class OnAddKeepalivePacketFilter(
                val slot: Int,
                val packet: KeepalivePacketData
            ) : CallbackEntry()
            data class OnRemoveKeepalivePacketFilter(val slot: Int) : CallbackEntry()
            data class OnStartSocketKeepalive(
                val slot: Int,
                val interval: Int,
                val packet: KeepalivePacketData
            ) : CallbackEntry()
            data class OnStopSocketKeepalive(val slot: Int) : CallbackEntry()
            data class OnSaveAcceptUnvalidated(val accept: Boolean) : CallbackEntry()
            object OnAutomaticReconnectDisabled : CallbackEntry()
            data class OnValidationStatus(val status: Int, val uri: Uri?) : CallbackEntry()
            data class OnSignalStrengthThresholdsUpdated(val thresholds: IntArray) : CallbackEntry()
            object OnNetworkCreated : CallbackEntry()
            object OnNetworkDestroyed : CallbackEntry()
        }

        override fun onBandwidthUpdateRequested() {
            history.add(OnBandwidthUpdateRequested)
        }

        override fun onNetworkUnwanted() {
            history.add(OnNetworkUnwanted)
        }

        override fun onAddKeepalivePacketFilter(slot: Int, packet: KeepalivePacketData) {
            history.add(OnAddKeepalivePacketFilter(slot, packet))
        }

        override fun onRemoveKeepalivePacketFilter(slot: Int) {
            history.add(OnRemoveKeepalivePacketFilter(slot))
        }

        override fun onStartSocketKeepalive(
            slot: Int,
            interval: Duration,
            packet: KeepalivePacketData
        ) {
            history.add(OnStartSocketKeepalive(slot, interval.seconds.toInt(), packet))
        }

        override fun onStopSocketKeepalive(slot: Int) {
            history.add(OnStopSocketKeepalive(slot))
        }

        override fun onSaveAcceptUnvalidated(accept: Boolean) {
            history.add(OnSaveAcceptUnvalidated(accept))
        }

        override fun onAutomaticReconnectDisabled() {
            history.add(OnAutomaticReconnectDisabled)
        }

        override fun onSignalStrengthThresholdsUpdated(thresholds: IntArray) {
            history.add(OnSignalStrengthThresholdsUpdated(thresholds))
        }

        fun expectEmptySignalStrengths() {
            expectCallback<OnSignalStrengthThresholdsUpdated>().let {
                // intArrayOf() without arguments makes an empty array
                assertArrayEquals(intArrayOf(), it.thresholds)
            }
        }

        override fun onValidationStatus(status: Int, uri: Uri?) {
            history.add(OnValidationStatus(status, uri))
        }

        override fun onNetworkCreated() {
            history.add(OnNetworkCreated)
        }

        override fun onNetworkDestroyed() {
            history.add(OnNetworkDestroyed)
        }

        // Expects the initial validation event that always occurs immediately after registering
        // a NetworkAgent whose network does not require validation (which test networks do
        // not, since they lack the INTERNET capability). It always contains the default argument
        // for the URI.
        fun expectNoInternetValidationStatus() = expectCallback<OnValidationStatus>().let {
            assertEquals(it.status, VALID_NETWORK)
            // The returned Uri is parsed from the empty string, which means it's an
            // instance of the (private) Uri.StringUri. There are no real good ways
            // to check this, the least bad is to just convert it to a string and
            // make sure it's empty.
            assertEquals("", it.uri.toString())
        }

        inline fun <reified T : CallbackEntry> expectCallback(): T {
            val foundCallback = history.poll(DEFAULT_TIMEOUT_MS)
            assertTrue(foundCallback is T, "Expected ${T::class} but found $foundCallback")
            return foundCallback
        }

        fun assertNoCallback() {
            assertTrue(waitForIdle(DEFAULT_TIMEOUT_MS),
                    "Handler didn't became idle after ${DEFAULT_TIMEOUT_MS}ms")
            assertNull(history.peek())
        }
    }

    private fun requestNetwork(request: NetworkRequest, callback: TestableNetworkCallback) {
        mCM.requestNetwork(request, callback)
        callbacksToCleanUp.add(callback)
    }

    private fun registerNetworkCallback(
        request: NetworkRequest,
        callback: TestableNetworkCallback
    ) {
        mCM.registerNetworkCallback(request, callback)
        callbacksToCleanUp.add(callback)
    }

    private fun createNetworkAgent(
        context: Context = realContext,
        name: String? = null,
        initialNc: NetworkCapabilities? = null,
        initialLp: LinkProperties? = null
    ): TestableNetworkAgent {
        val nc = initialNc ?: NetworkCapabilities().apply {
            addTransportType(TRANSPORT_TEST)
            removeCapability(NET_CAPABILITY_TRUSTED)
            removeCapability(NET_CAPABILITY_INTERNET)
            addCapability(NET_CAPABILITY_NOT_SUSPENDED)
            addCapability(NET_CAPABILITY_NOT_ROAMING)
            addCapability(NET_CAPABILITY_NOT_VPN)
            if (SdkLevel.isAtLeastS()) {
                addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
            }
            if (null != name) {
                setNetworkSpecifier(CompatUtil.makeEthernetNetworkSpecifier(name))
            }
        }
        val lp = initialLp ?: LinkProperties().apply {
            addLinkAddress(LinkAddress(LOCAL_IPV4_ADDRESS, 32))
            addRoute(RouteInfo(IpPrefix("0.0.0.0/0"), null, null))
        }
        val config = NetworkAgentConfig.Builder().build()
        return TestableNetworkAgent(context, mHandlerThread.looper, nc, lp, config).also {
            agentsToCleanUp.add(it)
        }
    }

    private fun createConnectedNetworkAgent(context: Context = realContext, name: String? = null):
            Pair<TestableNetworkAgent, TestableNetworkCallback> {
        val request: NetworkRequest = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_TEST)
                .build()
        val callback = TestableNetworkCallback(timeoutMs = DEFAULT_TIMEOUT_MS)
        requestNetwork(request, callback)
        val agent = createNetworkAgent(context, name)
        agent.setTeardownDelayMillis(0)
        agent.register()
        agent.markConnected()
        agent.expectCallback<OnNetworkCreated>()
        return agent to callback
    }

    private fun createNetworkAgentWithFakeCS() = createNetworkAgent().also {
        mFakeConnectivityService.connect(it.registerForTest(Network(FAKE_NET_ID)))
    }

    @Test
    fun testConnectAndUnregister() {
        val (agent, callback) = createConnectedNetworkAgent()
        callback.expectAvailableThenValidatedCallbacks(agent.network)
        agent.expectEmptySignalStrengths()
        agent.expectNoInternetValidationStatus()
        agent.unregister()
        callback.expectCallback<Lost>(agent.network)
        agent.expectCallback<OnNetworkUnwanted>()
        assertFailsWith<IllegalStateException>("Must not be able to register an agent twice") {
            agent.register()
        }
        agent.expectCallback<OnNetworkDestroyed>()
    }

    @Test
    fun testOnBandwidthUpdateRequested() {
        val (agent, callback) = createConnectedNetworkAgent()
        callback.expectAvailableThenValidatedCallbacks(agent.network)
        agent.expectEmptySignalStrengths()
        agent.expectNoInternetValidationStatus()
        mCM.requestBandwidthUpdate(agent.network)
        agent.expectCallback<OnBandwidthUpdateRequested>()
        agent.unregister()
    }

    @Test
    fun testSignalStrengthThresholds() {
        val thresholds = intArrayOf(30, 50, 65)
        val callbacks = thresholds.map { strength ->
            val request = NetworkRequest.Builder()
                    .clearCapabilities()
                    .addTransportType(TRANSPORT_TEST)
                    .setSignalStrength(strength)
                    .build()
            TestableNetworkCallback(DEFAULT_TIMEOUT_MS).also {
                registerNetworkCallback(request, it)
            }
        }
        createConnectedNetworkAgent().let { (agent, callback) ->
            callback.expectAvailableThenValidatedCallbacks(agent.network)
            agent.expectCallback<OnSignalStrengthThresholdsUpdated>().let {
                assertArrayEquals(it.thresholds, thresholds)
            }
            agent.expectNoInternetValidationStatus()

            // Send signal strength and check that the callbacks are called appropriately.
            val nc = NetworkCapabilities(agent.nc)
            nc.setSignalStrength(20)
            agent.sendNetworkCapabilities(nc)
            callbacks.forEach { it.assertNoCallback(NO_CALLBACK_TIMEOUT) }

            nc.setSignalStrength(40)
            agent.sendNetworkCapabilities(nc)
            callbacks[0].expectAvailableCallbacks(agent.network)
            callbacks[1].assertNoCallback(NO_CALLBACK_TIMEOUT)
            callbacks[2].assertNoCallback(NO_CALLBACK_TIMEOUT)

            nc.setSignalStrength(80)
            agent.sendNetworkCapabilities(nc)
            callbacks[0].expectCapabilitiesThat(agent.network) { it.signalStrength == 80 }
            callbacks[1].expectAvailableCallbacks(agent.network)
            callbacks[2].expectAvailableCallbacks(agent.network)

            nc.setSignalStrength(55)
            agent.sendNetworkCapabilities(nc)
            callbacks[0].expectCapabilitiesThat(agent.network) { it.signalStrength == 55 }
            callbacks[1].expectCapabilitiesThat(agent.network) { it.signalStrength == 55 }
            callbacks[2].expectCallback<Lost>(agent.network)
        }
        callbacks.forEach {
            mCM.unregisterNetworkCallback(it)
        }
    }

    @Test
    fun testSocketKeepalive(): Unit = createNetworkAgentWithFakeCS().let { agent ->
        val packet = NattKeepalivePacketData(
                LOCAL_IPV4_ADDRESS /* srcAddress */, 1234 /* srcPort */,
                REMOTE_IPV4_ADDRESS /* dstAddress */, 4567 /* dstPort */,
                ByteArray(100 /* size */))
        val slot = 4
        val interval = 37

        mFakeConnectivityService.agent.onAddNattKeepalivePacketFilter(slot, packet)
        mFakeConnectivityService.agent.onStartNattSocketKeepalive(slot, interval, packet)

        agent.expectCallback<OnAddKeepalivePacketFilter>().let {
            assertEquals(it.slot, slot)
            assertEquals(it.packet, packet)
        }
        agent.expectCallback<OnStartSocketKeepalive>().let {
            assertEquals(it.slot, slot)
            assertEquals(it.interval, interval)
            assertEquals(it.packet, packet)
        }

        agent.assertNoCallback()

        // Check that when the agent sends a keepalive event, ConnectivityService receives the
        // expected message.
        agent.sendSocketKeepaliveEvent(slot, SocketKeepalive.ERROR_UNSUPPORTED)
        verify(mFakeConnectivityService.mockRegistry, timeout(DEFAULT_TIMEOUT_MS))
                .sendSocketKeepaliveEvent(slot, SocketKeepalive.ERROR_UNSUPPORTED)

        mFakeConnectivityService.agent.onStopSocketKeepalive(slot)
        mFakeConnectivityService.agent.onRemoveKeepalivePacketFilter(slot)
        agent.expectCallback<OnStopSocketKeepalive>().let {
            assertEquals(it.slot, slot)
        }
        agent.expectCallback<OnRemoveKeepalivePacketFilter>().let {
            assertEquals(it.slot, slot)
        }
    }

    @Test
    fun testSendUpdates(): Unit = createConnectedNetworkAgent().let { (agent, callback) ->
        callback.expectAvailableThenValidatedCallbacks(agent.network)
        agent.expectEmptySignalStrengths()
        agent.expectNoInternetValidationStatus()
        val ifaceName = "adhocIface"
        val lp = LinkProperties(agent.lp)
        lp.setInterfaceName(ifaceName)
        agent.sendLinkProperties(lp)
        callback.expectLinkPropertiesThat(agent.network) {
            it.getInterfaceName() == ifaceName
        }
        val nc = NetworkCapabilities(agent.nc)
        nc.addCapability(NET_CAPABILITY_NOT_METERED)
        agent.sendNetworkCapabilities(nc)
        callback.expectCapabilitiesThat(agent.network) {
            it.hasCapability(NET_CAPABILITY_NOT_METERED)
        }
    }

    @Test
    fun testSendScore() {
        // This test will create two networks and check that the one with the stronger
        // score wins out for a request that matches them both.
        // First create requests to make sure both networks are kept up, using the
        // specifier so they are specific to each network
        val name1 = UUID.randomUUID().toString()
        val name2 = UUID.randomUUID().toString()
        val request1 = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_TEST)
                .setNetworkSpecifier(CompatUtil.makeEthernetNetworkSpecifier(name1))
                .build()
        val request2 = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_TEST)
                .setNetworkSpecifier(CompatUtil.makeEthernetNetworkSpecifier(name2))
                .build()
        val callback1 = TestableNetworkCallback(timeoutMs = DEFAULT_TIMEOUT_MS)
        val callback2 = TestableNetworkCallback(timeoutMs = DEFAULT_TIMEOUT_MS)
        requestNetwork(request1, callback1)
        requestNetwork(request2, callback2)

        // Then file the interesting request
        val request = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_TEST)
                .build()
        val callback = TestableNetworkCallback(timeoutMs = DEFAULT_TIMEOUT_MS)
        requestNetwork(request, callback)

        // Connect the first Network
        createConnectedNetworkAgent(name = name1).let { (agent1, _) ->
            callback.expectAvailableThenValidatedCallbacks(agent1.network)
            // Upgrade agent1 to a better score so that there is no ambiguity when
            // agent2 connects that agent1 is still better
            agent1.sendNetworkScore(BETTER_NETWORK_SCORE - 1)
            // Connect the second agent
            createConnectedNetworkAgent(name = name2).let { (agent2, _) ->
                agent2.markConnected()
                // The callback should not see anything yet
                callback.assertNoCallback(NO_CALLBACK_TIMEOUT)
                // Now update the score and expect the callback now prefers agent2
                agent2.sendNetworkScore(BETTER_NETWORK_SCORE)
                callback.expectCallback<Available>(agent2.network)
            }
        }

        // tearDown() will unregister the requests and agents
    }

    private fun hasAllTransports(nc: NetworkCapabilities?, transports: IntArray) =
            nc != null && transports.all { nc.hasTransport(it) }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    fun testSetUnderlyingNetworksAndVpnSpecifier() {
        val mySessionId = "MySession12345"
        val request = NetworkRequest.Builder()
                .addTransportType(TRANSPORT_TEST)
                .addTransportType(TRANSPORT_VPN)
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .removeCapability(NET_CAPABILITY_TRUSTED) // TODO: add to VPN!
                .build()
        val callback = TestableNetworkCallback(timeoutMs = DEFAULT_TIMEOUT_MS)
        registerNetworkCallback(request, callback)

        val nc = NetworkCapabilities().apply {
            addTransportType(TRANSPORT_TEST)
            addTransportType(TRANSPORT_VPN)
            removeCapability(NET_CAPABILITY_NOT_VPN)
            setTransportInfo(VpnTransportInfo(VpnManager.TYPE_VPN_SERVICE, mySessionId))
            if (SdkLevel.isAtLeastS()) {
                addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
            }
        }
        val defaultNetwork = mCM.activeNetwork
        assertNotNull(defaultNetwork)
        val defaultNetworkCapabilities = mCM.getNetworkCapabilities(defaultNetwork)
        val defaultNetworkTransports = defaultNetworkCapabilities.transportTypes

        val agent = createNetworkAgent(initialNc = nc)
        agent.register()
        agent.markConnected()
        callback.expectAvailableThenValidatedCallbacks(agent.network!!)

        // Check that the default network's transport is propagated to the VPN.
        var vpnNc = mCM.getNetworkCapabilities(agent.network)
        assertNotNull(vpnNc)
        assertEquals(VpnManager.TYPE_VPN_SERVICE,
                (vpnNc.transportInfo as VpnTransportInfo).type)
        // TODO: b/183938194 please fix the issue and enable following check.
        // assertEquals(mySessionId, (vpnNc.transportInfo as VpnTransportInfo).sessionId)

        val testAndVpn = intArrayOf(TRANSPORT_TEST, TRANSPORT_VPN)
        assertTrue(hasAllTransports(vpnNc, testAndVpn))
        assertFalse(vpnNc.hasCapability(NET_CAPABILITY_NOT_VPN))
        assertTrue(hasAllTransports(vpnNc, defaultNetworkTransports),
                "VPN transports ${Arrays.toString(vpnNc.transportTypes)}" +
                " lacking transports from ${Arrays.toString(defaultNetworkTransports)}")

        // Check that when no underlying networks are announced the underlying transport disappears.
        agent.setUnderlyingNetworks(listOf<Network>())
        callback.expectCapabilitiesThat(agent.network!!) {
            it.transportTypes.size == 2 && hasAllTransports(it, testAndVpn)
        }

        // Put the underlying network back and check that the underlying transport reappears.
        val expectedTransports = (defaultNetworkTransports.toSet() + TRANSPORT_TEST + TRANSPORT_VPN)
                .toIntArray()
        agent.setUnderlyingNetworks(null)
        callback.expectCapabilitiesThat(agent.network!!) {
            it.transportTypes.size == expectedTransports.size &&
                    hasAllTransports(it, expectedTransports)
        }

        // Check that some underlying capabilities are propagated.
        // This is not very accurate because the test does not control the capabilities of the
        // underlying networks, and because not congested, not roaming, and not suspended are the
        // default anyway. It's still useful as an extra check though.
        vpnNc = mCM.getNetworkCapabilities(agent.network)
        for (cap in listOf(NET_CAPABILITY_NOT_CONGESTED,
                NET_CAPABILITY_NOT_ROAMING,
                NET_CAPABILITY_NOT_SUSPENDED)) {
            val capStr = valueToString(NetworkCapabilities::class.java, "NET_CAPABILITY_", cap)
            if (defaultNetworkCapabilities.hasCapability(cap) && !vpnNc.hasCapability(cap)) {
                fail("$capStr not propagated from underlying: $defaultNetworkCapabilities")
            }
        }

        agent.unregister()
        callback.expectCallback<Lost>(agent.network)
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    fun testAgentStartsInConnecting() {
        val mockContext = mock(Context::class.java)
        val mockCm = mock(ConnectivityManager::class.java)
        doReturn(mockCm).`when`(mockContext).getSystemService(Context.CONNECTIVITY_SERVICE)
        val agent = createNetworkAgent(mockContext)
        agent.register()
        verify(mockCm).registerNetworkAgent(any(),
                argThat<NetworkInfo> { it.detailedState == NetworkInfo.DetailedState.CONNECTING },
                any(LinkProperties::class.java),
                any(NetworkCapabilities::class.java),
                any(NetworkScore::class.java),
                any(NetworkAgentConfig::class.java),
                eq(NetworkProvider.ID_NONE))
    }

    @Test
    fun testSetAcceptUnvalidated() {
        createNetworkAgentWithFakeCS().let { agent ->
            mFakeConnectivityService.agent.onSaveAcceptUnvalidated(true)
            agent.expectCallback<OnSaveAcceptUnvalidated>().let {
                assertTrue(it.accept)
            }
            agent.assertNoCallback()
        }
    }

    @Test
    fun testSetAcceptUnvalidatedPreventAutomaticReconnect() {
        createNetworkAgentWithFakeCS().let { agent ->
            mFakeConnectivityService.agent.onSaveAcceptUnvalidated(false)
            mFakeConnectivityService.agent.onPreventAutomaticReconnect()
            agent.expectCallback<OnSaveAcceptUnvalidated>().let {
                assertFalse(it.accept)
            }
            agent.expectCallback<OnAutomaticReconnectDisabled>()
            agent.assertNoCallback()
            // When automatic reconnect is turned off, the network is torn down and
            // ConnectivityService disconnects. As part of the disconnect, ConnectivityService will
            // also send itself a message to unregister the NetworkAgent from its internal
            // structure.
            mFakeConnectivityService.disconnect()
            agent.expectCallback<OnNetworkUnwanted>()
        }
    }

    @Test
    fun testPreventAutomaticReconnect() {
        createNetworkAgentWithFakeCS().let { agent ->
            mFakeConnectivityService.agent.onPreventAutomaticReconnect()
            agent.expectCallback<OnAutomaticReconnectDisabled>()
            agent.assertNoCallback()
            mFakeConnectivityService.disconnect()
            agent.expectCallback<OnNetworkUnwanted>()
        }
    }

    @Test
    fun testValidationStatus() = createNetworkAgentWithFakeCS().let { agent ->
        val uri = Uri.parse("http://www.google.com")
        mFakeConnectivityService.agent.onValidationStatusChanged(VALID_NETWORK,
                uri.toString())
        agent.expectCallback<OnValidationStatus>().let {
            assertEquals(it.status, VALID_NETWORK)
            assertEquals(it.uri, uri)
        }

        mFakeConnectivityService.agent.onValidationStatusChanged(INVALID_NETWORK, null)
        agent.expectCallback<OnValidationStatus>().let {
            assertEquals(it.status, INVALID_NETWORK)
            assertNull(it.uri)
        }
    }

    @Test
    fun testTemporarilyUnmeteredCapability() {
        // This test will create a networks with/without NET_CAPABILITY_TEMPORARILY_NOT_METERED
        // and check that the callback reflects the capability changes.
        // First create a request to make sure the network is kept up
        val request1 = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_TEST)
                .build()
        val callback1 = TestableNetworkCallback(timeoutMs = DEFAULT_TIMEOUT_MS).also {
            registerNetworkCallback(request1, it)
        }
        requestNetwork(request1, callback1)

        // Then file the interesting request
        val request = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_TEST)
                .build()
        val callback = TestableNetworkCallback(timeoutMs = DEFAULT_TIMEOUT_MS)
        requestNetwork(request, callback)

        // Connect the network
        createConnectedNetworkAgent().let { (agent, _) ->
            callback.expectAvailableThenValidatedCallbacks(agent.network)

            // Send TEMP_NOT_METERED and check that the callback is called appropriately.
            val nc1 = NetworkCapabilities(agent.nc)
                    .addCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            agent.sendNetworkCapabilities(nc1)
            callback.expectCapabilitiesThat(agent.network) {
                it.hasCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            }

            // Remove TEMP_NOT_METERED and check that the callback is called appropriately.
            val nc2 = NetworkCapabilities(agent.nc)
                    .removeCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            agent.sendNetworkCapabilities(nc2)
            callback.expectCapabilitiesThat(agent.network) {
                !it.hasCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            }
        }

        // tearDown() will unregister the requests and agents
    }
}
