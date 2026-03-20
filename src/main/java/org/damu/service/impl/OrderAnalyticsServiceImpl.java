package org.damu.service.impl;

import org.damu.model.Order;
import org.damu.service.OrderAnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ════════════════════════════════════════════════════════════════
 *  USE CASE 9  — Order Leaderboard (Top Customers by Spend)
 *  USE CASE 10 — Idempotency Keys (Prevent Duplicate Orders)
 *  USE CASE 11 — Order Analytics Counters (Real-time Stats)
 * ════════════════════════════════════════════════════════════════
 *
 *  These three patterns are used in EVERY large-scale order system.
 *  Master these and you'll be ahead of 90% of developers.
 */
@Service
public class OrderAnalyticsServiceImpl implements OrderAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(OrderAnalyticsServiceImpl.class);

    // Keys
    private static final String LB_TOTAL_SPEND   = "leaderboard:customers:spend";
    private static final String LB_ORDER_COUNT   = "leaderboard:customers:orders";
    private static final String IDEMPOTENCY_KEY  = "idempotency:order:";
    private static final String COUNTER_DAILY    = "counter:orders:daily:";
    private static final String COUNTER_STATUS   = "counter:orders:status:";
    private static final String COUNTER_REVENUE  = "counter:revenue:daily:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 9 — Leaderboard using ZSet
    // ══════════════════════════════════════════════════════════════

    /**
     * Record order completion — update customer leaderboard.
     *
     * ZINCRBY is the key command:
     * - If customer exists → add amount to existing score
     * - If customer is new → create with score = amount
     * - O(log N) — doesn't slow down with 1M customers
     *
     * This is called on EVERY successful order delivery.
     * The leaderboard is always up-to-date, no batch job needed.
     */
    @Override
    public void recordOrderForLeaderboard(Order order) {
        double amount = order.getFinalAmount().doubleValue();
        String customerId = "user:" + order.getUserId();
        String customerLabel = order.getCustomerName() + "#" + order.getUserId();

        // Increment total spend leaderboard
        Double newScore = redisTemplate.opsForZSet()
                .incrementScore(LB_TOTAL_SPEND, customerLabel, amount);

        // Increment order count leaderboard
        redisTemplate.opsForZSet().incrementScore(LB_ORDER_COUNT, customerLabel, 1);

        log.debug("Leaderboard updated: {} | totalSpend={}", customerLabel, newScore);
    }

    /**
     * Get Top N customers by total spend.
     *
     * ZREVRANGE → highest score first (DESC order)
     * WITHSCORES → include the actual score value
     *
     * Returns: [{customer: "Ravi#123", spend: 49850.00}, ...]
     */
    @Override
    public List<Map<String, Object>> getTopCustomersBySpend(int topN) {
        Set<ZSetOperations.TypedTuple<Object>> results =
                redisTemplate.opsForZSet().reverseRangeWithScores(LB_TOTAL_SPEND, 0, topN - 1);

        List<Map<String, Object>> leaderboard = new ArrayList<>();
        int rank = 1;

        for (ZSetOperations.TypedTuple<Object> tuple : results) {
            leaderboard.add(Map.of(
                    "rank",     rank++,
                    "customer", tuple.getValue(),
                    "spend",    String.format("₹%.2f", tuple.getScore()),
                    "score",    tuple.getScore()
            ));
        }
        return leaderboard;
    }

    /**
     * Get customer's rank and score in one operation.
     * ZREVRANK = rank in descending order (rank 0 = highest spender)
     */
    @Override
    public Map<String, Object> getCustomerRank(Long userId, String name) {
        String customerLabel = name + "#" + userId;

        Long rank  = redisTemplate.opsForZSet().reverseRank(LB_TOTAL_SPEND, customerLabel);
        Double score = redisTemplate.opsForZSet().score(LB_TOTAL_SPEND, customerLabel);

        return Map.of(
                "rank",       rank != null ? rank + 1 : -1,  // 1-indexed
                "totalSpend", score != null ? score : 0.0,
                "customer",   customerLabel
        );
    }

    /**
     * Top 3 for THIS MONTH only.
     *
     * Senior pattern: use a monthly key that auto-resets.
     * Key: leaderboard:customers:spend:2024-01
     * Expire it at end of month (or just let it live alongside annual board).
     */
    @Override
    public void recordMonthlySpend(Order order) {
        String monthKey = "leaderboard:monthly:" +
                java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        String customerLabel = order.getCustomerName() + "#" + order.getUserId();

        redisTemplate.opsForZSet()
                .incrementScore(monthKey, customerLabel, order.getFinalAmount().doubleValue());

        // Set TTL to expire 60 days after month ends (plenty of time for reports)
        // Only set on first entry (when size becomes 1)
        Long size = redisTemplate.opsForZSet().size(monthKey);
        if (size != null && size == 1) {
            redisTemplate.expire(monthKey, Duration.ofDays(60));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 10 — Idempotency Keys
    // ══════════════════════════════════════════════════════════════

    /**
     * IDEMPOTENCY — The unsung hero of reliable order systems.
     *
     * Problem: Client sends "Place Order" request.
     * Network timeout. Client doesn't know if it worked.
     * Client RETRIES. Now you have DUPLICATE ORDERS.
     *
     * Solution: Client generates a unique idempotency key (UUID)
     * and sends it with every request. Server stores the result
     * against this key. On retry, server returns the SAME result.
     *
     * This is how Stripe, Razorpay, and every payment API works.
     *
     * Flow:
     * 1. Client: POST /orders  Idempotency-Key: abc-123
     * 2. Server: check Redis for "idempotency:order:abc-123"
     * 3. Not found → process order → store result in Redis → return 201
     * 4. Client retries: POST /orders  Idempotency-Key: abc-123
     * 5. Server: found in Redis → return SAME 201 response → order NOT created again
     *
     * TTL = 24 hours (standard). Client should use same key for 24h retries.
     */
    @Override
    public String processWithIdempotency(String idempotencyKey, Long userId,
                                         java.util.function.Supplier<String> orderCreator) {
        String redisKey = IDEMPOTENCY_KEY + idempotencyKey;

        // Step 1: Check if we already processed this request
        Object cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            log.info("IDEMPOTENT: returning cached result for key={}", idempotencyKey);
            return cached.toString();  // Return SAME result as before
        }

        // Step 2: LOCK on idempotency key (prevent concurrent same-key requests)
        String lockKey = "lock:idempotency:" + idempotencyKey;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "locked", Duration.ofSeconds(30));

        if (!Boolean.TRUE.equals(locked)) {
            return "PROCESSING: Request is being processed, please retry in a moment";
        }

        try {
            // Step 3: Double-check after acquiring lock (classic double-checked locking)
            cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) return cached.toString();

            // Step 4: Actually process the order
            String result = orderCreator.get();

            // Step 5: Cache the result for 24 hours
            redisTemplate.opsForValue().set(redisKey, result, Duration.ofHours(24));
            log.info("Created order, cached idempotency key={} for 24h", idempotencyKey);

            return result;

        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 11 — Real-Time Analytics Counters
    // ══════════════════════════════════════════════════════════════

    /**
     * Atomic counters — O(1), no DB writes, real-time.
     *
     * INCR is atomic in Redis. No race conditions.
     * 1000 concurrent requests can all call INCR → perfect count.
     * No transaction, no lock needed.
     *
     * Use these for live dashboards that show:
     * - "Today: 1,247 orders placed"
     * - "Orders pending: 34"
     * - "Revenue today: ₹2,47,350"
     */
    @Override
    public void recordOrderPlaced(Order order) {
        String today = java.time.LocalDate.now().toString(); // "2024-01-15"

        // Increment daily order count
        String dailyKey = COUNTER_DAILY + today;
        redisTemplate.opsForValue().increment(dailyKey);
        redisTemplate.expire(dailyKey, Duration.ofDays(90)); // keep 90 days history

        // Increment status counter
        String statusKey = COUNTER_STATUS + order.getStatus().name();
        redisTemplate.opsForValue().increment(statusKey);

        // Accumulate revenue (stored as paise/cents to avoid float precision)
        String revenueKey = COUNTER_REVENUE + today;
        long amountInPaise = order.getFinalAmount()
                .multiply(java.math.BigDecimal.valueOf(100))
                .longValue();
        redisTemplate.opsForValue().increment(revenueKey, amountInPaise);
        redisTemplate.expire(revenueKey, Duration.ofDays(90));

        log.debug("Counters updated for order {}", order.getOrderNumber());
    }

    /**
     * Get live dashboard stats — all from Redis, no DB query.
     *
     * In production, this powers the admin dashboard that refreshes every 5 seconds.
     * Zero DB load, all from Redis counters.
     */
    @Override
    public Map<String, Object> getLiveDashboard() {
        String today = java.time.LocalDate.now().toString();

        Object ordersToday = redisTemplate.opsForValue().get(COUNTER_DAILY + today);
        Object revenueToday = redisTemplate.opsForValue().get(COUNTER_REVENUE + today);
        Object pendingCount = redisTemplate.opsForValue().get(COUNTER_STATUS + "PENDING");
        Object processingCount = redisTemplate.opsForValue().get(COUNTER_STATUS + "PROCESSING");
        Object deliveredCount = redisTemplate.opsForValue().get(COUNTER_STATUS + "DELIVERED");

        long revenuePaise = revenueToday != null ? Long.parseLong(revenueToday.toString()) : 0;

        return Map.of(
                "ordersToday",    ordersToday != null ? ordersToday : 0,
                "revenueToday",   String.format("₹%.2f", revenuePaise / 100.0),
                "pendingOrders",  pendingCount != null ? pendingCount : 0,
                "processing",     processingCount != null ? processingCount : 0,
                "delivered",      deliveredCount != null ? deliveredCount : 0,
                "timestamp",      System.currentTimeMillis()
        );
    }

    /**
     * HyperLogLog — count UNIQUE customers who placed orders today.
     *
     * Regular SET would store every userId → memory grows with users.
     * HyperLogLog stores ONLY a 12KB sketch → estimates unique count
     * with ~0.81% error. Perfect for analytics.
     *
     * You can have 1 billion unique users and it still uses 12KB.
     */
    @Override
    public void trackUniqueCustomer(Long userId) {
        String today = java.time.LocalDate.now().toString();
        String hllKey = "hll:unique:customers:" + today;
        redisTemplate.opsForHyperLogLog().add(hllKey, "user:" + userId);
        redisTemplate.expire(hllKey, Duration.ofDays(30));
    }

    @Override
    public Long countUniqueCustomersToday() {
        String today = java.time.LocalDate.now().toString();
        return redisTemplate.opsForHyperLogLog().size("hll:unique:customers:" + today);
    }
}