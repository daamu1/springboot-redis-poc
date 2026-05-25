package org.damu.service.impl;

import org.damu.model.Order;
import org.damu.service.OrderQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ════════════════════════════════════════════════════════════════
 * USE CASE 3 — Order Queue (List-based FIFO)
 * USE CASE 4 — Priority Order Queue (ZSet-based)
 * ════════════════════════════════════════════════════════════════
 * <p>
 * SENIOR INSIGHT: Which queue type to pick?
 * ------------------------------------------
 * Redis List  → Simple FIFO/LIFO queue. Orders processed in sequence.
 * "normal" orders, email notifications, invoice generation.
 * <p>
 * Redis ZSet  → Priority queue. Score = urgency level.
 * "express delivery" jumps ahead of "standard delivery".
 * Also used for delayed job scheduling (score = run_at timestamp).
 * <p>
 * Redis Stream → Kafka-lite. Multiple consumers, consumer groups, replay.
 * Use when you need delivery guarantees or fan-out.
 * (Advanced — learn List/ZSet first)
 * <p>
 * Real-world recommendation:
 * - 0–100 orders/min   → Redis List is PERFECT
 * - 100–1000 orders/min → Redis ZSet for prioritization
 * - 1000+ orders/min   → Redis Streams or Kafka
 */
