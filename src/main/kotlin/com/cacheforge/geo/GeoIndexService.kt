package com.cacheforge.geo

import java.time.Instant

interface GeoIndexService {

    suspend fun updatePosition(
        entityId: String,
        lat: Double,
        lon: Double,
        heading: Double?,
        speed: Double?,
        timestamp: Instant,
        source: String?,
        userId: String?,
        dataSource: String? = null
    ): GeoUpdateResult
}
