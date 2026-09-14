package paceline.training.adapters

import org.springframework.stereotype.Component
import paceline.intervals.adapters.IntervalsIcuClient
import paceline.training.domain.RecordedTrainingActivity
import paceline.training.ports.ActivityFileEncoder
import paceline.training.ports.ActivityUploadReceipt
import paceline.training.ports.ActivityUploader
import paceline.workout.domain.WorkoutSourceType

@Component
class IntervalsIcuActivityUploader(
    private val client: IntervalsIcuClient,
    private val encoder: ActivityFileEncoder,
) : ActivityUploader {
    override fun upload(activity: RecordedTrainingActivity): ActivityUploadReceipt {
        val file = encoder.encode(activity)
        val response =
            client.uploadActivity(
                fileName = file.fileName,
                contentType = file.contentType,
                content = file.content,
                name = activity.name,
                description = "Recorded by Paceline",
                externalId = activity.sessionId.toString(),
                pairedEventId = activity.pairedEventId(),
            )
        return ActivityUploadReceipt(
            remoteActivityId = response?.id,
        )
    }

    private fun RecordedTrainingActivity.pairedEventId(): Long? =
        workoutSource
            ?.takeIf {
                workoutCompleted &&
                    workoutSourceType == WorkoutSourceType.SCHEDULED &&
                    it.provider.equals(INTERVALS_ICU_PROVIDER, ignoreCase = true)
            }?.id
            ?.toLongOrNull()

    private companion object {
        const val INTERVALS_ICU_PROVIDER = "intervals.icu"
    }
}
