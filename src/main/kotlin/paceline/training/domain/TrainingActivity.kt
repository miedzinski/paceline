package paceline.training.domain

import paceline.device.domain.IndoorBikeTelemetry
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
    }
}

data class RecordedTrainingActivitySegment(
    val name: String,
    val targetPowerWatts: Int?,
    val startedAt: Instant,
    val stoppedAt: Instant,
    val samples: List<TrainingTelemetrySample>,
) {
    init {
        require(name.isNotBlank()) { "An activity segment must have a name" }
        require(!stoppedAt.isBefore(startedAt)) { "An activity segment cannot stop before it starts" }
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
            )
    }

    @Synchronized
    fun record(
        sessionId: UUID,
        telemetry: IndoorBikeTelemetry,
    ) {
        val recording = active?.takeIf { it.sessionId == sessionId } ?: return
        val previousReceivedAt = recording.lastReceivedAt
        if (previousReceivedAt == null || telemetry.receivedAt.isAfter(previousReceivedAt)) {
            val sample = TrainingTelemetrySample.from(telemetry)
            recording.samples += sample
            recording.currentSegment.samples += sample
            recording.lastReceivedAt = telemetry.receivedAt
        }
    }

    @Synchronized
    fun startSegment(
        sessionId: UUID,
        startedAt: Instant,
        name: String,
        targetPowerWatts: Int?,
    ) {
        active?.takeIf { it.sessionId == sessionId }?.startSegment(
            startedAt = startedAt,
            name = name,
            targetPowerWatts = targetPowerWatts,
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
        val samples: MutableList<TrainingTelemetrySample> = mutableListOf(),
        val segments: MutableList<MutableSegment> = mutableListOf(),
        var lastReceivedAt: Instant? = null,
        var workoutCompleted: Boolean = false,
    ) {
        var currentSegment: MutableSegment =
            MutableSegment(
                name = initialSegmentName,
                targetPowerWatts = initialTargetPowerWatts,
                startedAt = startedAt,
            )

        init {
            segments += currentSegment
        }

        fun startSegment(
            startedAt: Instant,
            name: String,
            targetPowerWatts: Int?,
        ) {
            currentSegment.stop(startedAt)
            currentSegment =
                MutableSegment(
                    name = name,
                    targetPowerWatts = targetPowerWatts,
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
                )
            }
    }

    private class MutableSegment(
        val name: String,
        val targetPowerWatts: Int?,
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
            )
    }
}
