package paceline.profile.adapters

import org.springframework.http.HttpStatus
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import paceline.intervals.adapters.IntervalsIcuClient
import paceline.intervals.adapters.IntervalsIcuException
import paceline.intervals.config.IntervalsIcuProperties
import paceline.profile.ports.ProfileProviderUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class IntervalsIcuProfileSourceTest {
    @Test
    fun `provider failures are translated to the profile port exception`() {
        // given Intervals.icu rejects the athlete profile request:
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
        server
            .expect(requestTo("http://intervals.test/api/v1/athlete/0/profile"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY))

        // when the profile is requested:
        val exception =
            assertFailsWith<ProfileProviderUnavailableException> {
                IntervalsIcuProfileSource(client).profile()
            }

        // then the profile slice exposes its own provider boundary exception:
        server.verify()
        assertEquals("Intervals.icu athlete profile could not be read", exception.message)
        assertIs<IntervalsIcuException>(exception.cause)
    }
}
