package com.cacheforge.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "cacheforge")
data class CacheForgeProperties(
    val geo: GeoProperties = GeoProperties(),
    val alerts: AlertProperties = AlertProperties(),
    val filter: FilterProperties = FilterProperties()
) {
    data class GeoProperties(
        val geoKey: String = "cacheforge:entity:positions:geo",
        val metaKeyPrefix: String = "cacheforge:entity:positions:meta:",
        val positionTtl: Duration = Duration.ofHours(24)
    )

    data class AlertProperties(
        val stateKeyPrefix: String = "cacheforge:alert:state",
        val stateTtl: Duration = Duration.ofHours(24)
    )

    data class FilterProperties(
        val outlierSpeedThresholdKnots: Double = 50.0,
        val minComparableIntervalSeconds: Long = 2,
        val cacheMaxEntries: Long = 500,
        val cacheTtl: Duration = Duration.ofHours(1),
        val redisKey: String = "cacheforge:gps-filter:last-positions",
        val redisKeyTtl: Duration = Duration.ofDays(7)
    )
}
