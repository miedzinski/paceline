package paceline.training.domain

import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.training.ports.TrainingDevice
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.ExecutableWorkoutStep
import java.time.Instant
import java.util.UUID

class TrainingActivitySession(
    private val trainingDevice: TrainingDevice,
) {
    private val activityRecorder = InMemoryTrainingActivityRecorder()
    private var completedActivity: RecordedTrainingActivity? = null
    private var activeSessionId: UUID? = null
    private var activeHeartRateSourceId: String? = null
    private var telemetryRegistration: AutoCloseable? = null
    private var heartRateRegistration: AutoCloseable? = null
    private var recordingActive = false
    private var telemetryObserver: ((IndoorBikeTelemetry) -> Unit)? = null
    private var heartRateObserver: ((UUID, String, HeartRateTelemetry) -> Unit)? = null

    @Synchronized
    fun start(
        sessionId: UUID,
        startedAt: Instant,
        workout: ExecutableWorkout?,
        heartRateSourceId: String?,
        initialTargetPowerWatts: Int?,
        onTelemetry: (IndoorBikeTelemetry) -> Unit,
        onHeartRate: (UUID, String, HeartRateTelemetry) -> Unit,
    ) {
        activityRecorder.start(
            sessionId = sessionId,
            startedAt = startedAt,
            name = workout?.name ?: "Paceline ride",
            workoutSource = workout?.source,
            workoutSourceType = workout?.sourceType,
            initialSegmentName =
                workout?.let {
                    activitySegmentName(
                        stepNumber = 1,
                        totalSteps = it.steps.size,
                        step = it.steps.first(),
                    )
                } ?: "Manual Free Ride",
            initialTargetPowerWatts = initialTargetPowerWatts,
            initialWorkoutStep = workout?.steps?.first(),
        )
        activeSessionId = sessionId
        activeHeartRateSourceId = heartRateSourceId
        telemetryObserver = onTelemetry
        heartRateObserver = onHeartRate
        completedActivity = null
        recordingActive = true
        registerTelemetry(sessionId)
        registerHeartRate(sessionId, heartRateSourceId)
    }

    @Synchronized
    fun selectHeartRateSource(
        sessionId: UUID,
        sourceId: String,
    ): HeartRateTelemetry? {
        check(activeSessionId == sessionId) { "No recording exists for session $sessionId" }
        heartRateRegistration?.close()
        heartRateRegistration = null
        activeHeartRateSourceId = sourceId
        registerHeartRate(sessionId, sourceId)
        return trainingDevice.currentHeartRate(sourceId)
    }

    @Synchronized
    fun selectedHeartRateSourceId(): String? = activeHeartRateSourceId

    @Synchronized
    fun currentHeartRate(): HeartRateTelemetry? = activeHeartRateSourceId?.let(trainingDevice::currentHeartRate)

    @Synchronized
    fun pauseRecording() {
        closeRecordingListeners()
        recordingActive = false
    }

    @Synchronized
    fun interruptTrainerRecording() {
        telemetryRegistration?.close()
        telemetryRegistration = null
    }

    @Synchronized
    fun resumeRecording(sessionId: UUID) {
        check(activeSessionId == sessionId) { "No recording exists for session $sessionId" }
        recordingActive = true
        registerTelemetry(sessionId)
        registerHeartRate(sessionId, activeHeartRateSourceId)
    }

    @Synchronized
    fun rebindAfterTrainerRecovery(
        sessionId: UUID,
        active: Boolean,
    ) {
        closeRecordingListeners()
        recordingActive = active
        if (active) {
            resumeRecording(sessionId)
        }
    }

    @Synchronized
    fun recordEvent(
        sessionId: UUID,
        event: TrainingActivityEvent,
    ) {
        activityRecorder.recordEvent(sessionId, event)
    }

    @Synchronized
    fun recordHeartRate(
        sessionId: UUID,
        sourceId: String,
        telemetry: HeartRateTelemetry,
    ): Boolean {
        if (!recordingActive || activeSessionId != sessionId || activeHeartRateSourceId != sourceId) {
            return false
        }
        activityRecorder.recordHeartRate(sessionId, sourceId, telemetry)
        return true
    }

    @Synchronized
    fun startSegment(
        sessionId: UUID,
        startedAt: Instant,
        name: String,
        targetPowerWatts: Int?,
        workoutStep: ExecutableWorkoutStep? = null,
    ) {
        activityRecorder.startSegment(
            sessionId = sessionId,
            startedAt = startedAt,
            name = name,
            targetPowerWatts = targetPowerWatts,
            workoutStep = workoutStep,
        )
    }

    @Synchronized
    fun startWorkoutSegment(
        sessionId: UUID,
        startedAt: Instant,
        stepNumber: Int,
        totalSteps: Int,
        targetPowerWatts: Int?,
        workoutStep: ExecutableWorkoutStep,
    ) {
        startSegment(
            sessionId = sessionId,
            startedAt = startedAt,
            name = activitySegmentName(stepNumber, totalSteps, workoutStep),
            targetPowerWatts = targetPowerWatts,
            workoutStep = workoutStep,
        )
    }

    @Synchronized
    fun completeWorkout(
        sessionId: UUID,
        completedAt: Instant,
    ) {
        activityRecorder.completeWorkout(sessionId, completedAt)
    }

    @Synchronized
    fun finish(
        sessionId: UUID,
        stoppedAt: Instant,
    ): RecordedTrainingActivity {
        closeRecordingListeners()
        recordingActive = false
        val activity = activityRecorder.finish(sessionId, stoppedAt)
        completedActivity = activity
        activeSessionId = null
        activeHeartRateSourceId = null
        telemetryObserver = null
        heartRateObserver = null
        return activity
    }

    @Synchronized
    fun completedActivity(): RecordedTrainingActivity? = completedActivity

    private fun registerTelemetry(sessionId: UUID) {
        telemetryRegistration =
            trainingDevice.addTelemetryListener { telemetry ->
                val observer =
                    synchronized(this) {
                        telemetryObserver.takeIf { recordingActive && activeSessionId == sessionId }
                    }
                observer?.invoke(telemetry)
                synchronized(this) {
                    if (recordingActive && activeSessionId == sessionId) {
                        activityRecorder.record(sessionId, telemetry)
                    }
                }
            }
    }

    private fun registerHeartRate(
        sessionId: UUID,
        sourceId: String?,
    ) {
        heartRateRegistration =
            sourceId?.let { selectedSourceId ->
                trainingDevice.addHeartRateListener(selectedSourceId) { telemetry ->
                    val observer =
                        synchronized(this) {
                            if (
                                !recordingActive ||
                                activeSessionId != sessionId ||
                                activeHeartRateSourceId != selectedSourceId
                            ) {
                                null
                            } else {
                                heartRateObserver
                            }
                        }
                    observer?.invoke(sessionId, selectedSourceId, telemetry)
                }
            }
    }

    private fun closeRecordingListeners() {
        telemetryRegistration?.close()
        telemetryRegistration = null
        heartRateRegistration?.close()
        heartRateRegistration = null
    }

    private fun activitySegmentName(
        stepNumber: Int,
        totalSteps: Int,
        step: ExecutableWorkoutStep,
    ): String {
        val stepName = step.text?.trim()?.takeIf(String::isNotBlank) ?: "Step $stepNumber"
        return "Step $stepNumber/$totalSteps: $stepName"
    }
}
