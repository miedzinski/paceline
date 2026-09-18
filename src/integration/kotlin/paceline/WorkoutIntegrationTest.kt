package paceline

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParamCount
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "paceline.intervals.athlete-id=athlete-integration",
        "paceline.intervals.api-key=integration-key",
        "paceline.intervals.base-url=http://intervals-mock.test",
    ],
)
@Import(MockIntervalsIcuRestClientConfiguration::class)
class WorkoutIntegrationTest {
    @Autowired
    private lateinit var mockIntervalsServer: MockRestServiceServer

    @LocalServerPort
    private var serverPort: Int = 0

    private lateinit var restClient: RestTestClient
    private lateinit var today: LocalDate

    @BeforeEach
    fun setUp() {
        today = LocalDate.now()
        mockIntervalsServer.reset()
        restClient =
            RestTestClient
                .bindToServer()
                .baseUrl("http://127.0.0.1:$serverPort")
                .build()
    }

    @Test
    fun `today endpoint reads the real client and hides completed calendar events`() {
        // given the mock Intervals.icu client has one completed and one uncompleted workout:
        expectTodayRequests()

        // when today's endpoint is requested through the running application:
        val response = get("/workouts/today")

        // then the real HTTP client, provider adapter, catalog, and controller expose only the uncompleted event:
        assertTrue(response.contains("\"date\":\"$today\""))
        assertTrue(response.contains("\"sourceEventId\":\"102\""))
        assertTrue(response.contains("\"name\":\"Threshold intervals\""))
        assertFalse(response.contains("Completed intervals"))
        assertTrue(response.contains("\"plannedZoneDistribution\":[{\"zone\":\"Z1\",\"durationSeconds\":900}"))
        assertTrue(response.contains("\"resolvedPower\":{\"value\":237.0"))
        assertTrue(response.contains("\"freeRide\":true"))
        mockIntervalsServer.verify()
    }

    @Test
    fun `library endpoints use the real client and map saved workout details`() {
        // given the mock Intervals.icu client has one saved workout:
        expectLibraryRequests()

        // when the library list and detail endpoints are requested:
        val listResponse = get("/workouts/library")
        val detailResponse = get("/workouts/library/77")

        // then both responses are mapped through the production integration:
        assertTrue(listResponse.contains("\"sourceWorkoutId\":\"77\""))
        assertTrue(listResponse.contains("\"name\":\"Saved tempo\""))
        assertTrue(detailResponse.contains("\"durationSeconds\":1200"))
        assertTrue(detailResponse.contains("\"value\":220.0"))
        mockIntervalsServer.verify()
    }

    private fun expectTodayRequests() {
        val expectedAuthorization = expectedAuthorization()
        mockIntervalsServer
            .expect(
                once(),
                requestTo(
                    "$MOCK_BASE_URL/api/v1/athlete/athlete-integration/activities" +
                        "?oldest=$today&newest=$today",
                ),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(queryParamCount(2))
            .andExpect(queryParam("oldest", today.toString()))
            .andExpect(queryParam("newest", today.toString()))
            .andExpect(header("Authorization", expectedAuthorization))
            .andRespond(withSuccess(COMPLETED_ACTIVITIES, MediaType.APPLICATION_JSON))

        mockIntervalsServer
            .expect(
                once(),
                requestTo(
                    "$MOCK_BASE_URL/api/v1/athlete/athlete-integration/events" +
                        "?oldest=$today&newest=$today&category=WORKOUT",
                ),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(queryParamCount(3))
            .andExpect(queryParam("oldest", today.toString()))
            .andExpect(queryParam("newest", today.toString()))
            .andExpect(queryParam("category", "WORKOUT"))
            .andExpect(header("Authorization", expectedAuthorization))
            .andRespond(withSuccess(calendarEvents(today), MediaType.APPLICATION_JSON))
    }

    private fun expectLibraryRequests() {
        val expectedAuthorization = expectedAuthorization()
        mockIntervalsServer
            .expect(
                once(),
                requestTo("$MOCK_BASE_URL/api/v1/athlete/athlete-integration/workouts"),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(queryParamCount(0))
            .andExpect(header("Authorization", expectedAuthorization))
            .andRespond(withSuccess(LIBRARY_WORKOUTS, MediaType.APPLICATION_JSON))

        mockIntervalsServer
            .expect(
                once(),
                requestTo("$MOCK_BASE_URL/api/v1/athlete/athlete-integration/workouts/77"),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(queryParamCount(0))
            .andExpect(header("Authorization", expectedAuthorization))
            .andRespond(withSuccess(LIBRARY_WORKOUT_DETAIL, MediaType.APPLICATION_JSON))

        mockIntervalsServer
            .expect(
                once(),
                requestTo("$MOCK_BASE_URL/api/v1/athlete/athlete-integration/sport-settings/Ride"),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(queryParamCount(0))
            .andExpect(header("Authorization", expectedAuthorization))
            .andRespond(withSuccess("""{"ftp":250}""", MediaType.APPLICATION_JSON))
    }

    private fun get(path: String): String =
        restClient
            .get()
            .uri(path)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun expectedAuthorization(): String =
        "Basic " +
            Base64.getEncoder().encodeToString("API_KEY:integration-key".toByteArray())

    companion object {
        private const val MOCK_BASE_URL = "http://intervals-mock.test"

        private const val COMPLETED_ACTIVITIES =
            """
            [{"id":"activity-101","paired_event_id":101}]
            """

        private fun calendarEvents(date: LocalDate): String =
            """
            [
              {"id":101,"category":"WORKOUT","name":"Completed intervals","type":"Ride"},
              {
                "id":102,
                "category":"WORKOUT",
                "name":"Threshold intervals",
                "type":"Ride",
                "start_date_local":"${date}T07:00:00",
                "end_date_local":"${date}T08:00:00",
                "indoor":true,
                "moving_time":3600,
                "target":"POWER",
                "workout_doc":{
                  "target":"POWER",
                  "ftp":250,
                  "zoneTimes":[900,2400,0,0,0,0,0],
                  "steps":[
                    {"duration":900,"power":{"value":95,"units":"%ftp"}},
                    {"duration":60,"freeride":true}
                  ]
                }
              }
            ]
            """.trimIndent()

        private const val LIBRARY_WORKOUTS =
            """
            [{"id":77,"name":"Saved tempo","type":"Ride","targets":["POWER"]}]
            """

        private const val LIBRARY_WORKOUT_DETAIL =
            """
            {"id":77,"name":"Saved tempo","type":"Ride","workout_doc":{"steps":[{"duration":1200,"power":{"value":88,"units":"%ftp"}}]}}
            """
    }
}

@TestConfiguration(proxyBeanMethods = false)
class MockIntervalsIcuRestClientConfiguration {
    private var server: MockRestServiceServer? = null

    @Bean
    @Primary
    @Qualifier("intervalsIcuRestClient")
    fun mockIntervalsIcuRestClient(): RestClient {
        val builder = RestClient.builder().baseUrl("http://intervals-mock.test")
        server = MockRestServiceServer.bindTo(builder).build()
        return builder.build()
    }

    @Bean
    @Suppress("UNUSED_PARAMETER")
    fun mockRestServiceServer(
        @Qualifier("intervalsIcuRestClient") mockIntervalsIcuRestClient: RestClient,
    ): MockRestServiceServer = requireNotNull(server) { "The mock RestClient must be initialized first" }
}
