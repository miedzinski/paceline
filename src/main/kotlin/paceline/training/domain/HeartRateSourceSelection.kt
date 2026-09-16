package paceline.training.domain

import paceline.device.domain.ConnectionPhase
import paceline.device.domain.HeartRateSourceDescriptor
import paceline.training.ports.TrainingDevice

class HeartRateSourceSelection(
    private val trainingDevice: TrainingDevice,
) {
    fun resolve(sourceId: String?): String? {
        val sources = connectedSources()
        if (sourceId != null) {
            requireConnected(sourceId, sources)
            return sourceId
        }
        if (sources.size > 1) {
            throw HeartRateSourceSelectionRequiredException()
        }
        return sources.singleOrNull()?.id
    }

    fun requireConnected(sourceId: String) {
        requireConnected(sourceId, connectedSources())
    }

    private fun connectedSources(): List<HeartRateSourceDescriptor> =
        trainingDevice
            .heartRateSources()
            .filter { source -> source.state == ConnectionPhase.CONNECTED }

    private fun requireConnected(
        sourceId: String,
        sources: List<HeartRateSourceDescriptor>,
    ) {
        if (sources.none { source -> source.id == sourceId }) {
            throw HeartRateSourceNotFoundException(sourceId)
        }
    }
}
