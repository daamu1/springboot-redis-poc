package org.damu.service.impl;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.damu.exception.RateLimitExceededException;
import org.damu.model.Order;
import org.damu.model.OrderItem;
import org.damu.model.OrderStatus;
import org.damu.model.PlaceOrderResult;
import org.damu.service.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ════════════════════════════════════════════════════════════════
 * ORDER SERVICE — The Single Orchestrator
 * ════════════════════════════════════════════════════════════════
 * <p>
 * ARCHITECTURE RULE (30 years experience):
 * -----------------------------------------
 * Controller  → only HTTP concerns (parse request, return response)
 * Service     → ALL business logic + orchestration
 * Repository  → only data access (DB / Redis)
 * <p>
 * Your original controller was calling 5 different services directly.
 * That makes the controller a "god controller" — it knows too much.
 * If you change the caching strategy tomorrow, you'd touch the controller.
 * That's wrong. Controller should NOT know Redis even exists.
 * <p>
 * This OrderService is the ONLY class the controller talks to.
 * It internally delegates to Redis services — controller doesn't care.
 * <p>
 * Dependency graph:
 * <p>
 * OrderController
 * └── OrderService  ← this file
 * ├── OrderCacheService       (UC1, UC2)
 * ├── OrderQueueService       (UC3, UC4)
 * ├── OrderRateLimiterService (UC5, UC6)
 * ├── OrderPubSubService      (UC7, UC8)
 * └── OrderAnalyticsService   (UC9, UC10, UC11)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderCacheService cacheService;
    private final OrderQueueService queueService;
    private final OrderRateLimiterService rateLimiter;
    private final OrderPubSubService pubSubService;
    private final OrderAnalyticsService analyticsService;

    /**
     * Place a new order — every Redis use case fires here.
     *
     * @throws RateLimitExceededException if user exceeded their order rate limit
     */
    @Override
    public PlaceOrderResult placeOrder(Long userId, String customerName, String idempotencyKey) {
        if (!rateLimiter.allowPlaceOrder(userId, 5)) {
            log.warn("Rate limit exceeded for userId={}", userId);
            throw new RateLimitExceededException("Too many orders. Max 5 per minute.");
        }
        String orderNumber = analyticsService.processWithIdempotency(idempotencyKey, userId, () -> createAndPersistOrder(userId, customerName));
        log.info("Order placed: orderNumber={} userId={}", orderNumber, userId);
        return new PlaceOrderResult(orderNumber, idempotencyKey);
    }

    /**
     * Actual order creation — called ONCE per idempotency key.
     * On any retry, processWithIdempotency returns the cached result
     * before this method is ever invoked again.
     */
    @Override
    public String createAndPersistOrder(Long userId, String customerName) {
        Order order = buildOrder(userId, customerName);
        // ── UC3: Enqueue for async processing ─────────────────────
        // Redis List queue — consumer thread processes payment, updates DB.
        // This keeps the HTTP response fast — we don't block on all that.
        queueService.enqueueOrder(order);
        // ── UC7: Publish status change event ─────────────────────
        // Email service, WebSocket gateway, analytics all listen.
        // OrderService doesn't know or care who — loose coupling.
        pubSubService.publishOrderStatusChange(order, null);
        // ── UC11: Increment live counters (atomic INCR — no DB write)
        analyticsService.recordOrderPlaced(order);
        analyticsService.trackUniqueCustomer(userId);
        // ── UC1: Cache full order JSON (dynamic TTL by status) ────
        cacheService.cacheOrder(order);
        // ── UC2: Store as Hash for partial field reads ────────────
        // Status-tracking page only needs 2 fields — HGET, not full JSON
        cacheService.saveOrderAsHash(order);
        // ── UC9: Update customer spend leaderboard ────────────────
        analyticsService.recordOrderForLeaderboard(order);
        return order.getOrderNumber();
    }


    /**
     * Fetch order by ID — cache-aside with dynamic TTL.
     * DELIVERED orders cached 7 days, PENDING only 5 min.
     */
    @Override
    public Order getOrder(Long orderId) {
        return cacheService.getOrderWithDynamicTtl(orderId);
    }

    /**
     * Fetch only the status field — uses Hash HGET, not full JSON load.
     * Fast and bandwidth-efficient for the order-tracking page.
     */
    @Override
    public Map<String, Object> getOrderStatus(Long orderId) {
        Object status = cacheService.getOrderStatusOnly(orderId);
        return Map.of("orderId", orderId, "status", status != null ? status : "NOT_FOUND");
    }

    /**
     * Update order status.
     * - Updates Hash field directly (no full object rewrite)
     * - Invalidates JSON cache so next read is fresh
     * - Publishes event so all subscribers are notified
     */
    @Override
    public void updateOrderStatus(Long orderId, OrderStatus newStatus, String reason) {
        Order order = cacheService.getOrderWithDynamicTtl(orderId);
        OrderStatus oldStatus = order.getStatus();
        cacheService.updateOrderStatus(orderId, newStatus);
        order.setStatus(newStatus);
        order.setStatusReason(reason);
        pubSubService.publishOrderStatusChange(order, oldStatus);
        log.info("Order {} status: {} → {}", orderId, oldStatus, newStatus);
    }
    @Override
    public Map<String, Object> getQueueDepth() {
        return Map.of("pending", queueService.getQueueDepth(), "scheduled", queueService.getScheduledCount());
    }

    /**
     * Pull next order from queue (blocking — waits up to timeoutSeconds).
     * Returns null if queue is still empty after timeout.
     */
    @Override
    public Order processNextInQueue(long timeoutSeconds) {
        Order order = queueService.blockingDequeue(timeoutSeconds);
        if (order != null) {
            log.info("Dequeued order for processing: {}", order.getOrderNumber());
            queueService.acknowledgeOrder(order);
        }
        return order;
    }

    @Override
    public Map<String, Object> addToCart(Long userId, Long productId, int quantity) {
        pubSubService.addToCart(userId, productId, quantity);
        Map<Object, Object> cart = pubSubService.getCart(userId);
        return Map.of("cart", cart, "itemCount", cart.size());
    }

    @Override
    public Map<Object, Object> getCart(Long userId) {
        return pubSubService.getCart(userId);
    }

    @Override
    public void clearCart(Long userId) {
        pubSubService.clearCart(userId);
    }
    /**
     * Create auth token post-login. Stored in Redis Hash, 24h sliding TTL.
     */
    @Override
    public Map<String, Object> login(Long userId, String role) {
        String token = pubSubService.createAuthToken(userId, role);
        log.info("Auth token created for userId={}", userId);
        return Map.of("token", token, "userId", userId);
    }

    /**
     * Validate token — O(1) HGETALL. Called on every request, must be fast.
     */
    @Override
    public Map<Object, Object> validateToken(String token) {
        return pubSubService.validateToken(token);
    }

    @Override
    public void logout(String token) {
        pubSubService.revokeToken(token);
        log.info("Token revoked: {}", token.substring(0, 8));
    }

    /**
     * Top N customers by total spend — ZSet ZREVRANGE with scores.
     */
    @Override
    public List<Map<String, Object>> getTopCustomers(int n) {
        return analyticsService.getTopCustomersBySpend(n);
    }

    /**
     * Live dashboard — all Redis counters, zero DB queries.
     */
    @Override
    public Map<String, Object> getLiveDashboard() {
        Map<String, Object> stats = new java.util.HashMap<>(analyticsService.getLiveDashboard());
        stats.put("uniqueCustomersToday", analyticsService.countUniqueCustomersToday());
        return stats;
    }

    private Order buildOrder(Long userId, String customerName) {
        Order order = Order.create(userId, customerName, customerName + "@example.com");
        order.setId(System.currentTimeMillis());
        order.setOrderNumber("ORD-" + System.currentTimeMillis());
        order.setPaymentMethod("UPI");
        order.setPriority(1);
        order.setShippingAddress("123 Main St");
        order.setCity("Delhi");
        order.setPincode("110001");

        OrderItem item = new OrderItem(101L, "Laptop Stand", 2, new BigDecimal("749.00"));
        order.getItems().add(item);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.recalculate();
        return order;
    }

}