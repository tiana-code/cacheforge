package com.cacheforge.geo

import com.cacheforge.config.CacheForgeProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.geo.Distance
import org.springframework.data.geo.GeoResult
import org.springframework.data.geo.Point
import org.springframework.data.redis.connection.RedisGeoCommands
import org.springframework.data.redis.core.ReactiveGeoOperations
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveZSetOperations
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.data.redis.domain.geo.BoundingBox
import org.springframework.data.redis.domain.geo.GeoReference
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class GeoQueryServiceImplTest {

    private lateinit var redisTemplate: ReactiveRedisTemplate<String, String>
    private lateinit var geoOps: ReactiveGeoOperations<String, String>
    private lateinit var zsetOps: ReactiveZSetOperations<String, String>
    private lateinit var service: GeoQueryServiceImpl
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val properties = CacheForgeProperties()

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk()
        geoOps = mockk()
        zsetOps = mockk()

        every { redisTemplate.opsForGeo() } returns geoOps
        every { redisTemplate.opsForZSet() } returns zsetOps

        service = GeoQueryServiceImpl(redisTemplate, objectMapper, properties)
    }

    @Test
    fun `findNearby returns enriched positions via batch metadata fetch`() = runTest {
        val entityId = "entity-001"
        val geoResult = GeoResult(
            RedisGeoCommands.GeoLocation(entityId, Point(24.95, 60.17)),
            Distance(2.5)
        )
        every { geoOps.search(any(), any<GeoReference<String>>(), any<Distance>(), any()) } returns
                Flux.just(geoResult)

        val batchJson =
            """[{"id":"entity-001","lat":"60.17","lon":"24.95","heading":"180.0","speed":"12.5","source":"ais","data_source":"REAL","userId":"user-001","timestamp":"2026-01-01T10:00:00Z"}]"""
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.just(batchJson)

        val results = service.findNearby(24.95, 60.17, 50.0).toList()

        assertEquals(1, results.size)
        assertEquals(entityId, results[0].entityId)
        assertEquals(60.17, results[0].lat, 0.0001)
        assertEquals(24.95, results[0].lon, 0.0001)
        assertEquals(12.5, results[0].speed)
        assertEquals("REAL", results[0].dataSource)
        assertNotNull(results[0].distanceKm)
        assertEquals(2.5, results[0].distanceKm!!, 0.0001)
    }

    @Test
    fun `findNearby returns empty when GEOSEARCH fails`() = runTest {
        every { geoOps.search(any(), any<GeoReference<String>>(), any<Distance>(), any()) } returns
                Flux.error(RuntimeException("Redis down"))

        val results = service.findNearby(24.95, 60.17, 50.0).toList()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `findNearby filters by sources when specified`() = runTest {
        val entityId = "entity-001"
        val geoResult = GeoResult(
            RedisGeoCommands.GeoLocation(entityId, Point(24.95, 60.17)),
            Distance(0.0)
        )
        every { geoOps.search(any(), any<GeoReference<String>>(), any<Distance>(), any()) } returns
                Flux.just(geoResult)

        val batchJson =
            """[{"id":"entity-001","lat":"60.17","lon":"24.95","source":"ais","data_source":"DEMO","timestamp":"2026-01-01T10:00:00Z"}]"""
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.just(batchJson)

        val results = service.findNearby(24.95, 60.17, 50.0, sources = "REAL").toList()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `getGeoStats returns total entity count from ZCARD`() = runTest {
        every { zsetOps.size(properties.geo.geoKey) } returns Mono.just(42L)

        val stats = service.getGeoStats()

        assertEquals(42L, stats.totalEntities)
    }

    @Test
    fun `findNearby skips entry with invalid coordinates in meta`() = runTest {
        val geoResult = GeoResult(
            RedisGeoCommands.GeoLocation("entity-bad", Point(0.0, 0.0)),
            Distance(0.0)
        )
        every { geoOps.search(any(), any<GeoReference<String>>(), any<Distance>(), any()) } returns
                Flux.just(geoResult)

        val batchJson =
            """[{"id":"entity-bad","lat":"999.0","lon":"24.95","source":"ais","data_source":"REAL","timestamp":"2026-01-01T10:00:00Z"}]"""
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.just(batchJson)

        val results = service.findNearby(24.95, 60.17, 50.0).toList()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `findNearby skips entry with missing timestamp`() = runTest {
        val geoResult = GeoResult(
            RedisGeoCommands.GeoLocation("entity-001", Point(24.95, 60.17)),
            Distance(0.0)
        )
        every { geoOps.search(any(), any<GeoReference<String>>(), any<Distance>(), any()) } returns
                Flux.just(geoResult)

        val batchJson = """[{"id":"entity-001","lat":"60.17","lon":"24.95","source":"ais","data_source":"REAL"}]"""
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.just(batchJson)

        val results = service.findNearby(24.95, 60.17, 50.0).toList()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `findNearby handles multiple entities in single batch`() = runTest {
        val geoResults = listOf(
            GeoResult(RedisGeoCommands.GeoLocation("entity-001", Point(24.95, 60.17)), Distance(0.0)),
            GeoResult(RedisGeoCommands.GeoLocation("entity-002", Point(25.0, 60.2)), Distance(1.0))
        )
        every { geoOps.search(any(), any<GeoReference<String>>(), any<Distance>(), any()) } returns
                Flux.fromIterable(geoResults)

        val batchJson =
            """[{"id":"entity-001","lat":"60.17","lon":"24.95","source":"ais","data_source":"REAL","timestamp":"2026-01-01T10:00:00Z"},{"id":"entity-002","lat":"60.2","lon":"25.0","source":"ais","data_source":"REAL","timestamp":"2026-01-01T10:01:00Z"}]"""
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.just(batchJson)

        val results = service.findNearby(24.95, 60.17, 50.0).toList()

        assertEquals(2, results.size)
        assertEquals("entity-001", results[0].entityId)
        assertEquals("entity-002", results[1].entityId)
    }

    @Test
    fun `findNearby returns empty when batch metadata script fails`() = runTest {
        val geoResult = GeoResult(
            RedisGeoCommands.GeoLocation("entity-001", Point(24.95, 60.17)),
            Distance(0.0)
        )
        every { geoOps.search(any(), any<GeoReference<String>>(), any<Distance>(), any()) } returns
                Flux.just(geoResult)
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.error(RuntimeException("Script error"))

        val results = service.findNearby(24.95, 60.17, 50.0).toList()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `findNearby preserves null heading and speed from metadata`() = runTest {
        val geoResult = GeoResult(
            RedisGeoCommands.GeoLocation("entity-001", Point(24.95, 60.17)),
            Distance(0.0)
        )
        every { geoOps.search(any(), any<GeoReference<String>>(), any<Distance>(), any()) } returns
                Flux.just(geoResult)

        val batchJson =
            """[{"id":"entity-001","lat":"60.17","lon":"24.95","heading":"","speed":"","source":"ais","data_source":"REAL","timestamp":"2026-01-01T10:00:00Z"}]"""
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.just(batchJson)

        val results = service.findNearby(24.95, 60.17, 50.0).toList()

        assertEquals(1, results.size)
        assertEquals(null, results[0].heading)
        assertEquals(null, results[0].speed)
    }

    @Test
    fun `findInViewport returns enriched positions via batch metadata fetch`() = runTest {
        val entityId = "entity-001"
        val geoResult = GeoResult(
            RedisGeoCommands.GeoLocation(entityId, Point(24.95, 60.17)),
            Distance(0.0)
        )
        every { geoOps.search(any(), any<GeoReference<String>>(), any<BoundingBox>(), any()) } returns
                Flux.just(geoResult)

        val batchJson =
            """[{"id":"entity-001","lat":"60.17","lon":"24.95","source":"ais","data_source":"REAL","timestamp":"2026-01-01T10:00:00Z"}]"""
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.just(batchJson)

        val results = service.findInViewport(24.95, 60.17, 100.0, 100.0).toList()

        assertEquals(1, results.size)
        assertEquals(entityId, results[0].entityId)
    }

    @Test
    fun `findNearEntity returns enriched positions via batch metadata fetch`() = runTest {
        val entityId = "entity-002"
        val geoResult = GeoResult(
            RedisGeoCommands.GeoLocation(entityId, Point(25.0, 60.2)),
            Distance(1.0)
        )
        every { geoOps.search(any(), any<GeoReference<String>>(), any<Distance>(), any()) } returns
                Flux.just(geoResult)

        val batchJson =
            """[{"id":"entity-002","lat":"60.2","lon":"25.0","source":"ais","data_source":"REAL","timestamp":"2026-01-01T10:00:00Z"}]"""
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), any<List<String>>()) } returns
                Flux.just(batchJson)

        val results = service.findNearEntity("entity-001", 50.0).toList()

        assertEquals(1, results.size)
        assertEquals(entityId, results[0].entityId)
        assertEquals(1.0, results[0].distanceKm!!, 0.0001)
    }

    @Test
    fun `getGeoStats returns zero when Redis fails`() = runTest {
        every { zsetOps.size(any()) } returns Mono.error(RuntimeException("Redis down"))

        val stats = service.getGeoStats()

        assertEquals(0L, stats.totalEntities)
    }
}
