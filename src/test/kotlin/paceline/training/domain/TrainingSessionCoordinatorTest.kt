package paceline.training.domain

import paceline.device.domain.ConnectionPhase
import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.DeviceEndpoint
import paceline.device.domain.HeartRateSourceDescriptor
import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.testsupport.FakeActivityUploader
import paceline.testsupport.FakeIndoorBikePowerControl
import paceline.testsupport.FakeTrainingDevice
import paceline.workout.domain.ExecutableSport
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutSourceType
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrainingSessionCoordinatorTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `cannot start a training session before a controlled device is connected`() {
        // given a session coordinator whose device connection is still ready:
        val session = TrainingSessionCoordinator(FakeTrainingDevice(), clock)

        // when a training session is started:
        // then the session remains not started because device control is unavailable:
        assertFailsWith<TrainingSessionUnavailableException> { session.start() }
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
    }

    @Test
    fun `starting a session acquires control and gates target changes`() {
        // given a connected device with an ERG power-control capability:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)

        // when the session is started and a target is changed:
        val started = session.start()
        val updated = session.setTargetPower(requireNotNull(started.sessionId), 300)

        // then control is requested once and the active session owns the target update:
        assertEquals(TrainingSessionPhase.ACTIVE, started.phase)
        assertEquals(1, powerControl.requestControlCalls)
        assertEquals(listOf(300), powerControl.targetPowers)
        assertEquals(300, updated.ergTargetPowerWatts)
    }

    @Test
    fun `requires an explicit source when multiple heart-rate devices are connected`() {
        // given a trainer and two connected heart-rate sources:
        val powerControl = FakeIndoorBikePowerControl()
        val trainingDevice =
            FakeTrainingDevice(
                powerControl = powerControl,
                availableHeartRateSources = listOf(heartRateSource("bridge"), heartRateSource("strap")),
            )
        val session = TrainingSessionCoordinator(trainingDevice, clock)

        // when a session is started without selecting one source:
        // then session creation is rejected before trainer control is requested:
        assertFailsWith<HeartRateSourceSelectionRequiredException> { session.start() }
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
        assertEquals(0, powerControl.requestControlCalls)
    }

    @Test
    fun `records only the explicitly selected heart-rate source and supports switching`() {
        // given a trainer and two connected heart-rate sources:
        val powerControl = FakeIndoorBikePowerControl()
        val trainingDevice =
            FakeTrainingDevice(
                powerControl = powerControl,
                availableHeartRateSources = listOf(heartRateSource("bridge"), heartRateSource("strap")),
            )
        val uploader = FakeActivityUploader()
        val session = TrainingSessionCoordinator(trainingDevice, clock, uploader)

        // when a session selects bridge HR, then switches to the strap:
        val started = session.start(heartRateSourceId = "bridge")
        val sessionId = requireNotNull(started.sessionId)
        trainingDevice.emitHeartRate("bridge", heartRate(140))
        session.selectHeartRateSource(sessionId, "strap")
        trainingDevice.emitHeartRate("bridge", heartRate(145))
        trainingDevice.emitHeartRate("strap", heartRate(155))
        session.stop(sessionId)
        session.upload(sessionId)

        // then the session state and raw activity preserve the selected-source sequence:
        assertEquals("strap", session.current().heartRateSourceId)
        assertEquals(155, session.current().heartRate?.heartRateBpm)
        assertEquals(
            listOf(140, 155),
            uploader.uploads
                .single()
                .samples
                .map { it.heartRateBpm },
        )
        assertEquals(
            listOf("bridge", "strap"),
            uploader.uploads
                .single()
                .samples
                .map { it.heartRateSourceId },
        )
    }

    @Test
    fun `merges an independently received selected heart-rate sample with trainer telemetry`() {
        // given a session with one selected heart-rate source:
        val powerControl = FakeIndoorBikePowerControl()
        val trainingDevice =
            FakeTrainingDevice(
                powerControl = powerControl,
                availableHeartRateSources = listOf(heartRateSource("strap")),
            )
        val uploader = FakeActivityUploader()
        val session = TrainingSessionCoordinator(trainingDevice, clock, uploader)
        val started = session.start(heartRateSourceId = "strap")
        val sessionId = requireNotNull(started.sessionId)
        val receivedAt = now.plusSeconds(1)

        // when trainer and heart-rate notifications arrive independently for the same timestamp:
        trainingDevice.emitTelemetry(telemetry(receivedAt = receivedAt, distanceMeters = 1_000.0))
        trainingDevice.emitHeartRate(
            "strap",
            HeartRateTelemetry(
                heartRateBpm = 151,
                receivedAt = receivedAt,
            ),
        )
        session.stop(sessionId)
        session.upload(sessionId)

        // then one raw observation retains both capability measurements and the source identity:
        val sample =
            uploader.uploads
                .single()
                .samples
                .single()
        assertEquals(receivedAt, sample.receivedAt)
        assertEquals(200, sample.powerWatts)
        assertEquals(151, sample.heartRateBpm)
        assertEquals("strap", sample.heartRateSourceId)
    }

    @Test
    fun `target changes are rejected without an active session`() {
        // given a connected device with ERG control but no started session:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val sessionId = java.util.UUID.randomUUID()

        // when a target is submitted before starting:
        // then no command is sent to the trainer:
        assertFailsWith<TrainingSessionNotActiveException> {
            session.setTargetPower(sessionId, 300)
        }
        assertEquals(emptyList(), powerControl.targetPowers)
    }

    @Test
    fun `a failed control request does not create a session`() {
        // given a connected device that rejects the control request:
        val powerControl =
            FakeIndoorBikePowerControl().also {
                it.requestControlFailure = IllegalStateException("control denied")
            }
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)

        // when the session is started:
        // then the failure is visible and the session remains not started:
        assertFailsWith<TrainingSessionUnavailableException> { session.start() }
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
        assertEquals(1, powerControl.requestControlCalls)
    }

    @Test
    fun `a started session remains bound to the control it acquired`() {
        // given a session that acquired control from one device:
        val firstPowerControl = FakeIndoorBikePowerControl()
        val trainingDevice = FakeTrainingDevice(firstPowerControl)
        val session = TrainingSessionCoordinator(trainingDevice, clock)
        val started = session.start()
        val secondPowerControl = FakeIndoorBikePowerControl()
        trainingDevice.powerControl = secondPowerControl

        // when the target is changed after the provider reports another device:
        session.setTargetPower(requireNotNull(started.sessionId), 300)

        // then the command stays bound to the capability that started the session:
        assertEquals(listOf(300), firstPowerControl.targetPowers)
        assertEquals(emptyList(), secondPowerControl.targetPowers)
    }

    @Test
    fun `stopping a session sends zero watts and marks it stopped`() {
        // given an active session with a previously selected ERG target:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val started = session.start()
        val sessionId = requireNotNull(started.sessionId)
        session.setTargetPower(sessionId, 300)

        // when the active session is stopped:
        val stopped = session.stop(sessionId)

        // then zero watts is sent before the session becomes a terminal stopped state:
        assertEquals(listOf(300, 0), powerControl.targetPowers)
        assertEquals(TrainingSessionPhase.STOPPED, stopped.phase)
        assertEquals(sessionId, stopped.sessionId)
        assertEquals(started.startedAt, stopped.startedAt)
        assertEquals(0, stopped.ergTargetPowerWatts)
        assertEquals(stopped, session.current())
    }

    @Test
    fun `stopped sessions reject further target changes`() {
        // given a session that has sent its zero-watt stop target:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)
        session.stop(sessionId)

        // when a target is submitted for the stopped session:
        // then no new target is sent to the trainer:
        assertFailsWith<TrainingSessionNotActiveException> {
            session.setTargetPower(sessionId, 300)
        }
        assertEquals(listOf(0), powerControl.targetPowers)
    }

    @Test
    fun `a failed zero-watt stop target leaves the session active`() {
        // given an active session whose device rejects its stop target:
        val powerControl =
            FakeIndoorBikePowerControl().also {
                it.targetPowerFailure = IllegalStateException("trainer unavailable")
            }
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)

        // when the active session is stopped:
        // then the failure is visible and the session remains active for retry:
        assertFailsWith<TrainingSessionUnavailableException> {
            session.stop(sessionId)
        }
        assertEquals(TrainingSessionPhase.ACTIVE, session.current().phase)
        assertEquals(emptyList(), powerControl.targetPowers)
    }

    @Test
    fun `finishing a workout keeps the session active for manual ERG continuation`() {
        // given a two-step workout whose power ranges resolve to different midpoint targets:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val workout =
            workout(
                timedStep("Work", seconds = 10, lowWatts = 200, highWatts = 300),
                timedStep("Recovery", seconds = 20, lowWatts = 100, highWatts = 100),
            )

        // when the workout reaches the first boundary and then its final boundary:
        val started = session.start(workout)
        val firstTransition = session.tick(now.plusSeconds(10))
        val completed = session.tick(now.plusSeconds(30))

        // then each step receives its midpoint and workout completion leaves the session open at zero watts:
        assertEquals(listOf(250, 100, 0), powerControl.targetPowers)
        assertEquals(2, firstTransition.workout?.currentStepNumber)
        assertEquals(TrainingSessionPhase.ACTIVE, completed.phase)
        assertEquals(true, completed.workout?.completed)
        assertEquals(0, completed.ergTargetPowerWatts)
        assertEquals(started.sessionId, completed.sessionId)

        // when the user selects a manual target after the planned workout:
        val continued = session.setTargetPower(requireNotNull(started.sessionId), 180)

        // then manual ERG control is available without starting a second session:
        assertEquals(180, continued.ergTargetPowerWatts)
        assertEquals(listOf(250, 100, 0, 180), powerControl.targetPowers)
    }

    @Test
    fun `updates the ERG target progressively during a timed ramp`() {
        // given a timed workout step with ordered ramp endpoints:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val workout =
            workout(
                ExecutableWorkoutStep(
                    text = "Ramp",
                    completion = WorkoutStepCompletion.Time(10),
                    target = WorkoutStepTarget.Ramp(startWatts = 100, endWatts = 200),
                ),
            )

        // when the scheduler observes the step at its midpoint and near its end:
        val started = session.start(workout)
        val halfway = session.tick(now.plusSeconds(5))
        val nearEnd = session.tick(now.plusSeconds(9))
        val completed = session.tick(now.plusSeconds(10))

        // then the trainer receives the interpolated targets and a safe zero at completion:
        assertEquals(listOf(100, 150, 190, 0), powerControl.targetPowers)
        assertEquals(150, halfway.ergTargetPowerWatts)
        assertEquals(190, nearEnd.ergTargetPowerWatts)
        assertEquals(0, completed.ergTargetPowerWatts)
        assertEquals(true, completed.workout?.completed)
        assertEquals(started.sessionId, completed.sessionId)
    }

    @Test
    fun `stopping records each telemetry notification once and uploads the in-memory activity`() {
        // given an active session and an uploader that records the submitted activity:
        val powerControl = FakeIndoorBikePowerControl()
        val trainingDevice = FakeTrainingDevice(powerControl)
        val uploader = FakeActivityUploader()
        val session = TrainingSessionCoordinator(trainingDevice, clock, uploader)
        val started = session.start()
        val firstSample = telemetry(receivedAt = now, distanceMeters = 1_000.0)
        val secondSample = telemetry(receivedAt = now.plusMillis(100), distanceMeters = 1_001.0)

        // when telemetry notifications include the same timestamp twice and the session is stopped and uploaded:
        trainingDevice.emitTelemetry(firstSample)
        trainingDevice.emitTelemetry(firstSample)
        trainingDevice.emitTelemetry(secondSample)
        val stopped = session.stop(requireNotNull(started.sessionId))
        val uploaded = session.upload(requireNotNull(started.sessionId))

        // then the upload is optional after stop and contains each notification timestamp once:
        assertEquals(TrainingActivityUploadPhase.AVAILABLE, stopped.activityUpload.phase)
        assertEquals(TrainingActivityUploadPhase.UPLOADED, uploaded.activityUpload.phase)
        assertEquals(
            listOf(firstSample.receivedAt, secondSample.receivedAt),
            uploader.uploads
                .single()
                .samples
                .map { it.receivedAt },
        )
    }

    @Test
    fun `recorded scheduled workouts retain step and manual continuation segments`() {
        // given a scheduled two-step workout and telemetry around each transition:
        val powerControl = FakeIndoorBikePowerControl()
        val trainingDevice = FakeTrainingDevice(powerControl)
        val uploader = FakeActivityUploader()
        var currentTime = now
        val mutableClock =
            object : Clock() {
                override fun instant(): Instant = currentTime

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this
            }
        val session = TrainingSessionCoordinator(trainingDevice, mutableClock, uploader)
        val workout =
            workout(
                timedStep("Work", seconds = 10, lowWatts = 200, highWatts = 300),
                timedStep("Recovery", seconds = 20, lowWatts = 100, highWatts = 100),
            ).copy(sourceType = WorkoutSourceType.SCHEDULED)
        val started = session.start(workout)
        val sessionId = requireNotNull(started.sessionId)

        // when the workout completes, the ride continues briefly, and the session is uploaded:
        trainingDevice.emitTelemetry(telemetry(receivedAt = now, distanceMeters = 1_000.0))
        session.tick(now.plusSeconds(10))
        trainingDevice.emitTelemetry(telemetry(receivedAt = now.plusSeconds(10), distanceMeters = 1_001.0))
        session.tick(now.plusSeconds(30))
        trainingDevice.emitTelemetry(telemetry(receivedAt = now.plusSeconds(30), distanceMeters = 1_002.0))
        currentTime = now.plusSeconds(31)
        session.stop(sessionId)
        session.upload(sessionId)
        val activity = uploader.uploads.single()

        // then the uploaded activity preserves the planned steps and the post-workout manual continuation:
        assertEquals(WorkoutSourceType.SCHEDULED, activity.workoutSourceType)
        assertEquals(
            listOf("Step 1/2: Work", "Step 2/2: Recovery", "Manual continuation"),
            activity.segments.map { it.name },
        )
        assertEquals(listOf(250, 100, null), activity.segments.map { it.targetPowerWatts })
        assertEquals(
            listOf(200, 100),
            activity.segments
                .take(2)
                .map { (it.workoutStep?.target as WorkoutStepTarget.Power).lowWatts },
        )
        assertEquals(null, activity.segments[2].workoutStep)
        assertEquals(listOf(1, 1, 1), activity.segments.map { it.samples.size })
        assertEquals(true, activity.workoutCompleted)
    }

    @Test
    fun `distance workout steps use the trainer distance counter`() {
        // given a distance step starting from the trainer's current total distance:
        val powerControl = FakeIndoorBikePowerControl()
        val trainingDevice =
            FakeTrainingDevice(
                powerControl = powerControl,
                telemetry = telemetry(distanceMeters = 1_000.0),
            )
        val session = TrainingSessionCoordinator(trainingDevice, clock)
        val workout =
            workout(
                distanceStep("Block", meters = 100.0, lowWatts = 200, highWatts = 200),
                timedStep("Finish", seconds = 1, lowWatts = 150, highWatts = 150),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)

        // when the trainer reports less than and then exactly the requested distance:
        trainingDevice.telemetry = telemetry(distanceMeters = 1_099.0)
        val beforeBoundary = session.tick(now.plusSeconds(1))
        trainingDevice.telemetry = telemetry(distanceMeters = 1_100.0)
        val afterBoundary = session.tick(now.plusSeconds(2))

        // then the step does not advance early and advances once the distance delta is met:
        assertEquals(1, beforeBoundary.workout?.currentStepNumber)
        assertEquals(2, afterBoundary.workout?.currentStepNumber)
        assertEquals(sessionId, afterBoundary.sessionId)
        assertEquals(listOf(200, 150), powerControl.targetPowers)
    }

    @Test
    fun `manual workout steps advance only through the explicit advance action`() {
        // given a workout beginning with a manual step:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val workout =
            workout(
                ExecutableWorkoutStep(
                    text = "Lap press",
                    completion = WorkoutStepCompletion.Manual,
                    target = WorkoutStepTarget.Open,
                ),
                timedStep("Finish", seconds = 1, lowWatts = 180, highWatts = 180),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)

        // when the explicit advance action is used:
        val advanced = session.advance(sessionId)

        // then the open step starts at zero watts and the next target is applied:
        assertEquals(2, advanced.workout?.currentStepNumber)
        assertEquals(180, advanced.ergTargetPowerWatts)
        assertEquals(listOf(0, 180), powerControl.targetPowers)
    }

    @Test
    fun `manual ERG target changes are rejected while a workout owns the target`() {
        // given an active executable workout:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val sessionId = requireNotNull(session.start(workout(timedStep("Work", 10, 200, 200))).sessionId)

        // when a manual target is submitted during workout execution:
        // then the current step remains the only owner of the ERG target:
        assertFailsWith<WorkoutTargetManagedException> {
            session.setTargetPower(sessionId, 300)
        }
        assertEquals(listOf(200), powerControl.targetPowers)
    }

    @Test
    fun `pausing a session sends zero watts and stops recording until resume`() {
        // given an active manual session with a selected ERG target and telemetry:
        val powerControl = FakeIndoorBikePowerControl()
        val trainingDevice = FakeTrainingDevice(powerControl)
        val uploader = FakeActivityUploader()
        val mutableClock = MutableTestClock(now)
        val session = TrainingSessionCoordinator(trainingDevice, mutableClock, uploader)
        val started = session.start()
        val sessionId = requireNotNull(started.sessionId)
        session.setTargetPower(sessionId, 300)
        val beforePause = telemetry(receivedAt = now.plusSeconds(1), distanceMeters = 1_000.0)
        trainingDevice.emitTelemetry(beforePause)

        // when the session is paused, a notification arrives, then the session resumes and records again:
        mutableClock.currentTime = now.plusSeconds(2)
        val paused = session.pause(sessionId)
        val duringPause = telemetry(receivedAt = now.plusSeconds(3), distanceMeters = 1_001.0)
        trainingDevice.emitTelemetry(duringPause)
        mutableClock.currentTime = now.plusSeconds(10)
        val resumed = session.resume(sessionId)
        val afterResume = telemetry(receivedAt = now.plusSeconds(11), distanceMeters = 1_002.0)
        trainingDevice.emitTelemetry(afterResume)
        session.stop(sessionId)
        session.upload(sessionId)

        // then the session is paused at zero watts, resumes its prior target, and excludes paused samples:
        assertEquals(TrainingSessionPhase.PAUSED, paused.phase)
        assertEquals(0, paused.ergTargetPowerWatts)
        assertEquals(TrainingSessionPhase.ACTIVE, resumed.phase)
        assertEquals(300, resumed.ergTargetPowerWatts)
        assertEquals(listOf(300, 0, 300, 0), powerControl.targetPowers)
        assertEquals(
            listOf(beforePause.receivedAt, afterResume.receivedAt),
            uploader.uploads
                .single()
                .samples
                .map { it.receivedAt },
        )
    }

    @Test
    fun `paused workout timing resumes from the remaining step duration`() {
        // given a timed workout that has run for part of its first step:
        val powerControl = FakeIndoorBikePowerControl()
        val mutableClock = MutableTestClock(now)
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), mutableClock)
        val workout = workout(timedStep("Work", seconds = 10, lowWatts = 200, highWatts = 200))
        val sessionId = requireNotNull(session.start(workout).sessionId)
        mutableClock.currentTime = now.plusSeconds(5)

        // when the workout is paused for twenty seconds, resumed, and advanced by four more seconds:
        val paused = session.pause(sessionId)
        mutableClock.currentTime = now.plusSeconds(25)
        val pausedTick = session.tick(mutableClock.currentTime)
        val resumed = session.resume(sessionId)
        mutableClock.currentTime = now.plusSeconds(29)
        val beforeCompletion = session.tick(mutableClock.currentTime)

        // then paused wall-clock time does not consume the timed step:
        assertEquals(TrainingSessionPhase.PAUSED, paused.phase)
        assertEquals(1, pausedTick.workout?.currentStepNumber)
        assertEquals(now.plusSeconds(20), resumed.workout?.stepStartedAt)
        assertEquals(1, beforeCompletion.workout?.currentStepNumber)

        // when the remaining step duration elapses after resume:
        mutableClock.currentTime = now.plusSeconds(30)
        val completed = session.tick(mutableClock.currentTime)

        // then the workout completes only at the adjusted boundary:
        assertEquals(true, completed.workout?.completed)
        assertEquals(listOf(200, 0, 200, 0), powerControl.targetPowers)
    }

    @Test
    fun `paused distance workout ignores trainer distance accumulated during the pause`() {
        // given a distance workout with thirty meters completed before pausing:
        val powerControl = FakeIndoorBikePowerControl()
        val trainingDevice = FakeTrainingDevice(powerControl, telemetry = telemetry(distanceMeters = 1_000.0))
        val mutableClock = MutableTestClock(now)
        val session = TrainingSessionCoordinator(trainingDevice, mutableClock)
        val workout =
            workout(
                distanceStep("Block", meters = 100.0, lowWatts = 200, highWatts = 200),
                timedStep("Finish", seconds = 1, lowWatts = 150, highWatts = 150),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)
        trainingDevice.telemetry = telemetry(distanceMeters = 1_030.0)
        mutableClock.currentTime = now.plusSeconds(1)

        // when the trainer moves fifty meters while paused and then moves sixty-nine meters after resume:
        session.pause(sessionId)
        trainingDevice.telemetry = telemetry(distanceMeters = 1_080.0)
        mutableClock.currentTime = now.plusSeconds(20)
        session.resume(sessionId)
        trainingDevice.telemetry = telemetry(distanceMeters = 1_149.0)
        val beforeBoundary = session.tick(now.plusSeconds(21), trainingDevice.telemetry)

        // then distance accumulated while paused is not counted:
        assertEquals(1, beforeBoundary.workout?.currentStepNumber)

        // when the trainer reaches seventy post-resume meters:
        trainingDevice.telemetry = telemetry(distanceMeters = 1_150.0)
        val atBoundary = session.tick(now.plusSeconds(22), trainingDevice.telemetry)

        // then the thirty pre-pause meters plus seventy post-resume meters complete the step:
        assertEquals(2, atBoundary.workout?.currentStepNumber)
    }

    @Test
    fun `stopping a paused session finalizes the recording and remains safe`() {
        // given an active session with a recorded sample:
        val powerControl = FakeIndoorBikePowerControl()
        val trainingDevice = FakeTrainingDevice(powerControl)
        val uploader = FakeActivityUploader()
        val session = TrainingSessionCoordinator(trainingDevice, clock, uploader)
        val sessionId = requireNotNull(session.start().sessionId)
        trainingDevice.emitTelemetry(telemetry(distanceMeters = 1_000.0))

        // when the session is paused and stopped without resuming:
        session.pause(sessionId)
        val stopped = session.stop(sessionId)

        // then stopping sends the safe target again and makes the pre-pause recording available:
        assertEquals(TrainingSessionPhase.STOPPED, stopped.phase)
        assertEquals(listOf(0, 0), powerControl.targetPowers)
        assertEquals(TrainingActivityUploadPhase.AVAILABLE, stopped.activityUpload.phase)
    }

    @Test
    fun `a failed pause target leaves the session active and recording`() {
        // given an active session whose trainer rejects the pause target:
        val powerControl =
            FakeIndoorBikePowerControl().also {
                it.targetPowerFailure = IllegalStateException("trainer unavailable")
            }
        val trainingDevice = FakeTrainingDevice(powerControl)
        val uploader = FakeActivityUploader()
        val session = TrainingSessionCoordinator(trainingDevice, clock, uploader)
        val sessionId = requireNotNull(session.start().sessionId)

        // when the session is paused:
        assertFailsWith<TrainingSessionUnavailableException> {
            session.pause(sessionId)
        }

        // then the state and recording subscription remain active for a retry:
        assertEquals(TrainingSessionPhase.ACTIVE, session.current().phase)
        assertEquals(emptyList(), powerControl.targetPowers)
        powerControl.targetPowerFailure = null
        trainingDevice.emitTelemetry(telemetry(distanceMeters = 1_000.0))
        session.stop(sessionId)
        session.upload(sessionId)
        assertEquals(
            1,
            uploader.uploads
                .single()
                .samples
                .size,
        )
    }

    @Test
    fun `a failed resume target leaves the session paused`() {
        // given a paused session whose trainer rejects the restored target:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)
        session.pause(sessionId)
        powerControl.targetPowerFailure = IllegalStateException("trainer unavailable")

        // when the paused session is resumed:
        assertFailsWith<TrainingSessionUnavailableException> {
            session.resume(sessionId)
        }

        // then the session remains paused and no recording subscription is reopened:
        assertEquals(TrainingSessionPhase.PAUSED, session.current().phase)
        assertEquals(0, session.current().ergTargetPowerWatts)
        assertEquals(listOf(0), powerControl.targetPowers)
    }

    private class MutableTestClock(
        var currentTime: Instant,
    ) : Clock() {
        override fun instant(): Instant = currentTime

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun workout(vararg steps: ExecutableWorkoutStep): ExecutableWorkout =
        ExecutableWorkout(
            source = WorkoutSourceReference("intervals.icu", "workout-1"),
            name = "Test workout",
            sport = ExecutableSport.CYCLING,
            steps = steps.toList(),
        )

    private fun timedStep(
        text: String,
        seconds: Int,
        lowWatts: Int,
        highWatts: Int,
    ): ExecutableWorkoutStep =
        ExecutableWorkoutStep(
            text = text,
            completion = WorkoutStepCompletion.Time(seconds),
            target = WorkoutStepTarget.Power(lowWatts, highWatts),
        )

    private fun distanceStep(
        text: String,
        meters: Double,
        lowWatts: Int,
        highWatts: Int,
    ): ExecutableWorkoutStep =
        ExecutableWorkoutStep(
            text = text,
            completion = WorkoutStepCompletion.Distance(meters),
            target = WorkoutStepTarget.Power(lowWatts, highWatts),
        )

    private fun telemetry(
        receivedAt: Instant = now,
        distanceMeters: Double,
    ): IndoorBikeTelemetry =
        IndoorBikeTelemetry(
            powerWatts = 200,
            cadenceRpm = 90.0,
            speedKph = 25.0,
            distanceMeters = distanceMeters,
            receivedAt = receivedAt,
        )

    private fun heartRateSource(id: String): HeartRateSourceDescriptor =
        HeartRateSourceDescriptor(
            id = id,
            device =
                DeviceAdvertisement(
                    name = id,
                    endpoint = DeviceEndpoint.Bluetooth("AA:BB:CC:DD:EE:${if (id == "bridge") "01" else "02"}", "11:22:33:44:55:66"),
                ),
            state = ConnectionPhase.CONNECTED,
        )

    private fun heartRate(bpm: Int): HeartRateTelemetry =
        HeartRateTelemetry(
            heartRateBpm = bpm,
            receivedAt = now.plusSeconds(bpm.toLong()),
        )
}
