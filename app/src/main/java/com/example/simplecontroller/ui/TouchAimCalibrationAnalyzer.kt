package com.example.simplecontroller.ui

import com.example.simplecontroller.model.*
import kotlin.math.abs
import kotlin.math.max

data class TouchAimCalibrationRecommendation(
    val sensors: List<TouchAimSensorTransform>,
    val quality: TouchAimCalibrationQuality,
    val shootOnThreshold: Float,
    val shootOffThreshold: Float,
    val smoothing: Float,
    val enterShootConfirmationMs: Long,
    val returnToAimConfirmationMs: Long,
    val falseActivationRate: Float,
    val missedActivationRate: Float,
    val movementReducedReliability: Boolean,
    val consistencyScore: Float,
    val passSummaries: List<TouchAimPassSummary>,
    val explanation: String
) {
    val isUsable: Boolean get() = quality != TouchAimCalibrationQuality.UNRELIABLE
    val hasBestGuess: Boolean get() = sensors.isNotEmpty() &&
        shootOnThreshold.isFinite() && shootOffThreshold.isFinite() &&
        shootOffThreshold < shootOnThreshold
}

sealed class TouchAimCalibrationAnalysis {
    data class Success(val recommendation: TouchAimCalibrationRecommendation) : TouchAimCalibrationAnalysis()
    data class Insufficient(val reason: String) : TouchAimCalibrationAnalysis()
}

/** Fits on one recording pair and validates the exact runtime detector on the other pair. */
object TouchAimCalibrationAnalyzer {
    private const val MIN_SAMPLES_PER_PASS = 12
    private const val MAX_FIT_SAMPLES_PER_PASS = 256

    private data class Tuning(val on: Float, val off: Float, val smoothing: Float, val enterMs: Long, val returnMs: Long)
    private data class Metrics(
        val errorRate: Float,
        val movingError: Float,
        val stillError: Float,
        val falseEntries: Int,
        val missedPass: Boolean,
        val unexpectedTransitions: Int,
        val activationLatencyMs: Long
    )
    private data class Fold(val tuning: Tuning, val aim: Metrics, val shoot: Metrics, val monotonic: Boolean)
    private data class Candidate(
        val sensorSet: Set<TouchAimSensor>,
        val transforms: List<TouchAimSensorTransform>,
        val folds: List<Fold>,
        val finalTuning: Tuning,
        val quality: TouchAimCalibrationQuality,
        val falseRate: Float,
        val missedRate: Float,
        val falseEntries: Int,
        val missedPasses: Int,
        val unexpected: Int,
        val movementReduced: Boolean,
        val consistency: Float,
        val objective: Float
    )

    fun analyze(passes: List<TouchAimCalibrationPass>): TouchAimCalibrationAnalysis {
        val completed = passes.filter { it.completed }
        val aim = completed.filter { it.state == TouchAimCalibrationState.AIM }.sortedBy { it.repetition }
        val shoot = completed.filter { it.state == TouchAimCalibrationState.SHOOT }.sortedBy { it.repetition }
        if (aim.size < 2 || shoot.size < 2) return TouchAimCalibrationAnalysis.Insufficient(
            "Two completed Aim recordings and two completed Aim + Shoot recordings are required."
        )
        if ((aim + shoot).any { invalidPass(it) }) return TouchAimCalibrationAnalysis.Insufficient(
            "A recording was interrupted or contained invalid touch samples. Retake that pass."
        )
        val repetitions = aim.map { it.repetition }.intersect(shoot.map { it.repetition }.toSet()).sorted()
        if (repetitions.size < 2) return TouchAimCalibrationAnalysis.Insufficient(
            "The repeated Aim and Aim + Shoot recordings could not be paired."
        )
        val pairedAim = repetitions.map { r -> aim.first { it.repetition == r } }
        val pairedShoot = repetitions.map { r -> shoot.first { it.repetition == r } }
        val candidates = sensorSubsets().mapNotNull { evaluate(it, pairedAim, pairedShoot) }
        if (candidates.isEmpty()) return TouchAimCalibrationAnalysis.Success(
            unreliable(completed, "None of Size, TouchMajor, or TouchMinor consistently increased in the training recordings.")
        )

        val ordered = candidates.sortedWith(candidateComparator())
        val single = ordered.firstOrNull { it.sensorSet.size == 1 }
        val combination = ordered.firstOrNull { it.sensorSet.size > 1 }
        val chosen = when {
            single == null -> checkNotNull(combination)
            combination == null -> single
            combination.falseEntries > single.falseEntries || combination.missedPasses > single.missedPasses -> single
            combination.falseRate > single.falseRate -> single
            qualityRank(combination.quality) > qualityRank(single.quality) -> combination
            qualityRank(combination.quality) == qualityRank(single.quality) &&
                single.objective - combination.objective >= .02f &&
                combination.objective <= single.objective * .80f -> combination
            else -> single
        }
        val sensors = chosen.transforms.joinToString(" + ") { it.sensor.displayName() }
        val explanation = when (chosen.quality) {
            TouchAimCalibrationQuality.GOOD -> "$sensors produced no confirmed accidental Shoot entries or missed Shoot passes in either held-out recording."
            TouchAimCalibrationQuality.BORDERLINE -> "$sensors showed usable separation, but at least one held-out pass was close to a safety limit. Validate carefully before applying it."
            TouchAimCalibrationQuality.UNRELIABLE -> "$sensors was the best candidate, but whole-pass validation could not establish a dependable automatic boundary."
        }
        return TouchAimCalibrationAnalysis.Success(TouchAimCalibrationRecommendation(
            chosen.transforms, chosen.quality,
            chosen.finalTuning.on,
            chosen.finalTuning.off,
            chosen.finalTuning.smoothing, chosen.finalTuning.enterMs, chosen.finalTuning.returnMs,
            chosen.falseRate, chosen.missedRate, chosen.movementReduced, chosen.consistency,
            completed.map(::summarize), explanation
        ))
    }

