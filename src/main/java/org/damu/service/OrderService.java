package org.damu.service;

import org.damu.model.Order;
import org.damu.model.OrderStatus;
import org.damu.model.PlaceOrderResult;

import java.util.List;
import java.util.Map;

public interface OrderService {
    PlaceOrderResult placeOrder(Long userId, String customerName, String idempotencyKey);

    String createAndPersistOrder(Long userId, String customerName);

    Order getOrder(Long orderId);

    Map<String, Object> getOrderStatus(Long orderId);

    void updateOrderStatus(Long orderId, OrderStatus newStatus, String reason);

    Map<String, Object> getQueueDepth();

    Order processNextInQueue(long timeoutSeconds);
    
    Map<String, Object> addToCart(Long userId, Long productId, int quantity);

    Map<Object, Object> getCart(Long userId);

    void clearCart(Long userId);

    Map<String, Object> login(Long userId, String role);

    Map<Object, Object> validateToken(String token);

    void logout(String token);

    List<Map<String, Object>> getTopCustomers(int n);

    Map<String, Object> getLiveDashboard();
}
