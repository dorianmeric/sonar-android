package com.echosonar.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Device heading used as the sonar's sweep angle when the user rotates the phone by hand.
 *
 * A rotation-vector sensor is used when present (absolute, drift free). Without it the sweep falls
 * back to an integrated gyroscope, and without both the UI keeps AUTO sweep but says so plainly.
 */
class OrientationTracker(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile
    var yawRad: Float = 0f
        private set

    @Volatile
    var rotationSpeedDegPerSec: Float = 0f
        private set

    @Volatile
    var hasAbsoluteHeading: Boolean = rotationSensor != null
        private set

    @Volatile
    var listening: Boolean = false
        private set

    private var lastTimestampNs = 0L
    private var lastYaw = 0f
    private var gyroYaw = 0f
    private var ema = 0f
    private var initialised = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    var yaw = orientation[0]
                    if (!initialised) {
                        ema = yaw
                        lastYaw = yaw
                        lastTimestampNs = event.timestamp
                        initialised = true
                        yawRad = yaw
                        return
                    }
                    val dt = if (lastTimestampNs > 0L) (event.timestamp - lastTimestampNs) / 1e9f else 0f
                    if (dt > 0f && dt < 1f) {
                        val delta = shortestArc(lastYaw, yaw)
                        val smoothing = 0.12f
                        ema = wrap(ema + (delta * smoothing))
                        rotationSpeedDegPerSec = 0.8f * rotationSpeedDegPerSec +
                            0.2f * (Math.toDegrees((abs(delta) / dt).toDouble()).toFloat())
                        lastYaw = yaw
                        lastTimestampNs = event.timestamp
                        yawRad = ema
                    }
                }

                Sensor.TYPE_GYROSCOPE -> {
                    if (!hasAbsoluteHeading) {
                        val dt = if (lastTimestampNs > 0L) (event.timestamp - lastTimestampNs) / 1e9f else 0f
                        if (dt > 0f && dt < 1f) {
                            // z axis rotation in the device frame; sign chosen to match heading increases.
                            gyroYaw = wrap(gyroYaw - event.values[2] * dt)
                            rotationSpeedDegPerSec = 0.8f * rotationSpeedDegPerSec +
                                0.2f * (Math.toDegrees(abs(event.values[2].toDouble())).toFloat())
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
        lastYaw = radians
        yawRad = radians
    }

    /** Tilt of the phone away from vertical, in degrees (used for the "hold upright" hint). */
    fun tiltDegrees(event: SensorEvent): Float {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        return Math.toDegrees(atan2(sqrt((x * x + y * y).toDouble()), z.toDouble())).toFloat()
    }

    companion object {
        fun wrap(radians: Float): Float {
            var r = radians % (2f * Math.PI.toFloat())
            if (r < 0f) r += 2f * Math.PI.toFloat()
            return r
        }

        fun shortestArc(from: Float, to: Float): Float {
            val twoPi = 2f * Math.PI.toFloat()
            var delta = (to - from) % twoPi
            if (delta > Math.PI.toFloat()) delta -= twoPi
            if (delta < -Math.PI.toFloat()) delta += twoPi
            return delta
        }
    }
}