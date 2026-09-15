package paceline.training.domain

import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutSourceType
import java.time.Instant
import java.util.UUID

data class TrainingTelemetrySample(
    val receivedAt: Instant,
    val powerWatts: Int?,
    val cadenceRpm: Double?,
    val speedKph: Double?,
    val distanceMeters: Double?,
    val heartRateBpm: Int? = null,
    val heartRateSourceId: String? = null,
) {
    companion object {
        fun from(telemetry: IndoorBikeTelemetry): TrainingTelemetrySample =
            TrainingTelemetrySample(
                receivedAt = telemetry.receivedAt,
                powerWatts = telemetry.powerWatts,
                cadenceRpm = telemetry.cadenceRpm,
                speedKph = telemetry.speedKph,
                distanceMeters = telemetry.distanceMeters,
            )

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

data class RecordedTrainingActivitySegment(
    val name: String,
    val targetPowerWatts: Int?,
    val startedAt: Instant,
    val stoppedAt: Instant,
    val samples: List<TrainingTelemetrySample>,
    val workoutStep: ExecutableWorkoutStep? = null,
) {
    init {
        require(name.isNotBlank()) { "An activity segment must have a name" }
        require(!stoppedAt.isBefore(startedAt)) { "An activity segment cannot stop before it starts" }
    }
}

enum class TrainingActivityEventType {
    ERG_PROTECTION_STARTED,
    ERG_PROTECTION_ENDED,
    ERG_PROTECTION_FAILED,
}

data class TrainingActivityEvent(
    val type: TrainingActivityEventType,
    val occurredAt: Instant,
    val cadenceRpm: Double? = null,
) {
    init {
        require(cadenceRpm == null || (cadenceRpm.isFinite() && cadenceRpm >= 0.0)) {
            "An activity event cadence must be finite and non-negative"
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
    val samples: List<TrainingTelemetrySample>,
    val workoutSourceType: WorkoutSourceType? = null,
    val segments: List<RecordedTrainingActivitySegment> = emptyList(),
    val events: List<TrainingActivityEvent> = emptyList(),
)

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
        telemetry: IndoorBikeTelemetry,
    ) {
        val recording = active?.takeIf { it.sessionId == sessionId } ?: return
        recording.record(TrainingTelemetrySample.from(telemetry))
    }

    @Synchronized
    fun recordHeartRate(
        sessionId: UUID,
        sourceId: String,
        telemetry: HeartRateTelemetry,
    ) {
        val recording = active?.takeIf { it.sessionId == sessionId } ?: return
        recording.record(TrainingTelemetrySample.fromHeartRate(telemetry, sourceId))
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
        val samples: MutableList<TrainingTelemetrySample> = mutableListOf(),
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

        fun record(sample: TrainingTelemetrySample) {
            mergeSample(samples, sample)
            mergeSample(segmentFor(sample.receivedAt).samples, sample)
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
                    samples = samples.toList(),
                    workoutSourceType = workoutSourceType,
                    segments = segments.map { it.toRecorded() },
                    events = events.sortedBy { it.occurredAt },
                )
            }
    }

    private companion object {
        fun mergeSample(
            samples: MutableList<TrainingTelemetrySample>,
            sample: TrainingTelemetrySample,
        ) {
            val index = samples.indexOfFirst { it.receivedAt >= sample.receivedAt }
            if (index >= 0 && samples[index].receivedAt == sample.receivedAt) {
                samples[index] = samples[index].merge(sample)
            } else if (index >= 0) {
                samples.add(index, sample)
            } else {
                samples += sample
            }
        }

        fun TrainingTelemetrySample.merge(other: TrainingTelemetrySample): TrainingTelemetrySample =
            copy(
                powerWatts = other.powerWatts ?: powerWatts,
                cadenceRpm = other.cadenceRpm ?: cadenceRpm,
                speedKph = other.speedKph ?: speedKph,
                distanceMeters = other.distanceMeters ?: distanceMeters,
                heartRateBpm = other.heartRateBpm ?: heartRateBpm,
                heartRateSourceId = other.heartRateSourceId ?: heartRateSourceId,
            )
    }

    private class MutableSegment(
        val name: String,
        val targetPowerWatts: Int?,
        val workoutStep: ExecutableWorkoutStep?,
        val startedAt: Instant,
        val samples: MutableList<TrainingTelemetrySample> = mutableListOf(),
        var stoppedAt: Instant? = null,
    ) {
        fun stop(at: Instant) {
            require(!at.isBefore(startedAt)) { "An activity segment cannot stop before it starts" }
            stoppedAt = at
        }

        fun toRecorded(): RecordedTrainingActivitySegment =
            RecordedTrainingActivitySegment(
                name = name,
                targetPowerWatts = targetPowerWatts,
                startedAt = startedAt,
                stoppedAt = requireNotNull(stoppedAt),
                samples = samples.toList(),
                workoutStep = workoutStep,
            )
    }
}
