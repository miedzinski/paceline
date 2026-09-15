package paceline.intervals.adapters

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsAthleteProfileDto(
    val athlete: IntervalsAthleteSummaryDto? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsAthleteSummaryDto(
    val id: String? = null,
    val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsSportSettingsDto(
    val types: List<String>? = null,
    val ftp: Int? = null,
    @JsonProperty("indoor_ftp") val indoorFtp: Int? = null,
    @JsonProperty("power_zones") val powerZones: List<Int>? = null,
    @JsonProperty("power_zone_names") val powerZoneNames: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsCalendarEventDto(
    val id: Long,
    @JsonProperty("start_date_local") val startDateLocal: String? = null,
    @JsonProperty("end_date_local") val endDateLocal: String? = null,
    val category: String? = null,
    val name: String? = null,
    val description: String? = null,
    val type: String? = null,
    val indoor: Boolean? = null,
    @JsonProperty("moving_time") val movingTimeSeconds: Int? = null,
    val distance: Double? = null,
    @JsonProperty("icu_training_load") val trainingLoad: Double? = null,
    val target: String? = null,
    @JsonProperty("workout_doc") val workoutDocument: IntervalsWorkoutDocumentDto? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsActivityDto(
    val id: String? = null,
    @JsonProperty("paired_event_id") val pairedEventId: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsActivityUploadDto(
    @JsonProperty("icu_athlete_id") val athleteId: String? = null,
    val id: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsLibraryWorkoutDto(
    val id: Long,
    val name: String? = null,
    val description: String? = null,
    val type: String? = null,
    val indoor: Boolean? = null,
    @JsonProperty("moving_time") val movingTimeSeconds: Int? = null,
    val distance: Double? = null,
    @JsonProperty("icu_training_load") val trainingLoad: Double? = null,
    @JsonProperty("icu_intensity") val intensity: Double? = null,
    val target: String? = null,
    val targets: List<String>? = null,
    @JsonProperty("folder_id") val folderId: Long? = null,
    @JsonProperty("workout_doc") val workoutDocument: IntervalsWorkoutDocumentDto? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsWorkoutDocumentDto(
    val description: String? = null,
    val duration: Int? = null,
    val distance: Double? = null,
    val ftp: Int? = null,
    val lthr: Int? = null,
    val target: String? = null,
    val steps: List<IntervalsWorkoutStepDto>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsWorkoutStepDto(
    val text: String? = null,
    val duration: Int? = null,
    val distance: Double? = null,
    val reps: Int? = null,
    val warmup: Boolean? = null,
    val cooldown: Boolean? = null,
    val intensity: String? = null,
    val ramp: Boolean? = null,
    @JsonProperty("until_lap_press") val untilLapPress: Boolean? = null,
    @JsonProperty("freeride") val freeRide: Boolean? = null,
    @JsonProperty("maxeffort") val maxEffort: Boolean? = null,
    @JsonProperty("hidepower") val hidePower: Boolean? = null,
    val steps: List<IntervalsWorkoutStepDto>? = null,
    val power: IntervalsWorkoutValueDto? = null,
    @JsonProperty("_power") val resolvedPower: IntervalsWorkoutValueDto? = null,
    val hr: IntervalsWorkoutValueDto? = null,
    @JsonProperty("_hr") val resolvedHeartRate: IntervalsWorkoutValueDto? = null,
    val pace: IntervalsWorkoutValueDto? = null,
    @JsonProperty("_pace") val resolvedPace: IntervalsWorkoutValueDto? = null,
    val cadence: IntervalsWorkoutValueDto? = null,
    @JsonProperty("_distance") val resolvedDistanceMeters: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalsWorkoutValueDto(
    val value: Double? = null,
    val start: Double? = null,
    val end: Double? = null,
    val units: String? = null,
    val target: String? = null,
)
