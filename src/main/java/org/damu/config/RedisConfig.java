package org.damu.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * ════════════════════════════════════════════════════════════════
 * REDIS CONFIGURATION — Spring Data Redis 4.0+ Compatible
 * ════════════════════════════════════════════════════════════════
 * <p>
 * What changed in Spring Data Redis 4.0?
 * ----------------------------------------
 * GenericJackson2JsonRedisSerializer  → DEPRECATED (marked for removal)
 * RedisSerializer.json(ObjectMapper)  → NEW standard way
 * <p>
 * RedisSerializer.json() internally uses Jackson's
 * JacksonObjectReader / JacksonObjectWriter — more efficient,
 * better aligned with Spring's broader Jackson integration.
 * <p>
 * Everything else (key naming, TTL strategy, @EnableCaching) stays the same.
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * <p>
     * Spring Data Redis 4.0 way:
     * RedisSerializer.json(objectMapper())
     * <p>
     * This replaces:
     * new GenericJackson2JsonRedisSerializer(objectMapper())  ← deprecated
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisSerializer<Object> jsonSerializer = RedisSerializer.json();
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * <p>
     * Same fix — RedisSerializer.json() replaces the deprecated class
     * in the SerializationPair for cache value serialization.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        RedisSerializer<Object> jsonSerializer = RedisSerializer.json();
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(1)).serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())).serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("orders", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigs.put("userOrders", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("products", defaultConfig.entryTtl(Duration.ofHours(2)));
        cacheConfigs.put("orderStats", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        return RedisCacheManager.builder(factory).cacheDefaults(defaultConfig).withInitialCacheConfigurations(cacheConfigs).build();
    }

    /**
     * <p>
     * No changes needed here.
     * <p>
     * RedisSerializer.json(mapper) reads this ObjectMapper and
     * uses it for all serialization/deserialization — same behaviour
     * as the old GenericJackson2JsonRedisSerializer(mapper).
     * <p>
     * The @class embedding (activateDefaultTyping) still works
     * exactly the same way with the new API.
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}