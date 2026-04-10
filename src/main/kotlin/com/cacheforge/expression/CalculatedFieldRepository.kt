package com.cacheforge.expression

interface CalculatedFieldRepository {
    fun findAllActive(): List<CalculatedFieldDefinition>
}
