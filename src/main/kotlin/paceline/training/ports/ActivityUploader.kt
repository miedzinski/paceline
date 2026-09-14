package paceline.training.ports

import paceline.training.domain.RecordedTrainingActivity

data class ActivityUploadReceipt(
    val remoteActivityId: String? = null,
)

fun interface ActivityUploader {
    fun upload(activity: RecordedTrainingActivity): ActivityUploadReceipt
}

class ActivityUploadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
