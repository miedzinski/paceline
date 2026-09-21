package paceline.training.domain

import paceline.telemetry.domain.CyclingTelemetry
import paceline.telemetry.domain.HeartRateTelemetry
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutSourceType
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

data class RecordedCyclingObservation(
    val receivedAt: Instant,
    val powerWatts: Int?,
    val cadenceRpm: Double?,
    val speedKph: Double?,
    val distanceMeters: Double?,
    val sourceId: String? = null,
    val powerSourceId: String? = null,
    val cadenceSourceId: String? = null,
    val speedSourceId: String? = null,
    val distanceSourceId: String? = null,
) {
    init {
        require(sourceId == null || sourceId.isNotBlank()) {
            "A cycling observation source ID must not be blank"
        }
        listOf(powerSourceId, cadenceSourceId, speedSourceId, distanceSourceId).forEach { fieldSourceId ->
            require(fieldSourceId == null || fieldSourceId.isNotBlank()) {
                "A cycling observation field source ID must not be blank"
            }
        }
    }

    val hasMeasurement: Boolean
        get() = powerWatts != null || cadenceRpm != null || speedKph != null || distanceMeters != null

    fun toExportSample(): TrainingTelemetrySample =
        TrainingTelemetrySample(
            receivedAt = receivedAt,
            powerWatts = powerWatts,
            cadenceRpm = cadenceRpm,
            speedKph = speedKph,
            distanceMeters = distanceMeters,
            powerSourceId = powerSourceId,
            cadenceSourceId = cadenceSourceId,
            speedSourceId = speedSourceId,
            distanceSourceId = distanceSourceId,
        )

    companion object {
        fun from(
            telemetry: CyclingTelemetry,
            sourceId: String? = null,
        ): RecordedCyclingObservation =
            RecordedCyclingObservation(
                receivedAt = telemetry.receivedAt,
                powerWatts = telemetry.powerWatts,
                cadenceRpm = telemetry.cadenceRpm,
                speedKph = telemetry.speedKph,
                distanceMeters = telemetry.distanceMeters,
                sourceId = sourceId,
                powerSourceId = sourceId.takeIf { telemetry.powerWatts != null },
                cadenceSourceId = sourceId.takeIf { telemetry.cadenceRpm != null },
                speedSourceId = sourceId.takeIf { telemetry.speedKph != null },
                distanceSourceId = sourceId.takeIf { telemetry.distanceMeters != null },
            )

        fun fromSelected(
            telemetry: CyclingTelemetry,
            sourceId: String,
            selection: RideEquipmentSelection,
        ): RecordedCyclingObservation {
            val powerSelected = sourceId == selection.powerSourceId
            val cadenceSelected = sourceId == selection.cadenceSourceId
            val controlSelected = sourceId == selection.controlSourceId
            return RecordedCyclingObservation(
                receivedAt = telemetry.receivedAt,
                powerWatts = telemetry.powerWatts.takeIf { powerSelected },
                cadenceRpm = telemetry.cadenceRpm.takeIf { cadenceSelected },
                speedKph = telemetry.speedKph.takeIf { controlSelected },
                distanceMeters = telemetry.distanceMeters.takeIf { controlSelected },
                sourceId = sourceId,
                powerSourceId = sourceId.takeIf { powerSelected && telemetry.powerWatts != null },
                cadenceSourceId = sourceId.takeIf { cadenceSelected && telemetry.cadenceRpm != null },
                speedSourceId = sourceId.takeIf { controlSelected && telemetry.speedKph != null },
                distanceSourceId = sourceId.takeIf { controlSelected && telemetry.distanceMeters != null },
            )
        }
    }
}

data class RecordedHeartRateObservation(
    val receivedAt: Instant,
    val heartRateBpm: Int,
    val sourceId: String,
) {
    init {
        require(sourceId.isNotBlank()) { "A heart-rate observation source ID must not be blank" }
        require(heartRateBpm in 0..65_535) {
            "Heart rate must fit the standard Bluetooth heart-rate measurement field"
        }
    }

    val heartRateSourceId: String
        get() = sourceId

    fun toExportSample(): TrainingTelemetrySample =
        TrainingTelemetrySample(
            receivedAt = receivedAt,
            powerWatts = null,
            cadenceRpm = null,
            speedKph = null,
            distanceMeters = null,
            heartRateBpm = heartRateBpm,
            heartRateSourceId = sourceId,
        )
}

