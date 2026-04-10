package com.cacheforge.state

import java.time.Instant

data class AlertValueState(
    val value: Double,
    val timestamp: Instant,
    val lastSeenTime: Instant
)

interface AlertStateCache {
    suspend fun getState(entityId: String, tagCode: String): AlertStateLookup
    suspend fun updateIfNewer(entityId: String, tagCode: String, state: AlertValueState): AlertStateUpdateResult
}
