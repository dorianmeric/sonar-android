package com.echosonar.sensors

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pose maths extracted from the Android sensor plumbing so it can be reasoned about and unit tested.
 *
 * Android's rotation matrix maps device coordinates to world coordinates (`world = R * device`), with
 * row-major storage and world axes X=east, Y=north, Z=up. Columns therefore hold the device axes in
 * world space:
 *
 *  - column 0 = device +X, the right edge
 *  - column 1 = device +Y, the top edge (where the microphone usually sits)
 *  - column 2 = device +Z, straight out of the screen (the back of the phone is -Z)
 *
 * Why this file exists: `SensorManager.getOrientation()[0]` reports the heading of the *top edge*.
 * That is fine for a phone lying flat, but the sonar is used with the phone held upright, where the
 * top edge points at the sky and its horizontal projection collapses to nothing — the heading becomes
 * numerically meaningless and flips sign depending on whether the user leans the phone towards or away
 * from themselves. The bearing below is taken from the *back of the phone* instead, which is horizontal
 * and stable in the upright pose, and is the direction the user is facing.
 */
object PoseMath {

    /** Beyond this the bearing gets noisy and floor/ceiling starts leaking into the map. */
    const val TILT_WARN_DEG = 25f

    /** 0° = perfectly upright (top edge vertical), 90° = flat on a table. */
    fun tiltFromVerticalDeg(r: FloatArray): Float {
        require(r.size == 9) { "rotation matrix must have 9 elements" }
        val topEdgeUp = abs(r[7]).coerceIn(0f, 1f)
        return Math.toDegrees(acos(topEdgeUp.toDouble())).toFloat()
    }

    /** True when the phone is held with its top edge pointing down (speaker and mic swapped). */
    fun isUpsideDown(r: FloatArray): Boolean {
        require(r.size == 9) { "rotation matrix must have 9 elements" }
        return r[7] < -0.5f
    }

    /**
     * Sweep angle in radar-canvas convention: 0 rad points right, +pi/2 points down the screen.
     *
     * The reference direction is where the **back** of the phone points — i.e. straight ahead when the
     * screen faces the user. That vector stays horizontal while the phone is upright. When the phone is
     * laid flat the back points at the sky, so the top-edge direction is used instead; both projections
     * lie on the same line through the middle of that transition, so the choice never jumps.
     *
     * The returned angle is offset so that "straight ahead" is drawn at the top of the radar, which is
     * how a plan-view map is normally read.
     */
    fun sweepAngleRad(r: FloatArray): Float {
        require(r.size == 9) { "rotation matrix must have 9 elements" }

        val backEast = -r[2]
        val backNorth = -r[5]
        val backHorizontal = hypot(backEast, backNorth)

        val topEast = r[1]
        val topNorth = r[4]
        val topHorizontal = hypot(topEast, topNorth)

        if (backHorizontal < 1e-4f && topHorizontal < 1e-4f) return 0f

        val useBack = backHorizontal >= topHorizontal
        val east = if (useBack) backEast else topEast
        val north = if (useBack) backNorth else topNorth

        // atan2(east, north) is the compass-style heading (clockwise from north).
        val heading = atan2(east, north)
        return wrap(heading - (PI / 2).toFloat())
    }

    /** True when the stable "back of the phone" reference is the one being used. */
    fun usesBackReference(r: FloatArray): Boolean {
        require(r.size == 9) { "rotation matrix must have 9 elements" }
        return hypot(-r[2], -r[5]) >= hypot(r[1], r[4])
    }

    fun wrap(radians: Float): Float {
        var value = radians % (2f * PI.toFloat())
        if (value < 0f) value += 2f * PI.toFloat()
        return value
    }

    /** Signed shortest rotation from [from] to [to], in (-pi, pi]. */
    fun shortestArc(from: Float, to: Float): Float {
        val twoPi = 2f * PI.toFloat()
        var delta = (to - from) % twoPi
        if (delta > PI.toFloat()) delta -= twoPi
        if (delta < -PI.toFloat()) delta += twoPi
        return delta
    }
}