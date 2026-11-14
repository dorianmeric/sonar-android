package com.echosonar.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Device pose used as the sonar's sweep angle when the user rotates the phone by hand.
 *
 * A rotation-vector sensor is preferred (absolute, drift free). Without it the sweep falls back to an
 * integrated gyroscope, and without both the UI keeps AUTO sweep but says so plainly.
 *
 * The bearing itself comes from [PoseMath], not from `SensorManager.getOrientation()[0]`: the latter
 * reports the heading of the *top edge*, which points at the sky in the intended upright pose and is
 * therefore meaningless there. [PoseMath] uses the back of the phone instead, which stays horizontal
 * while the phone is upright, and reports the tilt so the UI can tell the user when they drift away
 * from that pose.
 */
class OrientationTracker(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val rotationMatrix = FloatArray(9)

    /** Sweep angle in radar-canvas convention (0 = right, -pi/2 = up the screen). */
    @Volatile
    var yawRad: Float = 0f
        private set

    @Volatile
    var rotationSpeedDegPerSec: Float = 0f
        private set

    /** 0° = perfectly upright, 90° = flat on a table. Only meaningful when [poseKnown]. */
    @Volatile
    var tiltDeg: Float = 0f
        private set

    /** False when only the gyroscope is available, so tilt and absolute bearing are unknown. */
    @Volatile
    var poseKnown: Boolean = false
        private set

    /** True when the top edge points down, i.e. speaker and microphone are swapped. */
    @Volatile
    var upsideDown: Boolean = false
        private set

    @Volatile
    var hasAbsoluteHeading: Boolean = rotationSensor != null
        private set

    @Volatile
    var listening: Boolean = false
        private set

    private var lastTimestampNs = 0L
    private var lastBearing = 0f
    private var gyroYaw = 0f
    private var ema = 0f
    private var initialised = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val bearing = PoseMath.sweepAngleRad(rotationMatrix)
                    tiltDeg = PoseMath.tiltFromVerticalDeg(rotationMatrix)
                    upsideDown = PoseMath.isUpsideDown(rotationMatrix)
                    poseKnown = true

                    if (!initialised) {
                        ema = bearing
                        lastBearing = bearing
                        lastTimestampNs = event.timestamp
                        initialised = true
                        yawRad = bearing
                        return
                    }

                    val dt = if (lastTimestampNs > 0L) (event.timestamp - lastTimestampNs) / 1e9f else 0f
                    if (dt > 0f && dt < 1f) {
                        val delta = PoseMath.shortestArc(lastBearing, bearing)
                        val smoothing = 0.12f
                        ema = PoseMath.wrap(ema + delta * smoothing)
                        rotationSpeedDegPerSec = 0.8f * rotationSpeedDegPerSec +
                            0.2f * Math.toDegrees((abs(delta) / dt).toDouble()).toFloat()
                        lastBearing = bearing
                        lastTimestampNs = event.timestamp
                        yawRad = ema
                    }
                }

                Sensor.TYPE_GYROSCOPE -> {
                    if (!hasAbsoluteHeading) {
                        val dt = if (lastTimestampNs > 0L) (event.timestamp - lastTimestampNs) / 1e9f else 0f
                        if (dt > 0f && dt < 1f) {
                            // Device-frame z rotation; the sign matches the canvas convention.
                            gyroYaw = PoseMath.wrap(gyroYaw - event.values[2] * dt)
                            rotationSpeedDegPerSec = 0.8f * rotationSpeedDegPerSec +
                                0.2f * Math.toDegrees(abs(event.values[2].toDouble())).toFloat()
                            yawRad = gyroYaw
                        }
                        lastTimestampNs = event.timestamp
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(): Boolean {
        if (listening) return true
        val sensor = rotationSensor ?: gyroSensor ?: return false
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        listening = true
        if (rotationSensor == null) hasAbsoluteHeading = false
        return true
    }

    fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(listener)
        listening = false
        rotationSpeedDegPerSec = 0f
    }

    fun resetHeading(radians: Float = 0f) {
        ema = radians
        gyroYaw = radians
        lastBearing = radians
        yawRad = radians
    }

    companion object {
        fun wrap(radians: Float): Float = PoseMath.wrap(radians)

        fun shortestArc(from: Float, to: Float): Float = PoseMath.shortestArc(from, to)
    }
}