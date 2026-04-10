package com.cacheforge.state

import com.cacheforge.config.CacheForgeProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class AlertStateCacheImpl(
    private val redisTemplate: ReactiveStringRedisTemplate,
    @Qualifier("cacheforgeObjectMapper") private val objectMapper: ObjectMapper,
    private val properties: CacheForgeProperties
) : AlertStateCache {

    companion object {
        private val UPDATE_IF_NEWER_SCRIPT: RedisScript<Long> = RedisScript.of(
            """
            local existing = redis.call('GET', KEYS[1])
            if existing ~= false then
                local parsed = cjson.decode(existing)
                if tonumber(ARGV[2]) < tonumber(parsed['timestampEpoch']) then
                    return 0
                end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            return 1
            """.trimIndent(),
            Long::class.java
        )
    }

    private fun stateKey(entityId: String, tagCode: String) =
        "${properties.alerts.stateKeyPrefix}:$entityId:$tagCode"

    override suspend fun getState(entityId: String, tagCode: String): AlertStateLookup {
        return try {
            val json = redisTemplate.opsForValue().get(stateKey(entityId, tagCode)).awaitSingleOrNull()
                ?: return AlertStateLookup.Missing
            val map: Map<String, Any> = objectMapper.readValue(json)
            val state = AlertValueState(
                value = (map["value"] as Number).toDouble(),
                timestamp = Instant.parse(map["timestamp"] as String),
                lastSeenTime = Instant.parse(map["lastSeenTime"] as String)
            )
            AlertStateLookup.Found(state)
        } catch (e: Exception) {
            logger.warn { "Failed to read alert state from Redis for $entityId/$tagCode: ${e.message}" }
            AlertStateLookup.Failed(e.message ?: "unknown error")
        }
    }

    override suspend fun updateIfNewer(
        entityId: String,
        tagCode: String,
        state: AlertValueState
    ): AlertStateUpdateResult {
        return try {
            val payload = mapOf(
                "value" to state.value,
                "timestamp" to state.timestamp.toString(),
                "lastSeenTime" to state.lastSeenTime.toString(),
                "timestampEpoch" to state.timestamp.toEpochMilli()
            )
            val json = objectMapper.writeValueAsString(payload)
            val ttlSeconds = properties.alerts.stateTtl.seconds.toString()
            val epochMillis = state.timestamp.toEpochMilli().toString()
            val keys = listOf(stateKey(entityId, tagCode))
            val args = listOf(json, epochMillis, ttlSeconds)
            val result = redisTemplate.execute(UPDATE_IF_NEWER_SCRIPT, keys, args).awaitFirstOrNull()
            if (result == 1L) AlertStateUpdateResult.Updated else AlertStateUpdateResult.StaleRejected
        } catch (e: Exception) {
            logger.warn { "Failed to update alert state in Redis for $entityId/$tagCode: ${e.message}" }
            AlertStateUpdateResult.Failed(e.message ?: "unknown error")
        }
    }
}
