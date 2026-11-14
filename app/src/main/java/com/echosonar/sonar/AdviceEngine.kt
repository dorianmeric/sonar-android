package com.echosonar.sonar

import com.echosonar.dsp.SonarConfig
import com.echosonar.sensors.PoseMath

enum class AdviceSeverity { GOOD, INFO, HINT, WARN, CRITICAL }

data class Advice(
    val id: String,
    val severity: AdviceSeverity,
    val title: String,
    val detail: String,
    val action: String? = null,
)

/**
 * Rule-based coach. Every rule is a concrete, actionable nudge derived from measurements, not from
 * guesswork: coupling strength, in-band noise floor, echo prominence, rotation rate and map coverage.
 *
 * Rules are rate limited per id so the log stays readable instead of scrolling the same warning.
 */
class AdviceEngine(private val defaultCooldownMs: Long = 9_000L) {

    private val lastIssued = HashMap<String, Long>()

    fun reset() = lastIssued.clear()

    fun evaluate(
        nowMs: Long,
        stats: SessionStats,
        config: SonarConfig,
        map: MapSnapshot,
        rotateMode: Boolean,
        audioError: String? = null,
    ): List<Advice> {
        val candidates = ArrayList<Advice>(8)

        if (audioError != null) {
            candidates += Advice(
                id = "audio-error",
                severity = AdviceSeverity.CRITICAL,
                title = "Audio engine failed",
                detail = audioError,
                action = "Stop, close other audio apps, then press START again.",
            )
        }

        if (stats.pingCount >= 6 && stats.unlockRatio > 0.5f) {
            candidates += Advice(
                id = "timing-unlocked",
                severity = AdviceSeverity.CRITICAL,
                title = "Cannot lock the echo timing",
                detail = "The speaker-to-microphone reference pulse is too weak to calibrate ranges. " +
                    "Echoes cannot be measured until it returns.",
                action = "Remove the case, raise media volume to ~80 %, and hold the phone so the bottom speaker is unobstructed.",
            )
        }

        if (stats.pingCount >= 5 && stats.clippingRatio > 0.08f) {
            candidates += Advice(
                id = "clipping",
                severity = AdviceSeverity.WARN,
                title = "Microphone is clipping",
                detail = "Input hit full scale on ${(stats.clippingRatio * 100).toInt()} % of pings, which smears the echo returns.",
                action = "Lower media volume a notch or reduce Sensitivity gain in TUNE.",
            )
        }

        if (stats.pingCount >= 4 && stats.emaDirectAmp < 0.015f) {
            candidates += Advice(
                id = "weak-coupling",
                severity = AdviceSeverity.WARN,
                title = "Very weak speaker→mic coupling",
                detail = "The phone barely hears its own chirp, so echo levels are unreliable.",
                action = "Turn media volume up, take the case off, and avoid covering either end of the phone.",
            )
        }

        if (stats.pingCount >= 8 && stats.echoPingCount == 0) {
            candidates += Advice(
                id = "no-echoes",
                severity = AdviceSeverity.WARN,
                title = "No echoes above the threshold",
                detail = "Either there is no hard surface in front of the phone, or the returns are too quiet. " +
                    "The speaker and microphone sit in the top and bottom edges, so a wall anywhere in front works.",
                action = "Stand 1–3 m from a bare wall, keep the top and bottom edges uncovered, and try a tiled or empty room.",
            )
        }

        if (stats.echoPingCount > 0 && stats.lastNearestRangeM in 0.01f..0.6f) {
            candidates += Advice(
                id = "too-close",
                severity = AdviceSeverity.HINT,
                title = "Nearest surface is very close",
                detail = "At ${"%.2f".format(stats.lastNearestRangeM)} m the echo overlaps the transmit burst and range accuracy drops.",
                action = "Step back so the nearest wall is about 1–2 m away.",
            )
        }

        if (stats.pingCount >= 10 && stats.emaNoiseFloorDb > -42f && stats.unlockRatio < 0.4f && stats.emaDirectAmp > 0.01f) {
            candidates += Advice(
                id = "in-band-noise",
                severity = AdviceSeverity.WARN,
                title = "Lots of energy in the 18–21 kHz band",
                detail = "The ultrasonic noise floor is only ${"%.0f".format(stats.emaNoiseFloorDb)} dB below the coupling peak.",
                action = "Move away from fans, air-conditioners, aquarium pumps or another running sonar app.",
            )
        }

        if (stats.pingCount >= 12 && stats.emaQuality in 8f..42f && stats.echoPingCount > 0 && stats.unlockRatio < 0.5f) {
            candidates += Advice(
                id = "low-quality",
                severity = AdviceSeverity.HINT,
                title = "Echoes are weak but present",
                detail = "Signal quality is ${stats.emaQuality.toInt()}/100. A longer chirp or more gain would sharpen the map.",
                action = "In TUNE set chirp length to 20 ms and raise Gain by 3 dB.",
            )
        }

        if (stats.pingCount >= 12 && config.chirpMs <= 10 && stats.emaQuality < 45f) {
            candidates += Advice(
                id = "try-longer-chirp",
                severity = AdviceSeverity.INFO,
                title = "Try a longer chirp",
                detail = "A ${config.chirpMs} ms chirp is short. Doubling it adds ~3 dB of echo energy without changing the ping rate much.",
                action = "TUNE → Chirp length → 20 ms.",
            )
        }

        if (stats.pingCount >= 12 && config.centerHz >= 19_500f && stats.emaQuality < 55f) {
            candidates += Advice(
                id = "try-lower-frequency",
                severity = AdviceSeverity.INFO,
                title = "Your phone may roll off above 19 kHz",
                detail = "Most smartphone speakers fade above ~19.5 kHz, and cases attenuate it further.",
                action = "TUNE → Frequency probe, then use whatever centre frequency it reports as strongest.",
            )
        }

        if (stats.pingCount >= 10 && config.thresholdDb > 18f && stats.detectedRate < 0.15f) {
            candidates += Advice(
                id = "sensitivity-too-low",
                severity = AdviceSeverity.INFO,
                title = "Sensitivity is set very low",
                detail = "Only echoes ${config.thresholdDb.toInt()} dB above the local floor are drawn.",
                action = "Drag the sensitivity slider toward MORE until faint returns appear.",
            )
        }

        if (rotateMode && stats.poseKnown && stats.pingCount >= 6 && stats.tiltDeg > PoseMath.TILT_WARN_DEG) {
            candidates += Advice(
                id = "pose-tilt",
                severity = AdviceSeverity.WARN,
                title = "Hold the phone more upright",
                detail = "It is ${stats.tiltDeg.toInt()}° away from vertical. The radar's forward direction is the " +
                    "direction the BACK of the phone points, and that reference gets noisy as you tilt — the " +
                    "floor and ceiling also start leaking into the map.",
                action = "Hold it like a portrait photo: top edge up, screen towards you, within ~20° of vertical, " +
                    "at chest height.",
            )
        }

        if (rotateMode && stats.poseKnown && stats.upsideDown && stats.pingCount >= 6) {
            candidates += Advice(
                id = "pose-upside-down",
                severity = AdviceSeverity.INFO,
                title = "Phone is upside down",
                detail = "The top edge is pointing down, so the speaker and microphone are swapped relative to " +
                    "the TOP MIC / BOTTOM SPEAKER labels on the radar.",
                action = "Turn the phone so its top edge — the microphone end — points up.",
            )
        }

        if (rotateMode && stats.pingCount >= 10 && stats.rotationSpeedDeg > 75f) {
            candidates += Advice(
                id = "rotate-fast",
                severity = AdviceSeverity.WARN,
                title = "Turning too fast",
                detail = "At ${stats.rotationSpeedDeg.toInt()} °/s each ping smears across many degrees and the walls blur.",
                action = "Turn on the spot at roughly one full circle every 15–20 seconds.",
            )
        }

        if (rotateMode && stats.elapsedMs > 7_000L && stats.rotationSpeedDeg < 4f && map.coverage < 0.5f) {
            candidates += Advice(
                id = "rotate-idle",
                severity = AdviceSeverity.HINT,
                title = "Rotate to sweep the room",
                detail = "The map only fills in the directions you have pointed at. ${(map.coverage * 100).toInt()} % covered so far.",
                action = "Hold the phone upright — portrait, top edge up, screen towards you, within ~20° of " +
                    "vertical — at chest height, and turn on the spot.",
            )
        }

        if (!rotateMode && map.coverage > 0.85f && stats.elapsedMs > 20_000L) {
            candidates += Advice(
                id = "try-rotate-mode",
                severity = AdviceSeverity.INFO,
                title = "Synthetic sweep wrapped around",
                detail = "AUTO mode spreads each ping over a rotating angle; it cannot measure real bearing.",
                action = "Switch to ROTATE mode and turn the phone by hand for a genuinely oriented map.",
            )
        }

        if (stats.echoPingCount > 0 && stats.lastPeakRangeM > config.maxRangeM * 0.88f) {
            candidates += Advice(
                id = "range-limit",
                severity = AdviceSeverity.INFO,
                title = "Echoes reaching the range limit",
                detail = "Hits near ${"%0.1f".format(config.maxRangeM)} m are being cut off by the capture window.",
                action = "TUNE → Max range → raise it, or accept that soft rooms return little beyond it.",
            )
        }

        if (map.coverage > 0.45f && stats.emaQuality > 50f) {
            candidates += Advice(
                id = "map-growing",
                severity = AdviceSeverity.GOOD,
                title = "Room sketch is coming together",
                detail = "${(map.coverage * 100).toInt()} % of directions have at least one return; mean nearest surface ${"%.1f".format(map.meanRangeM)} m.",
                action = "Keep sweeping slowly to lock the far corners.",
            )
        }

        if (stats.pingCount >= 15 && stats.emaQuality > 68f && stats.detectedRate > 0.55f) {
            candidates += Advice(
                id = "good-signal",
                severity = AdviceSeverity.GOOD,
                title = "Excellent signal",
                detail = "Quality ${stats.emaQuality.toInt()}/100 with a ${"%.0f".format(stats.emaPeakSnrDb)} dB peak above the local floor.",
                action = "Nothing to fix — try a dark room and watch the phosphor build the outline.",
            )
        }

        if (candidates.isEmpty()) {
            candidates += Advice(
                id = "getting-started",
                severity = AdviceSeverity.INFO,
                title = "Listening for echoes",
                detail = "Each chirp is ${config.chirpMs} ms of ${config.centerHz.toInt()} Hz ultrasound, " +
                    "compressed to ${"%.0f".format(config.rangeResolutionM * 100)} cm range resolution.",
                action = "Hold still for a few pings, then start turning on the spot.",
            )
        }

        val now = nowMs
        return candidates
            .sortedByDescending { it.severity.ordinal }
            .filter { advice ->
                val last = lastIssued[advice.id]
                val cooldown = when (advice.severity) {
                    AdviceSeverity.GOOD -> 20_000L
                    AdviceSeverity.CRITICAL, AdviceSeverity.WARN -> defaultCooldownMs / 2
                    else -> defaultCooldownMs
                }
                last == null || now - last >= cooldown
            }
            .onEach { lastIssued[it.id] = now }
    }
}