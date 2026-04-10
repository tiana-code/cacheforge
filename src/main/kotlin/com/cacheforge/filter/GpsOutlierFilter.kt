package com.cacheforge.filter

import java.time.Instant

interface GpsOutlierFilter {

    fun isOutlier(
        currentLat: Double,
        currentLon: Double,
        currentTime: Instant,
        previousLat: Double,
        previousLon: Double,
        previousTime: Instant
    ): Boolean

    fun calculateSpeedKnots(
        currentLat: Double,
        currentLon: Double,
        currentTime: Instant,
        previousLat: Double,
        previousLon: Double,
        previousTime: Instant
    ): Double

    fun updateLastPosition(entityId: String, lat: Double, lon: Double, time: Instant)

    fun getLastPosition(entityId: String): LastPosition?

    data class LastPosition(val lat: Double, val lon: Double, val time: Instant)
}
