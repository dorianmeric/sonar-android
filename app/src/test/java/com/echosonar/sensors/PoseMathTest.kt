package com.echosonar.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds rotation matrices for known poses and checks the bearing/tilt extraction.
 *
 * Pose helper: given a compass heading `h` (radians clockwise from north) and a tilt `t` (0 = upright,
 * 90° = flat away from the user), the device axes in world space are
 *   top edge   Y = ( sin t · sin h,  sin t · cos h,  cos t )
 *   back       -Z = ( cos t · sin h,  cos t · cos h, -sin t )
 *   right      X = Y × Z
 */
class PoseMathTest {

    private fun matrix(headingRad: Float, tiltDeg: Float): FloatArray {
        val h = headingRad
        val t = Math.toRadians(tiltDeg.toDouble())
        val yEast = (sin(t) * sin(h.toDouble())).toFloat()
        val yNorth = (sin(t) * cos(h.toDouble())).toFloat()
        val yUp = cos(t).toFloat()
        val backEast = (cos(t) * sin(h.toDouble())).toFloat()
        val backNorth = (cos(t) * cos(h.toDouble())).toFloat()
        val backUp = -sin(t).toFloat()

        val zEast = -backEast
        val zNorth = -backNorth
        val zUp = -backUp

        // X = Y x Z
        val xEast = yNorth * zUp - yUp * zNorth
        val xNorth = yUp * zEast - yEast * zUp
        val xUp = yEast * zNorth - yNorth * zEast

        return floatArrayOf(
            xEast, yEast, zEast,
            xNorth, yNorth, zNorth,
            xUp, yUp, zUp,
        )
    }

    private fun assertAngle(expected: Float, actual: Float, tolerance: Float = 0.02f) {
        val delta = PoseMath.shortestArc(expected, actual)
        assertEquals("expected $expected, got $actual", 0f, delta, tolerance)
    }

    @Test
    fun uprightFacingNorthPutsForwardAtTheTopOfTheRadar() {
        val r = matrix(headingRad = 0f, tiltDeg = 0f)
        assertEquals(0f, PoseMath.tiltFromVerticalDeg(r), 0.5f)
        // Radar canvas: -pi/2 is "up the screen".
        assertAngle((-PI / 2).toFloat(), PoseMath.sweepAngleRad(r))
        assertTrue(PoseMath.usesBackReference(r))
        assertFalse(PoseMath.isUpsideDown(r))
    }

    @Test
    fun turningClockwiseRotatesTheSweepClockwise() {
        val north = PoseMath.sweepAngleRad(matrix(0f, 5f))
        val east = PoseMath.sweepAngleRad(matrix((PI / 2).toFloat(), 5f))
        val delta = PoseMath.shortestArc(north, east)
        // +90° of heading must appear as +90° on the canvas (clockwise on screen).
        assertEquals((PI / 2), delta.toDouble(), 0.02)
    }

    @Test
    fun bearingDoesNotDependOnHowFarThePhoneLeans() {
        // Leaning back 5° or 20° must give the same bearing: only the strength of the reference changes.
        val shallow = PoseMath.sweepAngleRad(matrix(0.4f, 5f))
        val steep = PoseMath.sweepAngleRad(matrix(0.4f, 30f))
        assertAngle(shallow, steep, 0.01f)
    }

    /** Rotates a pose about its own forward (back-of-phone) axis, i.e. a roll of the wrist. */
    private fun rolled(base: FloatArray, rollRad: Double): FloatArray {
        fun ax(i: Int) = floatArrayOf(base[i], base[i + 3], base[i + 6])
        val x = ax(0)
        val y = ax(1)
        val c = cos(rollRad).toFloat()
        val s = sin(rollRad).toFloat()
        val newX = floatArrayOf(x[0] * c - y[0] * s, x[1] * c - y[1] * s, x[2] * c - y[2] * s)
        val newY = floatArrayOf(y[0] * c + x[0] * s, y[1] * c + x[1] * s, y[2] * c + x[2] * s)
        val z = ax(2)
        return floatArrayOf(
            newX[0], newY[0], z[0],
            newX[1], newY[1], z[1],
            newX[2], newY[2], z[2],
        )
    }

    @Test
    fun bearingSurvivesBeingHeldNearlyVertical() {
        // Held within a degree of vertical, a 0.3° wrist roll barely moves the back-of-phone reference,
        // but it swings the top-edge heading (what getOrientation()[0] reports) enormously.
        val steady = matrix(0.7f, 1f)
        val jittered = rolled(steady, Math.toRadians(0.3))

        assertAngle(PoseMath.sweepAngleRad(steady), PoseMath.sweepAngleRad(jittered), 0.02f)

        fun eulerAzimuth(r: FloatArray): Float = kotlin.math.atan2(r[1], r[4])
        val eulerDelta = PoseMath.shortestArc(eulerAzimuth(steady), eulerAzimuth(jittered))
        assertTrue(
            "Euler azimuth was expected to be unstable near vertical, got ${Math.toDegrees(eulerDelta.toDouble())}°",
            kotlin.math.abs(eulerDelta) > 0.2f,
        )
    }

    @Test
    fun flatPhoneFallsBackToTheTopEdge() {
        val r = matrix(headingRad = (PI / 2).toFloat(), tiltDeg = 90f)
        assertEquals(90f, PoseMath.tiltFromVerticalDeg(r), 1f)
        assertFalse(PoseMath.usesBackReference(r))
        // Flat with the top edge pointing east: east is heading +90°, drawn right of the radar after the
        // -90° offset, i.e. canvas angle 0.
        assertAngle(0f, PoseMath.sweepAngleRad(r), 0.05f)
    }

    @Test
    fun detectsUpsideDownHold() {
        // Roll the device 180° about the forward axis: top edge ends up pointing down.
        val upright = matrix(0f, 0f)
        val flipped = floatArrayOf(
            -upright[0], -upright[1], upright[2],
            -upright[3], -upright[4], upright[5],
            -upright[6], -upright[7], upright[8],
        )
        assertTrue(PoseMath.isUpsideDown(flipped))
        assertFalse(PoseMath.isUpsideDown(upright))
        // Flipping upside down must not mirror the bearing.
        assertAngle(PoseMath.sweepAngleRad(upright), PoseMath.sweepAngleRad(flipped), 0.05f)
    }

    @Test
    fun shortestArcHandlesWraparound() {
        val almost = (2f * PI).toFloat() - 0.05f
        assertEquals(0.1f, PoseMath.shortestArc(almost, 0.05f), 1e-4f)
        assertEquals(0f, PoseMath.shortestArc(1f, 1f), 1e-6f)
        assertEquals(0f, PoseMath.wrap(2f * PI.toFloat()), 1e-6f)
    }
}