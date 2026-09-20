package paceline

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.client.RestTestClient
import paceline.device.domain.ConnectionPhase
import paceline.testsupport.FakeRideSourceCatalog
import paceline.testsupport.FakeTrainerControl
import paceline.testsupport.rideSource
import paceline.training.domain.RideSourceCapability
import paceline.training.domain.RideSourceDescriptor
import paceline.training.domain.TrainingSessionCoordinator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkoutExecutionIntegrationTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RideEquipmentApiIntegrationTest {
    @LocalServerPort
    private var serverPort: Int = 0

    @Autowired
    private lateinit var rideSourceCatalog: FakeRideSourceCatalog

    @Autowired
    private lateinit var powerControl: FakeTrainerControl

    @Autowired
    private lateinit var coordinator: TrainingSessionCoordinator

    private lateinit var restClient: RestTestClient

    @BeforeEach
    fun setUp() {
        restClient =
            RestTestClient
                .bindToServer()
                .baseUrl("http://127.0.0.1:$serverPort")
                .build()
        rideSourceCatalog.availableRideSources = listOf(trainer("trainer-a"), trainer("trainer-b"))
        rideSourceCatalog.sourcePowerControls["trainer-a"] = powerControl
    }

    @Test
    fun `equipment assignment is separate from connection state and remains sticky`() {
        // given two connected trainers and no persisted ride assignment:
        val ambiguous = getEquipment()

        // when the rider assigns control through the ride-equipment endpoint:
        val assigned =
            putEquipment(
                """
                {"controlSourceId":"trainer-a"}
                """.trimIndent(),
            )

        // then connection capabilities and role assignment are projected separately:
        assertTrue(ambiguous.contains("\"readiness\":\"SELECTION_REQUIRED\""))
        assertTrue(ambiguous.contains("\"code\":\"ROLE_SELECTION_REQUIRED\""))
        assertTrue(assigned.contains("\"readiness\":\"READY\""))
        assertTrue(assigned.contains("\"controlSourceId\":\"trainer-a\""))
        assertTrue(assigned.contains("\"powerSourceId\":\"trainer-a\""))
        assertTrue(assigned.contains("\"cadenceSourceId\":\"trainer-a\""))
        assertTrue(assigned.contains("\"state\":\"CONNECTED\""))

        // when another compatible trainer connects:
        rideSourceCatalog.availableRideSources =
            listOf(
                trainer("trainer-a"),
                trainer("trainer-b"),
                trainer("trainer-c"),
            )
        val afterConnection = getEquipment()

        // then the new source is visible but cannot replace the selected roles:
        assertTrue(afterConnection.contains("\"id\":\"trainer-c\""))
        assertTrue(afterConnection.contains("\"controlSourceId\":\"trainer-a\""))
        assertTrue(afterConnection.contains("\"powerSourceId\":\"trainer-a\""))
        assertTrue(afterConnection.contains("\"cadenceSourceId\":\"trainer-a\""))
    }

    @Test
    fun `roles can be assigned or cleared and invalid sources return clear errors`() {
        // given a resolved control trainer with a second compatible trainer:
        putEquipment("{\"controlSourceId\":\"trainer-a\"}")

        // when the required control role is explicitly cleared:
        val controlCleared = deleteRole("control")

        // then the API exposes the required role as unassigned and not ready:
        assertTrue(controlCleared.contains("\"controlSourceId\":null"))
        assertTrue(controlCleared.contains("\"readiness\":\"SELECTION_REQUIRED\""))
        assertTrue(controlCleared.contains("\"role\":\"RESISTANCE_CONTROL\",\"sourceId\":null,\"status\":\"AMBIGUOUS\""))

        // when control is selected again and an independent power role is assigned and cleared:
        putRole("control", "{\"sourceId\":\"trainer-a\"}")
        val split = putRole("power", "{\"sourceId\":\"trainer-b\"}")
        val cleared = deleteRole("power")

        // then the explicit role operation leaves the other assignments intact while optional telemetry stays non-blocking:
        assertTrue(split.body.contains("\"powerSourceId\":\"trainer-b\""))
        assertTrue(cleared.contains("\"powerSourceId\":null"))
        assertTrue(cleared.contains("\"readiness\":\"READY\""))
        assertTrue(cleared.contains("\"role\":\"POWER\""))
        assertTrue(cleared.contains("\"controlSourceId\":\"trainer-a\""))

        // when an unknown source and an incompatible source are assigned:
        val missing = putRole("power", "{\"sourceId\":\"missing\"}", expectedStatus = 404)
        rideSourceCatalog.availableRideSources += rideSource("cadence-sensor", setOf(RideSourceCapability.CADENCE))
        val incompatible = putRole("power", "{\"sourceId\":\"cadence-sensor\"}", expectedStatus = 400)

        // then the API identifies the invalid source and refuses the incompatible role assignment:
        assertTrue(missing.body.contains("Ride source missing is not connected"))
        assertEquals(400, incompatible.status)
        assertTrue(incompatible.body.contains("cannot provide the POWER role"))
    }

    @Test
    fun `heart rate can remain unselected without blocking control readiness`() {
        // given one connected trainer with no heart-rate capability:
        rideSourceCatalog.availableRideSources = listOf(trainer("trainer-a"))
        val withoutHeartRate = getEquipment()

        // then the control role is ready without a heart-rate assignment:
        assertTrue(withoutHeartRate.contains("\"readiness\":\"READY\""))
        assertTrue(withoutHeartRate.contains("\"role\":\"HEART_RATE\",\"sourceId\":null,\"status\":\"OPTIONAL\""))

        // when two heart-rate sources become available:
        rideSourceCatalog.availableRideSources =
            listOf(
                trainer("trainer-a"),
                heartRate("strap-a"),
                heartRate("strap-b"),
            )
        val multipleHeartRate = getEquipment()

        // then the ambiguous heart-rate source does not block control readiness:
        assertTrue(multipleHeartRate.contains("\"readiness\":\"READY\""))
        assertTrue(multipleHeartRate.contains("\"readinessReasons\":[]"))
        assertTrue(multipleHeartRate.contains("\"role\":\"HEART_RATE\",\"sourceId\":null,\"status\":\"AMBIGUOUS\""))

        // when one heart-rate source is explicitly selected:
        val selected = putRole("heart-rate", "{\"sourceId\":\"strap-a\"}")

        // then the source is retained as a normal selected role:
        assertTrue(selected.body.contains("\"readiness\":\"READY\""))
        assertTrue(selected.body.contains("\"heartRateSourceId\":\"strap-a\""))
    }

    @Test
    fun `session start rejects ambiguous and unavailable required roles`() {
        // given two compatible trainers with no explicit role selection:
        val ambiguous = postStart(expectedStatus = 409)

        // then the unresolved role is named in the API error:
        assertTrue(ambiguous.body.contains("Select a source for the RESISTANCE_CONTROL ride role"))

        // when one trainer is resolved and then disconnects before the start request:
        rideSourceCatalog.availableRideSources = listOf(trainer("trainer-a"))
        getEquipment()
        rideSourceCatalog.availableRideSources =
            listOf(
                trainer("trainer-a", ConnectionPhase.DISCONNECTED),
                trainer("trainer-b"),
            )
        val unavailable = postStart(expectedStatus = 409)

        // then the persisted source is reported as unavailable rather than replaced:
        assertTrue(unavailable.body.contains("Selected source trainer-a is unavailable"))
    }

    @Test
    fun `session start accepts an FTMS control-only source`() {
        // given one connected source with FTMS control and no power or cadence capability:
        rideSourceCatalog.availableRideSources =
            listOf(
                rideSource(
                    "ftms-control",
                    setOf(RideSourceCapability.RESISTANCE_CONTROL),
                ),
            )
        rideSourceCatalog.sourcePowerControls["ftms-control"] = powerControl

        // when the equipment projection and empty session start are requested:
        val equipment = getEquipment()
        val started = postStart(expectedStatus = 200)

        // then control is sufficient while the telemetry roles remain optional:
        assertTrue(equipment.contains("\"readiness\":\"READY\""))
        assertTrue(equipment.contains("\"role\":\"POWER\",\"sourceId\":null,\"status\":\"OPTIONAL\""))
        assertTrue(equipment.contains("\"role\":\"CADENCE\",\"sourceId\":null,\"status\":\"OPTIONAL\""))
        assertTrue(started.body.contains("\"state\":\"ACTIVE\""))
        assertTrue(started.body.contains("\"controlSourceId\":\"ftms-control\""))
        assertTrue(started.body.contains("\"powerSourceId\":null"))
        assertTrue(started.body.contains("\"cadenceSourceId\":null"))
    }

    @Test
    fun `active session projection keeps control owner when recovery starts`() {
        // given explicit sources for control, power, and cadence:
        val started =
            restClient
                .post()
                .uri("/training-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """
                    {
                      "equipment": {
                        "controlSourceId": "trainer-a",
                        "powerSourceId": "trainer-b",
                        "cadenceSourceId": "trainer-a"
                      }
                    }
                    """.trimIndent(),
                ).exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        // then the active session reports the source split and control owner:
        assertTrue(started.contains("\"state\":\"ACTIVE\""))
        assertTrue(started.contains("\"controlSourceId\":\"trainer-a\""))
        assertTrue(started.contains("\"powerSourceId\":\"trainer-b\""))
        assertTrue(started.contains("\"trainerConnection\":\"CONNECTED\""))

        // when the selected control trainer becomes unavailable and recovery is ticked:
        rideSourceCatalog.availableRideSources =
            listOf(
                trainer("trainer-a", ConnectionPhase.DISCONNECTED),
                trainer("trainer-b"),
            )
        coordinator.tick()
        val recovering =
            restClient
                .get()
                .uri("/training-sessions/current")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        // then recovery is tied to the same selected trainer rather than falling back:
        assertTrue(recovering.contains("\"controlSourceId\":\"trainer-a\""))
        assertTrue(recovering.contains("\"trainerConnection\":\"RECONNECTING\""))
        assertTrue(recovering.contains("\"role\":\"RESISTANCE_CONTROL\",\"sourceId\":\"trainer-a\",\"status\":\"UNAVAILABLE\""))
    }

    private fun getEquipment(): String =
        restClient
            .get()
            .uri("/training-sessions/equipment")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun putEquipment(body: String): String =
        restClient
            .put()
            .uri("/training-sessions/equipment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun putRole(
        role: String,
        body: String,
        expectedStatus: Int = 200,
    ): RoleResponse {
        val exchange =
            restClient
                .put()
                .uri("/training-sessions/equipment/$role")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
        val response = exchange.expectStatus()
        val result =
            when (expectedStatus) {
                200 -> response.isOk()
                400 -> response.isBadRequest()
                404 -> response.isNotFound()
                else -> error("Unsupported expected status $expectedStatus")
            }.expectBody(String::class.java).returnResult()
        return RoleResponse(
            status = expectedStatus,
            body = result.responseBody!!,
        )
    }

    private fun deleteRole(role: String): String =
        restClient
            .delete()
            .uri("/training-sessions/equipment/$role")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun postStart(expectedStatus: Int): RoleResponse {
        val result =
            restClient
                .post()
                .uri("/training-sessions")
                .exchange()
                .expectStatus()
                .isEqualTo(expectedStatus)
                .expectBody(String::class.java)
                .returnResult()
        return RoleResponse(
            status = expectedStatus,
            body = result.responseBody!!,
        )
    }

    private fun trainer(
        id: String,
        state: ConnectionPhase = ConnectionPhase.CONNECTED,
    ): RideSourceDescriptor =
        rideSource(
            id = id,
            state = state,
            capabilities =
                setOf(
                    RideSourceCapability.RESISTANCE_CONTROL,
                    RideSourceCapability.POWER,
                    RideSourceCapability.CADENCE,
                ),
        )

    private fun heartRate(id: String): RideSourceDescriptor =
        rideSource(
            id = id,
            capabilities = setOf(RideSourceCapability.HEART_RATE),
        )

    private data class RoleResponse(
        val status: Int,
        val body: String,
    )
}
