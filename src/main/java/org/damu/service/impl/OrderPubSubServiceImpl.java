package org.damu.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.damu.model.Order;
import org.damu.service.OrderPubSubService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ════════════════════════════════════════════════════════════════
 * USE CASE 7 — Order Status Pub/Sub (Real-Time Notifications)
 * USE CASE 8 — Session Store (User Cart + Auth Token)
 * ════════════════════════════════════════════════════════════════
 * <p>
 * SENIOR INSIGHT on Pub/Sub:
 * ---------------------------
 * Redis Pub/Sub is "fire and forget":
 * - Publisher doesn't know who is listening
 * - If subscriber is offline when message sent → message is LOST
 * - No persistence, no replay
 * <p>
 * Use Redis Pub/Sub for:
 * ✅ Cache invalidation across servers (broadcast "delete order:1001 from cache")
 * ✅ Real-time dashboards (admin sees order status change live)
 * ✅ WebSocket fan-out (push order update to customer's browser)
 * ✅ Microservice notifications (shipping service notified when order paid)
 * <p>
 * DON'T use Redis Pub/Sub for:
 * ❌ Critical events you can't afford to lose → use Streams or Kafka
 * ❌ Work queues (use Redis List instead)
 * ❌ Request-reply patterns
 * <p>
 * CHANNEL NAMING (follow this convention):
 * order:status:SHIPPED   → broadcast when any order gets shipped
 * order:user:123         → messages for user 123 only
 * order:*                → wildcard pattern subscription
 */
@Service
public class OrderPubSubServiceImpl implements OrderPubSubService {

    private static final Logger log = LoggerFactory.getLogger(OrderPubSubServiceImpl.class);

    // Channel patterns
    private static final String CH_ORDER_STATUS = "order:status:";  // + status name
    private static final String CH_ORDER_USER = "order:user:";    // + userId
    private static final String CH_ALL_ORDERS = "order:*";        // wildcard
    // Log reference for OrderShippedHandler and OrderAuditHandler
    private static final Logger log2 = LoggerFactory.getLogger("OrderEventHandlers");
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 7A — Publisher: Order Status Events
    // ══════════════════════════════════════════════════════════════
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Publish order status change event to Redis channel.
     * <p>
     * Who subscribes?
     * - Email service → sends "Your order has been shipped!" email
     * - WebSocket gateway → pushes update to customer's browser
     * - Analytics service → records status transition
     * - Inventory service → reserves/releases stock
     * <p>
     * All of these can react to the same published event WITHOUT
     * the order service knowing about any of them. Loose coupling!
     */
    @Override
    public void publishOrderStatusChange(Order order, Order.OrderStatus oldStatus) {
        try {
            // Build event payload
            Map<String, Object> event = new HashMap<>();
            event.put("orderId", order.getId());
            event.put("orderNumber", order.getOrderNumber());
            event.put("userId", order.getUserId());
            event.put("oldStatus", oldStatus.name());
            event.put("newStatus", order.getStatus().name());
            event.put("timestamp", System.currentTimeMillis());
            event.put("amount", order.getFinalAmount());

            String json = objectMapper.writeValueAsString(event);

            // Publish to status-specific channel (e.g., order:status:SHIPPED)
            String statusChannel = CH_ORDER_STATUS + order.getStatus().name();
            Long subscriberCount1 = redisTemplate.convertAndSend(statusChannel, json);

            // Also publish to user-specific channel (e.g., order:user:123)
            String userChannel = CH_ORDER_USER + order.getUserId();
            Long subscriberCount2 = redisTemplate.convertAndSend(userChannel, json);

            log.info("Published order event {} → channels: {} ({} subs) & {} ({} subs)", order.getOrderNumber(), statusChannel, subscriberCount1, userChannel, subscriberCount2);

        } catch (Exception e) {
            log.error("Failed to publish order event for order {}", order.getId(), e);
            // IMPORTANT: Don't let pub/sub failure break the main flow!
            // Pub/Sub is auxiliary — the order is already saved to DB.
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 7B — Subscriber Configuration (in a @Configuration class)
    // ══════════════════════════════════════════════════════════════

    /**
     * Publish cache invalidation — broadcast to ALL servers.
     * <p>
     * When order is updated on Server A:
     * - Server A updates DB + refreshes its own cache
     * - Server A publishes "invalidate:order:1001" to Redis
     * - Server B & C receive message → delete order:1001 from their local caches
     * <p>
     * This is how you keep cache consistent across MULTIPLE instances.
     * Without this, servers B and C serve stale cached data for up to TTL duration.
     */
    @Override
    public void broadcastCacheInvalidation(Long orderId) {
        redisTemplate.convertAndSend("cache:invalidate:order", orderId.toString());
        log.debug("Broadcast cache invalidation for order:{}", orderId);
    }

    /**
     * NOTE: In a real Spring Boot app, subscriber setup goes in a
     *
     * @Configuration class. Shown here inline for learning purposes.
     * <p>
     * RedisMessageListenerContainer is the HEART of subscription.
     * It:
     * - Maintains a dedicated connection to Redis (not from the pool)
     * - Listens for messages on subscribed channels
     * - Dispatches messages to your handler methods
     * - Reconnects automatically if Redis connection drops
     */
    @Override
    public RedisMessageListenerContainer createListenerContainer(org.springframework.data.redis.connection.RedisConnectionFactory factory) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);

        // Handler for order shipped events
        container.addMessageListener(new MessageListenerAdapter(new OrderShippedHandler()), new ChannelTopic(CH_ORDER_STATUS + "SHIPPED")  // exact channel
        );

        // Handler for ALL order events (wildcard pattern)
        container.addMessageListener(new MessageListenerAdapter(new OrderAuditHandler()), new PatternTopic("order:*")  // matches order:status:*, order:user:*, etc.
        );

        return container;
    }

    /**
     * User Cart as Redis Hash.
     * <p>
     * Why Hash for cart?
     * - Cart = {productId → quantity} map — perfect Hash fit
     * - Add item: HSET cart:user:123 "prod:456" 2
     * - Remove item: HDEL cart:user:123 "prod:456"
     * - Update qty: HSET cart:user:123 "prod:456" 5 (overwrites)
     * - Get all items: HGETALL cart:user:123
     * <p>
     * TTL = 7 days (cart expires after a week of inactivity)
     * Reset TTL on every cart operation (sliding expiry)
     */
    @Override
    public void addToCart(Long userId, Long productId, int quantity) {
        String cartKey = "cart:user:" + userId;
        String field = "product:" + productId;

        if (quantity <= 0) {
            redisTemplate.opsForHash().delete(cartKey, field);
        } else {
            redisTemplate.opsForHash().put(cartKey, field, quantity);
        }

        // Sliding TTL — cart stays alive as long as user is active
        redisTemplate.expire(cartKey, java.time.Duration.ofDays(7));
        log.debug("Cart updated for user {} → product {} qty={}", userId, productId, quantity);
    }

    // ══════════════════════════════════════════════════════════════
    //  USE CASE 8 — Session Store (Cart + Auth Token)
    // ══════════════════════════════════════════════════════════════

    @Override
    public Map<Object, Object> getCart(Long userId) {
        return redisTemplate.opsForHash().entries("cart:user:" + userId);
    }

    @Override
    public void clearCart(Long userId) {
        redisTemplate.delete("cart:user:" + userId);
    }

    /**
     * Auth Token Storage — simple but effective.
     * <p>
     * After login → generate token → store in Redis with userId as value.
     * On every API request → look up token → get userId.
     * <p>
     * Why Redis over DB for tokens?
     * - Token lookup on EVERY API call → must be microseconds, not milliseconds
     * - Redis GET = ~0.1ms | DB SELECT = ~10ms → 100x faster
     * - Auto-expiry handles logout-by-timeout automatically
     * - DEL key = instant logout (try that with JWT!)
     */
    @Override
    public String createAuthToken(Long userId, String role) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = "auth:token:" + token;

        Map<String, Object> session = new HashMap<>();
        session.put("userId", userId);
        session.put("role", role);
        session.put("createdAt", System.currentTimeMillis());
        session.put("ip", "127.0.0.1"); // in real app, get from request

        redisTemplate.opsForHash().putAll(key, session);
        redisTemplate.expire(key, java.time.Duration.ofHours(24));

        log.debug("Auth token created for user {} (expires 24h)", userId);
        return token;
    }

    @Override
    public Map<Object, Object> validateToken(String token) {
        String key = "auth:token:" + token;
        Map<Object, Object> session = redisTemplate.opsForHash().entries(key);

        if (session.isEmpty()) return null; // expired or invalid

        // Sliding session — reset TTL on activity
        redisTemplate.expire(key, java.time.Duration.ofHours(24));
        return session;
    }

    @Override
    public void revokeToken(String token) {
        redisTemplate.delete("auth:token:" + token);
        log.debug("Token revoked: {}", token.substring(0, 8));
    }

    /**
     * Handler for SHIPPED orders — triggers email + tracking notification
     */
    private static class OrderShippedHandler implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            String payload = new String(message.getBody());
            log.info("[SHIPPED HANDLER] Received: {}", payload);
            // → Send "Your order has been shipped!" email
            // → Update tracking page
            // → Notify delivery partner API
        }
    }

    /**
     * Audit handler — logs all order events for compliance
     */
    private static class OrderAuditHandler implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            log.info("[AUDIT] channel={} | payload={}", new String(message.getChannel()), new String(message.getBody()));
            // → Write to audit_log table
            // → Send to analytics pipeline
        }
    }
}