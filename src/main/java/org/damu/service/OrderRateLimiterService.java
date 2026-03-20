package org.damu.service;

public interface OrderRateLimiterService {
    boolean allowPlaceOrder(Long userId, int maxPerHour);

    boolean allowByIp(String clientIp, int maxPerMinute);

    boolean allowSlidingWindow(Long userId, int maxRequests, long windowMs);

    String acquireLock(String resource, int ttlSeconds);

    boolean releaseLock(String resource, String token);

    String tryLockWithRetry(String resource, int ttlSeconds, int maxRetries, long retryDelayMs);

    String placeOrderSafely(Long userId, String orderData);
}
