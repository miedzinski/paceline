package paceline.workout.adapters

import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import paceline.intervals.adapters.IntervalsIcuClient
import paceline.intervals.adapters.IntervalsIcuException
import paceline.intervals.config.IntervalsIcuProperties
import paceline.workout.domain.WorkoutZoneDistribution
import paceline.workout.ports.WorkoutProviderUnavailableException
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class IntervalsIcuWorkoutSourceTest {
    @Test
    fun `provider failures are translated to the workout port exception`() {
        // given Intervals.icu rejects the completed-activity request:
        val (client, server) = createClient()
        val source = IntervalsIcuWorkoutSource(client)
        val date = LocalDate.of(2026, 9, 14)
        server
            .expect(
                requestTo(
                    "http://intervals.test/api/v1/athlete/0/activities?oldest=2026-09-14&newest=2026-09-14",
                ),
            ).andRespond(withStatus(HttpStatus.BAD_GATEWAY))

        // when today's uncompleted workouts are requested:
        val exception =
            assertFailsWith<WorkoutProviderUnavailableException> {
                source.uncompletedFor(date)
            }

        // then the workout slice exposes its own provider boundary exception:
        server.verify()
        assertEquals("Intervals.icu completed activities could not be read", exception.message)
        assertIs<IntervalsIcuException>(exception.cause)
    }

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
                requestTo("http://intervals.test/api/v1/athlete/0/events?oldest=2026-09-14&newest=2026-09-14&category=WORKOUT"),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("oldest", "2026-09-14"))
            .andExpect(queryParam("newest", "2026-09-14"))
            .andExpect(queryParam("category", "WORKOUT"))
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
                        "icu_training_load":33,
                        "start_date_local":"2026-09-14T07:00:00",
                        "moving_time":1800,
                        "workout_doc":{"target":"POWER","ftp":0,"zoneTimes":[900,780,120,0,0,0,0],"steps":[{"duration":900,"power":{"value":95,"units":"%ftp"}},{"duration":60,"freeride":true}]}
                      }
                    ]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )
        server
            .expect(
                requestTo("http://intervals.test/api/v1/athlete/0/sport-settings/Ride"),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", authorization))
            .andRespond(
                withSuccess(
                    """{"ftp":250}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when today's uncompleted workouts are requested:
        val result = source.uncompletedFor(date)

        // then the completed event is hidden and the remaining workout keeps its source identity and steps:
        server.verify()
        assertEquals(listOf("102"), result.map { it.reference.id })
        assertEquals("intervals.icu", result.single().reference.provider)
        assertEquals(33.0, result.single().trainingLoad)
        assertEquals(250, result.single().workout?.ftpWatts)
        assertEquals(
            listOf(
                WorkoutZoneDistribution("Z1", 900),
                WorkoutZoneDistribution("Z2", 780),
                WorkoutZoneDistribution("Z3", 120),
                WorkoutZoneDistribution("Z4", 0),
                WorkoutZoneDistribution("Z5", 0),
                WorkoutZoneDistribution("Z6", 0),
                WorkoutZoneDistribution("Z7", 0),
            ),
            result.single().workout?.plannedZoneDistribution,
        )
        assertEquals(
            238.0,
            result
                .single()
                .workout
                ?.steps
                ?.first()
                ?.resolvedPower
                ?.value,
        )
        assertEquals(
            true,
            result
                .single()
                .workout
                ?.steps
                ?.get(1)
                ?.freeRide,
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
                    {"id":77,"name":"Saved tempo","type":"Ride","workout_doc":{"zoneTimes":[{"id":"Z1","secs":240},{"id":"Z2","secs":60}],"steps":[{"duration":300,"ramp":true,"power":{"start":60,"end":75,"units":"%ftp"}}]}}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )
        server
            .expect(
                requestTo("http://intervals.test/api/v1/athlete/0/sport-settings/Ride"),
            ).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Basic " + Base64.getEncoder().encodeToString("API_KEY:secret".toByteArray())))
            .andRespond(
                withSuccess(
                    """{"ftp":250}""",
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
            300,
            detail
                ?.workout
                ?.steps
                ?.single()
                ?.durationSeconds,
        )
        assertEquals(
            true,
            detail
                ?.workout
                ?.steps
                ?.single()
                ?.ramp,
        )
        assertEquals(
            listOf(
                WorkoutZoneDistribution("Z1", 240),
                WorkoutZoneDistribution("Z2", 60),
            ),
            detail?.workout?.plannedZoneDistribution,
        )
        assertEquals(
            150.0,
            detail
                ?.workout
                ?.steps
                ?.single()
                ?.resolvedPower
                ?.start,
        )
        assertEquals(
            188.0,
            detail
                ?.workout
                ?.steps
                ?.single()
                ?.resolvedPower
                ?.end,
        )
    }

    @Test
    fun `resolves provider power zone steps using the configured backend zones`() {
        // given a saved workout whose provider target is a power zone:
        val (client, server) = createClient()
        val source = IntervalsIcuWorkoutSource(client)
        server
            .expect(
                requestTo("http://intervals.test/api/v1/athlete/0/workouts/88"),
            ).andRespond(
                withSuccess(
                    """
                    {
                      "id":88,
                      "name":"Endurance",
                      "type":"Ride",
                      "workout_doc":{
                        "steps":[
                          {
                            "duration":600,
                            "power":{"value":2,"units":"power_zone"}
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )
        server
            .expect(
                requestTo("http://intervals.test/api/v1/athlete/0/sport-settings/Ride"),
            ).andRespond(
                withSuccess(
                    """{"ftp":250,"power_zones":[55,75,90],"power_zone_names":["Recovery","Endurance","Tempo"]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        // when the saved workout is read through the provider adapter:
        val workout = source.find("88")
        val step = workout?.workout?.steps?.single()

        // then the raw zone remains visible while execution receives the backend-resolved watt range:
        server.verify()
        assertEquals(2.0, step?.power?.value)
        assertEquals("power_zone", step?.power?.units)
        assertEquals(138.0, step?.resolvedPower?.start)
        assertEquals(187.0, step?.resolvedPower?.end)
        assertEquals("W", step?.resolvedPower?.units)
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
