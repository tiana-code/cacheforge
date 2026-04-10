package com.cacheforge.state

import com.cacheforge.config.CacheForgeProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

class AlertStateCacheImplTest {

    private lateinit var redisTemplate: ReactiveStringRedisTemplate
    private lateinit var valueOps: ReactiveValueOperations<String, String>
    private lateinit var cache: AlertStateCacheImpl

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val properties = CacheForgeProperties()

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk()
        valueOps = mockk()
        every { redisTemplate.opsForValue() } returns valueOps

        cache = AlertStateCacheImpl(redisTemplate, objectMapper, properties)
    }

    @Test
    fun `getState returns Found with deserialized state from Redis`() = runTest {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        val payload = mapOf(
            "value" to 42.5,
            "timestamp" to now.toString(),
            "lastSeenTime" to now.plusSeconds(300).toString(),
            "timestampEpoch" to now.toEpochMilli()
        )
        val json = objectMapper.writeValueAsString(payload)
        every { valueOps.get(any()) } returns Mono.just(json)

        val result = cache.getState("entity-001", "fuel.rate")

        assertTrue(result is AlertStateLookup.Found)
        val found = result as AlertStateLookup.Found
        assertEquals(42.5, found.state.value, 0.001)
        assertEquals(now, found.state.timestamp)
    }

    @Test
    fun `getState returns Missing when Redis key absent`() = runTest {
        every { valueOps.get(any()) } returns Mono.empty()

        val result = cache.getState("entity-001", "fuel.rate")

        assertEquals(AlertStateLookup.Missing, result)
    }

    @Test
    fun `getState returns Failed when Redis throws`() = runTest {
        every { valueOps.get(any()) } returns Mono.error(RuntimeException("Redis unavailable"))

        val result = cache.getState("entity-001", "fuel.rate")

        assertTrue(result is AlertStateLookup.Failed)
    }

    @Test
    fun `getState uses key derived from entityId and tagCode`() = runTest {
        val keySlot = slot<String>()
        every { valueOps.get(capture(keySlot)) } returns Mono.empty()

        cache.getState("entity-001", "fuel.rate")

        assertTrue(keySlot.captured.contains("entity-001"))
        assertTrue(keySlot.captured.contains("fuel.rate"))
    }

    @Test
    fun `updateIfNewer returns Updated when Lua script accepts update`() = runTest {
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                any<List<String>>()
            )
        } returns Flux.just(1L)

        val now = Instant.now()
        val state = AlertValueState(value = 10.0, timestamp = now, lastSeenTime = now)

        val result = cache.updateIfNewer("entity-001", "speed", state)

        assertEquals(AlertStateUpdateResult.Updated, result)
    }

    @Test
    fun `updateIfNewer returns StaleRejected when Lua script rejects stale update`() = runTest {
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                any<List<String>>()
            )
        } returns Flux.just(0L)

        val now = Instant.now()
        val state = AlertValueState(value = 10.0, timestamp = now, lastSeenTime = now)

        val result = cache.updateIfNewer("entity-001", "speed", state)

        assertEquals(AlertStateUpdateResult.StaleRejected, result)
    }

    @Test
    fun `updateIfNewer returns Failed when Redis throws`() = runTest {
        every { redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.error(RuntimeException("write failed"))

        val now = Instant.now()
        val state = AlertValueState(value = 10.0, timestamp = now, lastSeenTime = now)

        val result = cache.updateIfNewer("entity-001", "speed", state)

        assertTrue(result is AlertStateUpdateResult.Failed)
    }

    @Test
    fun `updateIfNewer passes json with timestampEpoch field in args`() = runTest {
        val argsSlot = slot<List<String>>()
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                capture(argsSlot)
            )
        } returns Flux.just(1L)

        val ts = Instant.parse("2026-01-01T10:00:00Z")
        val state = AlertValueState(value = 42.5, timestamp = ts, lastSeenTime = ts)

        cache.updateIfNewer("entity-001", "fuel.rate", state)

        val args = argsSlot.captured
        assertTrue(args[0].contains("timestampEpoch"), "JSON payload must contain timestampEpoch")
        assertEquals(ts.toEpochMilli().toString(), args[1])
        assertEquals(properties.alerts.stateTtl.seconds.toString(), args[2])
    }

    @Test
    fun `updateIfNewer passes correct key derived from entityId and tagCode`() = runTest {
        val keysSlot = slot<List<String>>()
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                capture(keysSlot),
                any<List<String>>()
            )
        } returns Flux.just(1L)

        val now = Instant.now()
        val state = AlertValueState(value = 1.0, timestamp = now, lastSeenTime = now)

        cache.updateIfNewer("entity-001", "fuel.rate", state)

        val keys = keysSlot.captured
        assertEquals(1, keys.size)
        assertTrue(keys[0].contains("entity-001"))
        assertTrue(keys[0].contains("fuel.rate"))
    }

    @Test
    fun `updateIfNewer uses execute not valueOps set`() = runTest {
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                any<List<String>>()
            )
        } returns Flux.just(1L)

        val now = Instant.now()
        val state = AlertValueState(value = 10.0, timestamp = now, lastSeenTime = now)

        cache.updateIfNewer("entity-001", "speed", state)

        verify(exactly = 0) { valueOps.set(any<String>(), any<String>(), any<java.time.Duration>()) }
        verify { redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), any<List<String>>()) }
    }
}
