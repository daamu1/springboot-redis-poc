package org.damu.service;

public interface OrderRateLimiterService {
    boolean allowPlaceOrder(Long userId, int maxPerHour);
}
