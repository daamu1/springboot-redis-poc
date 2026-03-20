package org.damu.service.impl;


import org.damu.service.OrderRateLimiterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

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
@Service
public class OrderRateLimiterServiceImpl implements OrderRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(OrderRateLimiterServiceImpl.class);

    private static final String RATE_KEY = "rate:order:";
    private static final String LOCK_KEY = "lock:order:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 5A — Fixed Window Rate Limiting
    // ══════════════════════════════════════════════════════════════
    // Trick for named args in Java — replace with actual constants in real code
    private int ttlSeconds;
    private int maxRetries;

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 5B — Sliding Window Rate Limiting (more accurate)
    // ══════════════════════════════════════════════════════════════
    private long retryDelayMs;

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 6 — Distributed Lock
    // ══════════════════════════════════════════════════════════════

    /**
     * DISTRIBUTED LOCK — Prevents duplicate order creation.
     *
     * The lock key pattern: lock:order:place:user:123
     * This means: "user 123 is currently placing an order"
     *
     * Algorithm (Redlock simplified for single-node):
     * 1. SET key value NX EX seconds
     *    - NX = only set if NOT eXists (acquire only if not locked)
     *    - EX = auto-expire after N seconds (prevent deadlock if server crashes)
     * 2. Value = unique owner ID (UUID) — critical for safe release
     * 3. To release: only delete IF value matches (Lua script for atomicity)
     *
     * WHY unique owner value?
     * Scenario without it:
     *  - Server A acquires lock, starts processing
     *  - Server A is slow, lock expires (TTL hit)
     *  - Server B acquires the same lock
     *  - Server A finishes, calls DELETE → deletes Server B's lock!
     *  - Server C now acquires lock → two concurrent writers. Bug!
     *
     * With owner check: Server A's delete fails because value ≠ owner.
     */

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
        // Window = current hour (changes every 60 minutes)
        long hourWindow = System.currentTimeMillis() / 3_600_000;
        String key = RATE_KEY + "user:" + userId + ":" + hourWindow;

        Long count = redisTemplate.opsForValue().increment(key);

        if (count == 1) {
            // First request this window — set TTL to expire key after window
            redisTemplate.expire(key, Duration.ofHours(1));
        }

        boolean allowed = count <= maxPerHour;
        if (!allowed) {
            log.warn("Rate limit exceeded for user {} | count={} | limit={}", userId, count, maxPerHour);
        }
        return allowed;
    }

    /**
     * Per-IP rate limiting — protect against bots and scrapers.
     * More aggressive: 5 requests per minute per IP.
     */
    @Override
    public boolean allowByIp(String clientIp, int maxPerMinute) {
        long minuteWindow = System.currentTimeMillis() / 60_000;
        String key = RATE_KEY + "ip:" + clientIp + ":" + minuteWindow;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(2)); // 2min safety margin
        }

        return count <= maxPerMinute;
    }

    /**
     * Sliding Window using ZSet.
     * <p>
     * Score = request timestamp (milliseconds).
     * Window slides from (now - windowMs) to now.
     * <p>
     * HOW IT WORKS:
     * 1. ZREMRANGEBYSCORE: remove all entries older than window
     * 2. ZCARD: count remaining entries = requests in sliding window
     * 3. ZADD: add this request with score=now
     * 4. EXPIRE: reset TTL
     * <p>
     * No burst at window boundary — the window SLIDES with time.
     * More accurate but uses slightly more memory than fixed window.
     */
    @Override
    public boolean allowSlidingWindow(Long userId, int maxRequests, long windowMs) {
        String key = RATE_KEY + "sliding:user:" + userId;
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;

        // All steps executed as pipeline (4 commands, 1 round-trip)
        // But we need the count result, so we use execute instead of pipelined
        Long count = redisTemplate.execute(connection -> {

            // Remove old entries outside the window
            connection.zRemRangeByScore(key.getBytes(), 0, windowStart);

            // Count current requests in window
            Long currentCount = connection.zCard(key.getBytes());

            // Add this request
            connection.zAdd(key.getBytes(), now, (now + "-" + Math.random()).getBytes());

            // Keep key alive
            connection.expire(key.getBytes(), (int) (windowMs / 1000) + 1);

            return currentCount;

        }, true); // true = pipeline (sends all commands, returns last result)

        boolean allowed = (count == null || count < maxRequests);
        log.debug("Sliding window check for user {}: count={}, limit={}, allowed={}", userId, count, maxRequests, allowed);
        return allowed;
    }

    /**
     * Acquire lock. Returns lock token if acquired, null if already locked.
     */
    @Override
    public String acquireLock(String resource, int ttlSeconds) {
        String key = LOCK_KEY + resource;
        String token = UUID.randomUUID().toString(); // unique owner ID

        // SET key token NX EX ttlSeconds
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(ttlSeconds));

        if (Boolean.TRUE.equals(acquired)) {
            log.debug("LOCK ACQUIRED: {} by token={}", resource, token.substring(0, 8));
            return token; // caller must hold this to release
        }

        log.debug("LOCK BUSY: {} already locked", resource);
        return null; // null = lock not acquired
    }

    /**
     * Release lock — ONLY if we own it.
     * <p>
     * Uses Lua script for atomicity.
     * GET + DEL is NOT atomic in plain Java — race condition between them.
     * Lua script runs atomically in Redis — no other command can interrupt it.
     * <p>
     * Script logic:
     * if redis.call('GET', key) == token
     * then redis.call('DEL', key)
     * return 1 (success)
     * else
     * return 0 (not owner)
     * end
     */
    @Override
    public boolean releaseLock(String resource, String token) {
        String key = LOCK_KEY + resource;

        // This Lua script is the canonical Redis lock release — memorize it
        String luaScript = "if redis.call('GET', KEYS[1]) == ARGV[1] " + "then return redis.call('DEL', KEYS[1]) " + "else return 0 end";

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);

        Long result = redisTemplate.execute(script, Collections.singletonList(key),  // KEYS[1]
                token                             // ARGV[1]
        );

        boolean released = Long.valueOf(1L).equals(result);
        log.debug("LOCK RELEASE: {} → {}", resource, released ? "SUCCESS" : "FAILED (not owner)");
        return released;
    }

    /**
     * TRYLOCK with retry — convenience method for placing orders.
     * <p>
     * Tries to acquire lock, retries up to maxRetries times with delay.
     * Better UX than immediate failure on first contention.
     */
    @Override
    public String tryLockWithRetry(String resource, int ttlSeconds, int maxRetries, long retryDelayMs) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            String token = acquireLock(resource, ttlSeconds);
            if (token != null) {
                log.debug("Lock acquired on attempt {}/{}", attempt, maxRetries);
                return token;
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        log.warn("Failed to acquire lock after {} attempts: {}", maxRetries, resource);
        return null;
    }

    /**
     * COMPLETE EXAMPLE — Place order with rate limit + lock.
     * <p>
     * This is the FULL PRODUCTION flow you'd use in a real OrderService.
     * Shows how rate limiting and locking compose together.
     */
    @Override
    public String placeOrderSafely(Long userId, String orderData) {
        // Step 1: Rate limit check (10 orders per hour per user)
        if (!allowPlaceOrder(userId, 10)) {
            return "RATE_LIMITED: Too many orders. Try again later.";
        }

        // Step 2: Acquire distributed lock (prevent double-submit)
        String lockResource = "place-order:user:" + userId;
        String lockToken = tryLockWithRetry(lockResource, ttlSeconds = 30,    // lock expires in 30s (prevents deadlock)
                maxRetries = 3, retryDelayMs = 100);

        if (lockToken == null) {
            return "CONFLICT: Order already being processed. Please wait.";
        }

        try {
            // Step 3: Check idempotency key (prevent exact duplicate requests)
            // (see OrderIdempotencyService)

            // Step 4: Business logic — create order
            log.info("Creating order for user {} with lock token {}", userId, lockToken.substring(0, 8));
            // ... orderService.create(userId, orderData) ...
            return "ORDER_CREATED";

        } finally {
            // Step 5: ALWAYS release lock — even if exception thrown
            releaseLock(lockResource, lockToken);
        }
    }
}