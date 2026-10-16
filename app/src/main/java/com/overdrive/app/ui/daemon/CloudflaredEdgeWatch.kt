package com.overdrive.app.ui.daemon

/**
 * Decides when a cloudflared process that is ALIVE but has NO edge connection must be restarted.
 *
 * ## Why this exists
 *
 * cloudflared runs with `--retries 20`. When the parked car's data link drops repeatedly, each
 * connection's backoff doubles (4m16s → 8m32s → 17m4s → 34m8s …) until the retry budget is spent;
 * the connection goroutines then exit with `Connection terminated` but the PROCESS stays up with
 * zero edge connections. Field incident (Sealion 6, 55.3): the tunnel sat dead for 1.5 h after a
 * parked drop while `ps` — the only liveness signal the 30s health check and
 * `TunnelLauncher.launchCloudflared` ("already running → reuse") look at — kept reporting it
 * healthy. Killing the process let the health check relaunch it, and the fresh process registered
 * all four connections in ~5 s.
 *
 * Raising `--retries` does not help: the backoff is uncapped, so more retries just means waiting
 * hours for the next attempt after the network is already back. cloudflared has no flag for the
 * retry interval or a backoff cap (only `--retries`; `--grace-period` is the SIGTERM drain timeout,
 * not a reconnect setting), so recovery has to come from outside the process.
 *
 * ## Policy
 *
 * - A tunnel must be continuously edge-less for [restartAfterMs] before a restart. The base
 *   ([BASE_RESTART_AFTER_MS], 2 min) clears the ~24 s ACC-OFF data-interface blackout plus
 *   cloudflared's first several 1s→2s→4s… reconnect attempts, so a normal reconnect never gets
 *   killed mid-flight.
 * - Each restart doubles the window up to [MAX_RESTART_AFTER_MS] so a car parked with no signal
 *   restarts cloudflared a handful of times per hour, not every two minutes. The first healthy
 *   probe resets it.
 *
 * State is time-based and process-wide (not a per-tick counter on a manager instance): the
 * boot-scoped and the Activity-scoped DaemonStartupManager can both be ticking at once, and a tick
 * counter would then advance at double speed.
 */
object CloudflaredEdgeWatch {

    const val BASE_RESTART_AFTER_MS = 120_000L
    const val MAX_RESTART_AFTER_MS = 16 * 60_000L

    private val lock = Any()
    private var downSinceMs = 0L
    private var restartAfterMs = BASE_RESTART_AFTER_MS

    /**
     * Feed one probe result. Returns true when the caller should restart cloudflared NOW.
     *
     * @param connected true = at least one ESTABLISHED edge connection, false = none,
     *   null = probe could not tell (unreadable /proc, ADB hiccup). Unknown never counts towards a
     *   restart and never resets the window — fail open, exactly like a failed `ps` snapshot.
     */
    fun onProbe(connected: Boolean?, nowMs: Long): Boolean = synchronized(lock) {
        when (connected) {
            null -> false
            true -> {
                downSinceMs = 0L
                restartAfterMs = BASE_RESTART_AFTER_MS
                false
            }
            false -> {
                if (downSinceMs == 0L) downSinceMs = nowMs
                if (nowMs - downSinceMs < restartAfterMs) {
                    false
                } else {
                    // Measure the next window from this restart, and back off.
                    downSinceMs = nowMs
                    restartAfterMs = (restartAfterMs * 2).coerceAtMost(MAX_RESTART_AFTER_MS)
                    true
                }
            }
        }
    }

    /** Current window, for logging. */
    fun currentRestartAfterMs(): Long = synchronized(lock) { restartAfterMs }

    /** Test hook. */
    internal fun reset() = synchronized(lock) {
        downSinceMs = 0L
        restartAfterMs = BASE_RESTART_AFTER_MS
    }
}
