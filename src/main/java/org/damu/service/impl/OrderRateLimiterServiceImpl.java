package org.damu.service.impl;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.damu.service.OrderRateLimiterService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * ════════════════════════════════════════════════════════════════
 * USE CASE 5 — Rate Limiting (protect order endpoints)
 * USE CASE 6 — Distributed Lock (prevent duplicate orders)
 * ════════════════════════════════════════════════════════════════
 * <p>
 * SENIOR INSIGHT on Rate Limiting:
 * ----------------------------------
 * Without rate limiting, one angry customer can:
 * - Submit 10,000 orders per second by accident (or intentionally)
 * - Crash your order service (DB connection pool exhausted)
 * - Cost you real money (payment gateway fees per API call)
 * <p>
 * Redis is PERFECT for rate limiting because:
 * 1. Sub-millisecond INCR operation
 * 2. Atomic: INCR + EXPIRE in one round trip
 * 3. Shared across ALL app instances (unlike in-memory rate limiting
 * which only counts requests to THIS server)
 * <p>
 * SENIOR INSIGHT on Distributed Locks:
 * --------------------------------------
 * Problem: Customer double-clicks "Place Order" button.
 * Two requests hit two different server instances simultaneously.
 * Both check DB → both see "no order exists" → BOTH create orders.
 * Customer gets charged twice. Disaster.
 * <p>
 * Redis distributed lock prevents this:
 * Only ONE server can hold the lock for "user:123:place-order" at a time.
 * Second request waits (or fails fast) until first completes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRateLimiterServiceImpl implements OrderRateLimiterService {
    private static final String RATE_KEY = "rate:order:";
    private final RedisTemplate<String, Object> redisTemplate;


    /**
     * Fixed Window Rate Limiter.
     * <p>
     * Rule: max N requests per user per time window.
     * <p>
     * Key: rate:order:user:123:2024010115  (hour-level window)
     * <p>
     * How it works:
     * 1. INCR the counter for this user+window
     * 2. If count == 1 (first request), set TTL = window duration
     * 3. If count > limit → REJECTED
     * <p>
     * WHY set TTL only on count==1?
     * If you call EXPIRE on every request, you'd keep resetting TTL.
     * Set it ONCE when key is created → key auto-deletes after window.
     * <p>
     * Limitation: burst at window boundary.
     * If window is 1 minute, user can send 10 at 00:59 and 10 at 01:00
     * = 20 requests in 2 seconds. Use Sliding Window to fix this.
     */
    @Override
    public boolean allowPlaceOrder(Long userId, int maxPerHour) {
        long hourWindow = System.currentTimeMillis() / 3_600_000;
        String key = RATE_KEY + "user:" + userId + ":" + hourWindow;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, Duration.ofHours(1));
        }
        boolean allowed = count <= maxPerHour;
        if (!allowed) {
            log.info("Rate limit exceeded for user {} | count={} | limit={}", userId, count, maxPerHour);
        }
        return allowed;
    }
}