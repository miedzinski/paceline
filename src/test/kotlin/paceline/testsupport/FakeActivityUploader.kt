package paceline.testsupport

import paceline.training.domain.RecordedTrainingActivity
import paceline.training.ports.ActivityUploadException
import paceline.training.ports.ActivityUploadReceipt
import paceline.training.ports.ActivityUploader

class FakeActivityUploader(
    var failure: ActivityUploadException? = null,
) : ActivityUploader {
    val uploads = mutableListOf<RecordedTrainingActivity>()

    override fun upload(activity: RecordedTrainingActivity): ActivityUploadReceipt {
        failure?.let { throw it }
        uploads += activity
        return ActivityUploadReceipt(remoteActivityId = "activity-${uploads.size}")
    }
}
