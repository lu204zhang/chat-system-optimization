package org.example.chatflow.consumer.dedup;

import java.util.concurrent.ConcurrentHashMap;

public class LocalMemoryMessageDeduplicator implements MessageDeduplicator {

    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();

    @Override
    public boolean tryClaimFirstProcess(String messageId) {
        if (messageId == null) {
            return true;
        }
        return seen.putIfAbsent(messageId, Boolean.TRUE) == null;
    }
}

