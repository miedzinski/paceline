package paceline.workout.adapters

import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import paceline.intervals.adapters.IntervalsIcuClient
import paceline.intervals.config.IntervalsIcuProperties
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class IntervalsIcuWorkoutSourceTest {
    @Test
    fun `today hides calendar workouts paired with completed activities`() {
        // given Intervals.icu has one completed activity paired to the first calendar event:
        val (client, server) = createClient()
        val source = IntervalsIcuWorkoutSource(client)
        val date = LocalDate.of(2026, 9, 14)
        val authorization =
            "Basic " +
                Base64.getEncoder().encodeToString("API_KEY:secret".toByteArray())

        server
            .expect(
                requestTo(
                    "http://intervals.test/api/v1/athlete/0/activities?oldest=2026-09-14&newest=2026-09-14",
                ),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("oldest", "2026-09-14"))
            .andExpect(queryParam("newest", "2026-09-14"))
            .andExpect(header("Authorization", authorization))
            .andRespond(
                withSuccess(
                    """
                    [{"id":"i-completed","paired_event_id":101}]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )
        server
            .expect(
                requestTo(
                    "http://intervals.test/api/v1/athlete/0/events?oldest=2026-09-14&newest=2026-09-14&category=WORKOUT&resolve=true",
                ),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("oldest", "2026-09-14"))
            .andExpect(queryParam("newest", "2026-09-14"))
            .andExpect(queryParam("category", "WORKOUT"))
            .andExpect(queryParam("resolve", "true"))
            .andExpect(header("Authorization", authorization))
            .andRespond(
                withSuccess(
                    """
                    [
                      {"id":101,"category":"WORKOUT","name":"Completed","type":"Ride"},
                      {
                        "id":102,
                        "category":"WORKOUT",
                        "name":"Threshold",
                        "type":"Ride",
                        "start_date_local":"2026-09-14T07:00:00",
                        "moving_time":1800,
                        "workout_doc":{"target":"POWER","steps":[{"duration":900,"power":{"value":95,"units":"%ftp"},"_power":{"value":275,"units":"W"}}]}
                      }
                    ]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when today's uncompleted workouts are requested:
        val result = source.uncompletedFor(date)

        // then the completed event is hidden and the remaining workout keeps its source identity and steps:
        server.verify()
        assertEquals(listOf("102"), result.map { it.reference.id })
        assertEquals("intervals.icu", result.single().reference.provider)
        assertEquals(
            275.0,
            result
                .single()
                .workout
                ?.steps
                ?.single()
                ?.resolvedPower
                ?.value,
        )
    }

    @Test
    fun `library exposes saved workout summaries and details through separate calls`() {
        // given Intervals.icu has a saved workout in its library:
        val (client, server) = createClient()
        val source = IntervalsIcuWorkoutSource(client)

        server
            .expect(
                requestTo("http://intervals.test/api/v1/athlete/0/workouts"),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Basic " + Base64.getEncoder().encodeToString("API_KEY:secret".toByteArray())))
            .andRespond(
                withSuccess(
                    """
                    [{"id":77,"name":"Saved tempo","type":"Ride","targets":["POWER"]}]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )
        server
            .expect(
                requestTo("http://intervals.test/api/v1/athlete/0/workouts/77"),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Basic " + Base64.getEncoder().encodeToString("API_KEY:secret".toByteArray())))
            .andRespond(
                withSuccess(
                    """
                    {"id":77,"name":"Saved tempo","type":"Ride","workout_doc":{"steps":[{"duration":1200,"power":{"value":88,"units":"%ftp"}}]}}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when the library is listed and one saved workout is opened:
        val summaries = source.list()
        val detail = source.find("77")

        // then the list stays lightweight while the detail call exposes its structured steps:
        server.verify()
        assertEquals("77", summaries.single().reference.id)
        assertEquals("Saved tempo", summaries.single().name)
        assertEquals("77", detail?.reference?.id)
        assertEquals(
            1200,
            detail
                ?.workout
                ?.steps
                ?.single()
                ?.durationSeconds,
        )
    }

    private fun createClient(): Pair<IntervalsIcuClient, MockRestServiceServer> {
        val builder = RestClient.builder().baseUrl("http://intervals.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client =
            IntervalsIcuClient(
                builder.build(),
                IntervalsIcuProperties(
                    baseUrl = "http://intervals.test",
                    apiKey = "secret",
                ),
            )
        return client to server
    }
}