    fun score(sample: TouchAimSensorSample, transforms: List<TouchAimSensorTransform>): Float {
        if (transforms.isEmpty()) return 0f
        var weighted = 0f
        var total = 0f
        transforms.forEach { t ->
            val raw = sample.value(t.sensor)
            if (!raw.isFinite() || !t.center.isFinite() || !t.scale.isFinite() || t.scale <= 0f ||
                !t.weight.isFinite() || t.weight <= 0f) return Float.NaN
            weighted += ((raw - t.center) / t.scale).coerceIn(-8f, 8f) * t.weight
            total += t.weight
        }
        return if (total <= 0f || !weighted.isFinite()) Float.NaN else weighted / total
    }

    private fun invalidPass(pass: TouchAimCalibrationPass): Boolean =
        pass.samples.size < MIN_SAMPLES_PER_PASS ||
            pass.samples.count { !it.moving } < 6 || pass.samples.count { it.moving } < 6 ||
            pass.samples.any { !it.size.isFinite() || !it.touchMajor.isFinite() || !it.touchMinor.isFinite() } ||
            pass.samples.zipWithNext().any { (a, b) -> b.elapsedMs < a.elapsedMs } ||
            pass.durationMs < 300L || pass.samples.first().elapsedMs < 0L ||
            pass.durationMs < pass.samples.last().elapsedMs ||
            pass.samples.first().elapsedMs > pass.durationMs * 3L / 10L ||
            pass.samples.last().elapsedMs < pass.durationMs * 3L / 4L

