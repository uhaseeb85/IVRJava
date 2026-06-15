package com.yourco.ivr.lookup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs {@link TokenLookupService#verify} calls with a hard timeout and a per-service circuit
 * breaker, so a slow or failing backend can never hang the request (servlet) thread.
 *
 * <p><strong>Timeout:</strong> each verify runs on a bounded worker pool; the calling thread
 * waits at most {@code ivr.lookup.timeout-ms}. On timeout the worker is interrupted/cancelled and
 * a {@link LookupUnavailableException} is thrown.
 *
 * <p><strong>Circuit breaker:</strong> after {@code ivr.lookup.circuit.failure-threshold}
 * consecutive failures for a given service, the breaker opens for
 * {@code ivr.lookup.circuit.open-seconds}. While open, calls short-circuit immediately (no
 * backend hit) with a {@link LookupUnavailableException}. After the window the breaker goes
 * half-open: one trial call is allowed; success closes it, failure re-opens it.
 *
 * <p>Callers ({@link com.yourco.ivr.engine.validation.ExternalValidator}) translate the
 * exception into the binding's {@code failClosed} policy.
 */
@Component
public class LookupExecutor {

    private static final Logger log = LoggerFactory.getLogger(LookupExecutor.class);

    private final ExecutorService pool;
    private final long timeoutMs;
    private final int failureThreshold;
    private final long openMillis;
    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    public LookupExecutor(
            @Value("${ivr.lookup.timeout-ms:2000}") long timeoutMs,
            @Value("${ivr.lookup.pool-size:16}") int poolSize,
            @Value("${ivr.lookup.circuit.failure-threshold:5}") int failureThreshold,
            @Value("${ivr.lookup.circuit.open-seconds:30}") long openSeconds) {
        this.timeoutMs = timeoutMs;
        this.failureThreshold = failureThreshold;
        this.openMillis = TimeUnit.SECONDS.toMillis(openSeconds);
        this.pool = Executors.newFixedThreadPool(poolSize, daemonFactory());
    }

    /**
     * Verifies the request through {@code service}, enforcing the timeout and circuit breaker.
     *
     * @throws LookupUnavailableException if the circuit is open, the call times out, or the
     *         backend throws.
     */
    public LookupResult verify(TokenLookupService service, LookupRequest request) {
        String serviceId = service.id();
        Breaker breaker = breakers.computeIfAbsent(serviceId, k -> new Breaker());

        if (breaker.isOpen(openMillis)) {
            throw new LookupUnavailableException("Circuit open for lookup service '" + serviceId + "'");
        }

        Future<LookupResult> future = pool.submit(() -> service.verify(request));
        try {
            LookupResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            breaker.recordSuccess();
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            recordFailure(serviceId, breaker);
            throw new LookupUnavailableException(
                "Lookup service '" + serviceId + "' timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            recordFailure(serviceId, breaker);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new LookupUnavailableException(
                "Lookup service '" + serviceId + "' failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LookupUnavailableException(
                "Lookup service '" + serviceId + "' interrupted", e);
        }
    }

    private void recordFailure(String serviceId, Breaker breaker) {
        if (breaker.recordFailure(failureThreshold)) {
            log.warn("Circuit OPENED for lookup service '{}' after {} consecutive failures (cooldown {}ms)",
                serviceId, failureThreshold, openMillis);
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "lookup-exec-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Per-service circuit breaker state. Tracks consecutive failures and an "opened at" timestamp
     * ({@code 0} = closed). Transitions are driven entirely by {@link #recordSuccess()} and
     * {@link #recordFailure(int)}; {@link #isOpen(long)} also performs the open → half-open
     * transition once the cooldown elapses.
     */
    private static final class Breaker {
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile long openedAt = 0L;

        /**
         * @return {@code true} if the breaker is open and still within its cooldown window. Once
         *         the window has elapsed it transitions to half-open (returns {@code false}),
         *         allowing a single trial call through.
         */
        boolean isOpen(long openMillis) {
            long t = openedAt;
            if (t == 0L) {
                return false;
            }
            if (System.currentTimeMillis() - t >= openMillis) {
                openedAt = 0L; // half-open: let one trial through
                return false;
            }
            return true;
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
            openedAt = 0L;
        }

        /** @return {@code true} if this failure transitioned the breaker from closed to open. */
        boolean recordFailure(int threshold) {
            boolean wasClosed = openedAt == 0L;
            if (consecutiveFailures.incrementAndGet() >= threshold) {
                openedAt = System.currentTimeMillis();
                return wasClosed;
            }
            return false;
        }
    }
}
