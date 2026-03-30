package org.example.chatflow.consumer.database;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Three-state circuit breaker for PostgreSQL write failures.
 *
 * <ul>
 *   <li>CLOSED  – normal operation; failures are counted</li>
 *   <li>OPEN    – writes rejected; entered after {@code failureThreshold} consecutive failures</li>
 *   <li>HALF_OPEN – one trial write allowed after {@code cooldownMs}; success → CLOSED, failure → OPEN</li>
 * </ul>
 */
public class DbCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<State> state    = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger          failures  = new AtomicInteger(0);
    private final AtomicLong             openedAt  = new AtomicLong(0);
    private final int                    failureThreshold;
    private final long                   cooldownMs;

    public DbCircuitBreaker(int failureThreshold, long cooldownMs) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs       = cooldownMs;
    }

    /**
     * Returns {@code true} when writes should be rejected (circuit is OPEN and cooldown
     * has not elapsed).  Automatically transitions OPEN → HALF_OPEN after cooldown.
     */
    public boolean isOpen() {
        State s = state.get();
        if (s == State.CLOSED || s == State.HALF_OPEN) return false;
        // OPEN: check if cooldown elapsed → allow one trial
        if (System.currentTimeMillis() - openedAt.get() >= cooldownMs) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
            return false;
        }
        return true;
    }

    /** Call after every successful batch write. */
    public void onSuccess() {
        State prev = state.getAndSet(State.CLOSED);
        failures.set(0);
        if (prev != State.CLOSED) {
            System.out.println("[DbCircuitBreaker] Circuit CLOSED — DB writes recovered.");
        }
    }

    /** Call after every failed batch write attempt. */
    public void onFailure() {
        State s = state.get();
        if (s == State.HALF_OPEN) {
            // Trial failed → reopen immediately
            openedAt.set(System.currentTimeMillis());
            state.set(State.OPEN);
            System.err.println("[DbCircuitBreaker] Trial write failed — circuit REOPENED.");
            return;
        }
        if (s == State.CLOSED) {
            int f = failures.incrementAndGet();
            if (f >= failureThreshold) {
                openedAt.set(System.currentTimeMillis());
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    System.err.println("[DbCircuitBreaker] Circuit OPENED after " + f + " failures.");
                }
            }
        }
    }

    public State getState()    { return state.get(); }
    public int   getFailures() { return failures.get(); }
}
