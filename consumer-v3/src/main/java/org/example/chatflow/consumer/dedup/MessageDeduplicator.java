package org.example.chatflow.consumer.dedup;

/**
 * Cross-instance message deduplication gate.
 *
 * <p>Contract: return {@code true} if this instance should treat {@code messageId}
 * as the first time it is being processed; return {@code false} if it has already
 * been processed elsewhere and should be skipped.</p>
 *
 * <p>Callers should only invoke this for non-null {@code messageId} values.</p>
 */
public interface MessageDeduplicator {
    boolean tryClaimFirstProcess(String messageId);
}

