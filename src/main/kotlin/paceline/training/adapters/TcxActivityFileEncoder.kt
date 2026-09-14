package paceline.training.adapters

import org.springframework.stereotype.Component
import paceline.training.domain.RecordedTrainingActivity
import paceline.training.domain.RecordedTrainingActivitySegment
import paceline.training.domain.TrainingTelemetrySample
import paceline.training.ports.ActivityFile
import paceline.training.ports.ActivityFileEncoder
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter
import kotlin.math.max
import kotlin.math.roundToInt

@Component
class TcxActivityFileEncoder : ActivityFileEncoder {
    override fun encode(activity: RecordedTrainingActivity): ActivityFile {
        val segments = activity.segments.ifEmpty { activity.defaultSegment() }
        val firstDistance = activity.samples.firstNotNullOfOrNull { it.distanceMeters }
        val output = StringWriter()
        val writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output)
        try {
            writer.writeStartDocument("UTF-8", "1.0")
            writer.writeStartElement("", ROOT_ELEMENT, TCX_NAMESPACE)
            writer.writeDefaultNamespace(TCX_NAMESPACE)
            writer.writeNamespace(EXTENSION_PREFIX, EXTENSION_NAMESPACE)
            writer.writeNamespace(XSI_PREFIX, XSI_NAMESPACE)
            writer.writeAttribute(
                XSI_PREFIX,
                XSI_NAMESPACE,
                SCHEMA_LOCATION_ATTRIBUTE,
                "$TCX_NAMESPACE $SCHEMA_URL",
            )

            writer.writeTcxStart("Activities")
            writer.writeTcxStart("Activity")
            writer.writeAttribute("Sport", "Biking")
            writer.writeTcxElement("Id", DateTimeFormatter.ISO_INSTANT.format(activity.startedAt))
            segments.forEach { segment ->
                writer.writeSegment(segment, firstDistance)
            }
            writer.writeEndElement()
            writer.writeEndElement()
            writer.writeEndElement()
            writer.writeEndDocument()
            writer.flush()
        } finally {
            writer.close()
        }

        return ActivityFile(
            fileName = "paceline-${activity.sessionId}.tcx",
            contentType = "application/xml",
            content = output.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun XMLStreamWriter.writeSegment(
        segment: RecordedTrainingActivitySegment,
        firstDistance: Double?,
    ) {
        val segmentDistance = segment.distanceMeters()
        val segmentTimeSeconds =
            max(0.0, Duration.between(segment.startedAt, segment.stoppedAt).toMillis() / 1_000.0)
        writeTcxStart("Lap")
        writeAttribute("StartTime", DateTimeFormatter.ISO_INSTANT.format(segment.startedAt))
        writeTcxElement("TotalTimeSeconds", format(segmentTimeSeconds))
        segmentDistance?.let { writeTcxElement("DistanceMeters", format(it)) }
        writeTcxElement("Intensity", "Active")
        writeTcxElement("TriggerMethod", "Manual")
        if (segment.samples.isNotEmpty()) {
            writeTcxStart("Track")
            segment.samples.forEach { sample ->
                writeTrackpoint(sample, firstDistance)
            }
            writeEndElement()
        }
        writeTcxElement("Notes", segment.notes())
        writeEndElement()
    }

    private fun XMLStreamWriter.writeTrackpoint(
        sample: TrainingTelemetrySample,
        firstDistance: Double?,
    ) {
        writeTcxStart("Trackpoint")
        writeTcxElement("Time", DateTimeFormatter.ISO_INSTANT.format(sample.receivedAt))
        sample.distanceMeters?.let { distance ->
            val relativeDistance = max(0.0, distance - (firstDistance ?: distance))
            writeTcxElement("DistanceMeters", format(relativeDistance))
        }
        sample.cadenceRpm?.takeIf(Double::isFinite)?.let { cadence ->
            writeTcxElement("Cadence", cadence.roundToInt().toString())
        }
        if (sample.speedKph?.isFinite() == true || sample.powerWatts != null) {
            writeTcxStart("Extensions")
            writeExtensionStart("TPX")
            sample.speedKph?.takeIf(Double::isFinite)?.let { speedKph ->
                writeExtensionElement("Speed", format(speedKph / 3.6))
            }
            sample.powerWatts?.let { powerWatts ->
                writeExtensionElement("Watts", powerWatts.toString())
            }
            writeEndElement()
            writeEndElement()
        }
        writeEndElement()
    }

    private fun XMLStreamWriter.writeTcxStart(localName: String) {
        writeStartElement("", localName, TCX_NAMESPACE)
    }

    private fun XMLStreamWriter.writeTcxElement(
        localName: String,
        value: String,
    ) {
        writeTcxStart(localName)
        writeCharacters(value)
        writeEndElement()
    }

    private fun XMLStreamWriter.writeExtensionStart(localName: String) {
        writeStartElement(EXTENSION_PREFIX, localName, EXTENSION_NAMESPACE)
    }

    private fun XMLStreamWriter.writeExtensionElement(
        localName: String,
        value: String,
    ) {
        writeExtensionStart(localName)
        writeCharacters(value)
        writeEndElement()
    }

    private fun RecordedTrainingActivity.defaultSegment(): List<RecordedTrainingActivitySegment> =
        listOf(
            RecordedTrainingActivitySegment(
                name = name,
                targetPowerWatts = null,
                startedAt = startedAt,
                stoppedAt = stoppedAt,
                samples = samples,
            ),
        )

    private fun RecordedTrainingActivitySegment.distanceMeters(): Double? {
        val firstDistance = samples.firstNotNullOfOrNull { it.distanceMeters }
        val lastDistance = samples.asReversed().firstOrNull { it.distanceMeters != null }?.distanceMeters
        return if (firstDistance != null && lastDistance != null) {
            max(0.0, lastDistance - firstDistance)
        } else {
            null
        }
    }

    private fun RecordedTrainingActivitySegment.notes(): String = targetPowerWatts?.let { "$name — target $it W" } ?: name

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private companion object {
        const val ROOT_ELEMENT = "TrainingCenterDatabase"
        const val TCX_NAMESPACE = "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
        const val EXTENSION_PREFIX = "ns3"
        const val EXTENSION_NAMESPACE = "http://www.garmin.com/xmlschemas/ActivityExtension/v2"
        const val XSI_PREFIX = "xsi"
        const val XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"
        const val SCHEMA_LOCATION_ATTRIBUTE = "schemaLocation"
        const val SCHEMA_URL = "http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd"
    }
}
