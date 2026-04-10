package com.cacheforge.geo

sealed interface GeoUpdateResult {
    data object Updated : GeoUpdateResult
    data object StaleRejected : GeoUpdateResult
    data class InvalidCoordinates(val lat: Double, val lon: Double) : GeoUpdateResult
    data class Failed(val reason: String) : GeoUpdateResult
}
