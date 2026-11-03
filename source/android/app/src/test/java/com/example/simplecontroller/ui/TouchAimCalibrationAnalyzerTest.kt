package com.example.simplecontroller.ui

import com.example.simplecontroller.model.TouchAimCalibrationPass
import com.example.simplecontroller.model.TouchAimCalibrationQuality
import com.example.simplecontroller.model.TouchAimCalibrationState
import com.example.simplecontroller.model.TouchAimSensor
import com.example.simplecontroller.model.TouchAimSensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchAimCalibrationAnalyzerTest {
    @Test
    fun clearSizeSeparation_selectsSize() {
        assertSelected(TouchAimSensor.SIZE, separated(size = true))
    }

    @Test
    fun clearMajorSeparation_selectsTouchMajor() {
        assertSelected(TouchAimSensor.TOUCH_MAJOR, separated(major = true))
    }

    @Test
    fun clearMinorSeparation_selectsTouchMinor() {
        assertSelected(TouchAimSensor.TOUCH_MINOR, separated(minor = true))
    }

    @Test
    fun complementaryNoise_materialCombinationIsSelected() {
        val passes = mutableListOf<TouchAimCalibrationPass>()
        repeat(2) { repetition ->
            val aim = List(80) { index ->
                if (index % 40 < 20) sample(0.2f, 4.0f, 1f, index) else sample(4.0f, 0.2f, 1f, index)
            }
            val shoot = List(80) { index ->
                if (index % 40 < 20) sample(2.2f, 6.0f, 1f, index) else sample(6.0f, 2.2f, 1f, index)
            }
            passes += pass(TouchAimCalibrationState.AIM, repetition, aim)
            passes += pass(TouchAimCalibrationState.SHOOT, repetition, shoot)
        }
        val rec = success(passes)
        assertTrue("${rec.sensors.map { it.sensor }} ${rec.quality} false=${rec.falseActivationRate} missed=${rec.missedActivationRate} ${rec.explanation}", rec.sensors.size > 1)
        assertTrue(rec.sensors.map { it.sensor }.containsAll(listOf(TouchAimSensor.SIZE, TouchAimSensor.TOUCH_MAJOR)))
    }

    @Test
    fun differentRawUnits_areRobustlyNormalized() {
        val normal = success(separated(size = true, sizeScale = 1f))
        val scaled = success(separated(size = true, sizeScale = 1000f))
        assertEquals(normal.sensors.map { it.sensor }, scaled.sensors.map { it.sensor })
        assertEquals(normal.quality, scaled.quality)
    }

    @Test
    fun nearTie_prefersOneSensor() {
        assertEquals(1, success(separated(size = true, major = true)).sensors.size)
    }

    @Test
    fun movingShootDrop_marksMovementRisk() {
        val passes = mutableListOf<TouchAimCalibrationPass>()
        repeat(2) { repetition ->
            val aim = List(40) { index -> sample(1f + (index % 3) * .01f, 1f, 1f, index) }
            val shoot = List(40) { index ->
                val value = if (index < 20) 3f else .2f
                sample(value, 1f, 1f, index)
            }
            passes += pass(TouchAimCalibrationState.AIM, repetition, aim)
            passes += pass(TouchAimCalibrationState.SHOOT, repetition, shoot)
        }
        val rec = success(passes)
        assertTrue("${rec.quality} false=${rec.falseActivationRate} missed=${rec.missedActivationRate} ${rec.explanation}", rec.movementReducedReliability)
    }

    @Test
    fun repeatedPassReversal_isUnreliable() {
        val passes = listOf(
            pass(TouchAimCalibrationState.AIM, 0, constant(1f, 1f, 1f)),
            pass(TouchAimCalibrationState.SHOOT, 0, constant(3f, 3f, 3f)),
            pass(TouchAimCalibrationState.AIM, 1, constant(4f, 4f, 4f)),
            pass(TouchAimCalibrationState.SHOOT, 1, constant(2f, 2f, 2f))
        )
        val rec = success(passes)
        assertEquals(TouchAimCalibrationQuality.UNRELIABLE, rec.quality)
        assertTrue(rec.sensors.isEmpty())
    }

    @Test
    fun overlappingData_isReportedUnreliable() {
        val passes = mutableListOf<TouchAimCalibrationPass>()
        repeat(2) { repetition ->
            passes += pass(
                TouchAimCalibrationState.AIM,
                repetition,
                List(40) { sample((it % 10).toFloat(), (it % 7).toFloat(), (it % 5).toFloat(), it) }
            )
            passes += pass(
                TouchAimCalibrationState.SHOOT,
                repetition,
                List(40) { sample((it % 10 + .2f), (it % 7 + .2f), (it % 5 + .2f), it) }
            )
        }
        val rec = success(passes)
        assertEquals(TouchAimCalibrationQuality.UNRELIABLE, rec.quality)
        assertTrue(!rec.isUsable)
        assertTrue(rec.hasBestGuess)
        assertTrue(rec.shootOffThreshold < rec.shootOnThreshold)
    }

    @Test
    fun heldOutDistributionShift_isNotHiddenBySameDirectionMedians() {
        val passes = listOf(
            pass(TouchAimCalibrationState.AIM, 0, constant(1f, 1f, 1f)),
            pass(TouchAimCalibrationState.SHOOT, 0, constant(3f, 3f, 3f)),
            pass(TouchAimCalibrationState.AIM, 1, constant(10f, 10f, 10f)),
            pass(TouchAimCalibrationState.SHOOT, 1, constant(12f, 12f, 12f))
        )
        assertEquals(TouchAimCalibrationQuality.UNRELIABLE, success(passes).quality)
    }

    @Test
    fun nonFiniteSample_isRejectedAndScorerFailsClosed() {
        val malformed = separated(size = true).toMutableList()
        malformed[0] = malformed[0].copy(
            samples = malformed[0].samples.toMutableList().also {
                it[5] = it[5].copy(size = Float.NaN)
            }
        )
        assertTrue(TouchAimCalibrationAnalyzer.analyze(malformed) is TouchAimCalibrationAnalysis.Insufficient)
        assertTrue(
            TouchAimCalibrationAnalyzer.score(
                sample(Float.NaN, 1f, 1f, 0),
                listOf(com.example.simplecontroller.model.TouchAimSensorTransform(TouchAimSensor.SIZE, 0f, 1f))
            ).isNaN()
        )
    }

    @Test
    fun repeatedAnalysis_isDeterministic() {
        val passes = separated(size = true)
        assertEquals(success(passes), success(passes))
    }

    @Test
    fun insufficientOrInterruptedRecordings_areRejected() {
        val onePair = listOf(
            pass(TouchAimCalibrationState.AIM, 0, constant(1f, 1f, 1f)),
            pass(TouchAimCalibrationState.SHOOT, 0, constant(2f, 2f, 2f))
        )
        assertTrue(TouchAimCalibrationAnalyzer.analyze(onePair) is TouchAimCalibrationAnalysis.Insufficient)

        val interrupted = separated(size = true).mapIndexed { index, pass ->
            if (index == 0) pass.copy(completed = false) else pass
        }
        assertTrue(TouchAimCalibrationAnalyzer.analyze(interrupted) is TouchAimCalibrationAnalysis.Insufficient)

        val noMovement = separated(size = true).map { pass ->
            pass.copy(samples = pass.samples.map { it.copy(moving = false) })
        }
        assertTrue(TouchAimCalibrationAnalyzer.analyze(noMovement) is TouchAimCalibrationAnalysis.Insufficient)
    }

    @Test
    fun delayedShootActivation_cannotBeRatedGood() {
        val passes = buildList {
            repeat(2) { repetition ->
                add(pass(TouchAimCalibrationState.AIM, repetition, constant(1f, 1f, 1f)))
                add(pass(TouchAimCalibrationState.SHOOT, repetition, List(40) { index ->
                    sample(if (index < 20) 1f else 3f, 1f, 1f, index)
                }))
            }
        }
        assertTrue(success(passes).quality != TouchAimCalibrationQuality.GOOD)
    }

    private fun assertSelected(sensor: TouchAimSensor, passes: List<TouchAimCalibrationPass>) {
        val rec = success(passes)
        assertEquals(listOf(sensor), rec.sensors.map { it.sensor })
        assertTrue(rec.quality != TouchAimCalibrationQuality.UNRELIABLE)
    }

    private fun separated(
        size: Boolean = false,
        major: Boolean = false,
        minor: Boolean = false,
        sizeScale: Float = 1f
    ): List<TouchAimCalibrationPass> = buildList {
        repeat(2) { repetition ->
            val offset = repetition * .02f
            add(pass(TouchAimCalibrationState.AIM, repetition, List(40) { index ->
                sample(
                    (if (size) 1f + (index % 4) * .01f else 1f + (index % 10) * .15f) * sizeScale,
                    if (major) 2f + (index % 4) * .02f else 2f + (index % 10) * .3f,
                    if (minor) 1.5f + (index % 4) * .02f else 1.5f + (index % 10) * .22f,
                    index,
                    offset
                )
            }))
            add(pass(TouchAimCalibrationState.SHOOT, repetition, List(40) { index ->
                sample(
                    (if (size) 4f + (index % 4) * .01f else 1.2f + (index % 10) * .15f) * sizeScale,
                    if (major) 8f + (index % 4) * .02f else 2.3f + (index % 10) * .3f,
                    if (minor) 6f + (index % 4) * .02f else 1.75f + (index % 10) * .22f,
                    index,
                    offset
                )
            }))
        }
    }

    private fun pass(
        state: TouchAimCalibrationState,
        repetition: Int,
        samples: List<TouchAimSensorSample>
    ) = TouchAimCalibrationPass(state, repetition, samples)

    private fun constant(size: Float, major: Float, minor: Float) =
        List(40) { sample(size, major, minor, it) }

    private fun sample(
        size: Float,
        major: Float,
        minor: Float,
        index: Int,
        offset: Float = 0f
    ) = TouchAimSensorSample(
        size + offset,
        major + offset,
        minor + offset,
        moving = index >= 20,
        elapsedMs = index * 16L
    )

    private fun success(passes: List<TouchAimCalibrationPass>) =
        (TouchAimCalibrationAnalyzer.analyze(passes) as TouchAimCalibrationAnalysis.Success).recommendation
}
