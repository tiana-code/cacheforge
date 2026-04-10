package com.cacheforge.geo

import com.cacheforge.config.CacheForgeProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import java.time.Instant

class GeoIndexServiceImplTest {

    private lateinit var redisTemplate: ReactiveRedisTemplate<String, String>
    private lateinit var service: GeoIndexServiceImpl
    private val properties = CacheForgeProperties()

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk()
        service = GeoIndexServiceImpl(redisTemplate, properties)
    }

    @Test
    fun `updatePosition returns Updated when Lua script accepts the update`() = runTest {
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(), any<List<String>>(),
                any<List<String>>()
            )
        } returns Flux.just(1L)

        val result = service.updatePosition(
            entityId = "entity-001",
            lat = 60.17,
            lon = 24.95,
            heading = 180.0,
            speed = 12.5,
            timestamp = Instant.parse("2026-01-01T10:00:00Z"),
            source = "ais",
            userId = "user-001"
        )

        assertEquals(GeoUpdateResult.Updated, result)
    }

    @Test
    fun `updatePosition returns StaleRejected when Lua script rejects stale update`() = runTest {
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(), any<List<String>>()
            )
        } returns Flux.just(0L)

        val result = service.updatePosition(
            entityId = "entity-001",
            lat = 60.17,
            lon = 24.95,
            heading = 180.0,
            speed = 12.5,
            timestamp = Instant.parse("2026-01-01T09:00:00Z"),
            source = "ais",
            userId = "user-001"
        )

        assertEquals(GeoUpdateResult.StaleRejected, result)
    }

    @Test
    fun `updatePosition returns Failed when Redis throws`() = runTest {
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(), any<List<String>>(),
                any<List<String>>()
            )
        } returns Flux.error(RuntimeException("Redis down"))

        val result = service.updatePosition(
            entityId = "entity-001",
            lat = 60.17,
            lon = 24.95,
            heading = 180.0,
            speed = 12.5,
            timestamp = Instant.now(),
            source = "ais",
            userId = "user-001"
        )

        assertTrue(result is GeoUpdateResult.Failed)
    }

    @Test
    fun `updatePosition returns InvalidCoordinates when lat is out of range`() = runTest {
        val result = service.updatePosition(
            entityId = "entity-001",
            lat = 95.0,
            lon = 24.95,
            heading = null,
            speed = null,
            timestamp = Instant.now(),
            source = null,
            userId = null
        )

        assertTrue(result is GeoUpdateResult.InvalidCoordinates)
        val invalid = result as GeoUpdateResult.InvalidCoordinates
        assertEquals(95.0, invalid.lat, 0.0001)
    }

    @Test
    fun `updatePosition returns InvalidCoordinates when lon is out of range`() = runTest {
        val result = service.updatePosition(
            entityId = "entity-001",
            lat = 60.17,
            lon = 200.0,
            heading = null,
            speed = null,
            timestamp = Instant.now(),
            source = null,
            userId = null
        )

        assertTrue(result is GeoUpdateResult.InvalidCoordinates)
    }

    @Test
    fun `updatePosition passes correct keys to Lua script`() = runTest {
        val keysSlot = slot<List<String>>()
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(), capture(keysSlot),
                any<List<String>>()
            )
        } returns Flux.just(1L)

        service.updatePosition(
            entityId = "entity-001",
            lat = 60.17,
            lon = 24.95,
            heading = null,
            speed = null,
            timestamp = Instant.now(),
            source = null,
            userId = null
        )

        val keys = keysSlot.captured
        assertEquals(properties.geo.geoKey, keys[0])
        assertEquals("${properties.geo.metaKeyPrefix}entity-001", keys[1])
    }

    @Test
    fun `updatePosition stores empty string for null heading and speed`() = runTest {
        val argsSlot = slot<List<String>>()
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(), any<List<String>>(),
                capture(argsSlot)
            )
        } returns Flux.just(1L)

        service.updatePosition(
            entityId = "entity-001",
            lat = 60.17,
            lon = 24.95,
            heading = null,
            speed = null,
            timestamp = Instant.now(),
            source = null,
            userId = null
        )

        val args = argsSlot.captured
        assertEquals("", args[5], "heading should be empty string when null")
        assertEquals("", args[7], "speed should be empty string when null")
    }

    @Test
    fun `updatePosition stores actual values for non-null heading and speed`() = runTest {
        val argsSlot = slot<List<String>>()
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(), any<List<String>>(),
                capture(argsSlot)
            )
        } returns Flux.just(1L)

        service.updatePosition(
            entityId = "entity-001",
            lat = 60.17,
            lon = 24.95,
            heading = 180.0,
            speed = 12.5,
            timestamp = Instant.now(),
            source = "ais",
            userId = "user-001"
        )

        val args = argsSlot.captured
        assertEquals("180.0", args[5], "heading should be '180.0'")
        assertEquals("12.5", args[7], "speed should be '12.5'")
    }

    @Test
    fun `updatePosition sends timestamp epoch in correct arg position`() = runTest {
        val argsSlot = slot<List<String>>()
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(), any<List<String>>(),
                capture(argsSlot)
            )
        } returns Flux.just(1L)

        val timestamp = Instant.parse("2026-01-01T10:00:00Z")
        service.updatePosition(
            entityId = "entity-001",
            lat = 60.17,
            lon = 24.95,
            heading = null,
            speed = null,
            timestamp = timestamp,
            source = null,
            userId = null
        )

        val args = argsSlot.captured
        assertEquals(timestamp.toEpochMilli().toString(), args[6], "ARGV[7] should be epoch millis")
        assertEquals(timestamp.toString(), args[8], "ARGV[9] should be ISO timestamp")
    }

    @Test
    fun `updatePosition uses same TTL value for both ARGV 13 and 14`() = runTest {
        val argsSlot = slot<List<String>>()
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(), any<List<String>>(),
                capture(argsSlot)
            )
        } returns Flux.just(1L)

        service.updatePosition(
            entityId = "entity-001",
            lat = 60.17,
            lon = 24.95,
            heading = null,
            speed = null,
            timestamp = Instant.now(),
            source = null,
            userId = null
        )

        val args = argsSlot.captured
        assertEquals(args[12], args[13], "TTL args at index 12 and 13 must match")
        assertEquals(properties.geo.positionTtl.seconds.toString(), args[12])
    }
}