data class TrainingTelemetrySample(
    val receivedAt: Instant,
    val powerWatts: Int?,
    val cadenceRpm: Double?,
    val speedKph: Double?,
    val distanceMeters: Double?,
    val powerSourceId: String? = null,
    val cadenceSourceId: String? = null,
    val speedSourceId: String? = null,
    val distanceSourceId: String? = null,
    val heartRateBpm: Int? = null,
    val heartRateSourceId: String? = null,
) {
    companion object {
        fun from(telemetry: CyclingTelemetry): TrainingTelemetrySample =
            TrainingTelemetrySample(
                receivedAt = telemetry.receivedAt,
                powerWatts = telemetry.powerWatts,
                cadenceRpm = telemetry.cadenceRpm,
                speedKph = telemetry.speedKph,
                distanceMeters = telemetry.distanceMeters,
            )

        fun fromSelected(
            telemetry: CyclingTelemetry,
            sourceId: String,
            selection: RideEquipmentSelection,
        ): TrainingTelemetrySample = RecordedCyclingObservation.fromSelected(telemetry, sourceId, selection).toExportSample()

        fun fromHeartRate(
            telemetry: HeartRateTelemetry,
            sourceId: String,
        ): TrainingTelemetrySample =
            TrainingTelemetrySample(
                receivedAt = telemetry.receivedAt,
                powerWatts = null,
                cadenceRpm = null,
                speedKph = null,
                distanceMeters = null,
                heartRateBpm = telemetry.heartRateBpm,
                heartRateSourceId = sourceId,
            )
    }
}

private fun TrainingTelemetrySample.toCyclingObservation(): RecordedCyclingObservation? {
    if (powerWatts == null && cadenceRpm == null && speedKph == null && distanceMeters == null) {
        return null
    }
    return RecordedCyclingObservation(
        receivedAt = receivedAt,
        powerWatts = powerWatts,
        cadenceRpm = cadenceRpm,
        speedKph = speedKph,
        distanceMeters = distanceMeters,
        powerSourceId = powerSourceId,
        cadenceSourceId = cadenceSourceId,
        speedSourceId = speedSourceId,
        distanceSourceId = distanceSourceId,
    )
}

private fun TrainingTelemetrySample.toHeartRateObservation(): RecordedHeartRateObservation? =
    if (heartRateBpm != null && heartRateSourceId != null) {
        RecordedHeartRateObservation(
            receivedAt = receivedAt,
            heartRateBpm = heartRateBpm,
            sourceId = heartRateSourceId,
        )
    } else {
        null
    }

data class RecordedTrainingActivitySegment(
    val name: String,
    val targetPowerWatts: Int?,
    val startedAt: Instant,
    val stoppedAt: Instant,
    val samples: List<TrainingTelemetrySample> = emptyList(),
    val workoutStep: ExecutableWorkoutStep? = null,
    val cyclingObservations: List<RecordedCyclingObservation> =
        samples.mapNotNull(TrainingTelemetrySample::toCyclingObservation),
    val heartRateObservations: List<RecordedHeartRateObservation> =
        samples.mapNotNull(TrainingTelemetrySample::toHeartRateObservation),
) {
    init {
        require(name.isNotBlank()) { "An activity segment must have a name" }
        require(!stoppedAt.isBefore(startedAt)) { "An activity segment cannot stop before it starts" }
    }

    val exportSamples: List<TrainingTelemetrySample>
        get() =
            if (samples.isNotEmpty()) {
                samples
            } else {
                combineForExport(cyclingObservations, heartRateObservations)
            }
}

enum class TrainingActivityEventType {
    ERG_PROTECTION_STARTED,
    ERG_PROTECTION_ENDED,
    ERG_PROTECTION_FAILED,
    WORKOUT_TARGET_ADJUSTED,
    TRAINER_CONNECTION_INTERRUPTED,
    TRAINER_RECONNECT_ATTEMPTED,
    TRAINER_RECONNECTED,
    TRAINER_TARGET_SYNCHRONIZED,
    TRAINING_PAUSED,
    TRAINING_RESUMED,
}

