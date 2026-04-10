package com.cacheforge.expression

import java.time.Instant

data class ReloadResult(
    val loadedCount: Int,
    val skippedCount: Int,
    val invalidExpressions: List<String>,
    val reloadedAt: Instant
)
