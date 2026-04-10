package com.cacheforge.state

sealed interface AlertStateLookup {
    data object Missing : AlertStateLookup
    data class Found(val state: AlertValueState) : AlertStateLookup
    data class Failed(val reason: String) : AlertStateLookup
}

sealed interface AlertStateUpdateResult {
    data object Updated : AlertStateUpdateResult
    data object StaleRejected : AlertStateUpdateResult
    data class Failed(val reason: String) : AlertStateUpdateResult
}
