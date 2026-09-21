package paceline

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.client.RestTestClient
import paceline.device.domain.CyclingTelemetry
import paceline.testsupport.FakeActivityUploader
import paceline.testsupport.FakePlannedWorkoutCalendar
import paceline.testsupport.FakeRideSourceCatalog
import paceline.testsupport.FakeTrainerControl
import paceline.testsupport.FakeWorkoutLibrary
import paceline.workout.domain.ScheduledWorkout
import paceline.workout.domain.WorkoutPlanSummary
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutStepSummary
import paceline.workout.domain.WorkoutTargetSummary
import paceline.workout.ports.PlannedWorkoutCalendar
import paceline.workout.ports.WorkoutLibrary
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkoutExecutionIntegrationTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkoutExecutionIntegrationTest {
    @LocalServerPort
    private var serverPort: Int = 0

    @Autowired
    private lateinit var powerControl: FakeTrainerControl

    @Autowired
    private lateinit var rideSourceCatalog: FakeRideSourceCatalog

    @Autowired
    private lateinit var activityUploader: FakeActivityUploader

    private lateinit var restClient: RestTestClient

    @BeforeEach
    fun setUp() {
        restClient =
            RestTestClient
                .bindToServer()
                .baseUrl("http://127.0.0.1:$serverPort")
                .build()
    }

    @Test
    fun `explicit workout selection starts and manually completes the trainer execution`() {
        // given a provider-backed scheduled workout with one manual open step:
        val request =
            """
            {
              "workout": {
                "provider": "test-provider",
                "sourceType": "SCHEDULED",
                "sourceId": "event-1"
              }
            }
            """.trimIndent()

        // when the selected workout is started and its manual step is advanced:
        val started =
            restClient
                .post()
                .uri("/training-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val sessionId =
            Regex("\"sessionId\":\"([^\"]+)\"")
                .find(started)
                ?.groupValues
                ?.get(1)
                ?: error("No session id in response: $started")
        val refreshed =
            restClient
                .get()
                .uri("/training-sessions/current")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val completed =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/advance")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        // then the source identity and execution state are exposed and the trainer enters Free Ride:
        assertTrue(started.contains("\"state\":\"ACTIVE\""))
        assertTrue(started.contains("\"sourceId\":\"event-1\""))
        assertTrue(started.contains("\"currentStep\":1"))
        assertTrue(started.contains("\"steps\":[{\"text\":\"Lap press\""))
        assertTrue(refreshed.contains("\"steps\":[{\"text\":\"Lap press\""))
        assertTrue(started.contains("\"controlMode\":\"FREE_RIDE\""))
        assertTrue(started.contains("\"ergRequestedTargetPowerWatts\":null"))
        assertTrue(started.contains("\"ergTargetPowerWatts\":null"))
        assertTrue(started.contains("\"ergProtection\":{\"state\":\"INACTIVE\""))
        assertTrue(completed.contains("\"state\":\"ACTIVE\""))
        assertTrue(completed.contains("\"completed\":true"))
        assertTrue(completed.contains("\"controlMode\":\"FREE_RIDE\""))
        assertEquals(emptyList(), powerControl.targetPowers)
        assertEquals(1, powerControl.freeRideCalls)
    }

    @Test
    fun `pause and resume expose the whole session lifecycle`() {
        // given an explicitly started workout session:
        val request =
            """
            {
              "workout": {
                "provider": "test-provider",
                "sourceType": "SCHEDULED",
                "sourceId": "event-1"
              }
            }
            """.trimIndent()
        val started =
            restClient
                .post()
                .uri("/training-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val sessionId =
            Regex("\"sessionId\":\"([^\"]+)\"")
                .find(started)
                ?.groupValues
                ?.get(1)
                ?: error("No session id in response: $started")

        // when the active session is paused and resumed through the REST API:
        val paused =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/pause")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val resumed =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/resume")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        // then the session state is paused and resumed while the workout context remains attached:
        assertTrue(paused.contains("\"state\":\"PAUSED\""))
        assertTrue(paused.contains("\"controlMode\":\"FREE_RIDE\""))
        assertTrue(paused.contains("\"ergRequestedTargetPowerWatts\":null"))
        assertTrue(paused.contains("\"ergTargetPowerWatts\":0"))
        assertTrue(paused.contains("\"sourceId\":\"event-1\""))
        assertTrue(resumed.contains("\"state\":\"ACTIVE\""))
        assertEquals(listOf(0), powerControl.targetPowers)
        assertEquals(2, powerControl.freeRideCalls)
        assertEquals(2, powerControl.requestControlCalls)
    }

    @Test
    fun `manual stop exposes optional upload without uploading automatically`() {
        // given a manually started session with one received telemetry notification:
        val started =
            restClient
                .post()
                .uri("/training-sessions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val sessionId =
            Regex("\"sessionId\":\"([^\"]+)\"")
                .find(started)
                ?.groupValues
                ?.get(1)
                ?: error("No session id in response: $started")
        rideSourceCatalog.emitTelemetry(
            CyclingTelemetry(
                powerWatts = 200,
                cadenceRpm = 90.0,
                speedKph = 25.0,
                distanceMeters = 1_000.0,
                receivedAt = Instant.now(),
            ),
        )

        // when the session is stopped and then explicitly uploaded:
        val stopped =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/stop")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val uploaded =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/upload")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        // then stop makes the upload available and a successful upload clears the session:
        assertTrue(stopped.contains("\"state\":\"STOPPED\""))
        assertTrue(stopped.contains("\"activityUpload\":{\"state\":\"AVAILABLE\""))
        assertTrue(stopped.contains("\"activitySummary\":{\"durationSeconds\":"))
        assertTrue(stopped.contains("\"averagePowerWatts\":200"))
        assertTrue(uploaded.contains("\"state\":\"NOT_STARTED\""))
        assertTrue(uploaded.contains("\"sessionId\":null"))
        assertTrue(uploaded.contains("\"activitySummary\":null"))
        assertEquals(1, activityUploader.uploads.size)
    }

    @Test
    fun `discarding a stopped session clears the current session`() {
        // given a manually started session that has been stopped:
        val started =
            restClient
                .post()
                .uri("/training-sessions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val sessionId =
            Regex("\"sessionId\":\"([^\"]+)\"")
                .find(started)
                ?.groupValues
                ?.get(1)
                ?: error("No session id in response: $started")
        restClient
            .post()
            .uri("/training-sessions/$sessionId/stop")
            .exchange()
            .expectStatus()
            .isOk()

        // when the stopped session is discarded:
        val discarded =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/discard")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        // then no stopped session remains in the runtime:
        assertTrue(discarded.contains("\"state\":\"NOT_STARTED\""))
        assertTrue(discarded.contains("\"sessionId\":null"))
    }

    @Test
    fun `discarding an active session returns a conflict`() {
        // given an active manually started session:
        val started =
            restClient
                .post()
                .uri("/training-sessions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val sessionId =
            Regex("\"sessionId\":\"([^\"]+)\"")
                .find(started)
                ?.groupValues
                ?.get(1)
                ?: error("No session id in response: $started")

        // when discard is requested before the session is stopped:
        val response =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/discard")
                .exchange()

        // then the endpoint reports a client conflict:
        response.expectStatus().isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `active workout target adjustment changes the trainer target and carries across steps`() {
        // given a scheduled workout with two power steps:
        val request =
            """
            {
              "workout": {
                "provider": "test-provider",
                "sourceType": "SCHEDULED",
                "sourceId": "event-2"
              }
            }
            """.trimIndent()
        val started =
            restClient
                .post()
                .uri("/training-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val sessionId =
            Regex("\"sessionId\":\"([^\"]+)\"")
                .find(started)
                ?.groupValues
                ?.get(1)
                ?: error("No session id in response: $started")

        // when the target is increased through the REST control and the first step is advanced:
        val adjusted =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/workout-target-adjustment")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"deltaPercent\":1}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val nextStep =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/advance")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        // then the API exposes the adjustment and the next step keeps using it:
        assertTrue(started.contains("\"steps\":[{\"text\":\"Work\""))
        assertTrue(started.contains("\"text\":\"Recovery\""))
        assertTrue(started.contains("\"sourceTarget\":{\"value\":80.0"))
        assertTrue(started.contains("\"sourceTarget\":{\"value\":40.0"))
        assertTrue(started.contains("\"workoutPowerTargetPercent\":100"))
        assertTrue(adjusted.contains("\"workoutPowerTargetPercent\":101"))
        assertTrue(adjusted.contains("\"ergTargetPowerWatts\":202"))
        assertTrue(nextStep.contains("\"workoutPowerTargetPercent\":101"))
        assertTrue(nextStep.contains("\"ergTargetPowerWatts\":101"))
        assertEquals(listOf(200, 202, 101), powerControl.targetPowers)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class WorkoutExecutionIntegrationTestConfiguration {
    @Bean
    @Primary
    fun fakePowerControl(): FakeTrainerControl = FakeTrainerControl()

    @Bean
    @Primary
    fun fakeRideSourceCatalog(powerControl: FakeTrainerControl): FakeRideSourceCatalog = FakeRideSourceCatalog(powerControl)

    @Bean
    @Primary
    fun fakeActivityUploader(): FakeActivityUploader = FakeActivityUploader()

    @Bean
    @Primary
    fun fakePlannedWorkoutCalendar(): PlannedWorkoutCalendar =
        FakePlannedWorkoutCalendar(
            mapOf(
                LocalDate.now() to
                    listOf(
                        ScheduledWorkout(
                            reference = WorkoutSourceReference("test-provider", "event-1"),
                            name = "Manual lap",
                            description = null,
                            type = "Ride",
                            startAt = null,
                            endAt = null,
                            indoor = true,
                            durationSeconds = null,
                            distanceMeters = null,
                            trainingLoad = null,
                            target = "POWER",
                            workout =
                                WorkoutPlanSummary(
                                    description = null,
                                    durationSeconds = null,
                                    distanceMeters = null,
                                    ftpWatts = null,
                                    thresholdHeartRateBpm = null,
                                    target = "POWER",
                                    steps =
                                        listOf(
                                            WorkoutStepSummary(
                                                text = "Lap press",
                                                durationSeconds = null,
                                                distanceMeters = null,
                                                repeats = null,
                                                warmup = null,
                                                cooldown = null,
                                                intensity = null,
                                                ramp = null,
                                                untilLapPress = true,
                                                freeRide = true,
                                                maxEffort = null,
                                                hidePower = null,
                                                power = null,
                                                resolvedPower = null,
                                                heartRate = null,
                                                resolvedHeartRate = null,
                                                pace = null,
                                                resolvedPace = null,
                                                cadence = null,
                                                resolvedDistanceMeters = null,
                                                steps = emptyList(),
                                            ),
                                        ),
                                ),
                        ),
                        ScheduledWorkout(
                            reference = WorkoutSourceReference("test-provider", "event-2"),
                            name = "Power steps",
                            description = null,
                            type = "Ride",
                            startAt = null,
                            endAt = null,
                            indoor = true,
                            durationSeconds = 20,
                            distanceMeters = null,
                            trainingLoad = null,
                            target = "POWER",
                            workout =
                                WorkoutPlanSummary(
                                    description = null,
                                    durationSeconds = 20,
                                    distanceMeters = null,
                                    ftpWatts = 250,
                                    thresholdHeartRateBpm = null,
                                    target = "POWER",
                                    steps =
                                        listOf(
                                            WorkoutStepSummary(
                                                text = "Work",
                                                durationSeconds = 10,
                                                distanceMeters = null,
                                                repeats = null,
                                                warmup = null,
                                                cooldown = null,
                                                intensity = null,
                                                ramp = null,
                                                untilLapPress = true,
                                                freeRide = null,
                                                maxEffort = null,
                                                hidePower = null,
                                                power =
                                                    WorkoutTargetSummary(
                                                        value = 80.0,
                                                        units = "%ftp",
                                                    ),
                                                resolvedPower = null,
                                                heartRate = null,
                                                resolvedHeartRate = null,
                                                pace = null,
                                                resolvedPace = null,
                                                cadence = null,
                                                resolvedDistanceMeters = null,
                                                steps = emptyList(),
                                            ),
                                            WorkoutStepSummary(
                                                text = "Recovery",
                                                durationSeconds = 10,
                                                distanceMeters = null,
                                                repeats = null,
                                                warmup = null,
                                                cooldown = null,
                                                intensity = null,
                                                ramp = null,
                                                untilLapPress = true,
                                                freeRide = null,
                                                maxEffort = null,
                                                hidePower = null,
                                                power =
                                                    WorkoutTargetSummary(
                                                        value = 40.0,
                                                        units = "%ftp",
                                                    ),
                                                resolvedPower = null,
                                                heartRate = null,
                                                resolvedHeartRate = null,
                                                pace = null,
                                                resolvedPace = null,
                                                cadence = null,
                                                resolvedDistanceMeters = null,
                                                steps = emptyList(),
                                            ),
                                        ),
                                ),
                        ),
                    ),
            ),
        )

    @Bean
    @Primary
    fun fakeWorkoutLibrary(): WorkoutLibrary = FakeWorkoutLibrary()
}
