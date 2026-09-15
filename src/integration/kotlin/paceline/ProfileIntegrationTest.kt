package paceline

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.servlet.client.RestTestClient
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
class ProfileIntegrationTest {
    @Autowired
    private lateinit var mockIntervalsServer: MockRestServiceServer

    @LocalServerPort
    private var serverPort: Int = 0

    private lateinit var restClient: RestTestClient

    @BeforeEach
    fun setUp() {
        mockIntervalsServer.reset()
        restClient =
            RestTestClient
                .bindToServer()
                .baseUrl("http://127.0.0.1:$serverPort")
                .build()
    }

    @Test
    fun `profile endpoint exposes compact athlete data and calculated watt ranges`() {
        // given the mock Intervals.icu athlete response contains basic identity and cycling settings:
        mockIntervalsServer
            .expect(requestTo("$MOCK_BASE_URL/api/v1/athlete/athlete-integration/profile"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", expectedAuthorization()))
            .andRespond(withSuccess(ATHLETE_SUMMARY, MediaType.APPLICATION_JSON))
        mockIntervalsServer
            .expect(requestTo("$MOCK_BASE_URL/api/v1/athlete/athlete-integration/sport-settings/Ride"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", expectedAuthorization()))
            .andRespond(withSuccess(CYCLING_SETTINGS, MediaType.APPLICATION_JSON))

        // when the app profile endpoint is requested:
        val response =
            restClient
                .get()
                .uri("/profile")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        // then the app receives only the useful profile data and not unrelated provider fields:
        assertTrue(response.contains("\"name\":\"Dominik\""))
        assertTrue(response.contains("\"ftpWatts\":250"))
        assertTrue(response.contains("\"minWatts\":0"))
        assertTrue(response.contains("\"maxWatts\":375"))
        assertFalse(response.contains("email"))
        mockIntervalsServer.verify()
    }

    private fun expectedAuthorization(): String =
        "Basic " +
            Base64.getEncoder().encodeToString("API_KEY:integration-key".toByteArray())

    companion object {
        private const val MOCK_BASE_URL = "http://intervals-mock.test"

        private val ATHLETE_SUMMARY =
            """
            {
              "athlete":{"id":"athlete-integration","name":"Dominik","email":"not-exposed@example.test"}
            }
            """.trimIndent()

        private val CYCLING_SETTINGS =
            """
            {
              "types":["Ride","VirtualRide"],
              "ftp":250,
              "power_zones":[55,75,90,105,120,150,999],
              "power_zone_names":["Active Recovery","Endurance","Tempo","Threshold","VO2 Max","Anaerobic","Neuromuscular"]
            }
            """.trimIndent()
    }
}
