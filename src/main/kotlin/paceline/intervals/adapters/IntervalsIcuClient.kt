package paceline.intervals.adapters

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import paceline.intervals.config.IntervalsIcuProperties
import paceline.workout.ports.WorkoutProviderUnavailableException
import java.time.LocalDate

@Component
class IntervalsIcuClient(
    @Qualifier("intervalsIcuRestClient") private val restClient: RestClient,
    private val properties: IntervalsIcuProperties,
) {
    fun calendarEvents(date: LocalDate): List<IntervalsCalendarEventDto> =
        try {
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/api/v1/athlete/{athleteId}/events")
                        .queryParam("oldest", date)
                        .queryParam("newest", date)
                        .queryParam("category", "WORKOUT")
                        .queryParam("resolve", true)
                        .build(properties.athleteId)
                }.headers { headers -> headers.setBasicAuth("API_KEY", apiKey()) }
                .retrieve()
                .body(object : ParameterizedTypeReference<List<IntervalsCalendarEventDto>>() {})
                ?: emptyList()
        } catch (exception: RestClientException) {
            throw WorkoutProviderUnavailableException(
                "Intervals.icu calendar workouts could not be read",
                exception,
            )
        }

    fun completedActivities(date: LocalDate): List<IntervalsActivityDto> =
        try {
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/api/v1/athlete/{athleteId}/activities")
                        .queryParam("oldest", date)
                        .queryParam("newest", date)
                        .build(properties.athleteId)
                }.headers { headers -> headers.setBasicAuth("API_KEY", apiKey()) }
                .retrieve()
                .body(object : ParameterizedTypeReference<List<IntervalsActivityDto>>() {})
                ?: emptyList()
        } catch (exception: RestClientException) {
            throw WorkoutProviderUnavailableException(
                "Intervals.icu completed activities could not be read",
                exception,
            )
        }

    fun libraryWorkouts(): List<IntervalsLibraryWorkoutDto> =
        try {
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/api/v1/athlete/{athleteId}/workouts")
                        .build(properties.athleteId)
                }.headers { headers -> headers.setBasicAuth("API_KEY", apiKey()) }
                .retrieve()
                .body(object : ParameterizedTypeReference<List<IntervalsLibraryWorkoutDto>>() {})
                ?: emptyList()
        } catch (exception: RestClientException) {
            throw WorkoutProviderUnavailableException(
                "Intervals.icu workout library could not be read",
                exception,
            )
        }

    fun libraryWorkout(workoutId: String): IntervalsLibraryWorkoutDto? =
        try {
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/api/v1/athlete/{athleteId}/workouts/{workoutId}")
                        .build(properties.athleteId, workoutId)
                }.headers { headers -> headers.setBasicAuth("API_KEY", apiKey()) }
                .retrieve()
                .body(IntervalsLibraryWorkoutDto::class.java)
        } catch (exception: HttpClientErrorException.NotFound) {
            null
        } catch (exception: RestClientException) {
            throw WorkoutProviderUnavailableException(
                "Intervals.icu workout could not be read",
                exception,
            )
        }

    private fun apiKey(): String {
        val apiKey =
            properties.apiKey
                ?.takeIf { it.isNotBlank() }
                ?: throw WorkoutProviderUnavailableException(
                    "Intervals.icu API key is not configured",
                )

        return apiKey
    }
}
