package org.damu.model;


import com.fasterxml.jackson.annotation.JsonFormat;

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
public class Order implements Serializable {

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
    public Order() {
    }
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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String v) {
        this.orderNumber = v;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long v) {
        this.userId = v;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String v) {
        this.customerName = v;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String v) {
        this.customerEmail = v;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> v) {
        this.items = v;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal v) {
        this.totalAmount = v;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal v) {
        this.discountAmount = v;
    }

    public BigDecimal getFinalAmount() {
        return finalAmount;
    }

    public void setFinalAmount(BigDecimal v) {
        this.finalAmount = v;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus v) {
        this.status = v;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String v) {
        this.statusReason = v;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public void setShippingAddress(String v) {
        this.shippingAddress = v;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String v) {
        this.city = v;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String v) {
        this.pincode = v;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime v) {
        this.createdAt = v;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime v) {
        this.updatedAt = v;
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(LocalDateTime v) {
        this.deliveredAt = v;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String v) {
        this.paymentMethod = v;
    }

    public boolean isPaid() {
        return isPaid;
    }

    public void setPaid(boolean v) {
        this.isPaid = v;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int v) {
        this.priority = v;
    }

    @Override
    public String toString() {
        return "Order{id=" + id + ", orderNumber='" + orderNumber + "', status=" + status + ", finalAmount=" + finalAmount + "}";
    }

    public enum OrderStatus {
        PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, REFUNDED
    }

    public static class OrderItem implements Serializable {
        private Long productId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;

        public OrderItem() {
        }

        public OrderItem(Long productId, String productName, int quantity, BigDecimal unitPrice) {
            this.productId = productId;
            this.productName = productName;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }

        // getters and setters
        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long v) {
            this.productId = v;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String v) {
            this.productName = v;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int v) {
            this.quantity = v;
        }

        public BigDecimal getUnitPrice() {
            return unitPrice;
        }

        public void setUnitPrice(BigDecimal v) {
            this.unitPrice = v;
        }

        public BigDecimal getSubtotal() {
            return subtotal;
        }

        public void setSubtotal(BigDecimal v) {
            this.subtotal = v;
        }
    }
}