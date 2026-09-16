package paceline.intervals.adapters

class IntervalsIcuException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
