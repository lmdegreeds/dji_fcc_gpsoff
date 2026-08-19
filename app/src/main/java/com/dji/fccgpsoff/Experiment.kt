package com.dji.fccgpsoff

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Frame-subset harness for finding the MINIMAL FCC sequence (2026-08-19).
 *
 * Runs against `fcc_full.json` — FreeFCC's original 21 frames — NOT the shipped
 * `fcc.json`, which those experiments have since cut down to a single frame. The
 * harness has to keep addressing all 21 or it can no longer reproduce, or
 * challenge, the result that shrank the profile.
 *
 * The full profile plays 21 frames twice, and nobody knew which of them do the work.
 * Two effects are visible in DJI Fly and are scored separately by eye: transmit
 * power and the 5.8 GHz band. This harness plays an arbitrary SUBSET of the
 * profile so a group of frames can be dropped and the effect re-checked.
 *
 * ## Why the verdict is recorded here and not in a notebook
 *
 * An experiment costs an aircraft reboot plus a five-shot series, and the answer
 * arrives minutes later from a human looking at a screen. Between the run and the
 * verdict the operator power-cycles hardware, so the subset that was actually
 * played has to survive in a file, not in anyone's memory. [verdict] writes the
 * scored run to `experiments.log` in filesDir; [log] prints the table.
 *
 * ## Why a negative result is weak
 *
 * A five-shot series misses on its own roughly once in six runs (doc/fcc-autoapply
 * -tests.md, twelve runs, two misses — one of them over eleven applies). So a
 * subset that fails ONCE proves nothing; three independent runs put a false
 * "does not work" near half a percent. The harness therefore records runs, not
 * conclusions, and the same subset is expected to appear three times.
 */
object Experiment {

    /** FreeFCC's original 21 frames. The shipped `fcc.json` is now one frame. */
    const val FULL_PROFILE = "fcc_full.json"

    /**
     * Which asset a run plays. Defaults to the 21-frame original; `ce_restore.json`
     * exists so a persistence run can be reset WITHOUT power-cycling the aircraft —
     * if CE takes effect live, which its own note doubts and which is worth testing,
     * since the identical claim about FCC ("takes effect after a reboot") turned out
     * to be wrong.
     */
    private fun asset(name: String?): String = when {
        name.isNullOrBlank() -> FULL_PROFILE
        name.startsWith(COUNTRY_PREFIX) -> name          // synthetic, never loaded from assets
        name.endsWith(".json") -> name
        else -> "$name.json"
    }

    /**
     * `profile=country:XX` — the one frame the whole FCC switch turned out to be,
     * with an arbitrary two-letter radio country instead of the hard-coded AU.
     *
     * `fcc.json` writes `07:30` with ASCII "AU" twice (2.4 and 5.8 channel groups).
     * Since that single frame is the switch, the same frame with another code is its
     * inverse — which is what a persistence run needs: a way back to CE that does not
     * cost an aircraft power cycle. Synthetic rather than an asset per country: the
     * codes the firmware knows are a short list (AU, CN, US, BO, RU, NL, MY) and each
     * would otherwise be a near-identical file.
     */
    private const val COUNTRY_PREFIX = "country:"

    private fun countryProfile(code: String): ProfileRunner.Profile {
        val c = code.trim().uppercase()
        require(c.length == 2 && c.all { it in 'A'..'Z' }) { "country must be two A-Z letters, got '$code'" }
        val a0 = c[0].code.toByte(); val a1 = c[1].code.toByte()
        // Same layout as fcc.json frame 6: code, two pad bytes, code again, two pad,
        // then 01 00. Both groups carry the same code there, so both carry it here.
        val payload = byteArrayOf(a0, a1, 0, 0, a0, a1, 0, 0, 1, 0)
        return ProfileRunner.Profile(
            name = "COUNTRY $c", port = DumlWire.PORT_FCC, wrapper = false, needsResponse = false,
            rounds = 1, interFrameMs = 30, interRoundMs = 1000, readWindowMs = 50,
            frames = listOf(ProfileRunner.Frame(
                cmdSet = 0x07, cmdId = 0x30, dst = 9, payload = payload,
                sender = DumlWire.SENDER_APP4, cmdType = DumlWire.CT_ACK_BEFORE,
                note = "radio country -> $c")),
        )
    }

