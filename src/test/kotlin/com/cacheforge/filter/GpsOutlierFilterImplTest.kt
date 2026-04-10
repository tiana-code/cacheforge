package com.cacheforge.filter

import com.cacheforge.config.CacheForgeProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveHashOperations
import org.springframework.data.redis.core.ReactiveRedisTemplate
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

class GpsOutlierFilterImplTest {

    private lateinit var redisTemplate: ReactiveRedisTemplate<String, String>
    private lateinit var hashOps: ReactiveHashOperations<String, String, String>
    private lateinit var filter: GpsOutlierFilterImpl
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val properties = CacheForgeProperties()

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk()
        hashOps = mockk()

        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        every { hashOps.entries(any()) } returns Flux.empty()
        every { redisTemplate.expire(any(), any()) } returns Mono.just(true)

        filter = GpsOutlierFilterImpl(redisTemplate, objectMapper, properties)
        filter.init()
    }

    @Test
    fun `normal movement is not an outlier`() {
        val previousTime = Instant.parse("2026-01-01T10:00:00Z")
        val currentTime = Instant.parse("2026-01-01T10:05:00Z")

        val isOutlier = filter.isOutlier(
            currentLat = 60.17,
            currentLon = 24.95,
            currentTime = currentTime,
            previousLat = 60.12,
            previousLon = 24.95,
            previousTime = previousTime
        )

        assertFalse(isOutlier)
    }

    @Test
    fun `teleporting GPS jump is detected as outlier`() {
        val previousTime = Instant.parse("2026-01-01T10:00:00Z")
        val currentTime = Instant.parse("2026-01-01T10:01:00Z")

        val isOutlier = filter.isOutlier(
            currentLat = 51.5,
            currentLon = -0.12,
            currentTime = currentTime,
            previousLat = 60.17,
            previousLon = 24.95,
            previousTime = previousTime
        )

        assertTrue(isOutlier)
    }

    @Test
    fun `zero time delta is not flagged as outlier`() {
        val sameTime = Instant.parse("2026-01-01T10:00:00Z")

        val isOutlier = filter.isOutlier(
            currentLat = 51.5,
            currentLon = -0.12,
            currentTime = sameTime,
            previousLat = 60.17,
            previousLon = 24.95,
            previousTime = sameTime
        )

        assertFalse(isOutlier)
    }

    @Test
    fun `1 second interval returns zero speed due to min comparable interval`() {
        val previousTime = Instant.parse("2026-01-01T10:00:00Z")
        val currentTime = previousTime.plusSeconds(1)

        val speed = filter.calculateSpeedKnots(
            currentLat = 60.17,
            currentLon = 24.95,
            currentTime = currentTime,
            previousLat = 60.12,
            previousLon = 24.95,
            previousTime = previousTime
        )

        assertEquals(0.0, speed, 0.001)
    }

    @Test
    fun `speed exactly at threshold boundary is not outlier`() {
        val previousTime = Instant.parse("2026-01-01T10:00:00Z")
        val currentTime = Instant.parse("2026-01-01T11:00:00Z")

        val isOutlier = filter.isOutlier(
            currentLat = 61.0,
            currentLon = 24.95,
            currentTime = currentTime,
            previousLat = 60.17,
            previousLon = 24.95,
            previousTime = previousTime
        )

        assertFalse(isOutlier)
    }

    @Test
    fun `calculated speed returns zero for same position`() {
        val previousTime = Instant.parse("2026-01-01T10:00:00Z")
        val currentTime = Instant.parse("2026-01-01T11:00:00Z")

        val speed = filter.calculateSpeedKnots(
            currentLat = 60.0,
            currentLon = 25.0,
            currentTime = currentTime,
            previousLat = 60.0,
            previousLon = 25.0,
            previousTime = previousTime
        )

        assertEquals(0.0, speed, 0.001)
    }

    @Test
    fun `calculated speed for zero time delta returns zero`() {
        val sameTime = Instant.parse("2026-01-01T10:00:00Z")

        val speed = filter.calculateSpeedKnots(
            currentLat = 51.5,
            currentLon = -0.12,
            currentTime = sameTime,
            previousLat = 60.17,
            previousLon = 24.95,
            previousTime = sameTime
        )

        assertEquals(0.0, speed, 0.001)
    }

    @Test
    fun `getLastPosition returns null when no position stored`() {
        assertNull(filter.getLastPosition("unknown-entity"))
    }

    @Test
    fun `updateLastPosition stores position in memory and writes to Redis`() {
        val time = Instant.parse("2026-01-01T10:00:00Z")
        val jsonSlot = slot<String>()
        every {
            hashOps.put(
                any(), eq("entity-001"),
                capture(jsonSlot)
            )
        } returns Mono.just(true)

        filter.updateLastPosition("entity-001", 60.17, 24.95, time)

        val stored = filter.getLastPosition("entity-001")
        assertNotNull(stored)
        assertEquals(60.17, stored!!.lat, 0.0001)
        assertEquals(24.95, stored.lon, 0.0001)
        assertEquals(time, stored.time)

        verify { hashOps.put(properties.filter.redisKey, "entity-001", any()) }
        assertTrue(jsonSlot.captured.contains("60.17"))
        assertTrue(jsonSlot.captured.contains("24.95"))
        assertTrue(jsonSlot.captured.contains(time.toString()))
    }

    @Test
    fun `updateLastPosition does not store invalid coordinates`() {
        filter.updateLastPosition("entity-001", 200.0, 24.95, Instant.now())

        assertNull(filter.getLastPosition("entity-001"))
    }

    @Test
    fun `updateLastPosition does not store invalid longitude`() {
        filter.updateLastPosition("entity-001", 60.0, 200.0, Instant.now())

        assertNull(filter.getLastPosition("entity-001"))
    }

    @Test
    fun `updateLastPosition overwrites previous entry for same entity`() {
        every { hashOps.put(any(), any(), any()) } returns Mono.just(true)

        val time1 = Instant.parse("2026-01-01T10:00:00Z")
        val time2 = Instant.parse("2026-01-01T10:05:00Z")
        filter.updateLastPosition("entity-001", 60.0, 24.0, time1)
        filter.updateLastPosition("entity-001", 61.0, 25.0, time2)

        val stored = filter.getLastPosition("entity-001")
        assertNotNull(stored)
        assertEquals(61.0, stored!!.lat, 0.0001)
        assertEquals(time2, stored.time)
    }

    @Test
    fun `positions for different entities are stored independently`() {
        every { hashOps.put(any(), any(), any()) } returns Mono.just(true)

        val time = Instant.parse("2026-01-01T10:00:00Z")
        filter.updateLastPosition("entity-001", 60.0, 24.0, time)
        filter.updateLastPosition("entity-002", 55.0, 12.0, time)

        assertEquals(60.0, filter.getLastPosition("entity-001")!!.lat, 0.0001)
        assertEquals(55.0, filter.getLastPosition("entity-002")!!.lat, 0.0001)
    }

    @Test
    fun `init loads positions from Redis into in-memory cache`() {
        val storedTime = Instant.parse("2026-01-01T09:00:00Z")
        val json = """{"lat":59.33,"lon":18.07,"time":"$storedTime"}"""
        val entries = mapOf("entity-redis-001" to json)

        every { hashOps.entries(properties.filter.redisKey) } returns Flux.fromIterable(entries.entries)
        every { redisTemplate.expire(any(), any()) } returns Mono.just(true)

        val freshFilter = GpsOutlierFilterImpl(redisTemplate, objectMapper, properties)
        freshFilter.init()

        val loaded = freshFilter.getLastPosition("entity-redis-001")
        assertNotNull(loaded)
        assertEquals(59.33, loaded!!.lat, 0.0001)
        assertEquals(18.07, loaded.lon, 0.0001)
        assertEquals(storedTime, loaded.time)
    }

    @Test
    fun `init skips corrupt Redis entries and continues loading valid ones`() {
        val storedTime = Instant.parse("2026-01-01T09:00:00Z")
        val validJson = """{"lat":59.33,"lon":18.07,"time":"$storedTime"}"""
        val corruptJson = """{"broken":true}"""
        val entries = mapOf(
            "entity-valid" to validJson,
            "entity-corrupt" to corruptJson
        )

        every { hashOps.entries(properties.filter.redisKey) } returns Flux.fromIterable(entries.entries)
        every { redisTemplate.expire(any(), any()) } returns Mono.just(true)

        val freshFilter = GpsOutlierFilterImpl(redisTemplate, objectMapper, properties)
        freshFilter.init()

        assertNotNull(freshFilter.getLastPosition("entity-valid"))
        assertNull(freshFilter.getLastPosition("entity-corrupt"))
    }

    @Test
    fun `updateLastPosition degrades gracefully when Redis write fails`() {
        every { hashOps.put(any(), any(), any()) } returns Mono.error(
            RuntimeException("Redis connection refused")
        )

        val time = Instant.parse("2026-01-01T10:00:00Z")
        filter.updateLastPosition("entity-001", 60.0, 24.0, time)

        val stored = filter.getLastPosition("entity-001")
        assertNotNull(stored)
        assertEquals(60.0, stored!!.lat, 0.0001)
    }

    @Test
    fun `init continues with empty cache when Redis is unavailable`() {
        every { hashOps.entries(any()) } returns Flux.error(RuntimeException("Redis down"))
        every { redisTemplate.expire(any(), any()) } returns Mono.just(true)

        val freshFilter = GpsOutlierFilterImpl(redisTemplate, objectMapper, properties)
        freshFilter.init()

        assertNull(freshFilter.getLastPosition("any-entity"))
    }

    @Test
    fun `works in memory-only mode when Redis is null`() {
        val memoryOnlyFilter = GpsOutlierFilterImpl(null, objectMapper, properties)
        memoryOnlyFilter.init()

        memoryOnlyFilter.updateLastPosition("entity-001", 60.0, 24.0, Instant.now())
        assertNotNull(memoryOnlyFilter.getLastPosition("entity-001"))
    }
}
