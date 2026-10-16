package com.overdrive.app.ui.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Policy tests for the stuck-cloudflared restart window.
 *
 * The failures that matter: killing a tunnel that is merely mid-reconnect (window too eager),
 * never killing one that is alive-but-edgeless (the field incident — 1.5 h dead behind a
 * healthy-looking `ps`), and restarting while there is no network at all (pointless churn). Backoff
 * keeps a tunnel that fails with a working network from restarting every 2 min.
 */
class CloudflaredEdgeWatchTest {

    private val base = CloudflaredEdgeWatch.BASE_RESTART_AFTER_MS
    private val settle = CloudflaredEdgeWatch.NETWORK_SETTLE_MS
    private val t0 = 1_000_000L

    private fun down(now: Long, net: Boolean? = true) = CloudflaredEdgeWatch.onProbe(false, net, now)

    @Before
    fun setUp() = CloudflaredEdgeWatch.reset()

    @Test
    fun connected_neverRestarts() {
        assertFalse(CloudflaredEdgeWatch.onProbe(true, null, t0))
        assertFalse(CloudflaredEdgeWatch.onProbe(true, null, t0 + 10 * base))
    }

    @Test
    fun down_withinWindow_doesNotRestart() {
        // A normal reconnect (ACC-OFF blackout + first retries) must not be killed.
        assertFalse(down(t0))
        assertFalse(down(t0 + base - 1))
    }

    @Test
    fun down_pastWindow_restartsOnce() {
        assertFalse(down(t0))
        assertTrue(down(t0 + base))
        // The window restarts from the kill — the very next tick must not kill the new process.
        assertFalse(down(t0 + base + 30_000))
    }

    @Test
    fun repeatedOutage_backsOffUpToCap() {
        var now = t0
        assertFalse(down(now))
        var expected = base
        repeat(8) {
            assertFalse(down(now + expected - 1))
            now += expected
            assertTrue(down(now))
            expected = (expected * 2).coerceAtMost(CloudflaredEdgeWatch.MAX_RESTART_AFTER_MS)
            assertEquals(expected, CloudflaredEdgeWatch.currentRestartAfterMs())
        }
        assertEquals(CloudflaredEdgeWatch.MAX_RESTART_AFTER_MS, CloudflaredEdgeWatch.currentRestartAfterMs())
    }

    @Test
    fun healthyProbe_resetsWindowAndBackoff() {
        assertFalse(down(t0))
        assertTrue(down(t0 + base))
        assertFalse(CloudflaredEdgeWatch.onProbe(true, null, t0 + base + 5_000))
        assertEquals(base, CloudflaredEdgeWatch.currentRestartAfterMs())
        // A later, unrelated outage starts a fresh base-length window.
        val later = t0 + 10 * base
        assertFalse(down(later))
        assertTrue(down(later + base))
    }

    @Test
    fun unknownEdgeProbe_isIgnored() {
        // Unreadable /proc or an ADB hiccup must never cause a kill, nor reset an outage in progress.
        assertFalse(CloudflaredEdgeWatch.onProbe(null, null, t0))
        assertFalse(down(t0))
        assertFalse(CloudflaredEdgeWatch.onProbe(null, null, t0 + base - 1))
        assertTrue(down(t0 + base))
    }

    // ---- network gate ----

    @Test
    fun noNetwork_neverRestarts_andDoesNotGrowBackoff() {
        assertFalse(down(t0, net = false))
        assertFalse(down(t0 + base, net = false))
        assertFalse(down(t0 + 20 * base, net = false))
        assertEquals(base, CloudflaredEdgeWatch.currentRestartAfterMs())
    }

    @Test
    fun networkReturns_waitsForSettle_thenRestartsPromptly() {
        // Long outage with no network: the down-window is long expired when the network comes back.
        assertFalse(down(t0, net = false))
        val back = t0 + 30 * 60_000L
        assertFalse(down(back, net = true))                 // just came back: let cloudflared try first
        assertFalse(down(back + settle - 1, net = true))
        assertTrue(down(back + settle, net = true))         // still edgeless after settle → restart
    }

    @Test
    fun networkFlap_restartsSettle() {
        assertFalse(down(t0))
        assertFalse(down(t0 + base - settle / 2, net = false))
        // Network back just after the window expired: settle starts again from here.
        val back = t0 + base + 1
        assertFalse(down(back, net = true))
        assertTrue(down(back + settle, net = true))
    }

    @Test
    fun unknownNetwork_behavesAsReachable() {
        // Missing `nc` or a proxied setup must not disable recovery.
        assertFalse(down(t0, net = null))
        assertTrue(down(t0 + base, net = null))
    }
}
