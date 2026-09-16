package paceline.training.adapters

import org.springframework.http.HttpStatus
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import paceline.intervals.adapters.IntervalsIcuClient
import paceline.intervals.adapters.IntervalsIcuException
import paceline.intervals.config.IntervalsIcuProperties
import paceline.training.domain.RecordedTrainingActivity
import paceline.training.ports.ActivityFile
import paceline.training.ports.ActivityUploadException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class IntervalsIcuActivityUploaderTest {
    @Test
    fun `provider failures are translated to the activity upload port exception`() {
        // given Intervals.icu rejects the activity upload request:
        val builder = RestClient.builder().baseUrl("http://intervals.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val client =
            IntervalsIcuClient(
                builder.build(),
                IntervalsIcuProperties(
                    baseUrl = "http://intervals.test",
                    apiKey = "secret",
                ),
            )
        val activity =
            RecordedTrainingActivity(
                sessionId = sessionId,
                startedAt = Instant.parse("2026-09-14T07:00:00Z"),
                stoppedAt = Instant.parse("2026-09-14T07:30:00Z"),
                name = "Paceline ride",
                workoutSource = null,
                workoutCompleted = false,
                samples = emptyList(),
            )
        server
            .expect(
                requestTo(
                    "http://intervals.test/api/v1/athlete/0/activities" +
                        "?name=Paceline%20ride&description=Recorded%20by%20Paceline&external_id=$sessionId",
                ),
            ).andRespond(withStatus(HttpStatus.BAD_GATEWAY))
        val uploader =
            IntervalsIcuActivityUploader(client) {
                ActivityFile(
                    fileName = "paceline.fit",
                    contentType = "application/octet-stream",
                    content = byteArrayOf(0x0e, 0x20, 0x00, 0x00),
                )
            }

        // when the stopped activity is uploaded:
        val exception = assertFailsWith<ActivityUploadException> { uploader.upload(activity) }

        // then the training slice exposes its own upload boundary exception:
        server.verify()
        assertEquals("Intervals.icu activity upload failed", exception.message)
        assertIs<IntervalsIcuException>(exception.cause)
    }
}
