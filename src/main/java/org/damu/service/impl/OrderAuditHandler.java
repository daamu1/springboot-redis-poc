package org.damu.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

/**
 * Audit handler — logs all order events for compliance
 */
@Slf4j
@Service
public class OrderAuditHandler implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("[AUDIT] channel={} | payload={}", new String(message.getChannel()), new String(message.getBody()));
    }
}