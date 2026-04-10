package com.cacheforge.geo

import kotlinx.coroutines.flow.Flow

interface GeoQueryService {

    fun findInViewport(
        centerLon: Double,
        centerLat: Double,
        widthKm: Double,
        heightKm: Double,
        count: Int = 500,
        sources: String? = null
    ): Flow<EntityPositionResult>

    fun findNearby(
        lon: Double,
        lat: Double,
        radiusKm: Double,
        count: Int = 100,
        sources: String? = null
    ): Flow<EntityPositionResult>

    fun findNearEntity(
        entityId: String,
        radiusKm: Double,
        count: Int = 100,
        sources: String? = null
    ): Flow<EntityPositionResult>

    suspend fun getGeoStats(): GeoStats
}
