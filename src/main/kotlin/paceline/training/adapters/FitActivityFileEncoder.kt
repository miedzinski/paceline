package paceline.training.adapters

import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.DeviceIndex
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.Intensity
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionTrigger
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import com.garmin.fit.WktStepDuration
import com.garmin.fit.WktStepTarget
import com.garmin.fit.WorkoutMesg
import com.garmin.fit.WorkoutStepMesg
import org.springframework.stereotype.Component
import paceline.training.domain.RecordedTrainingActivity
import paceline.training.domain.RecordedTrainingActivitySegment
import paceline.training.domain.TrainingTelemetrySample
import paceline.training.ports.ActivityFile
import paceline.training.ports.ActivityFileEncoder
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Component
class FitActivityFileEncoder : ActivityFileEncoder {
    override fun encode(activity: RecordedTrainingActivity): ActivityFile {
        val segments = activity.exportSegments()
        val workoutStepIndices = segments.workoutStepIndices()
        val temporaryPath = Files.createTempFile("paceline-activity-", ".fit")
        var fileEncoder: FileEncoder? = null
        var closed = false

        try {
            fileEncoder = FileEncoder(temporaryPath.toFile(), Fit.ProtocolVersion.V2_0)
            val serialNumber = activity.serialNumber()
            fileEncoder.write(activity.fileId(serialNumber))
            fileEncoder.write(activity.deviceInfo(serialNumber))

            val workoutSteps = segments.mapNotNull { it.workoutStep }
            if (workoutSteps.isNotEmpty()) {
                fileEncoder.write(activity.workout(workoutSteps.size))
                segments.forEachIndexed { index, segment ->
                    segment.workoutStep?.let { step ->
                        fileEncoder.write(segment.workoutStepMessage(index = workoutStepIndices[index]!!, step = step))
                    }
                }
            }

            fileEncoder.write(activity.timerEvent(EventType.START, activity.startedAt))
            val firstDistance = activity.samples.firstNotNullOfOrNull { it.distanceMeters?.takeIf(Double::isFinite) }
            activity.samples.forEach { sample ->
                fileEncoder.write(sample.recordMessage(firstDistance))
            }
            fileEncoder.write(activity.timerEvent(EventType.STOP_ALL, activity.stoppedAt))
            segments.forEachIndexed { index, segment ->
                fileEncoder.write(segment.lapMessage(index, workoutStepIndices[index]))
            }
            fileEncoder.write(activity.sessionMessage(segments.size, firstDistance))
            fileEncoder.write(activity.activityMessage())
            fileEncoder.close()
            closed = true

            return ActivityFile(
                fileName = "paceline-${activity.sessionId}.fit",
                contentType = FIT_CONTENT_TYPE,
                content = Files.readAllBytes(temporaryPath),
            )
        } finally {
            if (!closed) {
                runCatching { fileEncoder?.close() }
            }
            runCatching { Files.deleteIfExists(temporaryPath) }
        }
    }

    private fun RecordedTrainingActivity.fileId(serialNumber: Long): FileIdMesg =
        FileIdMesg().apply {
            type = File.ACTIVITY
            manufacturer = Manufacturer.DEVELOPMENT
            product = 0
            productName = PRODUCT_NAME
            this.serialNumber = serialNumber
            timeCreated = startedAt.toFitDateTime()
        }

    private fun RecordedTrainingActivity.deviceInfo(serialNumber: Long): DeviceInfoMesg =
        DeviceInfoMesg().apply {
            deviceIndex = DeviceIndex.CREATOR
            manufacturer = Manufacturer.DEVELOPMENT
            product = 0
            productName = PRODUCT_NAME
            this.serialNumber = serialNumber
            softwareVersion = SOFTWARE_VERSION
            timestamp = startedAt.toFitDateTime()
        }

    private fun RecordedTrainingActivity.workout(stepCount: Int): WorkoutMesg {
        val activityName = name
        return WorkoutMesg().apply {
            wktName = activityName
            sport = Sport.CYCLING
            subSport = SubSport.INDOOR_CYCLING
            numValidSteps = stepCount
        }
    }

    private fun RecordedTrainingActivity.timerEvent(
        eventType: EventType,
        timestamp: Instant,
    ): EventMesg =
        EventMesg().apply {
            this.timestamp = timestamp.toFitDateTime()
            event = Event.TIMER
            this.eventType = eventType
        }

    private fun RecordedTrainingActivity.sessionMessage(
        lapCount: Int,
        firstDistance: Double?,
    ): SessionMesg =
        SessionMesg().apply {
            messageIndex = 0
            timestamp = stoppedAt.toFitDateTime()
            startTime = startedAt.toFitDateTime()
            totalElapsedTime = durationSeconds().toFloat()
            totalTimerTime = durationSeconds().toFloat()
            samples.relativeDistance(firstDistance)?.toFloat()?.let { totalDistance = it }
            sport = Sport.CYCLING
            subSport = SubSport.INDOOR_CYCLING
            firstLapIndex = 0
            numLaps = lapCount
            trigger = SessionTrigger.ACTIVITY_END
            samples.averagePower()?.let { avgPower = it }
            samples.maximumPower()?.let { maxPower = it }
        }

