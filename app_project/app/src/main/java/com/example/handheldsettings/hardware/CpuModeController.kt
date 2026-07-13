package com.example.handheldsettings.hardware

import android.util.Log
import com.example.handheldsettings.data.CpuMode
import com.topjohnwu.superuser.Shell

/**
 * CPU frequency/governor controller (RP6_Complete_Guide.md §12).
 *
 * Policies are discovered dynamically at startup:
 *   /sys/devices/system/cpu/cpufreq/policy* (e.g. policy0, policy4, policy7)
 *
 * Modes:
 *   POWER_SAVING: schedutil governor + 60% of max freq cap
 *   BALANCED:     schedutil + no cap (kernel decides)
 *   ULTRA:        performance governor + no cap
 *   ADAPTIVE:     schedutil + dynamic cap set externally by AdaptiveGovernor
 */
object CpuModeController {

    private const val TAG = "CpuModeController"
    private const val BASE = "/sys/devices/system/cpu/cpufreq"

    data class Policy(val path: String, val maxFreqKHz: Int)

    /** Cached after first discovery. */
    @Volatile
    private var policies: List<Policy>? = null

    fun getPolicies(): List<Policy> {
        policies?.let { return it }
        val dirs = Shell.cmd("ls $BASE | grep policy").exec().out
        val discovered = dirs.mapNotNull { name ->
            val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val p = "$BASE/$trimmed"
            val maxFreq = Shell.cmd("cat $p/cpuinfo_max_freq").exec()
                .out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
            Policy(p, maxFreq)
        }
        Log.i(TAG, "Discovered ${discovered.size} CPU policies: $discovered")
        policies = discovered
        return discovered
    }

    fun apply(mode: CpuMode) {
        val pols = getPolicies()
        if (pols.isEmpty()) {
            Log.w(TAG, "No CPU policies found, skipping")
            return
        }

        val commands = buildList {
            for (policy in pols) {
                val (governor, freqPct) = when (mode) {
                    CpuMode.POWER_SAVING -> "schedutil" to 0.6
                    CpuMode.BALANCED     -> "schedutil" to 1.0
                    CpuMode.ULTRA        -> "performance" to 1.0
                    CpuMode.ADAPTIVE     -> "schedutil" to 1.0 // freq managed separately
                }
                val cappedFreq = (policy.maxFreqKHz * freqPct).toInt()
                add("echo $governor > ${policy.path}/scaling_governor")
                add("echo $cappedFreq > ${policy.path}/scaling_max_freq")
            }
        }
        Shell.cmd(*commands.toTypedArray()).exec()
        Log.d(TAG, "Applied CPU mode: $mode")
    }

    /** Read available governors for the first policy (used for POWER_SAVING fallback). */
    fun availableGovernors(): List<String> {
        val p = getPolicies().firstOrNull() ?: return emptyList()
        return Shell.cmd("cat ${p.path}/scaling_available_governors").exec()
            .out.firstOrNull()?.trim()?.split(" ") ?: emptyList()
    }

    data class PolicyLiveInfo(
        val name: String,
        val curFreqMHz: Int,
        val maxFreqMHz: Int,
        val governor: String
    )

    /** Read live frequency and governor for each policy. Returns a list of PolicyLiveInfo. */
    fun readLiveInfo(): List<PolicyLiveInfo> {
        val pols = getPolicies()
        if (pols.isEmpty()) return emptyList()

        // Build a single shell command to read all sysfs values at once
        val commands = pols.flatMap { p ->
            listOf(
                "cat ${p.path}/scaling_cur_freq",
                "cat ${p.path}/scaling_max_freq",
                "cat ${p.path}/scaling_governor"
            )
        }
        val result = Shell.cmd(*commands.toTypedArray()).exec().out
        // Each policy produces 3 lines: cur_freq, max_freq, governor
        return pols.mapIndexed { idx, policy ->
            val base = idx * 3
            val curKHz = result.getOrNull(base)?.trim()?.toIntOrNull() ?: 0
            val maxKHz = result.getOrNull(base + 1)?.trim()?.toIntOrNull() ?: policy.maxFreqKHz
            val gov = result.getOrNull(base + 2)?.trim() ?: "unknown"
            val name = policy.path.substringAfterLast("/")
            PolicyLiveInfo(
                name = name,
                curFreqMHz = curKHz / 1000,
                maxFreqMHz = maxKHz / 1000,
                governor = gov
            )
        }
    }
}
