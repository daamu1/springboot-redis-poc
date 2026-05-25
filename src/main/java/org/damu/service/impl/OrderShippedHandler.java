package org.damu.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

/**
 * Handler for SHIPPED orders — triggers email + tracking notification
 */
@Slf4j
@Service
public   class OrderShippedHandler implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody());
        log.info("[SHIPPED HANDLER] Received: {}", payload);

    }
}
