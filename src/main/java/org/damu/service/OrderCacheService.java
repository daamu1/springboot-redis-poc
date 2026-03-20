package org.damu.service;

import org.damu.model.Order;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

import java.util.List;
import java.util.Map;

public interface OrderCacheService {
    @Cacheable(value = "orders", key = "#id", unless = "#result != null && #result.status.name() == 'PENDING'")
    Order getOrder(Long id);

    @CachePut(value = "orders", key = "#order.id")
    Order updateOrder(Order order);

    @Caching(evict = {@CacheEvict(value = "orders", key = "#id"), @CacheEvict(value = "userOrders", key = "#userId")})
    void cancelOrder(Long id, Long userId, String reason);

    @CacheEvict(value = "orders", allEntries = true)
    void clearAllOrderCache();

    Order getOrderWithDynamicTtl(Long id);

    void saveOrderAsHash(Order order);

    void updateOrderStatus(Long orderId, Order.OrderStatus newStatus);

    Map<Object, Object> getOrderStatusInfo(Long orderId);

    Object getOrderStatusOnly(Long orderId);

    void warmUpOrderCache(List<Order> orders);

    void cacheOrder(Order order);
}
