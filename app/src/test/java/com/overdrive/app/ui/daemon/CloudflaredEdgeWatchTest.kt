package com.overdrive.app.ui.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Policy tests for the stuck-cloudflared restart window.
 *
 * The two failures that matter: killing a tunnel that is merely mid-reconnect (window too eager),
 * and never killing one that is alive-but-edgeless (the field incident — 1.5 h dead behind a
 * healthy-looking `ps`). Backoff keeps a car parked without signal from restarting every 2 min.
 */
class CloudflaredEdgeWatchTest {

    private val base = CloudflaredEdgeWatch.BASE_RESTART_AFTER_MS
    private val t0 = 1_000_000L

    @Before
    fun setUp() = CloudflaredEdgeWatch.reset()

    @Test
    fun connected_neverRestarts() {
        assertFalse(CloudflaredEdgeWatch.onProbe(true, t0))
        assertFalse(CloudflaredEdgeWatch.onProbe(true, t0 + 10 * base))
    }

    @Test
    fun down_withinWindow_doesNotRestart() {
        // A normal reconnect (ACC-OFF blackout + first retries) must not be killed.
        assertFalse(CloudflaredEdgeWatch.onProbe(false, t0))
        assertFalse(CloudflaredEdgeWatch.onProbe(false, t0 + base - 1))
    }

    @Test
    fun down_pastWindow_restartsOnce() {
        assertFalse(CloudflaredEdgeWatch.onProbe(false, t0))
        assertTrue(CloudflaredEdgeWatch.onProbe(false, t0 + base))
        // The window restarts from the kill — the very next tick must not kill the new process.
        assertFalse(CloudflaredEdgeWatch.onProbe(false, t0 + base + 30_000))
    }

    @Test
    fun repeatedOutage_backsOffUpToCap() {
        var now = t0
        assertFalse(CloudflaredEdgeWatch.onProbe(false, now))
        var expected = base
        repeat(8) {
            assertFalse(CloudflaredEdgeWatch.onProbe(false, now + expected - 1))
            now += expected
            assertTrue(CloudflaredEdgeWatch.onProbe(false, now))
            expected = (expected * 2).coerceAtMost(CloudflaredEdgeWatch.MAX_RESTART_AFTER_MS)
            assertEquals(expected, CloudflaredEdgeWatch.currentRestartAfterMs())
        }
        assertEquals(CloudflaredEdgeWatch.MAX_RESTART_AFTER_MS, CloudflaredEdgeWatch.currentRestartAfterMs())
    }

    @Test
    fun healthyProbe_resetsWindowAndBackoff() {
        assertFalse(CloudflaredEdgeWatch.onProbe(false, t0))
        assertTrue(CloudflaredEdgeWatch.onProbe(false, t0 + base))
        assertFalse(CloudflaredEdgeWatch.onProbe(true, t0 + base + 5_000))
        assertEquals(base, CloudflaredEdgeWatch.currentRestartAfterMs())
        // A later, unrelated outage starts a fresh base-length window.
        val later = t0 + 10 * base
        assertFalse(CloudflaredEdgeWatch.onProbe(false, later))
        assertTrue(CloudflaredEdgeWatch.onProbe(false, later + base))
    }

    @Test
    fun unknownProbe_isIgnored() {
        // Unreadable /proc or an ADB hiccup must never cause a kill, nor reset an outage in progress.
        assertFalse(CloudflaredEdgeWatch.onProbe(null, t0))
        assertFalse(CloudflaredEdgeWatch.onProbe(false, t0))
        assertFalse(CloudflaredEdgeWatch.onProbe(null, t0 + base - 1))
        assertTrue(CloudflaredEdgeWatch.onProbe(false, t0 + base))
    }
}
