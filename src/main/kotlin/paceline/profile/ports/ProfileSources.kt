package paceline.profile.ports

import paceline.profile.domain.AthleteProfile

interface AthleteProfileSource {
    fun profile(): AthleteProfile
}

class ProfileProviderUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
