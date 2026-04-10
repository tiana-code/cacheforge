package com.cacheforge.geo

import com.cacheforge.config.CacheForgeProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Qualifier
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.geo.Distance
import org.springframework.data.geo.GeoResult
import org.springframework.data.redis.connection.RedisGeoCommands
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.data.redis.domain.geo.BoundingBox
import org.springframework.data.redis.domain.geo.GeoReference
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class GeoQueryServiceImpl(
    private val reactiveStringRedisTemplate: ReactiveRedisTemplate<String, String>,
    @Qualifier("cacheforgeObjectMapper") private val objectMapper: ObjectMapper,
    private val properties: CacheForgeProperties
) : GeoQueryService {

    companion object {
        private val BATCH_META_SCRIPT: RedisScript<String> = RedisScript.of(
            """
            local prefix = ARGV[1]
            local result = {}
            for i = 2, #ARGV do
                local key = prefix .. ARGV[i]
                local data = redis.call('HGETALL', key)
                if #data > 0 then
                    local entry = {}
                    entry['id'] = ARGV[i]
                    for j = 1, #data, 2 do
                        entry[data[j]] = data[j+1]
                    end
                    result[#result + 1] = entry
                end
            end
            return cjson.encode(result)
            """.trimIndent(),
            String::class.java
        )
    }

    override fun findInViewport(
        centerLon: Double,
        centerLat: Double,
        widthKm: Double,
        heightKm: Double,
        count: Int,
        sources: String?
    ): Flow<EntityPositionResult> {
        val geoResults = reactiveStringRedisTemplate.opsForGeo()
            .search(
                properties.geo.geoKey,
                GeoReference.fromCoordinate(centerLon, centerLat),
                BoundingBox(widthKm, heightKm, RedisGeoCommands.DistanceUnit.KILOMETERS),
                searchArgs(count)
            )
            .onErrorResume { e ->
                logger.warn(e) { "GEOSEARCH viewport failed - returning empty" }
                Flux.empty()
            }

        return enrichBatch(geoResults, sources)
    }

    override fun findNearby(
        lon: Double,
        lat: Double,
        radiusKm: Double,
        count: Int,
        sources: String?
    ): Flow<EntityPositionResult> {
        val geoResults = reactiveStringRedisTemplate.opsForGeo()
            .search(
                properties.geo.geoKey,
                GeoReference.fromCoordinate(lon, lat),
                Distance(radiusKm, RedisGeoCommands.DistanceUnit.KILOMETERS),
                searchArgs(count)
            )
            .onErrorResume { e ->
                logger.warn(e) { "GEOSEARCH nearby failed - returning empty" }
                Flux.empty()
            }

        return enrichBatch(geoResults, sources)
    }

    override fun findNearEntity(
        entityId: String,
        radiusKm: Double,
        count: Int,
        sources: String?
    ): Flow<EntityPositionResult> {
        val geoResults = reactiveStringRedisTemplate.opsForGeo()
            .search(
                properties.geo.geoKey,
                GeoReference.fromMember(entityId),
                Distance(radiusKm, RedisGeoCommands.DistanceUnit.KILOMETERS),
                searchArgs(count)
            )
            .onErrorResume { e ->
                logger.warn(e) { "GEOSEARCH near entity $entityId failed - returning empty" }
                Flux.empty()
            }

        return enrichBatch(geoResults, sources)
    }

    override suspend fun getGeoStats(): GeoStats {
        return try {
            val count = reactiveStringRedisTemplate.opsForZSet()
                .size(properties.geo.geoKey)
                .awaitSingle()
            GeoStats(totalEntities = count)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get geo stats - returning zero" }
            GeoStats(totalEntities = 0)
        }
    }

    private fun searchArgs(count: Int): RedisGeoCommands.GeoSearchCommandArgs =
        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
            .limit(count.toLong())
            .sortAscending()
            .includeDistance()

    private fun enrichBatch(
        geoResults: Flux<GeoResult<RedisGeoCommands.GeoLocation<String>>>,
        sources: String?
    ): Flow<EntityPositionResult> {
        val allowed = sources?.split(',')?.map { it.trim() }?.toSet()

        return geoResults
            .collectList()
            .flatMap { results ->
                if (results.isEmpty()) return@flatMap Mono.just(emptyList<EntityPositionResult>())

                val distanceByEntityId = results.associate { it.content.name to it.distance.value }
                val entityIds = results.map { it.content.name }
                val args = listOf(properties.geo.metaKeyPrefix) + entityIds

                reactiveStringRedisTemplate.execute(BATCH_META_SCRIPT, emptyList(), args)
                    .next()
                    .map { json -> parseBatchMeta(json, distanceByEntityId) }
                    .defaultIfEmpty(emptyList())
                    .onErrorResume { e ->
                        logger.warn(e) { "Batch metadata fetch failed - returning empty" }
                        Mono.just(emptyList())
                    }
            }
            .defaultIfEmpty(emptyList())
            .flatMapIterable { positions ->
                if (allowed != null) positions.filter {
                    it.dataSource in allowed
                } else positions
            }
            .asFlow()
    }

    private fun parseBatchMeta(json: String, distanceByEntityId: Map<String, Double>): List<EntityPositionResult> {
        val entries: List<Map<String, String>> = objectMapper.readValue(json)
        return entries.mapNotNull { meta ->
            val entityId = meta["id"] ?: return@mapNotNull null
            val lat = meta["lat"]?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = meta["lon"]?.toDoubleOrNull() ?: return@mapNotNull null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                return@mapNotNull null
            }
            val timestamp = meta["timestamp"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return@mapNotNull null

            EntityPositionResult(
                entityId = entityId,
                lat = lat,
                lon = lon,
                distanceKm = distanceByEntityId[entityId],
                heading = meta["heading"]?.toDoubleOrNull(),
                speed = meta["speed"]?.toDoubleOrNull(),
                source = meta["source"] ?: "unknown",
                dataSource = meta["data_source"] ?: "unknown",
                userId = meta["userId"]?.takeIf { it.isNotBlank() },
                timestamp = timestamp
            )
        }
    }
}
