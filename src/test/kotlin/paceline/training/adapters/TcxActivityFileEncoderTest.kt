package paceline.training.adapters

import paceline.training.domain.RecordedTrainingActivity
import paceline.training.domain.RecordedTrainingActivitySegment
import paceline.training.domain.TrainingTelemetrySample
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TcxActivityFileEncoderTest {
    private val encoder = TcxActivityFileEncoder()

    @Test
    fun `activity file preserves timed power cadence speed and relative distance samples`() {
        // given an in-memory ride with two raw telemetry samples:
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                startedAt = Instant.parse("2026-09-14T12:00:00Z"),
                stoppedAt = Instant.parse("2026-09-14T12:00:01Z"),
                name = "Test ride",
                workoutSource = null,
                workoutCompleted = false,
                samples =
                    listOf(
                        sample("2026-09-14T12:00:00Z", 1_000.0, 200),
                        sample("2026-09-14T12:00:00.100Z", 1_001.5, 210),
                    ),
            )

        // when the activity is encoded as TCX:
        val file = encoder.encode(activity)
        val xml = file.content.toString(Charsets.UTF_8)
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(file.content.inputStream())

        // then the upload file contains both samples and the expected indoor-bike fields:
        assertEquals("paceline-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.tcx", file.fileName)
        assertEquals("TrainingCenterDatabase", document.documentElement.localName)
        assertEquals("http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2", document.documentElement.namespaceURI)
        assertContains(xml, "<TotalTimeSeconds>1.000</TotalTimeSeconds>")
        assertContains(xml, "<DistanceMeters>1.500</DistanceMeters>")
        assertContains(xml, "<ns3:Watts>200</ns3:Watts>")
        assertContains(xml, "<ns3:Watts>210</ns3:Watts>")
        assertContains(xml, "<Cadence>90</Cadence>")
        assertContains(xml, "<ns3:Speed>6.944</ns3:Speed>")
        assertEquals(2, Regex("<Trackpoint>").findAll(xml).count())
    }

    @Test
    fun `activity file preserves executed workout segments as separate laps`() {
        // given an activity whose telemetry is split across two executed workout segments:
        val firstSample = sample("2026-09-14T12:00:00Z", 1_000.0, 150)
        val secondSample = sample("2026-09-14T12:00:01Z", 1_001.0, 150)
        val thirdSample = sample("2026-09-14T12:00:02Z", 1_002.5, 220)
        val fourthSample = sample("2026-09-14T12:00:03Z", 1_004.0, 220)
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                startedAt = Instant.parse("2026-09-14T12:00:00Z"),
                stoppedAt = Instant.parse("2026-09-14T12:00:04Z"),
                name = "Structured ride",
                workoutSource = null,
                workoutCompleted = true,
                samples = listOf(firstSample, secondSample, thirdSample, fourthSample),
                segments =
                    listOf(
                        RecordedTrainingActivitySegment(
                            name = "Warmup",
                            targetPowerWatts = 150,
                            startedAt = Instant.parse("2026-09-14T12:00:00Z"),
                            stoppedAt = Instant.parse("2026-09-14T12:00:02Z"),
                            samples = listOf(firstSample, secondSample),
                        ),
                        RecordedTrainingActivitySegment(
                            name = "Intervals & recovery",
                            targetPowerWatts = 220,
                            startedAt = Instant.parse("2026-09-14T12:00:02Z"),
                            stoppedAt = Instant.parse("2026-09-14T12:00:04Z"),
                            samples = listOf(thirdSample, fourthSample),
                        ),
                    ),
            )

        // when the segmented activity is encoded as TCX:
        val xml = encoder.encode(activity).content.toString(Charsets.UTF_8)

        // then each executed segment is represented by a named, timed lap:
        assertEquals(2, Regex("<Lap ").findAll(xml).count())
        assertEquals(2, Regex("<TotalTimeSeconds>2.000</TotalTimeSeconds>").findAll(xml).count())
        assertContains(xml, "<DistanceMeters>1.000</DistanceMeters>")
        assertContains(xml, "<DistanceMeters>1.500</DistanceMeters>")
        assertContains(xml, "<Notes>Warmup — target 150 W</Notes>")
        assertContains(xml, "<Notes>Intervals &amp; recovery — target 220 W</Notes>")
    }

    private fun sample(
        receivedAt: String,
        distanceMeters: Double,
        powerWatts: Int,
    ): TrainingTelemetrySample =
        TrainingTelemetrySample(
            receivedAt = Instant.parse(receivedAt),
            powerWatts = powerWatts,
            cadenceRpm = 90.0,
            speedKph = 25.0,
            distanceMeters = distanceMeters,
        )
}
