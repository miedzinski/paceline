package paceline.training.domain

import paceline.device.domain.CyclingTelemetry
import paceline.device.domain.HeartRateTelemetry
import paceline.training.ports.HeartRateSource
import paceline.training.ports.SelectedRideEquipment
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.ExecutableWorkoutStep
import java.time.Instant
import java.util.UUID

class TrainingActivitySession {
    private val activityRecorder = InMemoryTrainingActivityRecorder()
    private var completedActivity: RecordedTrainingActivity? = null
    private var activeSessionId: UUID? = null
    private var activeEquipment: SelectedRideEquipment? = null
    private val telemetryRegistrations = mutableListOf<AutoCloseable>()
    private var heartRateRegistration: AutoCloseable? = null
    private var recordingActive = false
    private var telemetryObserver: ((CyclingTelemetry) -> Unit)? = null
    private var sourceTelemetryObserver: ((String, CyclingTelemetry) -> Unit)? = null
    private var heartRateObserver: ((UUID, String, HeartRateTelemetry) -> Unit)? = null

    @Synchronized
    fun start(
        sessionId: UUID,
        startedAt: Instant,
        workout: ExecutableWorkout?,
        initialTargetPowerWatts: Int?,
        onTelemetry: (CyclingTelemetry) -> Unit,
        onHeartRate: (UUID, String, HeartRateTelemetry) -> Unit,
        equipment: SelectedRideEquipment,
        onSourceTelemetry: ((String, CyclingTelemetry) -> Unit)? = null,
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
        activeEquipment = equipment
        telemetryObserver = onTelemetry
        sourceTelemetryObserver = onSourceTelemetry
        heartRateObserver = onHeartRate
        completedActivity = null
        recordingActive = true
        registerTelemetry(sessionId)
        registerHeartRate(sessionId)
    }

    @Synchronized
    fun selectedHeartRateSourceId(): String? = activeEquipment?.heartRate?.sourceId

    @Synchronized
    fun currentHeartRate(): HeartRateTelemetry? = activeEquipment?.heartRate?.current()

    @Synchronized
    fun pauseRecording() {
        closeRecordingListeners()
        recordingActive = false
    }

    @Synchronized
    fun interruptTrainerRecording() {
        closeTelemetryListeners()
    }

    @Synchronized
    fun resumeRecording(sessionId: UUID) {
        check(activeSessionId == sessionId) { "No recording exists for session $sessionId" }
        recordingActive = true
        registerTelemetry(sessionId)
        registerHeartRate(sessionId)
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
    ): Boolean =
        recordHeartRateObservation(
            sessionId = sessionId,
            observation =
                RecordedHeartRateObservation(
                    receivedAt = telemetry.receivedAt,
                    heartRateBpm = telemetry.heartRateBpm,
                    sourceId = sourceId,
                ),
        )

    @Synchronized
    fun recordCyclingObservation(
        sessionId: UUID,
        observation: RecordedCyclingObservation,
    ): Boolean {
        if (!recordingActive || activeSessionId != sessionId) {
            return false
        }
        activityRecorder.recordCyclingObservation(sessionId, observation)
        return true
    }

    @Synchronized
    fun recordHeartRateObservation(
        sessionId: UUID,
        observation: RecordedHeartRateObservation,
    ): Boolean {
        if (!recordingActive || activeSessionId != sessionId) {
            return false
        }
        if (activeEquipment?.heartRate?.sourceId != observation.sourceId) {
            return false
        }
        activityRecorder.recordHeartRateObservation(sessionId, observation)
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
        activeEquipment = null
        telemetryObserver = null
        sourceTelemetryObserver = null
        heartRateObserver = null
        return activity
    }

    @Synchronized
    fun completedActivity(): RecordedTrainingActivity? = completedActivity

    @Synchronized
    fun discard(sessionId: UUID) {
        if (completedActivity?.sessionId == sessionId) {
            completedActivity = null
        }
    }

    private fun registerTelemetry(sessionId: UUID) {
        closeTelemetryListeners()
        val equipment = requireNotNull(activeEquipment) { "No ride equipment is bound to the recording" }
        val sources =
            listOfNotNull(
                equipment.controlTelemetry,
                equipment.powerTelemetry,
                equipment.cadenceTelemetry,
            ).distinctBy { source -> source.sourceId }
        sources.forEach { source ->
            telemetryRegistrations +=
                source.subscribe { telemetry ->
                    handleTelemetry(sessionId, source.sourceId, telemetry)
                }
        }
    }

    private fun handleTelemetry(
        sessionId: UUID,
        sourceId: String,
        telemetry: CyclingTelemetry,
    ) {
        val observers =
            synchronized(this) {
                if (!recordingActive || activeSessionId != sessionId) {
                    null
                } else {
                    sourceTelemetryObserver to telemetryObserver
                }
            }
        if (observers == null) {
            return
        }
        val (sourceObserver, genericObserver) = observers
        sourceObserver?.invoke(sourceId, telemetry) ?: genericObserver?.invoke(telemetry)
        val equipment = synchronized(this) { activeEquipment }
        val observation =
            RecordedCyclingObservation.fromSelected(
                telemetry,
                sourceId,
                requireNotNull(equipment).selection,
            )
        recordCyclingObservation(sessionId, observation)
    }

    private fun registerHeartRate(sessionId: UUID) {
        val source = activeEquipment?.heartRate
        heartRateRegistration =
            source?.let { selectedSource ->
                selectedSource.subscribe { telemetry ->
                    val observer =
                        synchronized(this) {
                            if (
                                !recordingActive ||
                                activeSessionId != sessionId ||
                                selectedHeartRateSourceId() != selectedSource.sourceId
                            ) {
                                null
                            } else {
                                heartRateObserver
                            }
                        }
                    observer?.invoke(sessionId, selectedSource.sourceId, telemetry)
                }
            }
    }

    private fun closeRecordingListeners() {
        closeTelemetryListeners()
        heartRateRegistration?.close()
        heartRateRegistration = null
    }

    private fun closeTelemetryListeners() {
        telemetryRegistrations.forEach(AutoCloseable::close)
        telemetryRegistrations.clear()
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
