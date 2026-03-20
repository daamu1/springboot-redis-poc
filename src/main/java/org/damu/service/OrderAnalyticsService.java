package org.damu.service;

import org.damu.model.Order;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public interface OrderAnalyticsService {

    void recordOrderForLeaderboard(Order order);

    List<Map<String, Object>> getTopCustomersBySpend(int topN);

    String processWithIdempotency(String idempotencyKey, Long userId, Supplier<String> orderCreator);

    void recordOrderPlaced(Order order);

    Map<String, Object> getLiveDashboard();

    void trackUniqueCustomer(Long userId);

    Long countUniqueCustomersToday();
}