    private fun evaluate(sensors: Set<TouchAimSensor>, aim: List<TouchAimCalibrationPass>, shoot: List<TouchAimCalibrationPass>): Candidate? {
        val folds = mutableListOf<Fold>()
        repeat(minOf(aim.size, shoot.size)) { heldOut ->
            val trainAim = aim.filterIndexed { i, _ -> i != heldOut }
            val trainShoot = shoot.filterIndexed { i, _ -> i != heldOut }
            val transforms = createTransforms(sensors, trainAim, trainShoot) ?: return null
            val tuning = tune(trainAim, trainShoot, transforms)
            val heldAim = aim[heldOut]
            val heldShoot = shoot[heldOut]
            val monotonic = sensors.all { sensor ->
                median(heldShoot.samples.map { it.value(sensor) }) > median(heldAim.samples.map { it.value(sensor) })
            }
            folds += Fold(tuning, simulate(heldAim, transforms, tuning), simulate(heldShoot, transforms, tuning), monotonic)
        }
        val transforms = createTransforms(sensors, aim, shoot) ?: return null
        val finalTuning = tune(aim, shoot, transforms)
        val falseEntries = folds.sumOf { it.aim.falseEntries }
        val missedPasses = folds.count { it.shoot.missedPass }
        val unexpected = folds.sumOf { it.aim.unexpectedTransitions + it.shoot.unexpectedTransitions }
        val falseRate = folds.maxOf { it.aim.errorRate }
        val missedRate = folds.maxOf { it.shoot.errorRate }
        val maximumActivationLatency = folds.maxOf { it.shoot.activationLatencyMs }
        val aimMovementPenalty = folds.maxOf { (it.aim.movingError - it.aim.stillError).coerceAtLeast(0f) }
        val shootMovementPenalty = folds.maxOf { (it.shoot.movingError - it.shoot.stillError).coerceAtLeast(0f) }
        val movementPenalty = max(aimMovementPenalty, shootMovementPenalty)
        val movingSignalDrop = shoot.any { pass ->
            val stillScores = pass.samples.filter { !it.moving }.map { score(it, transforms) }
            val movingScores = pass.samples.filter { it.moving }.map { score(it, transforms) }
            median(movingScores) < median(stillScores) - 0.10f
        }
        val effectiveMovementPenalty = max(movementPenalty, if (movingSignalDrop) 0.10f else 0f)
        val movementReduced = effectiveMovementPenalty > .05f || folds.any {
            (it.shoot.missedPass || it.shoot.unexpectedTransitions > 0) &&
                it.shoot.movingError >= it.shoot.stillError
        } || movingSignalDrop
        val thresholdRange = folds.maxOf { it.tuning.on } - folds.minOf { it.tuning.on }
        val aimScores = balancedSamples(aim).map { score(it, transforms) }
        val shootScores = balancedSamples(shoot).map { score(it, transforms) }
        val separation = abs(median(shootScores) - median(aimScores)).coerceAtLeast(.001f)
        val consistency = (1f - thresholdRange / separation).coerceIn(0f, 1f)
        val monotonic = folds.all { it.monotonic }
        val quality = when {
            monotonic && falseEntries == 0 && missedPasses == 0 && unexpected == 0 &&
                maximumActivationLatency <= 300L && falseRate <= .005f && missedRate <= .30f &&
                effectiveMovementPenalty <= .08f && consistency >= .65f -> TouchAimCalibrationQuality.GOOD
            monotonic && falseEntries <= 1 && missedPasses == 0 && unexpected <= 2 &&
                maximumActivationLatency <= 700L && falseRate <= .03f && missedRate <= .50f &&
                effectiveMovementPenalty <= .20f && consistency >= .30f -> TouchAimCalibrationQuality.BORDERLINE
            else -> TouchAimCalibrationQuality.UNRELIABLE
        }
        val objective = falseEntries * 100f + missedPasses * 30f + unexpected * 15f +
            falseRate * 15f + missedRate * 2f + effectiveMovementPenalty + (1f - consistency) * .1f
        return Candidate(sensors, transforms, folds, finalTuning, quality, falseRate, missedRate,
            falseEntries, missedPasses, unexpected, movementReduced, consistency, objective)
    }

    private fun createTransforms(
        sensors: Set<TouchAimSensor>,
        aimPasses: List<TouchAimCalibrationPass>,
        shootPasses: List<TouchAimCalibrationPass>
    ): List<TouchAimSensorTransform>? {
        val aim = balancedSamples(aimPasses)
        val shoot = balancedSamples(shootPasses)
        return sensors.sortedBy { it.ordinal }.map { sensor ->
            val aimMedian = median(aim.map { it.value(sensor) })
            val shootMedian = median(shoot.map { it.value(sensor) })
            val increases = aimPasses.indices.all { i ->
                median(shootPasses[i].samples.map { it.value(sensor) }) > median(aimPasses[i].samples.map { it.value(sensor) })
            }
            if (!increases || shootMedian <= aimMedian) return null
            val spread = pooledSpread(aim.map { it.value(sensor) }, shoot.map { it.value(sensor) })
            val minimum = max(abs(shootMedian - aimMedian) * .05f, sensorEpsilon(sensor))
            TouchAimSensorTransform(sensor, aimMedian, max(spread, minimum), 1f)
        }
    }

    private fun tune(aim: List<TouchAimCalibrationPass>, shoot: List<TouchAimCalibrationPass>, transforms: List<TouchAimSensorTransform>): Tuning {
        val aimScores = balancedSamples(aim).map { score(it, transforms) }
        val shootScores = balancedSamples(shoot).map { score(it, transforms) }
        val base = conservativeThreshold(aimScores, shootScores)
        val spread = pooledSpread(aimScores, shootScores)
        val onValues = listOf(base - spread * .08f, base, base + spread * .08f).distinct()
        val gaps = listOf(max(.05f, spread * .12f), max(.08f, spread * .20f), max(.12f, spread * .30f)).distinct()
        val options = buildList {
            for (on in onValues) for (gap in gaps) for (alpha in listOf(.22f, .35f, .50f))
                for (enter in listOf(64L, 96L, 128L, 160L)) for (back in listOf(80L, 120L, 160L, 200L))
                    add(Tuning(on, on - gap, alpha, enter, back))
        }
        return options.minWithOrNull(compareBy<Tuning>(
            { trainingLoss(aim, shoot, transforms, it) }, { it.enterMs }, { it.returnMs }, { -it.on }, { it.smoothing }
        )) ?: Tuning(base, base - max(.08f, spread * .2f), .35f, 96L, 120L)
    }

