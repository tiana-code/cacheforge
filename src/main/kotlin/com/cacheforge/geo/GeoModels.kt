package com.cacheforge.geo

import java.time.Instant

data class EntityPositionResult(
    val entityId: String,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double?,
    val heading: Double?,
    val speed: Double?,
    val source: String,
    val dataSource: String,
    val userId: String?,
    val timestamp: Instant
)

data class GeoStats(
    val totalEntities: Long
)
