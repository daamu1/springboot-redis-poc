package org.damu.service.impl;

import org.damu.model.Order;
import org.damu.service.OrderCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ════════════════════════════════════════════════════════════════
 *  USE CASE 1 — Order Caching (Cache-Aside Pattern)
 *  USE CASE 2 — Hash-based Order Storage (field-level access)
 * ════════════════════════════════════════════════════════════════
 *
 *  SENIOR INSIGHT:
 *  ---------------
 *  After 30 years, I've seen teams use Redis wrong in two ways:
 * <p>
 *  ❌ WRONG: Cache everything blindly — "Redis is fast so let's cache all"
 *  ❌ WRONG: Cache nothing — "Redis is complex, just hit the DB"
 * <p>
 *  RIGHT: Cache what is:
 *     - Read-heavy (read 100x more than written)
 *     - Expensive to compute (JOINs, aggregations)
 *     - Tolerant to slight staleness (order list vs live payment status)
 * <p>
 *  The Order entity is PERFECT for caching:
 *  - Fetched on every page load, tracking page, email notification
 *  - DB query involves JOINs to order_items, users, products
 *  - Order data after delivery doesn't change — infinite TTL candidate
 * <p>
 *  KEY NAMING STRATEGY used throughout this project:
 *  -------------------------------------------------
 *  order:{id}           → full order JSON (String)
 *  order:{id}:fields    → order as Hash (individual fields)
 *  user:{uid}:orders    → list of order IDs for a user (List)
 *  order:queue          → pending orders to process (List/ZSet)
 *  order:leaderboard    → top orders by amount (ZSet)
 *  rate:order:{ip}      → rate limiting counter (String)
 *  order:lock:{id}      → distributed lock (String with NX+EX)
 *  order:status:*       → pub/sub channels
 */
@Service
public class OrderCacheServiceImpl  implements OrderCacheService {

    private static final Logger log = LoggerFactory.getLogger(OrderCacheServiceImpl.class);

    // Key prefix — centralise so refactoring is easy
    private static final String ORDER_KEY     = "order:";
    private static final String ORDER_HASH    = "order:hash:";
    private static final String USER_ORDERS   = "user:orders:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 1A — @Cacheable (annotation-driven cache)
    // ══════════════════════════════════════════════════════════════

    /**
     * @Cacheable — The simplest caching pattern.
     *
     * Flow:
     *  1. Request comes in for getOrder(1001)
     *  2. Spring checks Redis: key = "myapp:orders::1001"
     *  3. HIT  → return cached Order immediately, method body NEVER runs
     *  4. MISS → method body runs, result stored in Redis, then returned
     *
     * The "unless" condition: don't cache if order is still PENDING
     * (it changes too often in the first few minutes)
     *
     * SpEL (Spring Expression Language) in key/unless:
     *  #id       → method parameter named 'id'
     *  #result   → the return value
     */
    @Cacheable(
            value = "orders",
            key = "#id",
            unless = "#result != null && #result.status.name() == 'PENDING'"
    )
    @Override
    public Order getOrder(Long id) {
        log.debug("CACHE MISS — loading order {} from database", id);
        // In real app: return orderRepository.findById(id).orElseThrow()
        return simulateDbFetch(id);
    }

    /**
     * @CachePut — ALWAYS runs the method AND updates the cache.
     * Use this on UPDATE operations. If you used @Cacheable on update,
     * it would return the old cached value and skip the update entirely!
     *
     * RULE OF THUMB:
     *  - @Cacheable  → READ  (skip method if cache hit)
     *  - @CachePut   → WRITE (always run + update cache)
     *  - @CacheEvict → DELETE (remove from cache)
     */
    @CachePut(value = "orders", key = "#order.id")
    @Override
    public Order updateOrder(Order order) {
        log.debug("Updating order {} — cache will be refreshed", order.getId());
        // In real app: return orderRepository.save(order)
        order.setUpdatedAt(java.time.LocalDateTime.now());
        return order;
    }

    /**
     * @Caching — combine multiple cache operations in one annotation.
     *
     * When we delete an order we need to:
     * 1. Evict from "orders" cache (the order itself)
     * 2. Evict from "userOrders" cache (the user's order list)
     *
     * Without @Caching you'd need separate calls.
     */
    @Caching(evict = {
            @CacheEvict(value = "orders", key = "#id"),
            @CacheEvict(value = "userOrders", key = "#userId")
    })
    @Override
    public void cancelOrder(Long id, Long userId, String reason) {
        log.debug("Cancelling order {} — evicting from cache", id);
        // In real app: orderRepository.updateStatus(id, CANCELLED, reason)
    }

    /**
     * @CacheEvict with allEntries=true — nuclear option.
     * Clears EVERY entry in the "orders" cache.
     * Use after bulk updates (e.g., after running a migration script).
     * Don't call this often — it defeats the purpose of caching.
     */
    @CacheEvict(value = "orders", allEntries = true)
    @Override
    public void clearAllOrderCache() {
        log.warn("All order cache cleared! This should be rare.");
    }

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 1B — Manual Cache-Aside (RedisTemplate)
    // ══════════════════════════════════════════════════════════════

    /**
     * Manual Cache-Aside — When you need MORE CONTROL than @Cacheable.
     *
     * When to use manual over @Cacheable:
     * - Dynamic TTL based on order status (delivered = 7 days, pending = 5 min)
     * - Custom key patterns not expressible in SpEL
     * - Need to set multiple keys atomically
     * - Conditional logic before caching
     *
     * This is what @Cacheable does internally, but explicitly.
     */
    @Override
    public Order getOrderWithDynamicTtl(Long id) {
        String key = ORDER_KEY + id;

        // Step 1: Check cache
        Order cached = (Order) redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.debug("CACHE HIT for order:{}", id);
            return cached;
        }