    private fun RecordedTrainingActivity.activityMessage(): ActivityMesg {
        val stoppedAtFit = stoppedAt.toFitDateTime()
        val localTimestamp =
            stoppedAtFit.getTimestamp() +
                ZoneId
                    .systemDefault()
                    .rules
                    .getOffset(stoppedAt)
                    .totalSeconds
        return ActivityMesg().apply {
            timestamp = stoppedAt.toFitDateTime()
            totalTimerTime = durationSeconds().toFloat()
            numSessions = 1
            type = Activity.MANUAL
            this.localTimestamp = localTimestamp
        }
    }

    private fun RecordedTrainingActivitySegment.workoutStepMessage(
        index: Int,
        step: ExecutableWorkoutStep,
    ): WorkoutStepMesg {
        val segmentName = name
        return WorkoutStepMesg().apply {
            messageIndex = index
            wktStepName = segmentName
            notes = notes()
            intensity = step.intensity.toFitIntensity()
            when (val completion = step.completion) {
                is WorkoutStepCompletion.Time -> {
                    val durationMilliseconds = completion.seconds.toLong() * MILLIS_PER_SECOND
                    require(durationMilliseconds in 0..UINT32_MAX) {
                        "Workout step duration cannot be represented in FIT"
                    }
                    durationType = WktStepDuration.TIME
                    durationValue = durationMilliseconds
                }

                is WorkoutStepCompletion.Distance -> {
                    val durationCentimeters = (completion.meters * CENTIMETERS_PER_METER).roundToLong()
                    require(durationCentimeters in 0..UINT32_MAX) {
                        "Workout step distance cannot be represented in FIT"
                    }
                    durationType = WktStepDuration.DISTANCE
                    durationValue = durationCentimeters
                }

                WorkoutStepCompletion.Manual -> {
                    durationType = WktStepDuration.OPEN
                }
            }
            when (val target = step.target) {
                is WorkoutStepTarget.Power -> {
                    targetType = WktStepTarget.POWER
                    targetValue = 0L
                    customTargetValueLow = target.lowWatts.toLong()
                    customTargetValueHigh = target.highWatts.toLong()
                }

                is WorkoutStepTarget.Ramp -> {
                    targetType = WktStepTarget.POWER
                    targetValue = 0L
                    customTargetValueLow = target.lowWatts.toLong()
                    customTargetValueHigh = target.highWatts.toLong()
                }

                WorkoutStepTarget.Open -> {
                    targetType = WktStepTarget.OPEN
                }
            }
        }
    }

    private fun TrainingTelemetrySample.recordMessage(firstDistance: Double?): RecordMesg =
        RecordMesg().apply {
            timestamp = receivedAt.toFitDateTime()
            distanceMeters
                ?.takeIf(Double::isFinite)
                ?.let { rawDistance ->
                    distance = max(0.0, rawDistance - (firstDistance ?: rawDistance)).toFloat()
                }
            speedKph
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { rawSpeed -> speed = (rawSpeed / KPH_PER_MPS).toFloat() }
            cadenceRpm
                ?.takeIf { it.isFinite() }
                ?.roundToInt()
                ?.takeIf { it in FIT_UINT8_RANGE }
                ?.toShort()
                ?.let { cadence = it }
            heartRateBpm
                ?.takeIf { it in FIT_UINT8_RANGE }
                ?.toShort()
                ?.let { heartRate = it }
            powerWatts
                ?.takeIf { it in FIT_UINT16_RANGE }
                ?.let { power = it }
            fractionalSecond().let { fraction ->
                if (fraction > 0.0) {
                    time128 = fraction.toFloat()
                }
            }
        }

    private fun RecordedTrainingActivitySegment.lapMessage(
        index: Int,
        workoutStepIndex: Int?,
    ): LapMesg =
        LapMesg().apply {
            messageIndex = index
            timestamp = stoppedAt.toFitDateTime()
            startTime = startedAt.toFitDateTime()
            totalElapsedTime = durationSeconds().toFloat()
            totalTimerTime = durationSeconds().toFloat()
            distanceMeters()?.toFloat()?.let { totalDistance = it }
            intensity = workoutStep?.intensity.toFitIntensity()
            lapTrigger = workoutStep?.completion.toLapTrigger()
            sport = Sport.CYCLING
            subSport = SubSport.INDOOR_CYCLING
            workoutStepIndex?.let { wktStepIndex = it }
            samples.averagePower()?.let { avgPower = it }
            samples.maximumPower()?.let { maxPower = it }
        }

