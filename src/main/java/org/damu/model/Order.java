package org.damu.model;


import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ════════════════════════════════════════════════════════════════
 * ORDER — The core domain class for ALL Redis use cases
 * ════════════════════════════════════════════════════════════════
 * <p>
 * Why Serializable?
 * -----------------
 * When Spring serializes this to Redis as JSON (via Jackson),
 * Serializable is not strictly needed — BUT if you ever switch
 * to JDK serialization (not recommended), the JVM needs it.
 * It's a good habit for any class you plan to store remotely.
 * <p>
 * Why no @RedisHash here?
 * -----------------------
 *
 * @author Saurabh Maithani(30 years Java)
 * @RedisHash is for Spring Data Redis repositories (Hash-based storage).
 * We're using RedisTemplate with JSON for full control — much better
 * for real-world apps. You'll see both approaches in the services.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Order implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    private Long id;
    private String orderNumber;
    private Long userId;
    private String customerName;
    private String customerEmail;
    private List<OrderItem> items = new ArrayList<>();
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal finalAmount;
    private OrderStatus status;
    private String statusReason;
    private String shippingAddress;
    private String city;
    private String pincode;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deliveredAt;
    private String paymentMethod;
    private boolean isPaid;
    private int priority;

    public static Order create(Long userId, String customerName, String customerEmail) {
        Order o = new Order();
        o.userId = userId;
        o.customerName = customerName;
        o.customerEmail = customerEmail;
        o.status = OrderStatus.PENDING;
        o.isPaid = false;
        o.priority = 1;
        o.createdAt = LocalDateTime.now();
        o.updatedAt = LocalDateTime.now();
        o.discountAmount = BigDecimal.ZERO;
        return o;
    }

    /**
     * Business method — recalculate totals after adding items
     */
    public void recalculate() {
        this.totalAmount = items.stream().map(OrderItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.finalAmount = totalAmount.subtract(discountAmount != null ? discountAmount : BigDecimal.ZERO);
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "Order{id=" + id + ", orderNumber='" + orderNumber + "', status=" + status + ", finalAmount=" + finalAmount + "}";
    }


}