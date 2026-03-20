package org.damu.service;

import org.damu.model.Order;


public interface OrderCacheService {
    Order getOrderWithDynamicTtl(Long id);

    void saveOrderAsHash(Order order);

    void updateOrderStatus(Long orderId, Order.OrderStatus newStatus);

    Object getOrderStatusOnly(Long orderId);

    void cacheOrder(Order order);
}