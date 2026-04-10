package com.cacheforge.filter

import com.cacheforge.config.CacheForgeProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

@Component
class GpsOutlierFilterImpl(
    private val redisTemplate: ReactiveRedisTemplate<String, String>?,
    @Qualifier("cacheforgeObjectMapper") private val objectMapper: ObjectMapper,
    private val properties: CacheForgeProperties
) : GpsOutlierFilter {

    companion object {
        private const val EARTH_RADIUS_KM = 6371.0
        private const val KNOTS_PER_KMH = 0.539957
    }

    private val lastPositions = Caffeine.newBuilder()
        .maximumSize(properties.filter.cacheMaxEntries)
        .expireAfterWrite(properties.filter.cacheTtl)
        .build<String, GpsOutlierFilter.LastPosition>()

    @PostConstruct
    fun init() {
        if (redisTemplate == null) {
            logger.info { "Redis not available - GPS filter running in memory-only mode" }
            return
        }
        try {
            val ops = redisTemplate.opsForHash<String, String>()
            ops.entries(properties.filter.redisKey)
                .collectList()
                .doOnNext { entries ->
                    entries.forEach { (entityId, json) ->
                        runCatching { lastPositions.put(entityId, parseLastPosition(json)) }
                    }
                    logger.info { "Loaded ${lastPositions.estimatedSize()} GPS filter positions from Redis" }
                    redisTemplate.expire(properties.filter.redisKey, properties.filter.redisKeyTtl)
                        .subscribe({}, { e ->
                            logger.warn {
                                "Failed to set TTL on GPS filter key: ${e.message}"
                            }
                        })
                }
                .doOnError { e ->
                    logger.warn { "Failed to load GPS filter positions from Redis: ${e.message}" }
                }
                .subscribe()
        } catch (e: Exception) {
            logger.warn { "Redis unavailable for GPS filter init: ${e.message}" }
        }
    }

    override fun isOutlier(
        currentLat: Double,
        currentLon: Double,
        currentTime: Instant,
        previousLat: Double,
        previousLon: Double,
        previousTime: Instant
    ): Boolean {
        val speedKnots = calculateSpeedKnots(
            currentLat, currentLon, currentTime, previousLat,
            previousLon, previousTime
        )
        return speedKnots > properties.filter.outlierSpeedThresholdKnots
    }

    override fun calculateSpeedKnots(
        currentLat: Double,
        currentLon: Double,
        currentTime: Instant,
        previousLat: Double,
        previousLon: Double,
        previousTime: Instant
    ): Double {
        val timeDeltaSeconds = currentTime.epochSecond - previousTime.epochSecond
        if (timeDeltaSeconds <= 0) return 0.0
        if (timeDeltaSeconds < properties.filter.minComparableIntervalSeconds) return 0.0
        val distanceKm = haversineDistanceKm(previousLat, previousLon, currentLat, currentLon)
        return (distanceKm / (timeDeltaSeconds / 3600.0)) * KNOTS_PER_KMH
    }

    override fun updateLastPosition(entityId: String, lat: Double, lon: Double, time: Instant) {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        lastPositions.put(entityId, GpsOutlierFilter.LastPosition(lat, lon, time))
        try {
            val json = objectMapper.writeValueAsString(LastPositionJson(lat, lon, time.toString()))
            val redisKey = properties.filter.redisKey
            redisTemplate?.opsForHash<String, String>()
                ?.put(redisKey, entityId, json)
                ?.subscribe(
                    {
                        redisTemplate.expire(redisKey, properties.filter.redisKeyTtl)
                            .subscribe({}, {})
                    },
                    {}
                )
        } catch (_: Exception) {
        }
    }

    override fun getLastPosition(entityId: String): GpsOutlierFilter.LastPosition? =
        lastPositions.getIfPresent(entityId)

    private data class LastPositionJson(
        @JsonProperty("lat") val lat: Double,
        @JsonProperty("lon") val lon: Double,
        @JsonProperty("time") val time: String,
    )

    private fun parseLastPosition(json: String): GpsOutlierFilter.LastPosition {
        val parsed = objectMapper.readValue<LastPositionJson>(json)
        return GpsOutlierFilter.LastPosition(parsed.lat, parsed.lon, Instant.parse(parsed.time))
    }

    // a = sin²(Δlat/2) + cos(lat1) × cos(lat2) × sin²(Δlon/2)
    // distance = 2R × arcsin(√a)
    private fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }
}
