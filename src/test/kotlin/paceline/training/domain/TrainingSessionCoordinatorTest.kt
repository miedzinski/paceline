package paceline.training.domain

import org.awaitility.Awaitility
import paceline.device.domain.ConnectionPhase
import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.DeviceEndpoint
import paceline.telemetry.config.TelemetryProperties
import paceline.telemetry.domain.CyclingTelemetry
import paceline.telemetry.domain.HeartRateTelemetry
import paceline.telemetry.domain.TelemetryAvailability
import paceline.testsupport.FakeActivityUploader
import paceline.testsupport.FakeRideSourceCatalog
import paceline.testsupport.FakeTrainerControl
import paceline.testsupport.rideSource
import paceline.training.config.ErgProtectionProperties
import paceline.training.ports.ActivityUploadException
import paceline.workout.domain.ExecutableSport
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutSourceType
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TrainingSessionCoordinatorTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `cannot start a training session before a controlled device is connected`() {
        // given a session coordinator whose device connection is still ready:
        val session = coordinator(FakeRideSourceCatalog(), clock)

        // when a training session is started:
        // then the session remains not started because device control is unavailable:
        assertFailsWith<RideEquipmentUnavailableException> { session.start() }
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
    }

    @Test
    fun `starting a session acquires control and gates target changes`() {
        // given a connected device with an ERG power-control capability:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)

        // when the session is started in Free Ride and a target is changed:
        val started = session.start()
        val updated = session.setTargetPower(requireNotNull(started.sessionId), 300)

        // then control is requested, the trainer is started, Free Ride is selected, and the target update switches to ERG:
        assertEquals(TrainingSessionPhase.ACTIVE, started.phase)
        assertEquals(1, powerControl.requestControlCalls)
        assertEquals(1, powerControl.startOrResumeCalls)
        assertEquals(1, powerControl.freeRideCalls)
        assertEquals(TrainingControlMode.FREE_RIDE, started.controlMode)
        assertEquals(listOf(300), powerControl.targetPowers)
        assertEquals(TrainingControlMode.ERG, updated.controlMode)
        assertEquals(300, updated.ergTargetPowerWatts)
    }

    @Test
    fun trainerMustAcceptStartBeforeSessionBecomesActive() {
        // given a connected trainer that rejects its initial Start/Resume command:
        val control =
            FakeTrainerControl().also {
                it.startOrResumeFailure = IllegalStateException("trainer unavailable")
            }
        val session = coordinator(FakeRideSourceCatalog(control), clock)

        // when the session is started:
        assertFailsWith<TrainingSessionUnavailableException> { session.start() }

        // then no active session or initial riding mode is created:
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
        assertEquals(1, control.requestControlCalls)
        assertEquals(1, control.startOrResumeCalls)
        assertEquals(0, control.freeRideCalls)
        assertEquals(emptyList(), control.targetPowers)
    }

    @Test
    fun `starting a session needs control but not power or cadence telemetry`() {
        // given a connected FTMS source that exposes only resistance control:
        val control = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                availableRideSources =
                    listOf(
                        rideSource(
                            "ftms-control",
                            setOf(RideSourceCapability.RESISTANCE_CONTROL),
                        ),
                    ),
            ).also {
                it.sourcePowerControls["ftms-control"] = control
            }
        val session = coordinator(rideSourceCatalog, clock)

        // when the ride is started without telemetry sources:
        val started = session.start()

        // then the control-only trainer starts successfully and telemetry roles remain unassigned:
        assertEquals(TrainingSessionPhase.ACTIVE, started.phase)
        assertEquals("ftms-control", started.equipment?.selection?.controlSourceId)
        assertNull(started.equipment?.selection?.powerSourceId)
        assertNull(started.equipment?.selection?.cadenceSourceId)
        assertEquals(RideRoleStatus.OPTIONAL, started.equipment?.power?.status)
        assertEquals(RideRoleStatus.OPTIONAL, started.equipment?.cadence?.status)
        assertEquals(1, control.requestControlCalls)
        assertEquals(1, control.startOrResumeCalls)
        assertEquals(1, control.freeRideCalls)
    }

    @Test
    fun `does not require a heart-rate source when multiple devices are connected`() {
        // given a trainer and two connected heart-rate sources:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = powerControl,
                availableHeartRateSources = listOf(heartRateSource("bridge"), heartRateSource("strap")),
            )
        val session = coordinator(rideSourceCatalog, clock)

        // when a session is started without selecting one source:
        // then control is acquired and the session starts without heart-rate recording:
        val started = session.start()
        assertEquals(TrainingSessionPhase.ACTIVE, started.phase)
        assertNull(started.equipment?.selection?.heartRateSourceId)
        assertEquals(1, powerControl.requestControlCalls)
    }

    @Test
    fun `starts without heart rate when the selected source disconnects before start`() {
        // given a trainer and one heart-rate source that is selected automatically:
        val powerControl = FakeTrainerControl()
        val strap = heartRateSource("strap")
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = powerControl,
                availableHeartRateSources = listOf(strap),
            )
        val session = coordinator(rideSourceCatalog, clock)
        assertEquals("strap", session.equipment().selection.heartRateSourceId)

        // when the selected heart-rate source disconnects before the session starts:
        rideSourceCatalog.availableHeartRateSources =
            listOf(strap.copy(state = ConnectionPhase.DISCONNECTED))
        val started = session.start()

        // then control starts while the selected heart-rate stream is unavailable:
        assertEquals(TrainingSessionPhase.ACTIVE, started.phase)
        assertEquals("strap", started.equipment?.selection?.heartRateSourceId)
        assertEquals(RideRoleStatus.UNAVAILABLE, started.equipment?.heartRate?.status)
        assertEquals(TelemetryAvailability.UNAVAILABLE, started.telemetry?.heartRate?.availability)
        assertEquals(1, powerControl.requestControlCalls)
    }

    @Test
    fun `records only the explicitly selected heart-rate source`() {
        // given a trainer and two connected heart-rate sources:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = powerControl,
                availableHeartRateSources = listOf(heartRateSource("bridge"), heartRateSource("strap")),
            )
        val uploader = FakeActivityUploader()
        val session = coordinator(rideSourceCatalog, clock, uploader)

        // when a session starts with bridge HR selected:
        val started =
            session.start(
                equipment = RideEquipmentSelection(heartRateSourceId = "bridge"),
            )
        val sessionId = requireNotNull(started.sessionId)
        rideSourceCatalog.emitHeartRate("bridge", heartRate(140))
        rideSourceCatalog.emitHeartRate("strap", heartRate(145))
        rideSourceCatalog.emitHeartRate("bridge", heartRate(155))
        val stopped = session.stop(sessionId)
        session.upload(sessionId)

        // then the session keeps the start-time selection and records only that source:
        assertEquals("bridge", started.equipment?.selection?.heartRateSourceId)
        assertEquals(
            155,
            stopped
                .telemetry
                ?.heartRate
                ?.sample
                ?.heartRateBpm,
        )
        assertEquals(
            listOf(140, 155),
            uploader.uploads
                .single()
                .samples
                .map { it.heartRateBpm },
        )
        assertEquals(
            listOf("bridge", "bridge"),
            uploader.uploads
                .single()
                .samples
                .map { it.heartRateSourceId },
        )
    }

    @Test
    fun `merges an independently received selected heart-rate sample with trainer telemetry`() {
        // given a session with one selected heart-rate source:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = powerControl,
                availableHeartRateSources = listOf(heartRateSource("strap")),
            )
        val uploader = FakeActivityUploader()
        val session = coordinator(rideSourceCatalog, clock, uploader)
        val started =
            session.start(
                equipment = RideEquipmentSelection(heartRateSourceId = "strap"),
            )
        val sessionId = requireNotNull(started.sessionId)
        val receivedAt = now.plusSeconds(1)

        // when trainer and heart-rate notifications arrive independently for the same timestamp:
        rideSourceCatalog.emitTelemetry(telemetry(receivedAt = receivedAt, distanceMeters = 1_000.0))
        rideSourceCatalog.emitHeartRate(
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
    fun `exposes selected cycling and heart-rate values through one session telemetry projection`() {
        // given a trainer with cycling telemetry and one selected heart-rate source:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = powerControl,
                telemetryCapabilityAvailable = true,
                availableHeartRateSources = listOf(heartRateSource("strap")),
            )
        val session = coordinator(rideSourceCatalog, clock)
        val started =
            session.start(
                equipment = RideEquipmentSelection(heartRateSourceId = "strap"),
            )
        val sessionId = requireNotNull(started.sessionId)
        val cycling = telemetry(distanceMeters = 1_000.0)
        val heartRate = HeartRateTelemetry(heartRateBpm = 152, receivedAt = now)

        // when both selected streams publish their latest values:
        rideSourceCatalog.emitTelemetry(cycling)
        rideSourceCatalog.emitHeartRate("strap", heartRate)
        val current = session.current()

        // then the session exposes them under the same telemetry projection:
        assertEquals(cycling, current.telemetry?.cycling?.sample)
        assertEquals(heartRate, current.telemetry?.heartRate?.sample)
        assertEquals(TelemetryAvailability.CURRENT, current.telemetry?.heartRate?.availability)
        session.stop(sessionId)
    }

    @Test
    fun `pause response preserves fresh cycling telemetry without a projection gap`() {
        // given an active telemetry-capable session with a fresh trainer reading:
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = FakeTrainerControl(),
                telemetryCapabilityAvailable = true,
            )
        val session = coordinator(rideSourceCatalog, clock)
        val sessionId = requireNotNull(session.start().sessionId)
        val latest = telemetry(receivedAt = now, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(latest)
        session.current()

        // when the session is paused:
        val paused = session.pause(sessionId)

        // then the immediate pause response keeps the fresh power and cadence projection:
        assertEquals(TrainingSessionPhase.PAUSED, paused.phase)
        assertEquals(latest, paused.telemetry?.cycling?.sample)
        assertEquals(TelemetryAvailability.CURRENT, paused.telemetry?.cycling?.availability)
    }

    @Test
    fun `keeps selected live telemetry current while a session is paused`() {
        // given a paused session with selected cycling and heart-rate sources:
        val control = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = control,
                telemetryCapabilityAvailable = true,
                availableHeartRateSources = listOf(heartRateSource("strap")),
            )
        val mutableClock = MutableTestClock(now)
        val session = coordinator(rideSourceCatalog, mutableClock)
        val started = session.start(equipment = RideEquipmentSelection(heartRateSourceId = "strap"))
        val sessionId = requireNotNull(started.sessionId)
        session.pause(sessionId)

        // when both sources publish new readings during the pause:
        val receivedAt = now.plusSeconds(1)
        mutableClock.currentTime = receivedAt
        val cycling = telemetry(receivedAt = receivedAt, distanceMeters = 1_001.0)
        val heartRate = HeartRateTelemetry(heartRateBpm = 153, receivedAt = receivedAt)
        rideSourceCatalog.emitTelemetry(cycling)
        rideSourceCatalog.emitHeartRate("strap", heartRate)
        val paused = session.current()

        // then fresh readings remain visible without resuming or sending further control commands:
        assertEquals(TrainingSessionPhase.PAUSED, paused.phase)
        assertEquals(cycling, paused.telemetry?.cycling?.sample)
        assertEquals(heartRate, paused.telemetry?.heartRate?.sample)
        assertEquals(TelemetryAvailability.CURRENT, paused.telemetry?.cycling?.availability)
        assertEquals(TelemetryAvailability.CURRENT, paused.telemetry?.heartRate?.availability)
        assertEquals(listOf("target:0", "pause"), control.powerAndLifecycleCommands)

        // when the sources stop sending and the freshness window expires during the pause:
        mutableClock.currentTime = receivedAt.plusSeconds(20)
        val stale = session.current()

        // then old values are withheld and the connection is not treated as a workout resume:
        assertEquals(TelemetryAvailability.INTERRUPTED, stale.telemetry?.cycling?.availability)
        assertNull(stale.telemetry?.cycling?.sample)
        assertEquals(TelemetryAvailability.INTERRUPTED, stale.telemetry?.heartRate?.availability)
        assertNull(stale.telemetry?.heartRate?.sample)
        assertEquals(TrainingSessionPhase.PAUSED, stale.phase)
        assertEquals(listOf("target:0", "pause"), control.powerAndLifecycleCommands)
    }

    @Test
    fun `marks a selected heart-rate stream interrupted after its freshness window`() {
        // given a selected heart-rate source with a two-second freshness window:
        val mutableClock = MutableTestClock(now)
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = FakeTrainerControl(),
                availableHeartRateSources = listOf(heartRateSource("strap")),
            )
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = mutableClock,
                telemetryProperties = TelemetryProperties(freshness = Duration.ofSeconds(2)),
            )
        val sessionId =
            requireNotNull(
                session
                    .start(equipment = RideEquipmentSelection(heartRateSourceId = "strap"))
                    .sessionId,
            )
        val first = HeartRateTelemetry(heartRateBpm = 150, receivedAt = now)
        rideSourceCatalog.emitHeartRate("strap", first)

        // when no newer selected heart-rate notification arrives after the freshness window:
        mutableClock.currentTime = now.plusSeconds(3)
        val interrupted = session.current()

        // then the last value is withheld and only its receipt time remains visible:
        assertEquals(TelemetryAvailability.INTERRUPTED, interrupted.telemetry?.heartRate?.availability)
        assertNull(interrupted.telemetry?.heartRate?.sample)
        assertEquals(now, interrupted.telemetry?.heartRate?.lastReceivedAt)

        // when the selected source publishes a new notification:
        val recoveredAt = now.plusSeconds(4)
        mutableClock.currentTime = recoveredAt
        val recovered = HeartRateTelemetry(heartRateBpm = 152, receivedAt = recoveredAt)
        rideSourceCatalog.emitHeartRate("strap", recovered)

        // then the projection becomes current again with the new sample:
        val current = session.current()
        assertEquals(TelemetryAvailability.CURRENT, current.telemetry?.heartRate?.availability)
        assertEquals(recovered, current.telemetry?.heartRate?.sample)
        session.stop(sessionId)
    }

    @Test
    fun `target changes are rejected without an active session`() {
        // given a connected device with ERG control but no started session:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
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
            FakeTrainerControl().also {
                it.requestControlFailure = IllegalStateException("control denied")
            }
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)

        // when the session is started:
        // then the failure is visible and the session remains not started:
        assertFailsWith<TrainingSessionUnavailableException> { session.start() }
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
        assertEquals(1, powerControl.requestControlCalls)
    }

    @Test
    fun `a failed initial Free Ride command does not create a session`() {
        // given a connected device that rejects the initial Free Ride command:
        val powerControl =
            FakeTrainerControl().also {
                it.freeRideFailure = IllegalStateException("free ride rejected")
            }
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)

        // when a manual session is started:
        // then the failure is visible and the session remains not started:
        assertFailsWith<TrainingSessionUnavailableException> { session.start() }
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
        assertEquals(1, powerControl.requestControlCalls)
        assertEquals(1, powerControl.freeRideCalls)
    }

    @Test
    fun `a started session remains bound to the control it acquired`() {
        // given a session that acquired control from one device:
        val firstPowerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(firstPowerControl)
        val session = coordinator(rideSourceCatalog, clock)
        val started = session.start()
        val secondPowerControl = FakeTrainerControl()
        rideSourceCatalog.powerControl = secondPowerControl

        // when the target is changed after the provider reports another device:
        session.setTargetPower(requireNotNull(started.sessionId), 300)

        // then the command stays bound to the capability that started the session:
        assertEquals(listOf(300), firstPowerControl.targetPowers)
        assertEquals(emptyList(), secondPowerControl.targetPowers)
    }

    @Test
    fun `selected control source owns commands and recovery`() {
        // given two connected trainers and an explicit ride assignment to the second trainer:
        val firstPowerControl = FakeTrainerControl()
        val secondPowerControl = FakeTrainerControl()
        val recoveredPowerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                availableRideSources =
                    listOf(
                        rideSource("trainer-a", trainerCapabilities()),
                        rideSource("trainer-b", trainerCapabilities()),
                    ),
            ).also {
                it.sourcePowerControls["trainer-a"] = firstPowerControl
                it.sourcePowerControls["trainer-b"] = secondPowerControl
            }
        val session = coordinator(rideSourceCatalog, clock)
        session.selectEquipment(
            RideEquipmentSelection(
                controlSourceId = "trainer-b",
                powerSourceId = "trainer-b",
                cadenceSourceId = "trainer-b",
            ),
        )

        // when the ride starts, loses the selected trainer, and later recovers:
        val started = session.start()
        val sessionId = requireNotNull(started.sessionId)
        session.setTargetPower(sessionId, 250)
        rideSourceCatalog.availableRideSources =
            rideSourceCatalog.availableRideSources.map { source ->
                if (source.id == "trainer-b") source.copy(state = ConnectionPhase.DISCONNECTED) else source
            }
        rideSourceCatalog.sourcePowerControls["trainer-b"] = null
        rideSourceCatalog.sourceReconnectResults["trainer-b"] = recoveredPowerControl
        session.tick(now.plusSeconds(1), null)
        var recoveryTime = now.plusSeconds(1)
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            recoveryTime = recoveryTime.plusSeconds(1)
            session.tick(recoveryTime, null)
            assertEquals(listOf(250), recoveredPowerControl.targetPowers)
        }

        // then control and recovery commands stay on trainer-b and never fall back to trainer-a:
        assertEquals("trainer-b", started.equipment?.selection?.controlSourceId)
        assertEquals(1, secondPowerControl.requestControlCalls)
        assertEquals(1, secondPowerControl.freeRideCalls)
        assertEquals(listOf(250), secondPowerControl.targetPowers)
        assertEquals(emptyList(), firstPowerControl.targetPowers)
        assertEquals(0, firstPowerControl.requestControlCalls)
        assertEquals(listOf("trainer-b"), rideSourceCatalog.sourceReconnectCalls)
    }

    @Test
    fun `execution and recording keep power cadence and control telemetry source-specific`() {
        // given independent selected power and cadence sources alongside the control trainer:
        val controlPowerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                availableRideSources =
                    listOf(
                        rideSource(
                            "control",
                            setOf(
                                RideSourceCapability.RESISTANCE_CONTROL,
                                RideSourceCapability.POWER,
                                RideSourceCapability.CADENCE,
                            ),
                        ),
                        rideSource("power", setOf(RideSourceCapability.POWER)),
                        rideSource("cadence", setOf(RideSourceCapability.CADENCE)),
                    ),
            ).also {
                it.sourcePowerControls["control"] = controlPowerControl
                it.emitTelemetry(
                    "control",
                    CyclingTelemetry(
                        powerWatts = 180,
                        cadenceRpm = 80.0,
                        speedKph = 25.0,
                        distanceMeters = 1_000.0,
                        receivedAt = now,
                    ),
                )
                it.emitTelemetry(
                    "power",
                    CyclingTelemetry(powerWatts = 300, distanceMeters = 5_000.0, receivedAt = now),
                )
                it.emitTelemetry(
                    "cadence",
                    CyclingTelemetry(cadenceRpm = 90.0, distanceMeters = 9_000.0, receivedAt = now),
                )
            }
        val uploader = FakeActivityUploader()
        val mutableClock = MutableTestClock(now)
        val session =
            coordinator(
                rideSourceCatalog,
                mutableClock,
                uploader,
            )
        session.selectEquipment(
            RideEquipmentSelection(
                controlSourceId = "control",
                powerSourceId = "power",
                cadenceSourceId = "cadence",
            ),
        )
        val workout =
            workout(
                distanceStep("Distance", meters = 100.0, lowWatts = 200, highWatts = 200),
                timedStep("Finish", seconds = 10, lowWatts = 150, highWatts = 150),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)

        // when each selected source reports its own observation and the control trainer advances its distance:
        val beforeBoundary = now.plusSeconds(1)
        rideSourceCatalog.emitTelemetry(
            "control",
            CyclingTelemetry(speedKph = 28.0, distanceMeters = 1_099.0, receivedAt = beforeBoundary),
        )
        rideSourceCatalog.emitTelemetry(
            "power",
            CyclingTelemetry(powerWatts = 325, distanceMeters = 9_999.0, receivedAt = beforeBoundary.plusMillis(1)),
        )
        rideSourceCatalog.emitTelemetry(
            "cadence",
            CyclingTelemetry(cadenceRpm = 92.0, distanceMeters = 8_888.0, receivedAt = beforeBoundary.plusMillis(2)),
        )
        val beforeDistanceBoundary =
            session.tick(
                beforeBoundary,
                CyclingTelemetry(distanceMeters = 9_999.0, receivedAt = beforeBoundary),
            )
        rideSourceCatalog.emitTelemetry(
            "control",
            CyclingTelemetry(speedKph = 28.0, distanceMeters = 1_100.0, receivedAt = now.plusSeconds(2)),
        )
        val afterDistanceBoundary = session.tick(now.plusSeconds(2))
        mutableClock.currentTime = now.plusSeconds(2)
        session.stop(sessionId)
        session.upload(sessionId)

        // then execution uses control distance, while raw recording retains only each selected source's fields:
        assertEquals(1, beforeDistanceBoundary.workout?.currentStepNumber)
        assertEquals(2, afterDistanceBoundary.workout?.currentStepNumber)
        assertEquals(listOf(200, 150, 0), controlPowerControl.targetPowers)
        assertEquals(1, controlPowerControl.stopCalls)
        val activity = uploader.uploads.single()
        val controlObservations = activity.cyclingObservations.filter { it.sourceId == "control" }
        val powerObservations = activity.cyclingObservations.filter { it.sourceId == "power" }
        val cadenceObservations = activity.cyclingObservations.filter { it.sourceId == "cadence" }
        assertEquals(2, controlObservations.size)
        assertNull(controlObservations[0].powerWatts)
        assertNull(controlObservations[0].cadenceRpm)
        assertEquals(1_099.0, controlObservations[0].distanceMeters)
        assertEquals(listOf(325), powerObservations.map { it.powerWatts })
        assertNull(powerObservations.single().cadenceRpm)
        assertNull(powerObservations.single().distanceMeters)
        assertEquals(listOf(92.0), cadenceObservations.map { it.cadenceRpm })
        assertNull(cadenceObservations.single().powerWatts)
        assertNull(cadenceObservations.single().distanceMeters)
    }

    @Test
    fun `stale independent power and cadence are absent from live session telemetry`() {
        // given a selected control trainer and independent power and cadence sources with a short freshness window:
        val controlPowerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                availableRideSources =
                    listOf(
                        rideSource(
                            "control",
                            setOf(
                                RideSourceCapability.RESISTANCE_CONTROL,
                                RideSourceCapability.POWER,
                                RideSourceCapability.CADENCE,
                            ),
                        ),
                        rideSource("power", setOf(RideSourceCapability.POWER)),
                        rideSource("cadence", setOf(RideSourceCapability.CADENCE)),
                    ),
            ).also {
                it.sourcePowerControls["control"] = controlPowerControl
                it.emitTelemetry(
                    "control",
                    CyclingTelemetry(cadenceRpm = 90.0, receivedAt = now),
                )
                it.emitTelemetry(
                    "power",
                    CyclingTelemetry(powerWatts = 300, receivedAt = now),
                )
                it.emitTelemetry(
                    "cadence",
                    CyclingTelemetry(cadenceRpm = 20.0, receivedAt = now),
                )
            }
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = clock,
                ergProtectionProperties =
                    ergProtectionProperties(
                        lowCadenceDuration = Duration.ofSeconds(1),
                    ),
                telemetryProperties = TelemetryProperties(freshness = Duration.ofSeconds(2)),
            )
        session.selectEquipment(
            RideEquipmentSelection(
                controlSourceId = "control",
                powerSourceId = "power",
                cadenceSourceId = "cadence",
            ),
        )
        val sessionId = requireNotNull(session.start().sessionId)
        session.setTargetPower(sessionId, 300)

        // when only the control trainer reports a fresh high cadence sample after the independent source goes quiet:
        rideSourceCatalog.emitTelemetry(
            "control",
            CyclingTelemetry(cadenceRpm = 90.0, receivedAt = now.plusSeconds(3)),
        )
        val state = session.tick(now.plusSeconds(3))

        // then stale independent values are absent rather than triggering a protective command:
        assertEquals(listOf(300), controlPowerControl.targetPowers)
        assertEquals(300, state.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.INACTIVE, state.ergProtection.status)
        assertNull(
            state
                .telemetry
                ?.cycling
                ?.sample
                ?.powerWatts,
        )
        assertNull(
            state
                .telemetry
                ?.cycling
                ?.sample
                ?.cadenceRpm,
        )
        session.stop(sessionId)
    }

    @Test
    fun `a lost selected control source stays unavailable and cannot fall back to another trainer`() {
        // given a ride explicitly bound to trainer-b while trainer-a is also connected:
        val trainerAControl = FakeTrainerControl()
        val trainerBControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                availableRideSources =
                    listOf(
                        rideSource("trainer-a", trainerCapabilities()),
                        rideSource("trainer-b", trainerCapabilities()),
                    ),
            ).also {
                it.sourcePowerControls["trainer-a"] = trainerAControl
                it.sourcePowerControls["trainer-b"] = trainerBControl
            }
        val session = coordinator(rideSourceCatalog, clock)
        session.selectEquipment(
            RideEquipmentSelection(
                controlSourceId = "trainer-b",
                powerSourceId = "trainer-b",
                cadenceSourceId = "trainer-b",
            ),
        )
        val started = session.start()
        val sessionId = requireNotNull(started.sessionId)
        rideSourceCatalog.availableRideSources =
            rideSourceCatalog.availableRideSources.map { source ->
                if (source.id == "trainer-b") source.copy(state = ConnectionPhase.DISCONNECTED) else source
            }
        rideSourceCatalog.sourcePowerControls["trainer-b"] = null

        // when the scheduler observes the selected trainer loss and a target command is attempted:
        val interrupted = session.tick(now.plusSeconds(1), null)
        val unavailable = session.current()

        // then the selected source is reported unavailable and no command or recovery uses trainer-a:
        assertEquals(TrainerConnectionStatus.RECONNECTING, interrupted.trainerConnection)
        assertEquals("trainer-b", unavailable.equipment?.selection?.controlSourceId)
        assertEquals(RideRoleStatus.UNAVAILABLE, unavailable.equipment?.control?.status)
        assertEquals(listOf("trainer-a"), unavailable.equipment?.control?.compatibleSourceIds)
        assertFailsWith<TrainingSessionUnavailableException> {
            session.setTargetPower(sessionId, 250)
        }
        assertEquals(emptyList(), trainerAControl.targetPowers)
        assertEquals(0, trainerAControl.requestControlCalls)
        assertEquals(listOf("trainer-b"), rideSourceCatalog.sourceReconnectCalls)
    }

    @Test
    fun `a zero ERG target releases resistance without sending zero watts`() {
        // given an active manual session with a positive ERG target:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)
        session.setTargetPower(sessionId, 300)

        // when a zero-watt ERG target is requested:
        val released = session.setTargetPower(sessionId, 0)

        // then resistance is released without a zero-watt target command:
        assertEquals(listOf(300), powerControl.targetPowers)
        assertEquals(listOf(300), powerControl.targetPowerAttempts)
        assertEquals(1, powerControl.resistanceReleaseCalls)
        assertEquals(null, released.ergTargetPowerWatts)
    }

    @Test
    fun `stopping a session sends the trainer stop command and marks it stopped`() {
        // given an active session with a previously selected ERG target:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val started = session.start()
        val sessionId = requireNotNull(started.sessionId)
        session.setTargetPower(sessionId, 300)

        // when the active session is stopped:
        val stopped = session.stop(sessionId)

        // then the trainer receives a stop command before the session becomes terminal:
        assertEquals(listOf(300, 0), powerControl.targetPowers)
        assertEquals(listOf(300, 0), powerControl.targetPowerAttempts)
        assertEquals(listOf("target:300", "target:0", "stop"), powerControl.powerAndLifecycleCommands)
        assertEquals(1, powerControl.stopCalls)
        assertEquals(TrainingSessionPhase.STOPPED, stopped.phase)
        assertEquals(sessionId, stopped.sessionId)
        assertEquals(started.startedAt, stopped.startedAt)
        assertEquals(null, stopped.ergTargetPowerWatts)
        assertEquals(stopped, session.current())
    }

    @Test
    fun `stopped sessions reject further target changes`() {
        // given a session that has sent its trainer stop command:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)
        session.stop(sessionId)

        // when a target is submitted for the stopped session:
        // then no new target is sent to the trainer:
        assertFailsWith<TrainingSessionNotActiveException> {
            session.setTargetPower(sessionId, 300)
        }
        assertEquals(listOf(0), powerControl.targetPowers)
        assertEquals(listOf("target:0", "stop"), powerControl.powerAndLifecycleCommands)
        assertEquals(1, powerControl.stopCalls)
    }

    @Test
    fun `a stopped session can be discarded while the trainer is disconnected`() {
        // given a session that loses trainer control before it is stopped:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val session = coordinator(rideSourceCatalog, clock)
        val sessionId = requireNotNull(session.start().sessionId)
        rideSourceCatalog.powerControl = null
        val stopped = session.stop(sessionId)

        // when the stopped session is discarded before the trainer reconnects:
        val discarded = session.discard(sessionId)

        // then the recording is cleared without waiting for trainer recovery:
        assertEquals(TrainingSessionPhase.STOPPED, stopped.phase)
        assertEquals(TrainingSessionPhase.NOT_STARTED, discarded.phase)
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
    }

    @Test
    fun `a stopped session can be uploaded while the trainer is disconnected`() {
        // given a stopped session with a recording whose trainer connection is lost first:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        val session = coordinator(rideSourceCatalog, clock, uploader)
        val sessionId = requireNotNull(session.start().sessionId)
        rideSourceCatalog.emitTelemetry(telemetry(distanceMeters = 1_000.0))
        rideSourceCatalog.powerControl = null
        val stopped = session.stop(sessionId)

        // when the stopped recording is uploaded before the trainer reconnects:
        val uploaded = session.upload(sessionId)

        // then upload succeeds and clears the stopped session without waiting for recovery:
        assertEquals(TrainingActivityUploadPhase.AVAILABLE, stopped.activityUpload.phase)
        assertEquals(TrainingSessionPhase.NOT_STARTED, uploaded.phase)
        assertEquals(1, uploader.uploads.size)
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
    }

    @Test
    fun `a rejected trainer stop command leaves the session active`() {
        // given an active session whose device rejects its stop command:
        val powerControl =
            FakeTrainerControl().also {
                it.stopFailure = IllegalStateException("trainer unavailable")
            }
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)

        // when the active session is stopped:
        // then the failure is visible and the session remains active for retry:
        assertFailsWith<TrainingSessionUnavailableException> {
            session.stop(sessionId)
        }
        assertEquals(TrainingSessionPhase.ACTIVE, session.current().phase)
        assertEquals(1, powerControl.stopCalls)
        assertEquals(listOf(0), powerControl.targetPowers)
        assertEquals(listOf("target:0", "stop"), powerControl.powerAndLifecycleCommands)
    }

    @Test
    fun `finishing a workout keeps the session active for manual ERG continuation`() {
        // given a two-step workout whose power ranges resolve to different midpoint targets:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val workout =
            workout(
                timedStep("Work", seconds = 10, lowWatts = 200, highWatts = 300),
                timedStep("Recovery", seconds = 20, lowWatts = 100, highWatts = 100),
            )

        // when the workout reaches the first boundary and then its final boundary:
        val started = session.start(workout)
        val firstTransition = session.tick(now.plusSeconds(10))
        val completed = session.tick(now.plusSeconds(30))

        // then each step receives its midpoint and workout completion switches to Free Ride:
        assertEquals(listOf(250, 100), powerControl.targetPowers)
        assertEquals(1, powerControl.freeRideCalls)
        assertEquals(2, firstTransition.workout?.currentStepNumber)
        assertEquals(TrainingSessionPhase.ACTIVE, completed.phase)
        assertEquals(true, completed.workout?.completed)
        assertEquals(TrainingControlMode.FREE_RIDE, completed.controlMode)
        assertEquals(null, completed.ergRequestedTargetPowerWatts)
        assertEquals(null, completed.ergTargetPowerWatts)
        assertEquals(started.sessionId, completed.sessionId)

        // when the user selects a manual target after the planned workout:
        val continued = session.setTargetPower(requireNotNull(started.sessionId), 180)

        // then manual ERG control is available without starting a second session:
        assertEquals(180, continued.ergTargetPowerWatts)
        assertEquals(listOf(250, 100, 180), powerControl.targetPowers)
        assertEquals(TrainingControlMode.ERG, continued.controlMode)
    }

    @Test
    fun `starting another workout after stopping the previous one starts from its first step`() {
        // given a completed and stopped workout followed by a different executable workout:
        val powerControl = FakeTrainerControl()
        val mutableClock = MutableTestClock(now)
        val session = coordinator(FakeRideSourceCatalog(powerControl), mutableClock)
        val firstWorkout =
            workout(timedStep("First", seconds = 1, lowWatts = 200, highWatts = 200)).copy(
                source = WorkoutSourceReference("intervals.icu", "first-workout"),
                name = "First workout",
            )
        val secondWorkout =
            workout(timedStep("Second", seconds = 20, lowWatts = 300, highWatts = 300)).copy(
                source = WorkoutSourceReference("intervals.icu", "second-workout"),
                name = "Second workout",
            )
        val firstSessionId = requireNotNull(session.start(firstWorkout).sessionId)
        session.tick(now.plusSeconds(1))
        mutableClock.currentTime = now.plusSeconds(1)
        val stopped = session.stop(firstSessionId)

        // when the second workout is started:
        val second = session.start(secondWorkout)

        // then a new active session owns the new workout at its first step:
        assertEquals(null, stopped.workout)
        assertNotEquals(firstSessionId, second.sessionId)
        assertEquals(TrainingSessionPhase.ACTIVE, second.phase)
        assertEquals("second-workout", second.workout?.source?.id)
        assertEquals("Second workout", second.workout?.name)
        assertEquals(1, second.workout?.currentStepNumber)
        assertEquals(false, second.workout?.completed)
        assertEquals(300, second.ergRequestedTargetPowerWatts)
        assertEquals(300, second.ergTargetPowerWatts)
        assertEquals(listOf(200, 0, 300), powerControl.targetPowers)
        assertEquals(1, powerControl.stopCalls)
    }

    @Test
    fun `discarding a stopped session forgets its recording and resets the session`() {
        // given a stopped session with a recorded activity:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val session = coordinator(rideSourceCatalog, clock)
        val sessionId = requireNotNull(session.start().sessionId)
        rideSourceCatalog.emitTelemetry(telemetry(distanceMeters = 1_000.0))
        session.stop(sessionId)

        // when the stopped activity is discarded:
        val discarded = session.discard(sessionId)

        // then the runtime returns to its initial state and the old session cannot be uploaded:
        assertEquals(TrainingSessionPhase.NOT_STARTED, discarded.phase)
        assertEquals(null, discarded.sessionId)
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
        assertFailsWith<TrainingSessionMismatchException> { session.upload(sessionId) }
    }

    @Test
    fun `connection loss keeps the workout moving and synchronizes the current target after recovery`() {
        // given a timed workout whose trainer connection disappears after the first sample:
        val firstPowerControl = FakeTrainerControl()
        val recoveredPowerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(firstPowerControl)
        val uploader = FakeActivityUploader()
        val mutableClock = MutableTestClock(now)
        val session = coordinator(rideSourceCatalog, mutableClock, uploader)
        val workout =
            workout(
                timedStep("Hard", seconds = 3, lowWatts = 300, highWatts = 300),
                timedStep("Recovery", seconds = 10, lowWatts = 100, highWatts = 100),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)
        val beforeLoss = telemetry(receivedAt = now, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(beforeLoss)
        rideSourceCatalog.powerControl = null

        // when the scheduler observes the loss, advances the timed step, and later reconnects:
        val interrupted = session.tick(now.plusSeconds(1), null)
        val advancedWhileDisconnected = session.tick(now.plusSeconds(3), null)
        val afterRecovery = telemetry(receivedAt = now.plusSeconds(5), distanceMeters = 1_005.0)
        rideSourceCatalog.telemetry = afterRecovery
        rideSourceCatalog.reconnectPowerControlResult = recoveredPowerControl
        session.tick(now.plusSeconds(5), null)
        var recoveryTime = now.plusSeconds(5)
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            recoveryTime = recoveryTime.plusSeconds(1)
            session.tick(recoveryTime, null)
            assertEquals(listOf(100), recoveredPowerControl.targetPowers, session.current().toString())
        }
        rideSourceCatalog.emitTelemetry(afterRecovery)
        mutableClock.currentTime = now.plusSeconds(5)
        session.stop(sessionId)
        session.upload(sessionId)

        // then the workout progressed while disconnected, and recovery applied the current step target:
        assertEquals(TrainingSessionPhase.ACTIVE, interrupted.phase)
        assertEquals(2, advancedWhileDisconnected.workout?.currentStepNumber)
        assertEquals(100, advancedWhileDisconnected.ergRequestedTargetPowerWatts)
        assertEquals(300, advancedWhileDisconnected.ergTargetPowerWatts)
        assertEquals(listOf(300), firstPowerControl.targetPowers)
        assertEquals(listOf(100, 0), recoveredPowerControl.targetPowers)
        assertEquals(1, recoveredPowerControl.stopCalls)
        assertEquals(
            listOf(beforeLoss.receivedAt, afterRecovery.receivedAt),
            uploader
                .uploads
                .single()
                .samples
                .map { it.receivedAt },
        )
        assertEquals(
            listOf(
                TrainingActivityEventType.TRAINER_CONNECTION_INTERRUPTED,
                TrainingActivityEventType.TRAINER_RECONNECT_ATTEMPTED,
                TrainingActivityEventType.TRAINER_RECONNECT_ATTEMPTED,
                TrainingActivityEventType.TRAINER_RECONNECTED,
                TrainingActivityEventType.TRAINER_TARGET_SYNCHRONIZED,
            ),
            uploader
                .uploads
                .single()
                .events
                .map { it.type },
        )
    }

    @Test
    fun `manual pause remains available during loss and sends pause after recovery`() {
        // given an active workout whose trainer disappears while automatic recovery is pending:
        val firstPowerControl = FakeTrainerControl()
        val recoveredPowerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(firstPowerControl)
        val session = coordinator(rideSourceCatalog, clock)
        val sessionId = requireNotNull(session.start(workout(timedStep("Work", 20, 200, 200))).sessionId)
        rideSourceCatalog.powerControl = null
        session.tick(now.plusSeconds(1), null)

        // when the rider pauses and then the trainer becomes reachable again:
        val paused = session.pause(sessionId)
        rideSourceCatalog.reconnectPowerControlResult = recoveredPowerControl
        var recoveryTime = now.plusSeconds(1)
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            recoveryTime = recoveryTime.plusSeconds(1)
            session.tick(recoveryTime, null)
            assertEquals(listOf(0), recoveredPowerControl.targetPowerAttempts)
            assertEquals(listOf("target:0", "pause"), recoveredPowerControl.powerAndLifecycleCommands)
            assertEquals(1, recoveredPowerControl.pauseCalls)
        }

        // then the pause action was accepted immediately and recovery sends the explicit pause command:
        assertEquals(TrainingSessionPhase.PAUSED, paused.phase)
        assertEquals(TrainingSessionPhase.PAUSED, session.current().phase)
        assertEquals(TrainerConnectionStatus.CONNECTED, session.current().trainerConnection)
    }

    @Test
    fun `manual stop during loss remains terminal until stop is confirmed`() {
        // given an active ride whose trainer is disconnected before the stop command can be sent:
        val firstPowerControl = FakeTrainerControl()
        val recoveredPowerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(firstPowerControl)
        val session = coordinator(rideSourceCatalog, clock)
        val sessionId = requireNotNull(session.start().sessionId)
        rideSourceCatalog.powerControl = null
        session.tick(now.plusSeconds(1), null)

        // when the rider stops and the trainer later becomes reachable:
        val stopped = session.stop(sessionId)
        rideSourceCatalog.reconnectPowerControlResult = recoveredPowerControl
        var recoveryTime = now.plusSeconds(1)
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            recoveryTime = recoveryTime.plusSeconds(1)
            session.tick(recoveryTime, null)
            assertEquals(listOf(0), recoveredPowerControl.targetPowerAttempts)
            assertEquals(listOf("target:0", "stop"), recoveredPowerControl.powerAndLifecycleCommands)
            assertEquals(1, recoveredPowerControl.stopCalls)
        }

        // then the ride stays stopped and recovery sends the deferred stop command:
        assertEquals(TrainingSessionPhase.STOPPED, stopped.phase)
        assertEquals(TrainingSessionPhase.STOPPED, session.current().phase)
        assertEquals(null, session.current().ergTargetPowerWatts)
    }

    @Test
    fun `stale telemetry forces a transport recovery while the power capability is still open`() {
        // given a trainer that advertises telemetry and has emitted one sample:
        val firstPowerControl = FakeTrainerControl()
        val recoveredPowerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = firstPowerControl,
                telemetryCapabilityAvailable = true,
            )
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = clock,
                ergProtectionProperties = ergProtectionProperties(),
                telemetryProperties = TelemetryProperties(freshness = Duration.ofSeconds(2)),
            )
        session.start()
        val sample = telemetry(receivedAt = now, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(sample)
        session.tick(now, sample)
        rideSourceCatalog.reconnectPowerControlResult = recoveredPowerControl

        // when no newer sample arrives past the configured freshness window:
        val interrupted = session.tick(now.plusSeconds(3), null)

        // then the session exposes recovery and forces a fresh transport connection:
        assertEquals(TrainerConnectionStatus.RECONNECTING, interrupted.trainerConnection)
        assertEquals(true, rideSourceCatalog.lastReconnectForce)
        var recoveryTime = now.plusSeconds(3)
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            recoveryTime = recoveryTime.plusSeconds(1)
            assertEquals(TrainerConnectionStatus.CONNECTED, session.tick(recoveryTime, null).trainerConnection)
        }
        assertEquals(1, rideSourceCatalog.reconnectCalls)
    }

    @Test
    fun `failed target synchronization exposes the interrupted connection and retries`() {
        // given a workout whose replacement trainer rejects the first synchronized target:
        val firstPowerControl = FakeTrainerControl()
        val recoveredPowerControl =
            FakeTrainerControl().also {
                it.targetPowerFailure = IllegalStateException("target synchronization rejected")
            }
        val rideSourceCatalog = FakeRideSourceCatalog(firstPowerControl)
        val session = coordinator(rideSourceCatalog, clock)
        val sessionId =
            requireNotNull(
                session
                    .start(workout(timedStep("Work", seconds = 20, lowWatts = 200, highWatts = 200)))
                    .sessionId,
            )
        rideSourceCatalog.powerControl = null
        rideSourceCatalog.reconnectPowerControlResult = recoveredPowerControl

        // when the trainer reconnects, rejects synchronization, and then accepts the retry:
        session.tick(now.plusSeconds(1), null)
        var recoveryTime = now.plusSeconds(1)
        var interrupted = session.current()
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            recoveryTime = recoveryTime.plusSeconds(1)
            interrupted = session.tick(recoveryTime, null)
            assertEquals(TrainerConnectionStatus.INTERRUPTED, interrupted.trainerConnection)
            assertEquals(
                true,
                interrupted.trainerConnectionError?.contains("target"),
            )
        }
        recoveredPowerControl.targetPowerFailure = null
        var retryTime = recoveryTime
        var recovered = session.current()
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            retryTime = retryTime.plusSeconds(1)
            recovered = session.tick(retryTime, null)
            assertEquals(TrainerConnectionStatus.CONNECTED, recovered.trainerConnection)
            assertEquals(listOf(200), recoveredPowerControl.targetPowers)
        }

        // then the session remains active and the rejected synchronization is retried on the same connection:
        assertEquals(sessionId, recovered.sessionId)
        assertEquals(listOf(200, 200), recoveredPowerControl.targetPowerAttempts)
        assertEquals(1, rideSourceCatalog.reconnectCalls)
    }

    @Test
    fun `manual replacement is not force-reconnected after stale telemetry recovery fails`() {
        // given a telemetry-capable manual session whose sample becomes stale during recovery:
        val firstPowerControl = FakeTrainerControl()
        val manualPowerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = firstPowerControl,
                telemetryCapabilityAvailable = true,
            )
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = clock,
                ergProtectionProperties = ergProtectionProperties(),
                telemetryProperties = TelemetryProperties(freshness = Duration.ofSeconds(2)),
            )
        val sessionId = requireNotNull(session.start().sessionId)
        val sample = telemetry(receivedAt = now, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(sample)
        session.tick(now, sample)
        rideSourceCatalog.powerControl = null

        // when automatic recovery fails after a manual replacement takes over:
        val reconnecting = session.tick(now.plusSeconds(3), null)
        rideSourceCatalog.powerControl = manualPowerControl
        val afterManualReplacement = session.tick(now.plusSeconds(3), null)
        var recoveryTime = now.plusSeconds(3)
        var recovered = session.current()
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            recoveryTime = recoveryTime.plusSeconds(1)
            recovered = session.tick(recoveryTime, null)
            assertEquals(TrainerConnectionStatus.CONNECTED, recovered.trainerConnection)
        }

        // then the manual connection remains authoritative and receives synchronization without another transport attempt:
        assertEquals(TrainerConnectionStatus.RECONNECTING, reconnecting.trainerConnection)
        assertEquals(true, rideSourceCatalog.lastReconnectForce)
        assertEquals(TrainerConnectionStatus.RECONNECTING, afterManualReplacement.trainerConnection)
        assertEquals(sessionId, recovered.sessionId)
        assertEquals(1, rideSourceCatalog.reconnectCalls)
        assertEquals(1, manualPowerControl.requestControlCalls)
        assertEquals(1, manualPowerControl.freeRideCalls)
    }

    @Test
    fun `paused sessions do not reconnect only because telemetry is stale`() {
        // given a paused telemetry-capable session with an old but valid sample:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = powerControl,
                telemetryCapabilityAvailable = true,
            )
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = clock,
                ergProtectionProperties = ergProtectionProperties(),
                telemetryProperties = TelemetryProperties(freshness = Duration.ofSeconds(2)),
            )
        val sessionId = requireNotNull(session.start().sessionId)
        val sample = telemetry(receivedAt = now, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(sample)
        session.tick(now, sample)
        session.pause(sessionId)

        // when the paused scheduler ticks after the freshness window:
        val pausedTick = session.tick(now.plusSeconds(3), null)

        // then the intentional pause remains connected without starting transport recovery:
        assertEquals(TrainingSessionPhase.PAUSED, pausedTick.phase)
        assertEquals(TrainerConnectionStatus.CONNECTED, pausedTick.trainerConnection)
        assertEquals(0, rideSourceCatalog.reconnectCalls)
    }

    @Test
    fun `updates the ERG target progressively during a timed ramp`() {
        // given a timed workout step with ordered ramp endpoints:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
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

        // then the trainer receives the interpolated targets and switches to Free Ride at completion:
        assertEquals(listOf(100, 150, 190), powerControl.targetPowers)
        assertEquals(1, powerControl.freeRideCalls)
        assertEquals(150, halfway.ergTargetPowerWatts)
        assertEquals(190, nearEnd.ergTargetPowerWatts)
        assertEquals(TrainingControlMode.FREE_RIDE, completed.controlMode)
        assertEquals(null, completed.ergTargetPowerWatts)
        assertEquals(true, completed.workout?.completed)
        assertEquals(started.sessionId, completed.sessionId)
    }

    @Test
    fun `adjusts every workout power step from the session target percentage`() {
        // given a workout with two fixed power steps:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val workout =
            workout(
                timedStep("Work", seconds = 10, lowWatts = 200, highWatts = 300),
                timedStep("Recovery", seconds = 10, lowWatts = 100, highWatts = 100),
            )
        val started = session.start(workout)
        val sessionId = requireNotNull(started.sessionId)

        // when the target is increased by one percent and the first step completes:
        val increased = session.adjustWorkoutTarget(sessionId, 1L)
        val nextStep = session.tick(now.plusSeconds(10))

        // then the adjustment applies immediately and carries into the next step:
        assertEquals(100L, started.workoutPowerTargetPercent)
        assertEquals(101L, increased.workoutPowerTargetPercent)
        assertEquals(253, increased.ergTargetPowerWatts)
        assertEquals(101, nextStep.ergRequestedTargetPowerWatts)
        assertEquals(101, nextStep.ergTargetPowerWatts)
        assertEquals(101L, nextStep.workoutPowerTargetPercent)
        assertEquals(listOf(250, 253, 101), powerControl.targetPowers)
    }

    @Test
    fun `adjusts ascending and descending ramp targets without changing their progression`() {
        // given a timed ramp workout:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val workout =
            workout(
                ExecutableWorkoutStep(
                    text = "Ramp",
                    completion = WorkoutStepCompletion.Time(10),
                    target = WorkoutStepTarget.Ramp(startWatts = 200, endWatts = 100),
                ),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)

        // when the ramp is reduced by one percent and observed at its midpoint:
        val reduced = session.adjustWorkoutTarget(sessionId, -1L)
        val midpoint = session.tick(now.plusSeconds(5))
        val completed = session.tick(now.plusSeconds(10))

        // then the percentage is applied to each interpolated target and the ramp still descends:
        assertEquals(99L, reduced.workoutPowerTargetPercent)
        assertEquals(198, reduced.ergTargetPowerWatts)
        assertEquals(149, midpoint.ergTargetPowerWatts)
        assertEquals(listOf(200, 198, 149), powerControl.targetPowers)
        assertEquals(TrainingControlMode.FREE_RIDE, completed.controlMode)
    }

    @Test
    fun `adjusts the retained target while ERG protection is active`() {
        // given a workout that has released resistance after a sustained low cadence:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val mutableClock = MutableTestClock(now)
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = mutableClock,
                ergProtectionProperties =
                    ergProtectionProperties(
                        lowCadenceDuration = Duration.ofSeconds(1),
                        recoveryDuration = Duration.ofSeconds(1),
                    ),
            )
        val workout = workout(timedStep("Hard", seconds = 10, lowWatts = 300, highWatts = 300))
        val sessionId = requireNotNull(session.start(workout).sessionId)
        val lowCadence = telemetry(receivedAt = now, cadenceRpm = 40.0, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(lowCadence)
        session.tick(now, lowCadence)
        session.tick(now.plusSeconds(1), lowCadence)

        // when the target is increased during protection and cadence then recovers:
        val adjusted = session.adjustWorkoutTarget(sessionId, 1L)
        val highCadence = telemetry(receivedAt = now.plusSeconds(1), cadenceRpm = 70.0, distanceMeters = 1_001.0)
        rideSourceCatalog.emitTelemetry(highCadence)
        session.tick(now.plusSeconds(1), highCadence)
        val recovered = session.tick(now.plusSeconds(2), highCadence)

        // then recovery reapplies the adjusted target instead of the original prescription:
        assertEquals(101L, adjusted.workoutPowerTargetPercent)
        assertEquals(303, adjusted.ergRequestedTargetPowerWatts)
        assertEquals(null, adjusted.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.BAILED_OUT, adjusted.ergProtection.status)
        assertEquals(303, recovered.ergRequestedTargetPowerWatts)
        assertEquals(303, recovered.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.INACTIVE, recovered.ergProtection.status)
        assertEquals(listOf(300, 303), powerControl.targetPowers)
    }

    @Test
    fun `allows the target percentage to exceed ordinary intensity ranges while clamping device watts`() {
        // given a fixed workout target:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val sessionId = requireNotNull(session.start(workout(timedStep("Work", 10, 200, 200))).sessionId)

        // when a large positive percentage adjustment is requested:
        val increased = session.adjustWorkoutTarget(sessionId, 1_000_000L)

        // then no artificial intensity bound is applied, while the FTMS target remains representable:
        assertEquals(1_000_100L, increased.workoutPowerTargetPercent)
        assertEquals(Short.MAX_VALUE.toInt(), increased.ergTargetPowerWatts)
        assertEquals(listOf(200, Short.MAX_VALUE.toInt()), powerControl.targetPowers)
    }

    @Test
    fun `records workout target adjustments in the uploaded activity`() {
        // given an active workout with an in-memory activity uploader:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        val session = coordinator(rideSourceCatalog, clock, uploader)
        val sessionId = requireNotNull(session.start(workout(timedStep("Work", 10, 200, 200))).sessionId)

        // when the target is adjusted and the session is stopped and uploaded:
        session.adjustWorkoutTarget(sessionId, 1L)
        rideSourceCatalog.emitTelemetry(telemetry(distanceMeters = 1_000.0))
        session.stop(sessionId)
        session.upload(sessionId)

        // then the executed percentage and target remain reconstructable:
        val event =
            uploader
                .uploads
                .single()
                .events
                .single()
        assertEquals(TrainingActivityEventType.WORKOUT_TARGET_ADJUSTED, event.type)
        assertEquals(101L, event.workoutPowerTargetPercent)
        assertEquals(202, event.targetPowerWatts)
    }

    @Test
    fun `stopping records each telemetry notification once and uploads the in-memory activity`() {
        // given an active session and an uploader that records the submitted activity:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        val session = coordinator(rideSourceCatalog, clock, uploader)
        val started = session.start()
        val firstSample = telemetry(receivedAt = now, distanceMeters = 1_000.0)
        val secondSample = telemetry(receivedAt = now.plusMillis(100), distanceMeters = 1_001.0)

        // when telemetry notifications include the same timestamp twice and the session is stopped and uploaded:
        rideSourceCatalog.emitTelemetry(firstSample)
        rideSourceCatalog.emitTelemetry(firstSample)
        rideSourceCatalog.emitTelemetry(secondSample)
        val stopped = session.stop(requireNotNull(started.sessionId))
        val uploaded = session.upload(requireNotNull(started.sessionId))

        // then the upload contains each notification timestamp once and clears the stopped session:
        assertEquals(TrainingActivityUploadPhase.AVAILABLE, stopped.activityUpload.phase)
        assertEquals(0, stopped.activitySummary?.durationSeconds)
        assertEquals(200, stopped.activitySummary?.averagePowerWatts)
        assertEquals(TrainingSessionPhase.NOT_STARTED, uploaded.phase)
        assertEquals(null, uploaded.sessionId)
        assertEquals(null, uploaded.workout)
        assertNull(uploaded.activitySummary)
        assertEquals(TrainingActivityUploadPhase.UNAVAILABLE, uploaded.activityUpload.phase)
        assertEquals(
            listOf(firstSample.receivedAt, secondSample.receivedAt),
            uploader.uploads
                .single()
                .samples
                .map { it.receivedAt },
        )
        assertFailsWith<TrainingSessionMismatchException> { session.upload(requireNotNull(started.sessionId)) }
    }

    @Test
    fun `failed upload preserves the stopped activity summary`() {
        // given a stopped recording and an uploader that rejects the upload:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader(ActivityUploadException("upload failed"))
        val session = coordinator(rideSourceCatalog, clock, uploader)
        val sessionId = requireNotNull(session.start().sessionId)
        rideSourceCatalog.emitTelemetry(telemetry(distanceMeters = 1_000.0))
        session.stop(sessionId)

        // when the upload is attempted:
        assertFailsWith<ActivityUploadException> { session.upload(sessionId) }

        // then the stopped activity summary remains available for a retry:
        val current = session.current()
        assertEquals(TrainingSessionPhase.STOPPED, current.phase)
        assertEquals(TrainingActivityUploadPhase.FAILED, current.activityUpload.phase)
        assertEquals(0, current.activitySummary?.durationSeconds)
        assertEquals(200, current.activitySummary?.averagePowerWatts)
    }

    @Test
    fun `recorded scheduled workouts retain step and manual continuation segments`() {
        // given a scheduled two-step workout and telemetry around each transition:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        var currentTime = now
        val mutableClock =
            object : Clock() {
                override fun instant(): Instant = currentTime

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this
            }
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = mutableClock,
                activityUploader = uploader,
                telemetryProperties = TelemetryProperties(freshness = Duration.ofMinutes(1)),
            )
        val workout =
            workout(
                timedStep("Work", seconds = 10, lowWatts = 200, highWatts = 300),
                timedStep("Recovery", seconds = 20, lowWatts = 100, highWatts = 100),
            ).copy(sourceType = WorkoutSourceType.SCHEDULED)
        val started = session.start(workout)
        val sessionId = requireNotNull(started.sessionId)

        // when the workout completes, the ride continues briefly, and the session is uploaded:
        rideSourceCatalog.emitTelemetry(telemetry(receivedAt = now, distanceMeters = 1_000.0))
        session.tick(now.plusSeconds(10))
        rideSourceCatalog.emitTelemetry(telemetry(receivedAt = now.plusSeconds(10), distanceMeters = 1_001.0))
        session.tick(now.plusSeconds(30))
        rideSourceCatalog.emitTelemetry(telemetry(receivedAt = now.plusSeconds(30), distanceMeters = 1_002.0))
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
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = powerControl,
                telemetry = telemetry(distanceMeters = 1_000.0),
            )
        val session = coordinator(rideSourceCatalog, clock)
        val workout =
            workout(
                distanceStep("Block", meters = 100.0, lowWatts = 200, highWatts = 200),
                timedStep("Finish", seconds = 1, lowWatts = 150, highWatts = 150),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)

        // when the trainer reports less than and then exactly the requested distance:
        rideSourceCatalog.telemetry = telemetry(distanceMeters = 1_099.0)
        val beforeBoundary = session.tick(now.plusSeconds(1))
        rideSourceCatalog.telemetry = telemetry(distanceMeters = 1_100.0)
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
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
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

        // then the open step has selected Free Ride and the next ERG target is applied:
        assertEquals(2, advanced.workout?.currentStepNumber)
        assertEquals(180, advanced.ergTargetPowerWatts)
        assertEquals(1, powerControl.freeRideCalls)
        assertEquals(listOf(180), powerControl.targetPowers)
    }

    @Test
    fun `manual ERG target changes are rejected while a workout owns the target`() {
        // given an active executable workout:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val sessionId = requireNotNull(session.start(workout(timedStep("Work", 10, 200, 200))).sessionId)

        // when a manual target is submitted during workout execution:
        // then the current step remains the only owner of the ERG target:
        assertFailsWith<WorkoutTargetManagedException> {
            session.setTargetPower(sessionId, 300)
        }
        assertEquals(listOf(200), powerControl.targetPowers)
    }

    @Test
    fun `pausing a session sends trainer commands and records telemetry until resume`() {
        // given an active manual session with a selected ERG target and telemetry:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        val mutableClock = MutableTestClock(now)
        val session = coordinator(rideSourceCatalog, mutableClock, uploader)
        val started = session.start()
        val sessionId = requireNotNull(started.sessionId)
        session.setTargetPower(sessionId, 300)
        val beforePause = telemetry(receivedAt = now.plusSeconds(1), distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(beforePause)

        // when the session is paused, telemetry arrives, then the session resumes and records again:
        mutableClock.currentTime = now.plusSeconds(2)
        val paused = session.pause(sessionId)
        val duringPause = telemetry(receivedAt = now.plusSeconds(3), distanceMeters = 1_001.0)
        rideSourceCatalog.emitTelemetry(duringPause)
        mutableClock.currentTime = now.plusSeconds(10)
        val resumed = session.resume(sessionId)
        val afterResume = telemetry(receivedAt = now.plusSeconds(11), distanceMeters = 1_002.0)
        rideSourceCatalog.emitTelemetry(afterResume)
        session.stop(sessionId)
        session.upload(sessionId)

        // then the session pauses, resumes its prior target, and retains every observed sample:
        assertEquals(TrainingSessionPhase.PAUSED, paused.phase)
        assertEquals(null, paused.ergTargetPowerWatts)
        assertEquals(TrainingSessionPhase.ACTIVE, resumed.phase)
        assertEquals(300, resumed.ergTargetPowerWatts)
        assertEquals(listOf(300, 0, 300, 0), powerControl.targetPowers)
        assertEquals(
            listOf("target:300", "target:0", "pause", "target:300", "target:0", "stop"),
            powerControl.powerAndLifecycleCommands,
        )
        assertEquals(1, powerControl.pauseCalls)
        assertEquals(2, powerControl.startOrResumeCalls)
        assertEquals(1, powerControl.stopCalls)
        assertEquals(
            listOf(beforePause.receivedAt, duringPause.receivedAt, afterResume.receivedAt),
            uploader.uploads
                .single()
                .samples
                .map { it.receivedAt },
        )
        assertEquals(
            listOf(beforePause.receivedAt, duringPause.receivedAt, afterResume.receivedAt),
            uploader.uploads
                .single()
                .cyclingObservations
                .map { it.receivedAt },
        )
        assertEquals(
            listOf(
                TrainingActivityEventType.TRAINING_PAUSED,
                TrainingActivityEventType.TRAINING_RESUMED,
            ),
            uploader
                .uploads
                .single()
                .events
                .map { it.type },
        )
    }

    @Test
    fun `paused workout timing resumes from the remaining step duration`() {
        // given a timed workout that has run for part of its first step:
        val powerControl = FakeTrainerControl()
        val mutableClock = MutableTestClock(now)
        val session = coordinator(FakeRideSourceCatalog(powerControl), mutableClock)
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
        assertEquals(listOf(200, 0, 200), powerControl.targetPowers)
        assertEquals(1, powerControl.pauseCalls)
        assertEquals(2, powerControl.startOrResumeCalls)
        assertEquals(1, powerControl.freeRideCalls)
    }

    @Test
    fun `paused distance workout ignores trainer distance accumulated during the pause`() {
        // given a distance workout with thirty meters completed before pausing:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl, telemetry = telemetry(distanceMeters = 1_000.0))
        val mutableClock = MutableTestClock(now)
        val session = coordinator(rideSourceCatalog, mutableClock)
        val workout =
            workout(
                distanceStep("Block", meters = 100.0, lowWatts = 200, highWatts = 200),
                timedStep("Finish", seconds = 1, lowWatts = 150, highWatts = 150),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)
        rideSourceCatalog.telemetry = telemetry(receivedAt = now.plusSeconds(1), distanceMeters = 1_030.0)
        mutableClock.currentTime = now.plusSeconds(1)

        // when the trainer moves fifty meters while paused and then moves sixty-nine meters after resume:
        session.pause(sessionId)
        rideSourceCatalog.telemetry = telemetry(receivedAt = now.plusSeconds(1), distanceMeters = 1_080.0)
        mutableClock.currentTime = now.plusSeconds(20)
        session.resume(sessionId)
        rideSourceCatalog.telemetry = telemetry(receivedAt = now.plusSeconds(21), distanceMeters = 1_149.0)
        val beforeBoundary = session.tick(now.plusSeconds(21), rideSourceCatalog.telemetry)

        // then distance accumulated while paused is not counted:
        assertEquals(1, beforeBoundary.workout?.currentStepNumber)

        // when the trainer reaches seventy post-resume meters:
        rideSourceCatalog.telemetry = telemetry(receivedAt = now.plusSeconds(22), distanceMeters = 1_150.0)
        val atBoundary = session.tick(now.plusSeconds(22), rideSourceCatalog.telemetry)

        // then the thirty pre-pause meters plus seventy post-resume meters complete the step:
        assertEquals(2, atBoundary.workout?.currentStepNumber)
    }

    @Test
    fun `stopping a paused session finalizes the recording and remains safe`() {
        // given an active session with a recorded sample:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        val session = coordinator(rideSourceCatalog, clock, uploader)
        val sessionId = requireNotNull(session.start().sessionId)
        rideSourceCatalog.emitTelemetry(telemetry(distanceMeters = 1_000.0))

        // when the session is paused and stopped without resuming:
        session.pause(sessionId)
        val stopped = session.stop(sessionId)

        // then stopping sends a trainer stop command and makes the pre-pause recording available:
        assertEquals(TrainingSessionPhase.STOPPED, stopped.phase)
        assertEquals(listOf(0, 0), powerControl.targetPowers)
        assertEquals(listOf("target:0", "pause", "target:0", "stop"), powerControl.powerAndLifecycleCommands)
        assertEquals(1, powerControl.pauseCalls)
        assertEquals(1, powerControl.stopCalls)
        assertEquals(TrainingActivityUploadPhase.AVAILABLE, stopped.activityUpload.phase)
    }

    @Test
    fun `a failed pause command leaves the session active and recording`() {
        // given an active session whose trainer rejects the pause command:
        val powerControl =
            FakeTrainerControl().also {
                it.pauseFailure = IllegalStateException("trainer unavailable")
            }
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        val session = coordinator(rideSourceCatalog, clock, uploader)
        val sessionId = requireNotNull(session.start().sessionId)

        // when the session is paused:
        assertFailsWith<TrainingSessionUnavailableException> {
            session.pause(sessionId)
        }

        // then the state and recording subscription remain active for a retry:
        assertEquals(TrainingSessionPhase.ACTIVE, session.current().phase)
        assertEquals(1, powerControl.pauseCalls)
        assertEquals(listOf(0), powerControl.targetPowers)
        assertEquals(listOf("target:0", "pause"), powerControl.powerAndLifecycleCommands)
        powerControl.pauseFailure = null
        rideSourceCatalog.emitTelemetry(telemetry(distanceMeters = 1_000.0))
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
    fun `a failed resume Free Ride command leaves the session paused`() {
        // given a paused Free Ride session whose trainer rejects the restored mode:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)
        session.pause(sessionId)
        powerControl.freeRideFailure = IllegalStateException("trainer unavailable")

        // when the paused session is resumed:
        assertFailsWith<TrainingSessionUnavailableException> {
            session.resume(sessionId)
        }

        // then the session remains paused and no recording subscription is reopened:
        assertEquals(TrainingSessionPhase.PAUSED, session.current().phase)
        assertEquals(null, session.current().ergTargetPowerWatts)
        assertEquals(listOf(0), powerControl.targetPowers)
        assertEquals(2, powerControl.startOrResumeCalls)
        assertEquals(2, powerControl.freeRideCalls)
    }

    @Test
    fun `a rejected resume command keeps a Free Ride session paused`() {
        // given a paused Free Ride session whose trainer rejects resume:
        val powerControl = FakeTrainerControl()
        val session = coordinator(FakeRideSourceCatalog(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)
        session.pause(sessionId)
        powerControl.startOrResumeFailure = IllegalStateException("trainer unavailable")

        // when the paused session is resumed:
        assertFailsWith<TrainingSessionUnavailableException> {
            session.resume(sessionId)
        }

        // then no target mode is restored and the session remains paused:
        assertEquals(TrainingSessionPhase.PAUSED, session.current().phase)
        assertEquals(2, powerControl.startOrResumeCalls)
        assertEquals(1, powerControl.freeRideCalls)
        assertEquals(listOf(0), powerControl.targetPowers)
    }

    @Test
    fun `automatically releases and restores a manual ERG target around a cadence collapse`() {
        // given a manual session with cadence protection and an activity uploader:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = clock,
                activityUploader = uploader,
                ergProtectionProperties = ergProtectionProperties(),
            )
        val sessionId = requireNotNull(session.start().sessionId)
        session.setTargetPower(sessionId, 300)

        // when cadence stays below the threshold and then recovers without a manual action:
        val lowCadence =
            telemetry(
                receivedAt = now.plusSeconds(1),
                cadenceRpm = 44.0,
                distanceMeters = 1_000.0,
            )
        rideSourceCatalog.emitTelemetry(lowCadence)
        session.tick(lowCadence.receivedAt, lowCadence)
        session.tick(now.plusSeconds(2), lowCadence)
        val bailedOut = session.tick(now.plusSeconds(3), lowCadence)
        val recoveredCadence =
            telemetry(
                receivedAt = now.plusSeconds(4),
                cadenceRpm = 60.0,
                distanceMeters = 1_001.0,
            )
        rideSourceCatalog.emitTelemetry(recoveredCadence)
        session.tick(recoveredCadence.receivedAt, recoveredCadence)
        val recovered = session.tick(now.plusSeconds(6), recoveredCadence)

        // then the applied target is released and restored while the requested target remains visible:
        assertEquals(listOf(300, 300), powerControl.targetPowers)
        assertEquals(1, powerControl.resistanceReleaseCalls)
        assertEquals(TrainingSessionPhase.ACTIVE, bailedOut.phase)
        assertEquals(TrainingControlMode.ERG, bailedOut.controlMode)
        assertEquals(300, bailedOut.ergRequestedTargetPowerWatts)
        assertEquals(null, bailedOut.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.BAILED_OUT, bailedOut.ergProtection.status)
        assertEquals(TrainingSessionPhase.ACTIVE, recovered.phase)
        assertEquals(TrainingControlMode.ERG, recovered.controlMode)
        assertEquals(300, recovered.ergRequestedTargetPowerWatts)
        assertEquals(300, recovered.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.INACTIVE, recovered.ergProtection.status)

        // when the ride is stopped and uploaded:
        session.stop(sessionId)
        session.upload(sessionId)

        // then the exported activity retains the protection lifecycle:
        val events =
            uploader
                .uploads
                .single()
                .events
                .map { it.type }
        assertEquals(
            listOf(
                TrainingActivityEventType.ERG_PROTECTION_STARTED,
                TrainingActivityEventType.ERG_PROTECTION_ENDED,
            ),
            events,
        )
    }

    @Test
    fun `does not release ERG for a steady fifty rpm interval`() {
        // given a manual session with a forty-five-rpm bailout threshold:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = clock,
                ergProtectionProperties = ergProtectionProperties(),
            )
        val sessionId = requireNotNull(session.start().sessionId)
        session.setTargetPower(sessionId, 300)

        // when the rider holds fifty rpm and the scheduler evaluates after the low-cadence dwell:
        val cadence =
            telemetry(
                receivedAt = now.plusSeconds(4),
                cadenceRpm = 50.0,
                distanceMeters = 1_000.0,
            )
        rideSourceCatalog.emitTelemetry(cadence)
        session.tick(cadence.receivedAt, cadence)
        val state = session.tick(now.plusSeconds(6), cadence)

        // then the low-cadence interval remains under the requested ERG target:
        assertEquals(listOf(300), powerControl.targetPowers)
        assertEquals(300, state.ergRequestedTargetPowerWatts)
        assertEquals(300, state.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.INACTIVE, state.ergProtection.status)
        session.stop(sessionId)
    }

    @Test
    fun `continues workout timing while ERG protection is active`() {
        // given a timed workout and a short cadence-protection dwell:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val mutableClock = MutableTestClock(now)
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = mutableClock,
                ergProtectionProperties =
                    ergProtectionProperties(
                        lowCadenceDuration = Duration.ofSeconds(1),
                        recoveryDuration = Duration.ofSeconds(1),
                    ),
            )
        val workout =
            workout(
                timedStep("Hard", seconds = 3, lowWatts = 300, highWatts = 300),
                timedStep("Recovery", seconds = 3, lowWatts = 100, highWatts = 100),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)
        val lowCadence = telemetry(receivedAt = now, cadenceRpm = 40.0, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(lowCadence)
        session.tick(now, lowCadence)

        // when the target is bailed out and the first timed step reaches its wall-clock boundary:
        session.tick(now.plusSeconds(1), lowCadence)
        val nextStep = session.tick(now.plusSeconds(3), lowCadence)

        // then the workout advances while the trainer resistance remains released:
        assertEquals(TrainingSessionPhase.ACTIVE, nextStep.phase)
        assertEquals(2, nextStep.workout?.currentStepNumber)
        assertEquals(100, nextStep.ergRequestedTargetPowerWatts)
        assertEquals(null, nextStep.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.BAILED_OUT, nextStep.ergProtection.status)
        assertEquals(listOf(300), powerControl.targetPowers)
        assertEquals(1, powerControl.resistanceReleaseCalls)

        // when the incomplete ride is stopped:
        mutableClock.currentTime = now.plusSeconds(3)
        val stopped = session.stop(sessionId)

        // then the recording remains available instead of being discarded as a failed workout:
        assertEquals(TrainingActivityUploadPhase.AVAILABLE, stopped.activityUpload.phase)
    }

    @Test
    fun `does not retry a rejected workout recovery while the workout keeps progressing`() {
        // given a structured workout that has confirmed resistance-release protection:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        val mutableClock = MutableTestClock(now)
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = mutableClock,
                activityUploader = uploader,
                ergProtectionProperties =
                    ergProtectionProperties(
                        lowCadenceDuration = Duration.ofSeconds(1),
                        recoveryDuration = Duration.ofSeconds(1),
                    ),
            )
        val workout =
            workout(
                timedStep("Hard", seconds = 3, lowWatts = 300, highWatts = 300),
                timedStep("Next", seconds = 10, lowWatts = 100, highWatts = 100),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)
        val lowCadence = telemetry(receivedAt = now, cadenceRpm = 40.0, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(lowCadence)
        session.tick(now, lowCadence)
        session.tick(now.plusSeconds(1), lowCadence)
        val highCadence = telemetry(receivedAt = now.plusSeconds(1), cadenceRpm = 70.0, distanceMeters = 1_001.0)
        rideSourceCatalog.emitTelemetry(highCadence)
        session.tick(now.plusSeconds(1), highCadence)
        powerControl.targetPowerFailure = IllegalStateException("temporary trainer rejection")

        // when the one recovery command is rejected and the trainer would accept later commands:
        val failed = session.tick(now.plusSeconds(2), highCadence)
        powerControl.targetPowerFailure = null
        val progressed = session.tick(now.plusSeconds(3), highCadence)
        val later = session.tick(now.plusSeconds(5), highCadence)

        // then the workout progresses, resistance stays released, and no second recovery command is sent:
        assertEquals(ErgProtectionStatus.RECOVERY_FAILED, failed.ergProtection.status)
        assertEquals(2, progressed.workout?.currentStepNumber)
        assertEquals(100, progressed.ergRequestedTargetPowerWatts)
        assertEquals(null, progressed.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.RECOVERY_FAILED, later.ergProtection.status)
        assertEquals(listOf(300, 300), powerControl.targetPowerAttempts)

        // when the incomplete workout is stopped and uploaded:
        mutableClock.currentTime = now.plusSeconds(5)
        session.stop(sessionId)
        session.upload(sessionId)

        // then the recording contains one failed recovery event and no recovery completion:
        assertEquals(
            listOf(
                TrainingActivityEventType.ERG_PROTECTION_STARTED,
                TrainingActivityEventType.ERG_PROTECTION_FAILED,
            ),
            uploader
                .uploads
                .single()
                .events
                .map { it.type },
        )
    }

    @Test
    fun `holds workout progression after a failed protective release`() {
        // given a timed workout whose trainer rejects the protective resistance release:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val mutableClock = MutableTestClock(now)
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = mutableClock,
                ergProtectionProperties =
                    ergProtectionProperties(
                        lowCadenceDuration = Duration.ofSeconds(1),
                        recoveryDuration = Duration.ofSeconds(1),
                    ),
            )
        val workout =
            workout(
                timedStep("Hard", seconds = 3, lowWatts = 300, highWatts = 300),
                timedStep("Next", seconds = 3, lowWatts = 100, highWatts = 100),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)
        powerControl.resistanceReleaseFailure = IllegalStateException("trainer unavailable")
        val lowCadence = telemetry(receivedAt = now, cadenceRpm = 40.0, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(lowCadence)
        session.tick(now, lowCadence)

        // when the protection command fails and the current timed step reaches its boundary:
        val unavailable = session.tick(now.plusSeconds(1), lowCadence)
        val held = session.tick(now.plusSeconds(3), lowCadence)

        // then the workout does not move to a new target while the trainer state is unknown:
        assertEquals(ErgProtectionStatus.UNAVAILABLE, unavailable.ergProtection.status)
        assertEquals(1, held.workout?.currentStepNumber)
        assertEquals(300, held.ergRequestedTargetPowerWatts)
        assertEquals(null, held.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.UNAVAILABLE, held.ergProtection.status)
        assertEquals(listOf(300), powerControl.targetPowers)

        // when the trainer accepts a stop command:
        powerControl.resistanceReleaseFailure = null
        mutableClock.currentTime = now.plusSeconds(3)
        val stopped = session.stop(sessionId)

        // then the collected activity remains available despite the incomplete workout:
        assertEquals(TrainingActivityUploadPhase.AVAILABLE, stopped.activityUpload.phase)
    }

    @Test
    fun `reconnection resolves unavailable ERG protection after confirming resistance release`() {
        // given a session whose resistance release failed and left the trainer state unknown:
        val powerControl = FakeTrainerControl()
        val recoveredPowerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val mutableClock = MutableTestClock(now)
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = mutableClock,
                ergProtectionProperties =
                    ergProtectionProperties(
                        lowCadenceDuration = Duration.ofSeconds(1),
                        recoveryDuration = Duration.ofSeconds(1),
                    ),
            )
        val sessionId =
            requireNotNull(
                session
                    .start(workout(timedStep("Hard", 10, 300, 300)))
                    .sessionId,
            )
        powerControl.resistanceReleaseFailure = IllegalStateException("trainer unavailable")
        val lowCadence = telemetry(receivedAt = now, cadenceRpm = 40.0, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(lowCadence)
        session.tick(now, lowCadence)
        session.tick(now.plusSeconds(1), lowCadence)
        rideSourceCatalog.powerControl = null
        powerControl.resistanceReleaseFailure = null
        rideSourceCatalog.reconnectPowerControlResult = recoveredPowerControl

        // when the connection is recovered while ERG protection is still unavailable:
        session.tick(now.plusSeconds(2), null)
        var recoveryTime = now.plusSeconds(2)
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            recoveryTime = recoveryTime.plusSeconds(1)
            session.tick(recoveryTime, null)
            assertEquals(1, recoveredPowerControl.resistanceReleaseCalls)
        }

        // then the trainer has confirmed released resistance and protection is no longer unknown:
        assertEquals(TrainingSessionPhase.ACTIVE, session.current().phase)
        assertEquals(ErgProtectionStatus.BAILED_OUT, session.current().ergProtection.status)
        assertEquals(null, session.current().ergTargetPowerWatts)
        assertEquals(sessionId, session.current().sessionId)
    }

    @Test
    fun `clears ERG protection when a workout enters an open target step`() {
        // given a workout whose next step does not request ERG resistance:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val mutableClock = MutableTestClock(now)
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = mutableClock,
                ergProtectionProperties =
                    ergProtectionProperties(
                        lowCadenceDuration = Duration.ofSeconds(1),
                        recoveryDuration = Duration.ofSeconds(1),
                    ),
            )
        val workout =
            workout(
                timedStep("Hard", seconds = 3, lowWatts = 300, highWatts = 300),
                ExecutableWorkoutStep(
                    text = "Open",
                    completion = WorkoutStepCompletion.Manual,
                    target = WorkoutStepTarget.Open,
                ),
            )
        val sessionId = requireNotNull(session.start(workout).sessionId)
        val lowCadence = telemetry(receivedAt = now, cadenceRpm = 40.0, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(lowCadence)
        session.tick(now, lowCadence)
        session.tick(now.plusSeconds(1), lowCadence)

        // when the protected workout advances into the open step:
        val openStep = session.tick(now.plusSeconds(3), lowCadence)

        // then no stale protection overlay remains after resistance is released for the open step:
        assertEquals(2, openStep.workout?.currentStepNumber)
        assertEquals(TrainingControlMode.FREE_RIDE, openStep.controlMode)
        assertEquals(null, openStep.ergRequestedTargetPowerWatts)
        assertEquals(null, openStep.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.INACTIVE, openStep.ergProtection.status)
        assertEquals(listOf(300), powerControl.targetPowers)
        assertEquals(1, powerControl.resistanceReleaseCalls)
        assertEquals(1, powerControl.freeRideCalls)

        mutableClock.currentTime = now.plusSeconds(3)
        session.stop(sessionId)
    }

    @Test
    fun `records a failed resistance release without claiming it was applied`() {
        // given a manual session whose trainer rejects resistance release after a target is active:
        val powerControl = FakeTrainerControl()
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl)
        val uploader = FakeActivityUploader()
        val session =
            coordinator(
                rideSourceCatalog = rideSourceCatalog,
                clock = clock,
                activityUploader = uploader,
                ergProtectionProperties = ergProtectionProperties(),
            )
        val sessionId = requireNotNull(session.start().sessionId)
        session.setTargetPower(sessionId, 300)
        powerControl.resistanceReleaseFailure = IllegalStateException("trainer unavailable")
        val lowCadence = telemetry(receivedAt = now, cadenceRpm = 40.0, distanceMeters = 1_000.0)
        rideSourceCatalog.emitTelemetry(lowCadence)
        session.tick(now, lowCadence)

        // when the low cadence dwell completes and the protective resistance release fails:
        val unavailable = session.tick(now.plusSeconds(3), lowCadence)

        // then the session exposes the protection failure and does not retain a stale applied target:
        assertEquals(ErgProtectionStatus.UNAVAILABLE, unavailable.ergProtection.status)
        assertEquals(300, unavailable.ergRequestedTargetPowerWatts)
        assertEquals(null, unavailable.ergTargetPowerWatts)
        assertEquals(listOf(300), powerControl.targetPowers)

        // when the incomplete recording is stopped while the release command remains unavailable:
        val stopped = session.stop(sessionId)
        session.upload(sessionId)

        // then the failed protective attempt remains visible in the exported activity:
        val event =
            uploader
                .uploads
                .single()
                .events
                .single()
        assertEquals(
            TrainingActivityEventType.ERG_PROTECTION_FAILED,
            event.type,
        )
        assertEquals(TrainingActivityUploadPhase.AVAILABLE, stopped.activityUpload.phase)
    }

    private fun trainerCapabilities(): Set<RideSourceCapability> =
        setOf(
            RideSourceCapability.RESISTANCE_CONTROL,
            RideSourceCapability.POWER,
            RideSourceCapability.CADENCE,
        )

    private fun coordinator(
        rideSourceCatalog: FakeRideSourceCatalog,
        clock: Clock,
        activityUploader: FakeActivityUploader = FakeActivityUploader(),
        ergProtectionProperties: ErgProtectionProperties = ErgProtectionProperties(),
        telemetryProperties: TelemetryProperties = TelemetryProperties(freshness = Duration.ofSeconds(5)),
    ): TrainingSessionCoordinator =
        TrainingSessionCoordinator(
            rideSourceCatalog = rideSourceCatalog,
            clock = clock,
            activityUploader = activityUploader,
            ergProtectionProperties = ergProtectionProperties,
            telemetryProperties = telemetryProperties,
        )

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
        cadenceRpm: Double = 90.0,
        distanceMeters: Double,
    ): CyclingTelemetry =
        CyclingTelemetry(
            powerWatts = 200,
            cadenceRpm = cadenceRpm,
            speedKph = 25.0,
            distanceMeters = distanceMeters,
            receivedAt = receivedAt,
        )

    private fun heartRateSource(id: String): RideSourceDescriptor =
        RideSourceDescriptor(
            id = id,
            state = ConnectionPhase.CONNECTED,
            capabilities = setOf(RideSourceCapability.HEART_RATE),
            device =
                DeviceAdvertisement(
                    name = id,
                    endpoint = DeviceEndpoint.Bluetooth("AA:BB:CC:DD:EE:${if (id == "bridge") "01" else "02"}", "11:22:33:44:55:66"),
                ),
        )

    private fun heartRate(bpm: Int): HeartRateTelemetry =
        HeartRateTelemetry(
            heartRateBpm = bpm,
            receivedAt = now.minusMillis((200 - bpm).toLong()),
        )

    private fun ergProtectionProperties(
        lowCadenceDuration: Duration = Duration.ofSeconds(2),
        recoveryDuration: Duration = Duration.ofSeconds(2),
    ): ErgProtectionProperties =
        ErgProtectionProperties(
            lowCadenceDuration = lowCadenceDuration,
            recoveryDuration = recoveryDuration,
            targetChangeGracePeriod = Duration.ZERO,
        )
}
