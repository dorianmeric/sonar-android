package com.echosonar.sonar

import com.echosonar.dsp.RangeProcessor
import com.echosonar.dsp.SonarConfig
import com.echosonar.dsp.SynthRoom
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * End-to-end pipeline check: synthesise one chirp capture per sweep angle from a room whose walls are
 * at different distances in different directions, run the real RangeProcessor, accumulate the real
 * map, and recover the geometry. Also prints an ASCII unwrap of the map so the shape can be eyeballed
 * without a device.
 */
class RoomSimulationTest {

    @Test
    fun recoversRoomGeometryAcrossASweep() {
        val config = SonarConfig(temperatureC = 21f)
        val processor = RangeProcessor(config)
        val map = MapAccumulator(config.rangeBins, 72, config.minRangeM, config.rangeBinM)

        // Room: a close wall to the left, a far wall ahead, an opening to the right.
        fun wallRange(angle: Float): Float {
            val a = if (angle > PI.toFloat()) angle - 2f * PI.toFloat() else angle
            return when {
                a < -0.9f -> 1.2f
                a < 0.9f -> 2.6f
                else -> 3.6f
            }
        }

        val steps = 72
        for (step in 0 until steps) {
            val angle = step * (2f * PI.toFloat() / steps)
            val r = wallRange(angle)
            val capture = SynthRoom.capture(
                config = config,
                directDelayMs = 34f,
                directAmplitude = 0.55f,
                echoes = listOf(SynthRoom.Echo(r, 0.18f * (1.4f / r))),
                noiseRms = 0.0008f,
                seed = step.toLong() + 1L,
            )
            val result = processor.process(capture, angle, step * 320L, step.toLong())
            if (step % 18 == 0) {
                println(
                    "step=%d angle=%.2f wall=%.2f detected=%d peak=%.2f prominence=%.1f quality=%d".format(
                        step,
                        angle,
                        r,
                        result.metrics.detectedBins,
                        result.metrics.peakRangeM,
                        result.metrics.peakSnrDb,
                        result.metrics.quality,
                    ),
                )
            }
            map.ingest(result)
        }

        val snapshot = map.snapshot()
        println(unwrap(snapshot, config.rangeBins, 72))
        println("coverage=%.2f fill=%.3f mean=%.2fm".format(snapshot.coverage, snapshot.fill, snapshot.meanRangeM))

        assertTrue("coverage should be large", snapshot.coverage > 0.9f)
        // Check the recovered contour in three sectors.
        val checks = listOf(
            -1.4f to 1.2f,
            0f to 2.6f,
            2.4f to 3.6f,
        )
        for ((angle, expected) in checks) {
            val idx = map.angleToBin(angle)
            val found = snapshot.strongestRangeM[idx]
            assertTrue("angle $angle expected ~$expected got $found", abs(found - expected) < 0.2f)
        }
    }

    private fun unwrap(snapshot: MapSnapshot, rangeBins: Int, angleBins: Int): String {
        val sb = StringBuilder("\nPOLAR UNWRAP (rows = range, cols = bearing 0..360°)\n")
        val chars = charArrayOf(' ', '.', ':', '-', '=', '+', '*', '#', '@')
        for (bin in rangeBins - 1 downTo 0) {
            val rangeM = snapshot.startRangeM + bin * snapshot.binSizeM
            sb.append("%4.1f |".format(rangeM))
            for (a in 0 until angleBins) {
                val v = snapshot.cells[a * rangeBins + bin]
                val index = (v * (chars.size - 1)).toInt().coerceIn(0, chars.size - 1)
                sb.append(chars[index])
            }
            sb.append("|\n")
        }
        sb.append("     +")
        sb.append("-".repeat(angleBins))
        sb.append("+\n     0°")
        sb.append(" ".repeat(angleBins / 2 - 4))
        sb.append("180°")
        sb.append(" ".repeat(angleBins / 2 - 5))
        sb.append("360°")
        return sb.toString()
    }
}