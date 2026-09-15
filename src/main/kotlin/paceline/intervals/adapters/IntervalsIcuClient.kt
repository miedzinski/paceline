package paceline.intervals.adapters

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import paceline.intervals.config.IntervalsIcuProperties
import paceline.training.ports.ActivityUploadException
import paceline.workout.ports.WorkoutProviderUnavailableException
import java.time.LocalDate

@Component
class IntervalsIcuClient(
    @Qualifier("intervalsIcuRestClient") private val restClient: RestClient,
    private val properties: IntervalsIcuProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun athleteProfile(): IntervalsAthleteProfileDto =
        try {
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/api/v1/athlete/{athleteId}/profile")
                        .build(properties.athleteId)
                }.headers { headers -> headers.setBasicAuth("API_KEY", apiKey()) }
                .retrieve()
                .body(IntervalsAthleteProfileDto::class.java)
                ?: throw WorkoutProviderUnavailableException(
                    "Intervals.icu athlete profile response was empty",
                )
        } catch (exception: RestClientException) {
            throw WorkoutProviderUnavailableException(
                "Intervals.icu athlete profile could not be read",
                exception,
            )
        }

    fun sportSettings(sportType: String): IntervalsSportSettingsDto =
        try {
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/api/v1/athlete/{athleteId}/sport-settings/{sportType}")
                        .build(properties.athleteId, sportType)
                }.headers { headers -> headers.setBasicAuth("API_KEY", apiKey()) }
                .retrieve()
                .body(IntervalsSportSettingsDto::class.java)
                ?: throw WorkoutProviderUnavailableException(
                    "Intervals.icu sport settings response was empty",
                )
        } catch (exception: RestClientException) {
            throw WorkoutProviderUnavailableException(
                "Intervals.icu sport settings could not be read",
                exception,
            )
        }

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

    fun uploadActivity(
        fileName: String,
        contentType: String,
        content: ByteArray,
        name: String,
        description: String,
        externalId: String,
        pairedEventId: Long? = null,
    ): IntervalsActivityUploadDto? {
        logger.info(
            "Uploading activity to Intervals.icu: externalId={}, pairedEventId={}, fileName={}, contentBytes={}",
            externalId,
            pairedEventId,
            fileName,
            content.size,
        )

        return try {
            val multipart =
                MultipartBodyBuilder()
                    .apply {
                        part(
                            "file",
                            object : ByteArrayResource(content) {
                                override fun getFilename(): String = fileName
                            },
                        ).contentType(MediaType.parseMediaType(contentType))
                    }.build()
            val response =
                restClient
                    .post()
                    .uri { builder ->
                        builder
                            .path("/api/v1/athlete/{athleteId}/activities")
                            .queryParam("name", name)
                            .queryParam("description", description)
                            .queryParam("external_id", externalId)
                            .apply {
                                pairedEventId?.let { queryParam("paired_event_id", it) }
                            }.build(properties.athleteId)
                    }.headers { headers -> headers.setBasicAuth("API_KEY", apiKey()) }
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart)
                    .retrieve()
                    .body(IntervalsActivityUploadDto::class.java)
            logger.info(
                "Intervals.icu activity upload completed: externalId={}, remoteActivityId={}",
                externalId,
                response?.id,
            )
            response
        } catch (exception: RestClientException) {
            logUploadFailure(exception, fileName, externalId)
            throw ActivityUploadException(
                "Intervals.icu activity upload failed",
                exception,
            )
        } catch (exception: WorkoutProviderUnavailableException) {
            logger.warn(
                "Intervals.icu activity upload could not start: externalId={}, fileName={}, reason={}",
                externalId,
                fileName,
                exception.message,
            )
            throw ActivityUploadException(
                exception.message ?: "Intervals.icu API key is not configured",
                exception,
            )
        } catch (exception: IllegalArgumentException) {
            logger.warn(
                "Intervals.icu activity upload could not be prepared: externalId={}, fileName={}, reason={}",
                externalId,
                fileName,
                exception.message,
                exception,
            )
            throw ActivityUploadException(
                "Intervals.icu activity upload could not be prepared",
                exception,
            )
        }
    }

    private fun logUploadFailure(
        exception: RestClientException,
        fileName: String,
        externalId: String,
    ) {
        if (exception is RestClientResponseException) {
            logger.warn(
                "Intervals.icu activity upload failed: externalId={}, fileName={}, status={}, responseBody={}",
                externalId,
                fileName,
                exception.statusCode.value(),
                exception.responseBodyAsString.take(MAX_LOGGED_RESPONSE_BODY_LENGTH),
                exception,
            )
        } else {
            logger.warn(
                "Intervals.icu activity upload failed: externalId={}, fileName={}, reason={}",
                externalId,
                fileName,
                exception.message,
                exception,
            )
        }
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

    private companion object {
        const val MAX_LOGGED_RESPONSE_BODY_LENGTH = 500
    }
}
