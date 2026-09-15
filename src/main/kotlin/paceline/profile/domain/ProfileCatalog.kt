package paceline.profile.domain

import org.springframework.stereotype.Component
import paceline.profile.ports.AthleteProfileSource

@Component
class ProfileCatalog(
    private val source: AthleteProfileSource,
) {
    fun profile(): AthleteProfile = source.profile()
}