    private fun RecordedTrainingActivitySegment.distanceMeters(): Double? {
        val firstDistance = samples.firstNotNullOfOrNull { it.distanceMeters?.takeIf(Double::isFinite) }
        val lastDistance = samples.asReversed().firstNotNullOfOrNull { it.distanceMeters?.takeIf(Double::isFinite) }
        return if (firstDistance != null && lastDistance != null) {
            max(0.0, lastDistance - firstDistance)
        } else {
            null
        }
    }

    private fun RecordedTrainingActivitySegment.notes(): String =
        when (val target = workoutStep?.target) {
            is WorkoutStepTarget.Power -> "$name — target ${target.lowWatts}-${target.highWatts} W"
            is WorkoutStepTarget.Ramp -> "$name — ramp ${target.startWatts}→${target.endWatts} W"
            WorkoutStepTarget.Open -> name
            null -> targetPowerWatts?.let { "$name — target $it W" } ?: name
        }

    private fun List<TrainingTelemetrySample>.relativeDistance(firstDistance: Double?): Double? {
        val lastDistance = asReversed().firstNotNullOfOrNull { it.distanceMeters?.takeIf(Double::isFinite) }
        return if (firstDistance != null && lastDistance != null) {
            max(0.0, lastDistance - firstDistance)
        } else {
            null
        }
    }

    private fun List<TrainingTelemetrySample>.averagePower(): Int? =
        mapNotNull { it.powerWatts?.takeIf { power -> power in FIT_UINT16_RANGE } }
            .takeIf(List<Int>::isNotEmpty)
            ?.average()
            ?.roundToInt()

    private fun List<TrainingTelemetrySample>.maximumPower(): Int? =
        mapNotNull { it.powerWatts?.takeIf { power -> power in FIT_UINT16_RANGE } }
            .maxOrNull()

    private fun RecordedTrainingActivity.durationSeconds(): Double =
        max(0.0, Duration.between(startedAt, stoppedAt).toMillis() / MILLIS_PER_SECOND.toDouble())

    private fun RecordedTrainingActivitySegment.durationSeconds(): Double =
        max(0.0, Duration.between(startedAt, stoppedAt).toMillis() / MILLIS_PER_SECOND.toDouble())

    private fun TrainingTelemetrySample.fractionalSecond(): Double =
        floor(receivedAt.nano / NANOSECONDS_PER_SECOND.toDouble() * TIME128_HZ) / TIME128_HZ

    private fun String?.toFitIntensity(): Intensity =
        when (this?.trim()?.lowercase()) {
            "rest" -> Intensity.REST
            "warmup", "warm-up" -> Intensity.WARMUP
            "cooldown", "cool-down" -> Intensity.COOLDOWN
            "recovery" -> Intensity.RECOVERY
            "interval" -> Intensity.INTERVAL
            "other" -> Intensity.OTHER
            else -> Intensity.ACTIVE
        }

    private fun WorkoutStepCompletion?.toLapTrigger(): LapTrigger =
        when (this) {
            is WorkoutStepCompletion.Time -> LapTrigger.TIME
            is WorkoutStepCompletion.Distance -> LapTrigger.DISTANCE
            WorkoutStepCompletion.Manual, null -> LapTrigger.MANUAL
        }

    private fun Instant.toFitDateTime(): DateTime = DateTime(this)

    private fun RecordedTrainingActivity.serialNumber(): Long {
        val value = (sessionId.mostSignificantBits xor sessionId.leastSignificantBits) and UINT32_MAX
        return if (value == 0L) 1L else value
    }

    private fun RecordedTrainingActivity.exportSegments(): List<RecordedTrainingActivitySegment> =
        segments.ifEmpty {
            listOf(
                RecordedTrainingActivitySegment(
                    name = name,
                    targetPowerWatts = null,
                    startedAt = startedAt,
                    stoppedAt = stoppedAt,
                    samples = samples,
                ),
            )
        }

    private fun List<RecordedTrainingActivitySegment>.workoutStepIndices(): List<Int?> {
        var nextIndex = 0
        return map { segment ->
            segment.workoutStep?.let {
                nextIndex.also { nextIndex += 1 }
            }
        }
    }

    private companion object {
        const val PRODUCT_NAME = "Paceline"
        const val SOFTWARE_VERSION = 1.0f
        const val FIT_CONTENT_TYPE = "application/octet-stream"
        const val MILLIS_PER_SECOND = 1_000L
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        const val CENTIMETERS_PER_METER = 100.0
        const val KPH_PER_MPS = 3.6
        const val TIME128_HZ = 128.0
        const val UINT32_MAX = 0xFFFF_FFFFL
        val FIT_UINT8_RANGE = 0..255
        val FIT_UINT16_RANGE = 0..65_535
    }
}