    private fun trainingLoss(aim: List<TouchAimCalibrationPass>, shoot: List<TouchAimCalibrationPass>, transforms: List<TouchAimSensorTransform>, tuning: Tuning): Float {
        val am = aim.map { simulate(it, transforms, tuning) }
        val sm = shoot.map { simulate(it, transforms, tuning) }
        return am.sumOf { it.falseEntries } * 100f + sm.count { it.missedPass } * 30f +
            (am + sm).sumOf { it.unexpectedTransitions } * 15f +
            am.map { it.errorRate }.average().toFloat() * 15f +
            sm.map { it.errorRate }.average().toFloat() * 2f +
            tuning.enterMs * .00005f + tuning.returnMs * .00002f
    }

    private fun simulate(pass: TouchAimCalibrationPass, transforms: List<TouchAimSensorTransform>, tuning: Tuning): Metrics {
        val machine = TwoStateTouchAimStateMachine(TwoStateTouchAimConfig(
            tuning.on, tuning.off, tuning.smoothing, tuning.enterMs, tuning.returnMs,
            TouchAimShootBehavior.PRESS_ON_ENTER, false, true, false
        ))
        machine.onContactStarted(0L)
        var entries = 0
        var transitions = 0
        var entered = false
        var firstEntryTime: Long? = null
        val errors = mutableListOf<Boolean>()
        val moving = mutableListOf<Boolean>()
        val still = mutableListOf<Boolean>()
        fun observe(previous: TwoStateTouchAimState, timeMs: Long) {
            if (machine.state == previous) return
            transitions++
            if (machine.state == TwoStateTouchAimState.SHOOT) {
                entries++
                entered = true
                if (firstEntryTime == null) firstEntryTime = timeMs
            }
        }
        evaluationSamples(pass).forEach { sample ->
            while (true) {
                val deadline = machine.nextDeadlineMs() ?: break
                if (deadline > sample.elapsedMs) break
                val previous = machine.state
                machine.onTime(deadline)
                observe(previous, deadline)
                if (machine.state == previous) break
            }
            val previous = machine.state
            machine.onScore(score(sample, transforms), sample.elapsedMs)
            observe(previous, sample.elapsedMs)
            val error = if (pass.state == TouchAimCalibrationState.AIM)
                machine.state == TwoStateTouchAimState.SHOOT else machine.state != TwoStateTouchAimState.SHOOT
            errors += error
            (if (sample.moving) moving else still) += error
        }
        machine.nextDeadlineMs()?.takeIf { it <= pass.durationMs }?.let { deadline ->
            val previous = machine.state
            machine.onTime(deadline)
            observe(previous, deadline)
        }
        val expected = if (pass.state == TouchAimCalibrationState.SHOOT && entered) 1 else 0
        return Metrics(booleanRate(errors), booleanRate(moving), booleanRate(still),
            if (pass.state == TouchAimCalibrationState.AIM) entries else 0,
            pass.state == TouchAimCalibrationState.SHOOT && !entered,
            (transitions - expected).coerceAtLeast(0),
            if (pass.state == TouchAimCalibrationState.SHOOT) firstEntryTime ?: Long.MAX_VALUE else 0L)
    }

    private fun conservativeThreshold(aim: List<Float>, shoot: List<Float>): Float {
        val sorted = (aim + shoot).filter { it.isFinite() }.distinct().sorted()
        if (sorted.isEmpty()) return 0f
        val options = buildList {
            add(sorted.first() - .0001f)
            sorted.zipWithNext().forEach { (a, b) -> add((a + b) / 2f) }
            add(sorted.last() + .0001f)
        }
        return options.minWithOrNull(compareBy<Float>(
            { t -> rate(aim) { it >= t } * 12f + rate(shoot) { it < t } },
            { t -> rate(aim) { it >= t } }, { t -> -t }
        )) ?: 0f
    }

    private fun balancedSamples(passes: List<TouchAimCalibrationPass>): List<TouchAimSensorSample> =
        passes.flatMap { pass ->
            listOf(false, true).flatMap { moving ->
                resampleByTime(pass.samples.filter { it.moving == moving }, MAX_FIT_SAMPLES_PER_PASS / 2)
            }
        }

