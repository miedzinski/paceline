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
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.client.RestTestClient
import paceline.device.domain.IndoorBikeTelemetry
import paceline.testsupport.FakeActivityUploader
import paceline.testsupport.FakeIndoorBikePowerControl
import paceline.testsupport.FakePlannedWorkoutCalendar
import paceline.testsupport.FakeTrainingDevice
import paceline.testsupport.FakeWorkoutLibrary
import paceline.workout.domain.ScheduledWorkout
import paceline.workout.domain.WorkoutPlanSummary
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutStepSummary
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
    private lateinit var powerControl: FakeIndoorBikePowerControl

    @Autowired
    private lateinit var trainingDevice: FakeTrainingDevice

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

        // then the source identity and execution state are exposed and the trainer ends at zero watts:
        assertTrue(started.contains("\"state\":\"ACTIVE\""))
        assertTrue(started.contains("\"sourceId\":\"event-1\""))
        assertTrue(started.contains("\"currentStep\":1"))
        assertTrue(started.contains("\"ergTargetPowerWatts\":0"))
        assertTrue(completed.contains("\"state\":\"ACTIVE\""))
        assertTrue(completed.contains("\"completed\":true"))
        assertEquals(listOf(0, 0), powerControl.targetPowers)
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
        assertTrue(paused.contains("\"ergTargetPowerWatts\":0"))
        assertTrue(paused.contains("\"sourceId\":\"event-1\""))
        assertTrue(resumed.contains("\"state\":\"ACTIVE\""))
        assertEquals(listOf(0, 0, 0), powerControl.targetPowers)
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
        trainingDevice.emitTelemetry(
            IndoorBikeTelemetry(
                powerWatts = 200,
                cadenceRpm = 90.0,
                speedKph = 25.0,
                distanceMeters = 1_000.0,
                receivedAt = Instant.parse("2026-09-14T12:00:00Z"),
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

        // then stop only makes the upload available and the explicit action performs exactly one upload:
        assertTrue(stopped.contains("\"state\":\"STOPPED\""))
        assertTrue(stopped.contains("\"activityUpload\":{\"state\":\"AVAILABLE\""))
        assertTrue(uploaded.contains("\"activityUpload\":{\"state\":\"UPLOADED\""))
        assertEquals(1, activityUploader.uploads.size)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class WorkoutExecutionIntegrationTestConfiguration {
    @Bean
    @Primary
    fun fakePowerControl(): FakeIndoorBikePowerControl = FakeIndoorBikePowerControl()

    @Bean
    @Primary
    fun fakeTrainingDevice(powerControl: FakeIndoorBikePowerControl): FakeTrainingDevice = FakeTrainingDevice(powerControl)

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
                    ),
            ),
        )

    @Bean
    @Primary
    fun fakeWorkoutLibrary(): WorkoutLibrary = FakeWorkoutLibrary()
}
