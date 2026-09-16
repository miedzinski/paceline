package paceline.intervals.adapters

import org.hamcrest.Matchers.containsString
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import paceline.intervals.config.IntervalsIcuProperties
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IntervalsIcuClientTest {
    @Test
    fun `missing api key is reported as an intervals integration failure`() {
        // given an Intervals.icu client without a configured API key:
        val client =
            IntervalsIcuClient(
                RestClient.builder().baseUrl("http://intervals.test").build(),
                IntervalsIcuProperties(baseUrl = "http://intervals.test"),
            )

        // when the client is asked to read completed activities:
        val exception =
            assertFailsWith<IntervalsIcuException> {
                client.completedActivities(LocalDate.of(2026, 9, 14))
            }

        // then the integration slice owns the transport failure:
        assertEquals("Intervals.icu API key is not configured", exception.message)
    }

    @Test
    fun `activity upload sends a multipart file with api key authentication`() {
        // given an Intervals.icu client pointed at a controlled HTTP fixture:
        val builder = RestClient.builder().baseUrl("http://intervals.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client =
            IntervalsIcuClient(
                builder.build(),
                IntervalsIcuProperties(
                    baseUrl = "http://intervals.test",
                    athleteId = "athlete-1",
                    apiKey = "secret",
                ),
            )
        val authorization =
            "Basic " +
                Base64.getEncoder().encodeToString("API_KEY:secret".toByteArray())
        server
            .expect(
                requestTo(
                    "http://intervals.test/api/v1/athlete/athlete-1/activities" +
                        "?name=Paceline%20ride&description=Recorded%20by%20Paceline&external_id=session-1" +
                        "&paired_event_id=123",
                ),
            ).andExpect(method(HttpMethod.POST))
            .andExpect(queryParam("name", "Paceline%20ride"))
            .andExpect(queryParam("description", "Recorded%20by%20Paceline"))
            .andExpect(queryParam("external_id", "session-1"))
            .andExpect(queryParam("paired_event_id", "123"))
            .andExpect(header("Authorization", authorization))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andExpect(content().string(containsString("paceline.fit")))
            .andRespond(
                withSuccess(
                    "{\"icu_athlete_id\":\"athlete-1\",\"id\":\"activity-1\"}",
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when the completed activity is uploaded:
        val result =
            client.uploadActivity(
                fileName = "paceline.fit",
                contentType = "application/octet-stream",
                content = byteArrayOf(0x0e, 0x20, 0x00, 0x00),
                name = "Paceline ride",
                description = "Recorded by Paceline",
                externalId = "session-1",
                pairedEventId = 123L,
            )

        // then the remote activity identity is returned to the session runtime:
        server.verify()
        assertEquals("activity-1", result?.id)
    }
}
