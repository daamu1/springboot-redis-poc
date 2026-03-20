package org.damu.service;

import org.damu.model.Order;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Map;

public interface OrderPubSubService {
    void publishOrderStatusChange(Order order, Order.OrderStatus oldStatus);

    void broadcastCacheInvalidation(Long orderId);

    RedisMessageListenerContainer createListenerContainer(
            org.springframework.data.redis.connection.RedisConnectionFactory factory);

    void addToCart(Long userId, Long productId, int quantity);

    Map<Object, Object> getCart(Long userId);

    String createAuthToken(Long userId, String role);

    Map<Object, Object> validateToken(String token);

    void revokeToken(String token);

    void clearCart(Long userId);
}
