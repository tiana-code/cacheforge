package com.cacheforge.geo

import com.cacheforge.config.CacheForgeProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class GeoIndexServiceImpl(
    private val reactiveStringRedisTemplate: ReactiveRedisTemplate<String, String>,
    private val properties: CacheForgeProperties
) : GeoIndexService {

    companion object {
        const val GEO_KEY = "cacheforge:entity:positions:geo"
        const val META_KEY_PREFIX = "cacheforge:entity:positions:meta:"

        private val UPDATE_SCRIPT: RedisScript<Long> = RedisScript.of(
            """
            local existing_ts = redis.call('HGET', KEYS[2], 'timestamp_epoch')
            if existing_ts == false or tonumber(ARGV[7]) >= tonumber(existing_ts) then
                redis.call('GEOADD', KEYS[1], ARGV[1], ARGV[2], ARGV[3])
                redis.call('EXPIRE', KEYS[1], ARGV[14])
                redis.call('HSET', KEYS[2],
                    'lat', ARGV[4],
                    'lon', ARGV[5],
                    'heading', ARGV[6],
                    'speed', ARGV[8],
                    'timestamp', ARGV[9],
                    'timestamp_epoch', ARGV[7],
                    'source', ARGV[10],
                    'data_source', ARGV[11],
                    'userId', ARGV[12])
                redis.call('EXPIRE', KEYS[2], ARGV[13])
                return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java
        )
    }

    override suspend fun updatePosition(
        entityId: String,
        lat: Double,
        lon: Double,
        heading: Double?,
        speed: Double?,
        timestamp: Instant,
        source: String?,
        userId: String?,
        dataSource: String?
    ): GeoUpdateResult {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return GeoUpdateResult.InvalidCoordinates(lat, lon)
        }

        val ttlSeconds = properties.geo.positionTtl.seconds.toString()
        val metaKey = "${properties.geo.metaKeyPrefix}$entityId"
        val keys = listOf(properties.geo.geoKey, metaKey)
        val args = listOf(
            lon.toString(),
            lat.toString(),
            entityId,
            lat.toString(),
            lon.toString(),
            heading?.toString() ?: "",
            timestamp.toEpochMilli().toString(),
            speed?.toString() ?: "",
            timestamp.toString(),
            source ?: "unknown",
            dataSource ?: "unknown",
            userId ?: "",
            ttlSeconds,
            ttlSeconds
        )

        return try {
            val result = reactiveStringRedisTemplate.execute(UPDATE_SCRIPT, keys, args)
                .awaitFirstOrNull()
            if (result == 1L) GeoUpdateResult.Updated else GeoUpdateResult.StaleRejected
        } catch (e: Exception) {
            logger.warn(e) { "GEO index update failed for entity $entityId - continuing" }
            GeoUpdateResult.Failed(e.message ?: "unknown error")
        }
    }
}