        // Step 2: Cache miss — load from DB
        log.debug("CACHE MISS for order:{} — hitting database", id);
        Order order = simulateDbFetch(id);

        // Step 3: Store with DYNAMIC TTL based on business logic
        // Senior insight: delivered orders don't change → cache longer
        Duration ttl = switch (order.getStatus()) {
            case PENDING, CONFIRMED  -> Duration.ofMinutes(5);   // changes often
            case PROCESSING, SHIPPED -> Duration.ofMinutes(30);  // moderate
            case DELIVERED           -> Duration.ofDays(7);      // stable
            case CANCELLED, REFUNDED -> Duration.ofDays(30);     // never changes
        };

        redisTemplate.opsForValue().set(key, order, ttl);
        log.debug("Cached order:{} with TTL={}", id, ttl);

        return order;
    }

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 2 — Hash-Based Order Storage
    // ══════════════════════════════════════════════════════════════

    /**
     * Hash storage — store order fields individually.
     *
     * When to use Hash vs String (JSON)?
     *
     * ✅ Use HASH when:
     *   - You often update individual fields (status, paid flag)
     *   - You often read only some fields (status page needs only status+ETA)
     *   - The object has many fields (saves bandwidth vs fetching full JSON)
     *
     * ✅ Use STRING/JSON when:
     *   - You always read the whole object
     *   - The object has nested lists (like our OrderItems)
     *   - Simplicity matters more than bandwidth
     *
     * In practice: store FULL order as JSON for reads, ALSO store key
     * fields in Hash for partial updates. This is a common real-world pattern.
     */
    @Override
    public void saveOrderAsHash(Order order) {
        String key = ORDER_HASH + order.getId();

        // putAll = HMSET — stores multiple fields atomically
        Map<String, Object> fields = Map.of(
                "id",            order.getId(),
                "orderNumber",   order.getOrderNumber(),
                "customerId",    order.getUserId(),
                "customerName",  order.getCustomerName(),
                "status",        order.getStatus().name(),
                "totalAmount",   order.getFinalAmount().toString(),
                "isPaid",        String.valueOf(order.isPaid()),
                "createdAt",     order.getCreatedAt().toString()
        );

        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, Duration.ofHours(2));

        log.debug("Saved order {} as Hash in Redis with {} fields", order.getId(), fields.size());
    }

    /**
     * Update ONLY the status field — without loading the full object.
     *
     * This is the KILLER FEATURE of Hash storage.
     * In JSON storage you'd have to: read → deserialize → update → serialize → write.
     * With Hash: just HSET order:hash:1001 status SHIPPED — one command, done.
     */
    @Override
    public void updateOrderStatus(Long orderId, Order.OrderStatus newStatus) {
        String key = ORDER_HASH + orderId;

        // Update only the status field — all other fields untouched
        redisTemplate.opsForHash().put(key, "status", newStatus.name());
        redisTemplate.opsForHash().put(key, "updatedAt", java.time.LocalDateTime.now().toString());

        log.debug("Updated order:{} status → {}", orderId, newStatus);

        // Also invalidate the JSON cache so next read gets fresh data
        redisTemplate.delete(ORDER_KEY + orderId);
    }

    /**
     * Read only the fields you need — HMGET — very bandwidth efficient.
     * Status tracking page needs: status, updatedAt — not the full 50-field order.
     */
    @Override
    public Map<Object, Object> getOrderStatusInfo(Long orderId) {
        String key = ORDER_HASH + orderId;
        // Get ALL hash fields
        return redisTemplate.opsForHash().entries(key);
    }

    /**
     * Get specific fields — HMGET equivalent (partial fetch)
     */
    @Override
    public Object getOrderStatusOnly(Long orderId) {
        return redisTemplate.opsForHash().get(ORDER_HASH + orderId, "status");
    }

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE — Batch Operations (the big performance trick)
    // ══════════════════════════════════════════════════════════════

    /**
     * Pipeline: send 100 Redis commands in ONE network round-trip.
     *
     * WITHOUT pipeline: 100 orders × 1ms network latency = 100ms
     * WITH pipeline:    100 orders × 1 round-trip           = 1ms
     *
     * Use pipelining for bulk loads, warmup, or batch saves.
     * Don't pipeline when you need intermediate results.
     */
    @Override
    public void warmUpOrderCache(List<Order> orders) {
        redisTemplate.executePipelined((RedisCallback<?>) connection -> {
            for (Order order : orders) {
                String key = ORDER_KEY + order.getId();
                // Inside pipeline, use connection directly
                // Spring handles serialization
                redisTemplate.opsForValue().set(key, order, Duration.ofHours(1));
            }
            return null; // pipeline callback must return null
        });
        log.info("Warmed up cache for {} orders via pipeline", orders.size());
    }

    @Override
    public void cacheOrder(Order order) {

    }

    // ── Simulate DB fetch (in real app this calls orderRepository) ──
    private Order simulateDbFetch(Long id) {
        Order order = Order.create(100L, "Ravi Kumar", "ravi@example.com");
        order.setId(id);
        order.setOrderNumber("ORD-" + id);
        order.setStatus(Order.OrderStatus.DELIVERED);
        order.setFinalAmount(new BigDecimal("1499.00"));
        order.setPaid(true);
        return order;
    }
}