package com.cacheforge.expression

import java.time.Instant
import java.util.UUID

data class CalculatedFieldDefinition(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val expression: String,
    val inputTags: List<String>,
    val outputTag: String,
    val unit: String? = null,
    val windowMinutes: Int? = null,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(name.isNotBlank()) { "Field name must not be blank" }
        require(expression.isNotBlank()) { "Expression must not be blank" }
        require(outputTag.isNotBlank()) { "Output tag must not be blank" }
        require(inputTags.isNotEmpty()) { "Input tags must not be empty" }
        require(inputTags.all { it.isNotBlank() }) { "Input tags must not contain blank entries" }
        require(windowMinutes == null || windowMinutes > 0) { "Window minutes must be positive if set" }
    }
}

data class ComputedFieldResult(
    val fieldId: UUID,
    val fieldName: String,
    val entityId: String,
    val tagCode: String,
    val value: Double,
    val unit: String?,
    val timestamp: Instant,
    val batchId: UUID,
    val isDemoData: Boolean,
    val dataSource: DataSourceType,
    val userId: String?
)
