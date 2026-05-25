package org.damu.controller;

import lombok.RequiredArgsConstructor;
import org.damu.model.Order;
import org.damu.model.OrderStatus;
import org.damu.model.PlaceOrderResult;
import org.damu.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <p>
 * WHAT THIS CLASS IS RESPONSIBLE FOR:
 * ✅ Parsing HTTP request (headers, path vars, body)
 * ✅ Calling OrderService (ONE dependency only)
 * ✅ Mapping results to HTTP responses
 * ✅ Mapping domain exceptions to HTTP status codes
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;


    /**
     * POST /api/orders/place
     * <p>
     * Headers:
     * X-User-Id        : 123
     * X-Customer-Name  : Ravi Kumar
     * Idempotency-Key  : abc-uuid-123   (optional — generated if missing)
     */
    @PostMapping("/place")
    public ResponseEntity<Map<String, Object>> placeOrder(@RequestHeader("X-User-Id") Long userId, @RequestHeader("X-Customer-Name") String customerName, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String iKey = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        PlaceOrderResult result = orderService.placeOrder(userId, customerName, iKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("orderNumber", result.orderNumber(), "idempotencyKey", result.idempotencyKey(), "message", "Order placed successfully"));
    }

    /**
     * GET /api/orders/{orderId}
     * Returns full order JSON — served from Redis cache on hit.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }


    /**
     * GET /api/orders/{orderId}/status
     * Returns only the status field — uses Hash HGET (no full object load).
     */
    @GetMapping("/{orderId}/status")
    public ResponseEntity<Map<String, Object>> getOrderStatus(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getOrderStatus(orderId));
    }

    /**
     * PATCH /api/orders/{orderId}/status
     * Update status + publish event to all subscribers.
     */
    @PatchMapping("/{orderId}/status")
    public ResponseEntity<Void> updateOrderStatus(@PathVariable Long orderId, @RequestParam OrderStatus newStatus, @RequestParam(required = false, defaultValue = "") String reason) {

        orderService.updateOrderStatus(orderId, newStatus, reason);
        return ResponseEntity.noContent().build();
    }


    /**
     * GET /api/orders/queue/depth
     */
    @GetMapping("/queue/depth")
    public ResponseEntity<Map<String, Object>> getQueueDepth() {
        return ResponseEntity.ok(orderService.getQueueDepth());
    }


    /**
     * POST /api/orders/queue/process-next — pull and process next order
     */
    @PostMapping("/queue/process-next")
    public ResponseEntity<Map<String, Object>> processNext() {
        Order order = orderService.processNextInQueue(5);
        Optional.ofNullable(order).ifPresent(order1 -> order1.setStatus(OrderStatus.PROCESSING));
        if (order == null) {
            return ResponseEntity.ok(Map.of("message", "Queue is empty"));
        }
        return ResponseEntity.ok(Map.of("message", "Processed successfully", "orderNumber", order.getOrderNumber()));
    }


    /**
     * POST /api/orders/cart/{userId}/add?productId=101&quantity=2
     */
    @PostMapping("/cart/{userId}/add")
    public ResponseEntity<Map<String, Object>> addToCart(@PathVariable Long userId, @RequestParam Long productId, @RequestParam int quantity) {
        return ResponseEntity.ok(orderService.addToCart(userId, productId, quantity));
    }


    /**
     * GET /api/orders/cart/{userId}
     */
    @GetMapping("/cart/{userId}")
    public ResponseEntity<Map<Object, Object>> getCart(@PathVariable Long userId) {
        return ResponseEntity.ok(orderService.getCart(userId));
    }


    /**
     * DELETE /api/orders/cart/{userId}
     */
    @DeleteMapping("/cart/{userId}")
    public ResponseEntity<Void> clearCart(@PathVariable Long userId) {
        orderService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }


    /**
     * POST /api/orders/auth/login
     */
    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> creds) {
        Long userId = 123L;
        return ResponseEntity.ok(orderService.login(userId, "USER"));
    }

    /**
     * GET /api/orders/auth/validate  (Header: X-Auth-Token)
     */
    @GetMapping("/auth/validate")
    public ResponseEntity<?> validateToken(@RequestHeader("X-Auth-Token") String token) {
        Map<Object, Object> session = orderService.validateToken(token);
        if (session == null || session.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid or expired token"));
        }
        return ResponseEntity.ok(session);
    }

    /**
     * POST /api/orders/auth/logout  (Header: X-Auth-Token)
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@RequestHeader("X-Auth-Token") String token) {
        orderService.logout(token);
        return ResponseEntity.noContent().build();
    }


    /**
     * GET /api/orders/leaderboard/top/{n}
     */
    @GetMapping("/leaderboard/top/{n}")
    public ResponseEntity<List<Map<String, Object>>> getTopCustomers(@PathVariable int n) {
        return ResponseEntity.ok(orderService.getTopCustomers(n));
    }

    /**
     * GET /api/orders/dashboard/live
     */
    @GetMapping("/dashboard/live")
    public ResponseEntity<Map<String, Object>> getLiveDashboard() {
        return ResponseEntity.ok(orderService.getLiveDashboard());
    }

}