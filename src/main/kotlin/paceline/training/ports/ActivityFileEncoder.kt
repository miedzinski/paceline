package paceline.training.ports

import paceline.training.domain.RecordedTrainingActivity

data class ActivityFile(
    val fileName: String,
    val contentType: String,
    val content: ByteArray,
)

fun interface ActivityFileEncoder {
    fun encode(activity: RecordedTrainingActivity): ActivityFile
}
