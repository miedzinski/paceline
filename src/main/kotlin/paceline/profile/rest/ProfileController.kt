package paceline.profile.rest

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import paceline.profile.domain.AthleteProfile
import paceline.profile.domain.PowerZone
import paceline.profile.domain.ProfileCatalog
import paceline.profile.ports.ProfileProviderUnavailableException

@RestController
@RequestMapping("/profile")
class ProfileController(
    private val catalog: ProfileCatalog,
) {
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun profile(): AthleteProfileResponse =
        try {
            catalog.profile().toResponse()
        } catch (exception: ProfileProviderUnavailableException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.message, exception)
        }
}

data class AthleteProfileResponse(
    val athleteId: String?,
    val name: String?,
    val ftpWatts: Int?,
    val indoorFtpWatts: Int?,
    val powerZones: List<PowerZoneResponse>,
)

data class PowerZoneResponse(
    val number: Int,
    val name: String,
    val minPercent: Int,
    val maxPercent: Int?,
    val minWatts: Int,
    val maxWatts: Int?,
)

private fun AthleteProfile.toResponse(): AthleteProfileResponse =
    AthleteProfileResponse(
        athleteId = athleteId,
        name = name,
        ftpWatts = ftpWatts,
        indoorFtpWatts = indoorFtpWatts,
        powerZones = powerZones.map(PowerZone::toResponse),
    )

private fun PowerZone.toResponse(): PowerZoneResponse =
    PowerZoneResponse(
        number = number,
        name = name,
        minPercent = minPercent,
        maxPercent = maxPercent,
        minWatts = minWatts,
        maxWatts = maxWatts,
    )
