package org.damu.service;

import org.damu.model.Order;

import java.util.List;
import java.util.Map;

public interface   OrderAnalyticsService{

    void recordOrderForLeaderboard(Order order);

    List<Map<String, Object>> getTopCustomersBySpend(int topN);

    Map<String, Object> getCustomerRank(Long userId, String name);

    void recordMonthlySpend(Order order);

    String processWithIdempotency(String idempotencyKey, Long userId,
                                  java.util.function.Supplier<String> orderCreator);

    void recordOrderPlaced(Order order);

    Map<String, Object> getLiveDashboard();

    void trackUniqueCustomer(Long userId);

    Long countUniqueCustomersToday();
}