data class TrainingActivityEvent(
    val type: TrainingActivityEventType,
    val occurredAt: Instant,
    val cadenceRpm: Double? = null,
    val workoutPowerTargetPercent: Long? = null,
    val targetPowerWatts: Int? = null,
    val retryAttempt: Int? = null,
) {
    init {
        require(cadenceRpm == null || (cadenceRpm.isFinite() && cadenceRpm >= 0.0)) {
            "An activity event cadence must be finite and non-negative"
        }
        require(targetPowerWatts == null || targetPowerWatts in 0..Short.MAX_VALUE.toInt()) {
            "An activity event target must fit the non-negative FTMS signed 16-bit watt field"
        }
        require(retryAttempt == null || retryAttempt > 0) {
            "An activity event retry attempt must be positive"
        }
    }
}

data class RecordedTrainingActivity(
    val sessionId: UUID,
    val startedAt: Instant,
    val stoppedAt: Instant,
    val name: String,
    val workoutSource: WorkoutSourceReference?,
    val workoutCompleted: Boolean,
    val samples: List<TrainingTelemetrySample> = emptyList(),
    val workoutSourceType: WorkoutSourceType? = null,
    val segments: List<RecordedTrainingActivitySegment> = emptyList(),
    val events: List<TrainingActivityEvent> = emptyList(),
    val cyclingObservations: List<RecordedCyclingObservation> =
        samples.mapNotNull(TrainingTelemetrySample::toCyclingObservation),
    val heartRateObservations: List<RecordedHeartRateObservation> =
        samples.mapNotNull(TrainingTelemetrySample::toHeartRateObservation),
) {
    val exportSamples: List<TrainingTelemetrySample>
        get() =
            if (samples.isNotEmpty()) {
                samples
            } else {
                combineForExport(cyclingObservations, heartRateObservations)
            }
}

data class TrainingActivitySummary(
    val durationSeconds: Long,
    val averagePowerWatts: Int?,
)

fun RecordedTrainingActivity.summary(): TrainingActivitySummary {
    val powerValues =
        exportSamples
            .asSequence()
            .filter { sample ->
                !sample.receivedAt.isBefore(startedAt) && !sample.receivedAt.isAfter(stoppedAt)
            }.mapNotNull { sample -> sample.powerWatts?.takeIf { power -> power in 0..65_535 } }
            .toList()
    return TrainingActivitySummary(
        durationSeconds = Duration.between(startedAt, stoppedAt).seconds.coerceAtLeast(0),
        averagePowerWatts = powerValues.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
    )
}

class InMemoryTrainingActivityRecorder {
    private var active: MutableRecording? = null

    @Synchronized
    fun start(
        sessionId: UUID,
        startedAt: Instant,
        name: String,
        workoutSource: WorkoutSourceReference?,
        workoutSourceType: WorkoutSourceType? = null,
        initialSegmentName: String = name,
        initialTargetPowerWatts: Int? = null,
        initialWorkoutStep: ExecutableWorkoutStep? = null,
    ) {
        check(active == null) { "A training activity is already being recorded" }
        active =
            MutableRecording(
                sessionId = sessionId,
                startedAt = startedAt,
                name = name,
                workoutSource = workoutSource,
                workoutSourceType = workoutSourceType,
                initialSegmentName = initialSegmentName,
                initialTargetPowerWatts = initialTargetPowerWatts,
                initialWorkoutStep = initialWorkoutStep,
            )
    }

    @Synchronized
    fun record(
        sessionId: UUID,
        telemetry: CyclingTelemetry,
        sourceId: String? = null,
        equipment: RideEquipmentSelection? = null,
    ) {
        val recording = active?.takeIf { it.sessionId == sessionId } ?: return
        val observation =
            if (sourceId != null && equipment != null) {
                RecordedCyclingObservation.fromSelected(telemetry, sourceId, equipment)
            } else {
                RecordedCyclingObservation.from(telemetry, sourceId)
            }
        recording.recordCyclingObservation(observation)
    }

    @Synchronized
    fun recordCyclingObservation(
        sessionId: UUID,
        observation: RecordedCyclingObservation,
    ) {
        active?.takeIf { it.sessionId == sessionId }?.recordCyclingObservation(observation)
    }