@Service
public class OrderQueueServiceImpl implements OrderQueueService {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueServiceImpl.class);
    private static final String QUEUE_PENDING = "queue:orders:pending";
    private static final String QUEUE_PROCESSING = "queue:orders:processing";
    private static final String QUEUE_PRIORITY = "queue:orders:priority";
    private static final String QUEUE_SCHEDULED = "queue:orders:scheduled";
    private static final String QUEUE_DEAD_LETTER = "queue:orders:failed";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * ENQUEUE — Producer pushes order to LEFT end of list.
     * <p>
     * List in Redis visualized:
     * [newest] ← LPUSH →  [order5, order4, order3, order2, order1]  → RPOP → [oldest]
     * <p>
     * LPUSH = push to left (tail insertion, newest at head)
     * RPOP  = pop from right (oldest first = FIFO)
     */
    @Override
    public void enqueueOrder(Order order) {
        redisTemplate.opsForList().leftPush(QUEUE_PENDING, order);
        log.info("Enqueued order {} | Queue size: {}", order.getOrderNumber(), redisTemplate.opsForList().size(QUEUE_PENDING));
    }

    /**
     * DEQUEUE — Consumer pops order from RIGHT (oldest = FIFO order).
     * <p>
     * Returns null if queue is empty. Your consumer thread should
     * poll with a small sleep, or better — use blockingRightPop.
     */
    @Override
    public Order dequeueOrder() {
        Order order = (Order) redisTemplate.opsForList().rightPop(QUEUE_PENDING);
        if (order != null) {
            log.info("Dequeued order {} for processing", order.getOrderNumber());
        }
        return order;
    }

    /**
     * BLOCKING DEQUEUE — The RIGHT way to implement a consumer.
     * <p>
     * Instead of polling (wasteful CPU + Redis connections), blocking pop
     * WAITS at Redis until a message arrives or timeout occurs.
     * <p>
     * This is how real message consumers work.
     * In your consumer thread: call this in a loop.
     * <p>
     * Timeout=0 → wait forever (use carefully, connection held open)
     * Timeout=5s → wait 5 seconds, return null if nothing, loop again
     */
    @Override
    public Order blockingDequeue(long timeoutSeconds) {
        Order order = (Order) redisTemplate.opsForList().rightPop(QUEUE_PENDING, timeoutSeconds, TimeUnit.SECONDS);

        if (order != null) {
            log.info("Blocking dequeue got order: {}", order.getOrderNumber());
            redisTemplate.opsForList().leftPush(QUEUE_PROCESSING, order);
        }
        return order;
    }

    /**
     * RELIABLE QUEUE PATTERN — Senior-level technique.
     * <p>
     * Problem with basic RPOP:
     * 1. Consumer pops order from queue
     * 2. Consumer crashes BEFORE processing
     * 3. Order is lost forever!
     * <p>
     * Solution — RightPopAndLeftPush (atomic move):
     * 1. Atomically pop from pending AND push to processing in ONE command
     * 2. If consumer crashes, order is still in processing queue
     * 3. A monitor thread can re-enqueue orders stuck in processing
     * <p>
     * This is the "RPOPLPUSH" pattern — one of Redis's most powerful.
     */
    @Override
    public Order reliableDequeue() {
        Order order = (Order) redisTemplate.opsForList().rightPopAndLeftPush(QUEUE_PENDING, QUEUE_PROCESSING);

        if (order != null) {
            log.info("Reliably dequeued order: {} (safe in processing queue)", order.getOrderNumber());
        }
        return order;
    }

    /**
     * ACK — mark order as successfully processed.
     * Remove from "processing" queue once done.
     */
    @Override
    public void acknowledgeOrder(Order order) {
        redisTemplate.opsForList().remove(QUEUE_PROCESSING, 1, order);
        log.info("ACK: order {} processed successfully", order.getOrderNumber());
    }

    /**
     * NACK — processing failed, send to dead-letter queue.
     * Ops team can inspect and replay failed orders.
     */
    @Override
    public void nackOrder(Order order, String reason) {
        redisTemplate.opsForList().remove(QUEUE_PROCESSING, 1, order);
        redisTemplate.opsForList().leftPush(QUEUE_DEAD_LETTER, order);
        log.error("NACK: order {} failed — reason: {}", order.getOrderNumber(), reason);
    }

    /**
     * Peek at queue without removing — for monitoring dashboards
     */
    @Override
    public List<Object> peekQueue(int count) {
        return redisTemplate.opsForList().range(QUEUE_PENDING, 0, count - 1);
    }

    @Override
    public Long getQueueDepth() {
        return redisTemplate.opsForList().size(QUEUE_PENDING);
    }


    /**
     * ZSet score = priority level.
     * Lower score = higher priority (we use zrangeByScore to get cheapest-score first).
     * <p>
     * Score mapping:
     * 1 = normal   (standard 5-day delivery)
     * 2 = express  (2-day delivery)
     * 3 = urgent   (same-day delivery)
     * <p>
     * ZADD will UPDATE score if member already exists — idempotent!
     */
    @Override
    public void enqueuePriority(Order order) {
        double score = order.getPriority(); // 1, 2, or 3
        redisTemplate.opsForZSet().add(QUEUE_PRIORITY, order, score);
        log.info("Priority queue: added order {} with priority={}", order.getOrderNumber(), score);
    }

    /**
     * Dequeue by HIGHEST priority (highest score first).
     * <p>
     * We use popMax — gets AND removes the highest-score element.
     * This is the Redis 5.0+ way (no Lua script needed).
     */
    @Override
    public Order dequeueHighestPriority() {
        TypedTuple<Object> tuple = redisTemplate.opsForZSet().popMax(QUEUE_PRIORITY);
        if (tuple != null) {
            Order order = (Order) tuple.getValue();
            log.info("Priority dequeue: order {} (priority={})", order.getOrderNumber(), tuple.getScore());
            return order;
        }
        return null;
    }

    /**
     * Process only URGENT orders (priority=3) first.
     * rangeByScore → elements within score range [minScore, maxScore]
     */
    @Override
    public Set<Object> getUrgentOrders() {
        return redisTemplate.opsForZSet().rangeByScore(QUEUE_PRIORITY, 3, 3);
    }


    /**
     * SCHEDULED QUEUE — one of the most powerful Redis patterns.
     * <p>
     * Score = epoch timestamp (milliseconds) when the job should run.
     * A scheduler thread polls: "give me all jobs where score <= NOW"
     * <p>
     * Use cases:
     * - Send "how was your order?" email 2 hours after delivery
     * - Auto-cancel unpaid orders after 30 minutes
     * - Retry failed payment after 10 minutes
     * - Send shipment reminder if not shipped in 24 hours
     */
    @Override
    public void scheduleOrderJob(Order order, long runAtEpochMs) {
        redisTemplate.opsForZSet().add(QUEUE_SCHEDULED, order, runAtEpochMs);
        log.info("Scheduled order {} job at epoch={}", order.getOrderNumber(), runAtEpochMs);
    }

    /**
     * Convenience — schedule auto-cancel if not paid within minutes
     */
    @Override
    public void scheduleAutoCancelIfUnpaid(Order order, int delayMinutes) {
        long runAt = System.currentTimeMillis() + (delayMinutes * 60_000L);
        scheduleOrderJob(order, runAt);
        log.info("Auto-cancel scheduled for order {} in {} minutes", order.getOrderNumber(), delayMinutes);
    }

    /**
     * POLL for due jobs — the scheduler thread calls this every 1–5 seconds.
     * Gets all jobs where score (run_at) <= current time.
     * Then removes them from the queue and processes.
     * <p>
     * This is how cron-like scheduling works in Redis without any cron jobs!
     */
    @Override
    public Set<Object> pollDueJobs() {
        long now = System.currentTimeMillis();
        // Range: score from 0 to now — all jobs due NOW or overdue
        Set<Object> dueJobs = redisTemplate.opsForZSet().rangeByScore(QUEUE_SCHEDULED, 0, now);

        if (!dueJobs.isEmpty()) {
            // Remove them from queue atomically
            redisTemplate.opsForZSet().removeRangeByScore(QUEUE_SCHEDULED, 0, now);
            log.info("Found {} due scheduled jobs", dueJobs.size());
        }

        return dueJobs;
    }

    /**
     * How many orders are waiting in the delayed queue?
     */
    @Override
    public Long getScheduledCount() {
        return redisTemplate.opsForZSet().size(QUEUE_SCHEDULED);
    }
}