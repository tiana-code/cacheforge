package com.cacheforge.expression

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.SimpleEvaluationContext
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class CalculatedFieldEngineImpl(
    private val fieldRepository: CalculatedFieldRepository
) : CalculatedFieldEngine {

    private val expressionParser = SpelExpressionParser()

    private data class FieldSnapshot(
        val activeFields: Map<UUID, CalculatedFieldDefinition>,
        val tagToFields: Map<String, Set<UUID>>,
        val expressions: Map<String, Expression>
    )

    @Volatile
    private var snapshot: FieldSnapshot = FieldSnapshot(emptyMap(), emptyMap(), emptyMap())

    @PostConstruct
    fun loadFields() {
        val fields = fieldRepository.findAllActive()
        val (built, invalid) = buildSnapshot(fields)
        snapshot = built
        if (invalid.isNotEmpty()) {
            logger.warn { "Skipped ${invalid.size} fields with invalid expressions: $invalid" }
        }
        logger.info { "Loaded ${built.activeFields.size} active calculated fields" }
    }

    override suspend fun reloadFields(): ReloadResult {
        val fields = fieldRepository.findAllActive()
        val (built, invalid) = buildSnapshot(fields)
        snapshot = built
        logger.info { "Reloaded ${built.activeFields.size} active calculated fields" }
        return ReloadResult(
            loadedCount = built.activeFields.size,
            skippedCount = invalid.size,
            invalidExpressions = invalid,
            reloadedAt = Instant.now()
        )
    }

    override fun processCalculatedFields(
        entityId: String,
        batchId: UUID,
        tagValues: Map<String, Double>,
        timestamp: Instant,
        isDemoData: Boolean,
        userId: String?
    ): List<ComputedFieldResult> {
        val current = snapshot

        if (current.activeFields.isEmpty()) return emptyList()

        val incomingTags = tagValues.keys
        val candidateFieldIds = mutableSetOf<UUID>()

        for (tag in incomingTags) {
            current.tagToFields[tag]?.let { candidateFieldIds.addAll(it) }
        }

        if (candidateFieldIds.isEmpty()) return emptyList()

        val results = mutableListOf<ComputedFieldResult>()

        for (fieldId in candidateFieldIds) {
            val field = current.activeFields[fieldId] ?: continue

            if (field.windowMinutes != null) continue

            val allInputsPresent = field.inputTags.all { tag -> tagValues.containsKey(tag) }
            if (!allInputsPresent) continue

            try {
                val result = evaluateExpression(current, field, tagValues)
                if (result != null && result.isFinite()) {
                    results.add(
                        ComputedFieldResult(
                            fieldId = field.id,
                            fieldName = field.name,
                            entityId = entityId,
                            tagCode = field.outputTag,
                            value = result,
                            unit = field.unit,
                            timestamp = timestamp,
                            batchId = batchId,
                            isDemoData = isDemoData,
                            dataSource = DataSourceType.CALCULATED,
                            userId = userId
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn { "Failed to evaluate field '${field.name}' for entity $entityId: ${e.message}" }
            }
        }

        return results
    }

    override fun testExpression(field: CalculatedFieldDefinition, sampleValues: Map<String, Double>): Double {
        val missingTags = field.inputTags.filter { !sampleValues.containsKey(it) }
        if (missingTags.isNotEmpty()) {
            throw IllegalArgumentException("Missing required input tags: $missingTags")
        }

        val result = evaluateExpression(snapshot, field, sampleValues)
            ?: throw RuntimeException("Expression returned null for field '${field.name}'")

        if (!result.isFinite()) {
            throw RuntimeException("Expression returned non-finite value ($result) for field '${field.name}'")
        }

        return result
    }

    override fun getActiveFieldCount(): Int = snapshot.activeFields.size

    override fun getActiveFields(): List<CalculatedFieldDefinition> = snapshot.activeFields.values.toList()

    private fun evaluateExpression(
        current: FieldSnapshot,
        field: CalculatedFieldDefinition,
        tagValues: Map<String, Double>
    ): Double? {
        val context = SimpleEvaluationContext.forReadOnlyDataBinding().build()

        for ((tag, value) in tagValues) {
            val variableName = tag.replace('.', '_')
            context.setVariable(variableName, value)
        }

        val expression = current.expressions[field.expression]
            ?: expressionParser.parseExpression(field.expression)
        return expression.getValue(context, Double::class.java)
    }

    private fun buildSnapshot(fields: List<CalculatedFieldDefinition>): Pair<FieldSnapshot, List<String>> {
        val newActiveFields = mutableMapOf<UUID, CalculatedFieldDefinition>()
        val newTagToFields = mutableMapOf<String, MutableSet<UUID>>()
        val newExpressions = mutableMapOf<String, Expression>()
        val invalidExpressions = mutableListOf<String>()

        for (field in fields) {
            val dummyValues = field.inputTags.associateWith { 1.0 }
            val parsed = try {
                expressionParser.parseExpression(field.expression)
            } catch (_: Exception) {
                invalidExpressions.add(field.name)
                continue
            }
            try {
                val context = SimpleEvaluationContext.forReadOnlyDataBinding().build()
                for ((tag, value) in dummyValues) {
                    context.setVariable(tag.replace('.', '_'), value)
                }
                parsed.getValue(context, Double::class.java)
            } catch (_: Exception) {
                invalidExpressions.add(field.name)
                continue
            }
            newActiveFields[field.id] = field
            for (tag in field.inputTags) {
                newTagToFields.getOrPut(tag) { mutableSetOf() }.add(field.id)
            }
            newExpressions[field.expression] = parsed
        }

        val snapshot = FieldSnapshot(
            activeFields = newActiveFields.toMap(),
            tagToFields = newTagToFields.mapValues { (_, v) -> v.toSet() },
            expressions = newExpressions.toMap()
        )
        return Pair(snapshot, invalidExpressions)
    }
}
