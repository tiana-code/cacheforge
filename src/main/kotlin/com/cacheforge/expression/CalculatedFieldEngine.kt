package com.cacheforge.expression

import java.time.Instant
import java.util.UUID

interface CalculatedFieldEngine {

    suspend fun reloadFields(): ReloadResult

    fun processCalculatedFields(
        entityId: String,
        batchId: UUID,
        tagValues: Map<String, Double>,
        timestamp: Instant,
        isDemoData: Boolean = false,
        userId: String? = null
    ): List<ComputedFieldResult>

    fun testExpression(field: CalculatedFieldDefinition, sampleValues: Map<String, Double>): Double

    fun getActiveFieldCount(): Int

    fun getActiveFields(): List<CalculatedFieldDefinition>
}
