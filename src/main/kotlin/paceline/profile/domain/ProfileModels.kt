package paceline.profile.domain

data class AthleteProfile(
    val athleteId: String?,
    val name: String?,
    val ftpWatts: Int?,
    val indoorFtpWatts: Int?,
    val powerZones: List<PowerZone>,
)

data class PowerZone(
    val number: Int,
    val name: String,
    val minPercent: Int,
    val maxPercent: Int?,
    val minWatts: Int,
    val maxWatts: Int?,
)