    @Synchronized
    fun recordHeartRate(
        sessionId: UUID,
        sourceId: String,
        telemetry: HeartRateTelemetry,
    ) {
        recordHeartRateObservation(
            sessionId = sessionId,
            observation =
                RecordedHeartRateObservation(
                    receivedAt = telemetry.receivedAt,
                    heartRateBpm = telemetry.heartRateBpm,
                    sourceId = sourceId,
                ),
        )
    }

    @Synchronized
    fun recordHeartRateObservation(
        sessionId: UUID,
        observation: RecordedHeartRateObservation,
    ) {
        active?.takeIf { it.sessionId == sessionId }?.recordHeartRateObservation(observation)
    }

    @Synchronized
    fun recordEvent(
        sessionId: UUID,
        event: TrainingActivityEvent,
    ) {
        active?.takeIf { it.sessionId == sessionId }?.events?.add(event)
    }

    @Synchronized
    fun startSegment(
        sessionId: UUID,
        startedAt: Instant,
        name: String,
        targetPowerWatts: Int?,
        workoutStep: ExecutableWorkoutStep? = null,
    ) {
        active?.takeIf { it.sessionId == sessionId }?.startSegment(
            startedAt = startedAt,
            name = name,
            targetPowerWatts = targetPowerWatts,
            workoutStep = workoutStep,
        )
    }

    @Synchronized
    fun completeWorkout(
        sessionId: UUID,
        completedAt: Instant,
    ) {
        active?.takeIf { it.sessionId == sessionId }?.let { recording ->
            recording.workoutCompleted = true
            recording.startSegment(
                startedAt = completedAt,
                name = "Manual continuation",
                targetPowerWatts = null,
            )
        }
    }

    @Synchronized
    fun finish(
        sessionId: UUID,
        stoppedAt: Instant,
    ): RecordedTrainingActivity {
        val recording =
            active?.takeIf { it.sessionId == sessionId } ?: throw IllegalStateException(
                "No in-memory training activity exists for session $sessionId",
            )
        val activity = recording.toActivity(stoppedAt)
        active = null
        return activity
    }

    private class MutableRecording(
        val sessionId: UUID,
        val startedAt: Instant,
        val name: String,
        val workoutSource: WorkoutSourceReference?,
        val workoutSourceType: WorkoutSourceType?,
        initialSegmentName: String,
        initialTargetPowerWatts: Int?,
        initialWorkoutStep: ExecutableWorkoutStep?,
        val cyclingObservations: MutableList<RecordedCyclingObservation> = mutableListOf(),
        val heartRateObservations: MutableList<RecordedHeartRateObservation> = mutableListOf(),
        val segments: MutableList<MutableSegment> = mutableListOf(),
        val events: MutableList<TrainingActivityEvent> = mutableListOf(),
        var workoutCompleted: Boolean = false,
    ) {
        var currentSegment: MutableSegment =
            MutableSegment(
                name = initialSegmentName,
                targetPowerWatts = initialTargetPowerWatts,
                workoutStep = initialWorkoutStep,
                startedAt = startedAt,
            )

        init {
            segments += currentSegment
        }

        fun recordCyclingObservation(observation: RecordedCyclingObservation) {
            if (!observation.hasMeasurement) {
                return
            }
            insertByTimestamp(cyclingObservations, observation, RecordedCyclingObservation::receivedAt)
            segmentFor(observation.receivedAt).recordCyclingObservation(observation)
        }

        fun recordHeartRateObservation(observation: RecordedHeartRateObservation) {
            insertByTimestamp(heartRateObservations, observation, RecordedHeartRateObservation::receivedAt)
            segmentFor(observation.receivedAt).recordHeartRateObservation(observation)
        }

        private fun segmentFor(receivedAt: Instant): MutableSegment =
            segments.lastOrNull { segment ->
                !receivedAt.isBefore(segment.startedAt) &&
                    (segment.stoppedAt == null || receivedAt.isBefore(requireNotNull(segment.stoppedAt)))
            } ?: currentSegment

        fun startSegment(
            startedAt: Instant,
            name: String,
            targetPowerWatts: Int?,
            workoutStep: ExecutableWorkoutStep? = null,
        ) {
            currentSegment.stop(startedAt)
            currentSegment =
                MutableSegment(
                    name = name,
                    targetPowerWatts = targetPowerWatts,
                    workoutStep = workoutStep,
                    startedAt = startedAt,
                )
            segments += currentSegment
        }

        fun toActivity(stoppedAt: Instant): RecordedTrainingActivity =
            run {
                currentSegment.stop(stoppedAt)
                RecordedTrainingActivity(
                    sessionId = sessionId,
                    startedAt = startedAt,
                    stoppedAt = stoppedAt,
                    name = name,
                    workoutSource = workoutSource,
                    workoutCompleted = workoutCompleted,
                    samples = combineForExport(cyclingObservations, heartRateObservations),
                    workoutSourceType = workoutSourceType,
                    segments = segments.map { it.toRecorded() },
                    events = events.sortedBy { it.occurredAt },
                    cyclingObservations = cyclingObservations.toList(),
                    heartRateObservations = heartRateObservations.toList(),
                )
            }
    }

