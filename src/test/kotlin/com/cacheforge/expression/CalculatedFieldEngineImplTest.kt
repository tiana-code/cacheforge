package com.cacheforge.expression

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class CalculatedFieldEngineImplTest {

    private lateinit var repository: CalculatedFieldRepository
    private lateinit var engine: CalculatedFieldEngineImpl

    private val fuelEfficiencyField = CalculatedFieldDefinition(
        id = UUID.randomUUID(),
        name = "fuel_efficiency",
        expression = "#fuel_consumption / #speed_knots",
        inputTags = listOf("fuel.consumption", "speed.knots"),
        outputTag = "fuel.efficiency",
        unit = "kg/kn",
        isActive = true
    )

    @BeforeEach
    fun setUp() {
        repository = mockk()
        every { repository.findAllActive() } returns listOf(fuelEfficiencyField)

        engine = CalculatedFieldEngineImpl(repository)
        engine.loadFields()
    }

    @Test
    fun `processCalculatedFields computes derived value when all inputs present`() {
        val results = engine.processCalculatedFields(
            entityId = "entity-001",
            batchId = UUID.randomUUID(),
            tagValues = mapOf("fuel.consumption" to 1200.0, "speed.knots" to 12.0),
            timestamp = Instant.now()
        )

        assertEquals(1, results.size)
        assertEquals("fuel.efficiency", results[0].tagCode)
        assertEquals(100.0, results[0].value, 0.001)
        assertEquals(DataSourceType.CALCULATED, results[0].dataSource)
        assertEquals(fuelEfficiencyField.id, results[0].fieldId)
        assertEquals("fuel_efficiency", results[0].fieldName)
    }

    @Test
    fun `processCalculatedFields skips field when input tags are missing`() {
        val results = engine.processCalculatedFields(
            entityId = "entity-001",
            batchId = UUID.randomUUID(),
            tagValues = mapOf("fuel.consumption" to 1200.0),
            timestamp = Instant.now()
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `processCalculatedFields returns empty when no incoming tags match any field`() {
        val results = engine.processCalculatedFields(
            entityId = "entity-001",
            batchId = UUID.randomUUID(),
            tagValues = mapOf("engine.rpm" to 500.0),
            timestamp = Instant.now()
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `testExpression evaluates SpEL expression with sample values`() {
        val result = engine.testExpression(
            field = fuelEfficiencyField,
            sampleValues = mapOf("fuel.consumption" to 600.0, "speed.knots" to 10.0)
        )

        assertEquals(60.0, result, 0.001)
    }

    @Test
    fun `testExpression throws when required input tags are missing`() {
        assertThrows<IllegalArgumentException> {
            engine.testExpression(
                field = fuelEfficiencyField,
                sampleValues = mapOf("fuel.consumption" to 600.0)
            )
        }
    }

    @Test
    fun `getActiveFieldCount returns correct count after load`() {
        assertEquals(1, engine.getActiveFieldCount())
    }

    @Test
    fun `getActiveFields returns all loaded fields`() {
        val fields = engine.getActiveFields()
        assertEquals(1, fields.size)
        assertEquals("fuel_efficiency", fields[0].name)
    }

    @Test
    fun `processCalculatedFields skips windowed fields`() {
        val windowedField = fuelEfficiencyField.copy(
            id = UUID.randomUUID(),
            name = "windowed_avg",
            windowMinutes = 30
        )
        every { repository.findAllActive() } returns listOf(windowedField)
        val freshEngine = CalculatedFieldEngineImpl(repository)
        freshEngine.loadFields()

        val results = freshEngine.processCalculatedFields(
            entityId = "entity-001",
            batchId = UUID.randomUUID(),
            tagValues = mapOf("fuel.consumption" to 1200.0, "speed.knots" to 12.0),
            timestamp = Instant.now()
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `processCalculatedFields carries userId and isDemoData onto result`() {
        val results = engine.processCalculatedFields(
            entityId = "entity-001",
            batchId = UUID.randomUUID(),
            tagValues = mapOf("fuel.consumption" to 1200.0, "speed.knots" to 12.0),
            timestamp = Instant.now(),
            isDemoData = true,
            userId = "user-abc"
        )

        assertEquals(1, results.size)
        assertTrue(results[0].isDemoData)
        assertEquals("user-abc", results[0].userId)
        assertEquals(DataSourceType.CALCULATED, results[0].dataSource)
    }

    @Test
    fun `reloadFields returns ReloadResult with correct counts`() = runTest {
        val newField = CalculatedFieldDefinition(
            id = UUID.randomUUID(),
            name = "power_output",
            expression = "#voltage * #current",
            inputTags = listOf("voltage", "current"),
            outputTag = "power.output",
            unit = "W",
            isActive = true
        )
        every { repository.findAllActive() } returns listOf(newField)

        val reloadResult = engine.reloadFields()

        assertEquals(1, reloadResult.loadedCount)
        assertEquals(0, reloadResult.skippedCount)
        assertTrue(reloadResult.invalidExpressions.isEmpty())
        assertTrue(reloadResult.reloadedAt.isBefore(Instant.now().plusSeconds(1)))
    }

    @Test
    fun `reloadFields skips invalid expression and reports in ReloadResult`() = runTest {
        val invalidField = CalculatedFieldDefinition(
            id = UUID.randomUUID(),
            name = "bad_expr",
            expression = "#undefined_tag ??? #other",
            inputTags = listOf("undefined_tag", "other"),
            outputTag = "bad.output",
            isActive = true
        )
        every { repository.findAllActive() } returns listOf(invalidField)

        val reloadResult = engine.reloadFields()

        assertEquals(0, reloadResult.loadedCount)
        assertEquals(1, reloadResult.skippedCount)
        assertTrue(reloadResult.invalidExpressions.contains("bad_expr"))
    }

    @Test
    fun `reload replaces field set atomically`() = runTest {
        val newField = CalculatedFieldDefinition(
            id = UUID.randomUUID(),
            name = "power_output",
            expression = "#voltage * #current",
            inputTags = listOf("voltage", "current"),
            outputTag = "power.output",
            unit = "W",
            isActive = true
        )
        every { repository.findAllActive() } returns listOf(newField)

        engine.reloadFields()

        assertEquals(1, engine.getActiveFieldCount())
        assertEquals("power_output", engine.getActiveFields()[0].name)

        val oldResults = engine.processCalculatedFields(
            entityId = "entity-001",
            batchId = UUID.randomUUID(),
            tagValues = mapOf("fuel.consumption" to 1200.0, "speed.knots" to 12.0),
            timestamp = Instant.now()
        )
        assertTrue(oldResults.isEmpty())

        val newResults = engine.processCalculatedFields(
            entityId = "entity-001",
            batchId = UUID.randomUUID(),
            tagValues = mapOf("voltage" to 220.0, "current" to 5.0),
            timestamp = Instant.now()
        )
        assertEquals(1, newResults.size)
        assertEquals(1100.0, newResults[0].value, 0.001)
    }

    @Test
    fun `expression cache reuses parsed expressions across evaluations`() {
        val results1 = engine.processCalculatedFields(
            entityId = "entity-001",
            batchId = UUID.randomUUID(),
            tagValues = mapOf("fuel.consumption" to 1200.0, "speed.knots" to 12.0),
            timestamp = Instant.now()
        )
        val results2 = engine.processCalculatedFields(
            entityId = "entity-002",
            batchId = UUID.randomUUID(),
            tagValues = mapOf("fuel.consumption" to 600.0, "speed.knots" to 10.0),
            timestamp = Instant.now()
        )

        assertEquals(100.0, results1[0].value, 0.001)
        assertEquals(60.0, results2[0].value, 0.001)
    }

    @Test
    fun `loadFields degrades gracefully on repository failure`() {
        every { repository.findAllActive() } throws RuntimeException("DB connection failed")

        val freshEngine = CalculatedFieldEngineImpl(repository)
        freshEngine.loadFields()

        assertEquals(0, freshEngine.getActiveFieldCount())
    }
}