    /** Uniform time samples keep event-rate changes during movement from dominating validation rates. */
    private fun evaluationSamples(pass: TouchAimCalibrationPass): List<TouchAimSensorSample> {
        val count = (pass.durationMs / 20L + 1L).coerceIn(16L, 600L).toInt()
        return resampleByTime(pass.samples, count)
    }

    private fun resampleByTime(samples: List<TouchAimSensorSample>, count: Int): List<TouchAimSensorSample> {
        if (samples.isEmpty() || count <= 0) return emptyList()
        if (samples.size == 1) return List(count) { samples.single() }
        val sorted = samples.sortedBy { it.elapsedMs }
        val start = sorted.first().elapsedMs
        val end = sorted.last().elapsedMs
        if (end <= start) return List(count) { sorted.first() }
        var cursor = 0
        return List(count) { index ->
            val target = start + (end - start) * index / (count - 1).coerceAtLeast(1)
            while (cursor < sorted.lastIndex &&
                kotlin.math.abs(sorted[cursor + 1].elapsedMs - target) <= kotlin.math.abs(sorted[cursor].elapsedMs - target)
            ) cursor++
            sorted[cursor].copy(elapsedMs = target)
        }
    }

    private fun summarize(pass: TouchAimCalibrationPass) = TouchAimPassSummary(
        pass.state, pass.repetition, pass.samples.size, pass.samples.count { !it.moving }, pass.samples.count { it.moving },
        TouchAimSensor.entries.map { distribution(it, pass.samples) },
        TouchAimSensor.entries.map { s -> distribution(s, pass.samples.filter { !it.moving }) },
        TouchAimSensor.entries.map { s -> distribution(s, pass.samples.filter { it.moving }) },
        balancedSamples(listOf(pass)),
        pass.durationMs
    )

    private fun distribution(sensor: TouchAimSensor, samples: List<TouchAimSensorSample>): TouchAimDistributionSummary {
        val values = samples.map { it.value(sensor) }
        return TouchAimDistributionSummary(sensor, values.minOrNull() ?: 0f, percentile(values, .1f), median(values), percentile(values, .9f), values.maxOrNull() ?: 0f)
    }

    private fun unreliable(passes: List<TouchAimCalibrationPass>, reason: String) = TouchAimCalibrationRecommendation(
        emptyList(), TouchAimCalibrationQuality.UNRELIABLE, 0f, 0f, .25f, 220L, 240L,
        1f, 1f, true, 0f, passes.map(::summarize), reason
    )

    private fun candidateComparator() = compareBy<Candidate>(
        { -qualityRank(it.quality) }, { it.falseEntries }, { it.missedPasses }, { it.unexpected },
        { it.objective }, { it.sensorSet.size }, { it.sensorSet.sumOf(TouchAimSensor::ordinal) }
    )
    private fun sensorSubsets() = (1 until (1 shl TouchAimSensor.entries.size)).map { mask ->
        TouchAimSensor.entries.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
    }.sortedWith(compareBy({ it.size }, { it.sumOf(TouchAimSensor::ordinal) }))
    private fun pooledSpread(a: List<Float>, b: List<Float>) = max(
        ((percentile(a, .9f) - percentile(a, .1f)) + (percentile(b, .9f) - percentile(b, .1f))) / 2f, .000001f)
    private fun rate(values: List<Float>, predicate: (Float) -> Boolean) = if (values.isEmpty()) 0f else values.count(predicate).toFloat() / values.size
    private fun booleanRate(values: List<Boolean>) = if (values.isEmpty()) 0f else values.count { it }.toFloat() / values.size
    private fun median(values: List<Float>) = percentile(values, .5f)
    private fun percentile(values: List<Float>, fraction: Float): Float {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return 0f
        val position = fraction.coerceIn(0f, 1f) * (sorted.size - 1)
        val lower = position.toInt(); val upper = minOf(lower + 1, sorted.lastIndex); val weight = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
    }
    private fun sensorEpsilon(sensor: TouchAimSensor) = if (sensor == TouchAimSensor.SIZE) .0001f else .01f
    private fun qualityRank(q: TouchAimCalibrationQuality) = when (q) {
        TouchAimCalibrationQuality.GOOD -> 2; TouchAimCalibrationQuality.BORDERLINE -> 1; TouchAimCalibrationQuality.UNRELIABLE -> 0
    }
    fun TouchAimSensor.displayName() = when (this) {
        TouchAimSensor.SIZE -> "Size"; TouchAimSensor.TOUCH_MAJOR -> "TouchMajor"; TouchAimSensor.TOUCH_MINOR -> "TouchMinor"
    }
}
