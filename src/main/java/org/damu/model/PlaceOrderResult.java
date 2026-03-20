package org.damu.model;

/**
 * Immutable result for placeOrder.
 * Record = Java 16+ — clean, no boilerplate.
 */
public record PlaceOrderResult(String orderNumber, String idempotencyKey) {
}
