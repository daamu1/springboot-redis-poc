package org.damu.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.damu.model.Order;
import org.damu.model.OrderStatus;
import org.damu.service.OrderCacheService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * ════════════════════════════════════════════════════════════════
 * USE CASE 1 — Order Caching (Cache-Aside Pattern)
 * USE CASE 2 — Hash-based Order Storage (field-level access)
 * ════════════════════════════════════════════════════════════════
 * <p>
 * SENIOR INSIGHT:
 * ---------------
 * After 3 years, I've seen teams use Redis wrong in two ways:
 * <p>
 * ❌ WRONG: Cache everything blindly — "Redis is fast so let's cache all"
 * ❌ WRONG: Cache nothing — "Redis is complex, just hit the DB"
 * <p>
 * RIGHT: Cache what is:
 * - Read-heavy (read 100x more than written)
 * - Expensive to compute (JOINs, aggregations)
 * - Tolerant to slight staleness (order list vs live payment status)
 * <p>
 * The Order entity is PERFECT for caching:
 * - Fetched on every page load, tracking page, email notification
 * - DB query involves JOINs to order_items, users, products
 * - Order data after delivery doesn't change — infinite TTL candidate
 * <p>
 * KEY NAMING STRATEGY used throughout this project:
 * -------------------------------------------------
 * order:{id}           → full order JSON (String)
 * order:{id}:fields    → order as Hash (individual fields)
 * user:{uid}:orders    → list of order IDs for a user (List)
 * order:queue          → pending orders to process (List/ZSet)
 * order:leaderboard    → top orders by amount (ZSet)
 * rate:order:{ip}      → rate limiting counter (String)
 * order:lock:{id}      → distributed lock (String with NX+EX)
 * order:status:*       → pub/sub channels
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCacheServiceImpl implements OrderCacheService {
    private static final String ORDER_KEY = "order:";
    private static final String ORDER_HASH = "order:hash:";
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Manual Cache-Aside — When you need MORE CONTROL than @Cacheable.
     * <p>
     * When to use manual over @Cacheable:
     * - Dynamic TTL based on order status (delivered = 7 days, pending = 5 min)
     * - Custom key patterns not expressible in SpEL
     * - Need to set multiple keys atomically
     * - Conditional logic before caching
     * <p>
     * This is what @Cacheable does internally, but explicitly.
     */
    @Override
    public Order getOrderWithDynamicTtl(Long id) {
        String key = ORDER_KEY + id;
        Order cached = (Order) redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.debug("CACHE HIT for order:{}", id);
            return cached;
        }
        log.debug("CACHE MISS for order:{} — hitting database", id);
        Order order = simulateDbFetch(id);

        Duration ttl = switch (order.getStatus()) {
            case PENDING, CONFIRMED -> Duration.ofMinutes(5);
            case PROCESSING, SHIPPED -> Duration.ofMinutes(30);
            case DELIVERED -> Duration.ofDays(7);
            case CANCELLED, REFUNDED -> Duration.ofDays(30);
        };

        redisTemplate.opsForValue().set(key, order, ttl);
        return order;
    }


    /**
     * Hash storage — store order fields individually.
     * <p>
     * When to use Hash vs String (JSON)?
     * <p>
     * ✅ Use HASH when:
     * - You often update individual fields (status, paid flag)
     * - You often read only some fields (status page needs only status+ETA)
     * - The object has many fields (saves bandwidth vs fetching full JSON)
     * <p>
     * ✅ Use STRING/JSON when:
     * - You always read the whole object
     * - The object has nested lists (like our OrderItems)
     * - Simplicity matters more than bandwidth
     * <p>
     * In practice: store FULL order as JSON for reads, ALSO store key
     * fields in Hash for partial updates. This is a common real-world pattern.
     */
    @Override
    public void saveOrderAsHash(Order order) {
        String key = ORDER_HASH + order.getId();
        Map<String, Object> fields = Map.of("id", order.getId(), "orderNumber", order.getOrderNumber(), "customerId", order.getUserId(), "customerName", order.getCustomerName(), "status", order.getStatus().name(), "totalAmount", order.getFinalAmount().toString(), "isPaid", String.valueOf(order.isPaid()), "createdAt", order.getCreatedAt().toString());
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, Duration.ofHours(2));
        log.debug("Saved order {} as Hash in Redis with {} fields", order.getId(), fields.size());
    }

    /**
     * Update ONLY the status field — without loading the full object.
     * <p>
     * This is the KILLER FEATURE of Hash storage.
     * In JSON storage you'd have to: read → deserialize → update → serialize → write.
     * With Hash: just HSET order:hash:1001 status SHIPPED — one command, done.
     */
    @Override
    public void updateOrderStatus(Long orderId, OrderStatus newStatus) {
        String key = ORDER_HASH + orderId;
        redisTemplate.opsForHash().put(key, "status", newStatus.name());
        redisTemplate.opsForHash().put(key, "updatedAt", LocalDateTime.now().toString());
        log.debug("Updated order:{} status → {}", orderId, newStatus);
        redisTemplate.delete(ORDER_KEY + orderId);
    }


    /**
     * Get specific fields — HMGET equivalent (partial fetch)
     */
    @Override
    public Object getOrderStatusOnly(Long orderId) {
        return redisTemplate.opsForHash().get(ORDER_HASH + orderId, "status");
    }


    @Override
    public void cacheOrder(Order order) {
        String key = ORDER_KEY + order.getId();
        if (order.getStatus() ==OrderStatus.PENDING) {
            log.debug("Skipping cache for PENDING order:{}", order.getId());
            return;
        }
        Duration ttl = switch (order.getStatus()) {
            case CONFIRMED -> Duration.ofMinutes(5);
            case PROCESSING, SHIPPED -> Duration.ofMinutes(30);
            case DELIVERED -> Duration.ofDays(7);
            case CANCELLED, REFUNDED -> Duration.ofDays(30);
            default -> Duration.ofMinutes(10);
        };

        redisTemplate.opsForValue().set(key, order, ttl);
        log.debug("Cached order:{} with TTL={}", order.getId(), ttl);
    }

    private Order simulateDbFetch(Long id) {
        Order order = Order.create(100L, "Ravi Kumar", "ravi@example.com");
        order.setId(id);
        order.setOrderNumber("ORD-" + id);
        order.setStatus(OrderStatus.DELIVERED);
        order.setFinalAmount(new BigDecimal("1499.00"));
        order.setPaid(true);
        return order;
    }
}