package org.damu.service;

import org.damu.model.Order;

import java.util.List;
import java.util.Set;

public interface OrderQueueService {
    void enqueueOrder(Order order);

    Order dequeueOrder();

    Order blockingDequeue(long timeoutSeconds);

    Order reliableDequeue();

    void acknowledgeOrder(Order order);

    void nackOrder(Order order, String reason);

    List<Object> peekQueue(int count);

    Long getQueueDepth();

    void enqueuePriority(Order order);

    Order dequeueHighestPriority();

    Set<Object> getUrgentOrders();

    void scheduleOrderJob(Order order, long runAtEpochMs);

    void scheduleAutoCancelIfUnpaid(Order order, int delayMinutes);

    Set<Object> pollDueJobs();

    Long getScheduledCount();
}