    private companion object {
        fun <T> insertByTimestamp(
            observations: MutableList<T>,
            observation: T,
            timestamp: (T) -> Instant,
        ) {
            val receivedAt = timestamp(observation)
            val index = observations.indexOfFirst { timestamp(it).isAfter(receivedAt) }
            if (index >= 0) {
                observations.add(index, observation)
            } else {
                observations += observation
            }
        }
    }

    private class MutableSegment(
        val name: String,
        val targetPowerWatts: Int?,
        val workoutStep: ExecutableWorkoutStep?,
        val startedAt: Instant,
        val cyclingObservations: MutableList<RecordedCyclingObservation> = mutableListOf(),
        val heartRateObservations: MutableList<RecordedHeartRateObservation> = mutableListOf(),
        var stoppedAt: Instant? = null,
    ) {
        fun stop(at: Instant) {
            require(!at.isBefore(startedAt)) { "An activity segment cannot stop before it starts" }
            stoppedAt = at
        }

        fun recordCyclingObservation(observation: RecordedCyclingObservation) {
            insertByTimestamp(cyclingObservations, observation, RecordedCyclingObservation::receivedAt)
        }

        fun recordHeartRateObservation(observation: RecordedHeartRateObservation) {
            insertByTimestamp(heartRateObservations, observation, RecordedHeartRateObservation::receivedAt)
        }

        fun toRecorded(): RecordedTrainingActivitySegment =
            RecordedTrainingActivitySegment(
                name = name,
                targetPowerWatts = targetPowerWatts,
                startedAt = startedAt,
                stoppedAt = requireNotNull(stoppedAt),
                samples = combineForExport(cyclingObservations, heartRateObservations),
                workoutStep = workoutStep,
                cyclingObservations = cyclingObservations.toList(),
                heartRateObservations = heartRateObservations.toList(),
            )
    }
}

private fun combineForExport(
    cyclingObservations: List<RecordedCyclingObservation>,
    heartRateObservations: List<RecordedHeartRateObservation>,
): List<TrainingTelemetrySample> {
    val samples = mutableListOf<TrainingTelemetrySample>()
    val add = { sample: TrainingTelemetrySample ->
        val index = samples.indexOfFirst { it.receivedAt >= sample.receivedAt }
        if (index >= 0 && samples[index].receivedAt == sample.receivedAt) {
            samples[index] = samples[index].mergeAtSameTimestamp(sample)
        } else if (index >= 0) {
            samples.add(index, sample)
        } else {
            samples += sample
        }
    }
    cyclingObservations.forEach { add(it.toExportSample()) }
    heartRateObservations.forEach { add(it.toExportSample()) }
    return samples
}

private fun TrainingTelemetrySample.mergeAtSameTimestamp(other: TrainingTelemetrySample): TrainingTelemetrySample =
    copy(
        powerWatts = other.powerWatts ?: powerWatts,
        cadenceRpm = other.cadenceRpm ?: cadenceRpm,
        speedKph = other.speedKph ?: speedKph,
        distanceMeters = other.distanceMeters ?: distanceMeters,
        powerSourceId = other.powerSourceId ?: powerSourceId,
        cadenceSourceId = other.cadenceSourceId ?: cadenceSourceId,
        speedSourceId = other.speedSourceId ?: speedSourceId,
        distanceSourceId = other.distanceSourceId ?: distanceSourceId,
        heartRateBpm = other.heartRateBpm ?: heartRateBpm,
        heartRateSourceId = other.heartRateSourceId ?: heartRateSourceId,
    )
