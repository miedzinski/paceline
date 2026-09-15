package paceline.profile.adapters

import org.springframework.stereotype.Component
import paceline.intervals.adapters.IntervalsAthleteSummaryDto
import paceline.intervals.adapters.IntervalsIcuClient
import paceline.intervals.adapters.IntervalsSportSettingsDto
import paceline.profile.domain.AthleteProfile
import paceline.profile.domain.PowerZoneCalculator
import paceline.profile.ports.AthleteProfileSource
import paceline.profile.ports.ProfileProviderUnavailableException
import paceline.workout.ports.WorkoutProviderUnavailableException

@Component
class IntervalsIcuProfileSource(
    private val client: IntervalsIcuClient,
) : AthleteProfileSource {
    override fun profile(): AthleteProfile =
        try {
            val athlete = client.athleteProfile().athlete
            val cyclingSettings = client.sportSettings(CYCLING_SPORT)
            athlete.toProfile(cyclingSettings)
        } catch (exception: WorkoutProviderUnavailableException) {
            throw ProfileProviderUnavailableException(
                "Intervals.icu athlete profile could not be read",
                exception,
            )
        }

    private fun IntervalsAthleteSummaryDto?.toProfile(cyclingSettings: IntervalsSportSettingsDto): AthleteProfile {
        val ftpWatts = cyclingSettings.ftp
        val indoorFtpWatts = cyclingSettings.indoorFtp
        val zoneReferenceFtpWatts = indoorFtpWatts ?: ftpWatts

        return AthleteProfile(
            athleteId = this?.id,
            name = this?.displayName(),
            ftpWatts = ftpWatts,
            indoorFtpWatts = indoorFtpWatts,
            powerZones =
                PowerZoneCalculator.calculate(
                    ftpWatts = zoneReferenceFtpWatts,
                    upperBoundsPercent = cyclingSettings.powerZones,
                    names = cyclingSettings.powerZoneNames,
                ),
        )
    }

    private fun IntervalsAthleteSummaryDto.displayName(): String? = name?.takeIf(String::isNotBlank)

    private companion object {
        const val CYCLING_SPORT = "Ride"
    }
}
