package org.damu.service;

import org.damu.model.Order;
import org.damu.model.OrderStatus;


public interface OrderCacheService {
    Order getOrderWithDynamicTtl(Long id);

    void saveOrderAsHash(Order order);

    void updateOrderStatus(Long orderId, OrderStatus newStatus);

    Object getOrderStatusOnly(Long orderId);

    void cacheOrder(Order order);
}