    private fun profileOf(ctx: Context, name: String?): ProfileRunner.Profile {
        val a = asset(name)
        return if (a.startsWith(COUNTRY_PREFIX)) countryProfile(a.removePrefix(COUNTRY_PREFIX))
               else ProfileRunner(ctx).load(a)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** The subset of the current/last series, so a verdict lands on the right run. */
    @Volatile private var current: Run? = null

    data class Run(
        val label: String,
        val keep: List<Int>,
        val count: Int,
        val gapSec: Int,
        val rounds: Int,
        val withReg: Boolean,
        val startedMs: Long,
        var sentOk: Int = 0,
        var sentFail: Int = 0,
        var done: Boolean = false,
        var cancelled: Boolean = false,
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "experiments.log")

    /** Frames of the full profile with the index that `keep=`/`drop=` address. */
    fun frames(ctx: Context, profile: String? = null): String {
        val p = profileOf(ctx, profile)
        val sb = StringBuilder("${p.name}: ${p.frames.size} frames, rounds=${p.rounds}, port=${p.port}\n")
        p.frames.forEachIndexed { i, fr ->
            sb.append("%2d  %02X:%02X d=%-3d %-22s %s\n".format(
                i, fr.cmdSet, fr.cmdId, fr.dst, DumlWire.toHex(fr.payload), fr.note))
        }
        sb.append("\nplus (outside the profile): ce_regulatory_level=01 write — `reg=1` adds it.\n")
        sb.append("Measured 2026-08-19 it does NOT stick (reads back ff either way), so Apply\n")
        sb.append("FCC no longer sends it and `reg` defaults to 0 here as well.\n")
        return sb.toString()
    }

    /**
     * Parse `keep=0,1,8` / `drop=3,4` into the indices to play. `drop` is applied
     * to the full profile; giving neither plays everything. An index outside the
     * profile is an error rather than a silent no-op — a typo in a subset would
     * otherwise be scored as a real result.
     */
    fun select(ctx: Context, keep: String?, drop: String?, profile: String? = null): List<Int> {
        val n = profileOf(ctx, profile).frames.size
        fun parse(s: String) = s.split(',').filter { it.isNotBlank() }.map {
            val v = it.trim().toIntOrNull() ?: throw IllegalArgumentException("not a frame index: '$it'")
            require(v in 0 until n) { "frame index $v out of range 0..${n - 1}" }
            v
        }
        return when {
            keep != null -> parse(keep).distinct().sorted()
            drop != null -> { val d = parse(drop).toSet(); (0 until n).filter { it !in d } }
            else -> (0 until n).toList()
        }
    }

    /**
     * Play the full profile restricted to [keep], under ONE port lease so the subset is
     * atomic against another of our sessions — same shape as [Features.applyFcc],
     * which this deliberately mirrors rather than calls: the point of the harness
     * is to vary what applyFcc holds fixed.
     */
    suspend fun applySubset(ctx: Context, keep: List<Int>, rounds: Int, withReg: Boolean, profile: String? = null): Boolean {
        val runner = ProfileRunner(ctx)
        val full = profileOf(ctx, profile)
        val sub = full.copy(
            name = "${asset(profile).removeSuffix(".json").removePrefix(COUNTRY_PREFIX)}[${keep.joinToString(",")}]",
            rounds = rounds,
            frames = full.frames.filterIndexed { i, _ -> i in keep },
        )
        if (sub.frames.isEmpty() && !withReg) {
            DiagLog.warn("experiment: empty subset and reg=0 — nothing to send")
            return false
        }
        val lease = PortSessionLock.acquire(DumlWire.PORT_FCC, timeoutMs = 300) ?: run {
            DiagLog.warn("experiment: another apply is already running — skipped"); return false
        }
        return try {
            val framesOk = if (sub.frames.isEmpty()) true else runner.run(sub, alreadyLeased = true).sent
            val regOk = if (!withReg) true else
                ParameterAddress.REGULATORY.write(byteArrayOf(1), port = DumlWire.PORT_FCC, wrapped = false)
            val ok = framesOk && regOk
            if (!ok) DiagLog.warn("experiment: incomplete send (frames=$framesOk, regulatory=$regOk)")
            ok
        } finally { lease.close() }
    }

    /** Start a series of [count] applies of the subset, [gapSec] apart. */
    fun start(ctx: Context, label: String, keep: List<Int>, count: Int, gapSec: Int,
              rounds: Int, withReg: Boolean, profile: String? = null): String {
        if (job?.isActive == true) return "busy: a series is already running — /exp/cancel first"
        val run = Run(label, keep, count, gapSec, rounds, withReg, System.currentTimeMillis())
        current = run
        DiagLog.info("experiment '$label': ${asset(profile)} ${keep.size} frames [${keep.joinToString(",")}], " +
                     "rounds=$rounds reg=${if (withReg) 1 else 0}, $count applies every ${gapSec}s")
        job = scope.launch {
            repeat(count) { k ->
                if (!isActive) { run.cancelled = true; return@launch }
                val ok = runCatching { applySubset(ctx, keep, rounds, withReg, profile) }.getOrDefault(false)
                if (ok) run.sentOk++ else run.sentFail++
                DiagLog.info("experiment '$label': apply ${k + 1}/$count ${if (ok) "sent" else "FAILED"}")
                if (k < count - 1) delay(gapSec * 1000L)
            }
            run.done = true
            DiagLog.info("experiment '$label': series done — ${run.sentOk} sent, ${run.sentFail} failed. " +
                         "Score it with /exp/verdict?power=0|1&r58=0|1")
        }
        return "started '$label': ${count} applies of ${keep.size} frames [${keep.joinToString(",")}] " +
               "every ${gapSec}s, rounds=$rounds, reg=${if (withReg) 1 else 0}\n" +
               "when the series ends, look at DJI Fly and score it:\n" +
               "  /exp/verdict?power=1&r58=1   (1 = present, 0 = absent)"
    }

    fun cancel(): String {
        val j = job ?: return "no series"
        return if (j.isActive) { j.cancel(); current?.cancelled = true; "series cancelled" } else "series already finished"
    }

    fun status(): String {
        val r = current ?: return "no experiment yet"
        val state = when { r.cancelled -> "cancelled"; r.done -> "done"; else -> "running" }
        return "'${r.label}' $state — ${r.sentOk + r.sentFail}/${r.count} applies " +
               "(${r.sentOk} sent, ${r.sentFail} failed), frames [${r.keep.joinToString(",")}], " +
               "rounds=${r.rounds}, reg=${if (r.withReg) 1 else 0}" +
               if (r.done && !r.cancelled) "\nscore it: /exp/verdict?power=0|1&r58=0|1" else ""
    }

    /**
     * Record the operator's visual verdict for the current subset. Scored by eye in
     * DJI Fly: [power] = transmit power raised, [r58] = 5.8 GHz present.
     */
    fun verdict(ctx: Context, power: Boolean, r58: Boolean, note: String?): String {
        val r = current ?: return "no experiment to score"
        val line = listOf(
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
            r.label,
            "[" + r.keep.joinToString(",") + "]",
            "n=${r.keep.size}",
            "rounds=${r.rounds}",
            "reg=${if (r.withReg) 1 else 0}",
            "applies=${r.sentOk}/${r.count}",
            "power=${if (power) 1 else 0}",
            "r58=${if (r58) 1 else 0}",
            note?.let { "note=$it" } ?: "",
        ).filter { it.isNotEmpty() }.joinToString("\t")
        runCatching { file(ctx).appendText(line + "\n") }
            .onFailure { return "could not write experiments.log: ${it.message}" }
        DiagLog.info("experiment '${r.label}': scored power=$power r58=$r58")
        return "recorded:\n$line"
    }

    fun log(ctx: Context): String {
        val f = file(ctx)
        if (!f.exists()) return "no experiments recorded yet"
        val body = runCatching { f.readText() }.getOrElse { return "read error: ${it.message}" }
        return "date\tlabel\tframes\t...\tpower\tr58\n$body"
    }
}
