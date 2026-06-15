package com.yourco.ivr.lookup;

import com.yourco.ivr.domain.TokenType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the resilience guarantees of {@link LookupExecutor}: a hard per-call timeout, and a
 * per-service circuit breaker that opens after consecutive failures and recovers (half-open)
 * once its cooldown window elapses.
 */
class LookupExecutorTest {

    /** Lookup service whose latency / failure behaviour can be toggled per test. */
    private static final class ControllableLookup implements TokenLookupService {
        volatile long sleepMs = 0;
        volatile boolean fail = false;
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String id() {
            return "ctrl";
        }

        @Override
        public Set<TokenType> supportedTokens() {
            return EnumSet.allOf(TokenType.class);
        }

        @Override
        public LookupResult verify(LookupRequest request) {
            calls.incrementAndGet();
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted");
                }
            }
            if (fail) {
                throw new RuntimeException("backend down");
            }
            return LookupResult.ok();
        }
    }

    private static LookupRequest request() {
        return new LookupRequest(TokenType.PIN, "1234", "+15551230000", "acme", null, null);
    }

    @Test
    void successfulVerifyPassesThrough() {
        LookupExecutor executor = new LookupExecutor(1000, 4, 5, 30);
        ControllableLookup svc = new ControllableLookup();

        assertTrue(executor.verify(svc, request()).isVerified());
    }

    @Test
    void slowBackendIsCutOffAtTheTimeout() {
        LookupExecutor executor = new LookupExecutor(100, 4, 5, 30);
        ControllableLookup svc = new ControllableLookup();
        svc.sleepMs = 1000; // far longer than the 100ms timeout

        assertThrows(LookupUnavailableException.class, () -> executor.verify(svc, request()));
    }

    @Test
    void circuitOpensAfterThresholdAndShortCircuitsWithoutCallingBackend() {
        LookupExecutor executor = new LookupExecutor(1000, 4, 3, 30);
        ControllableLookup svc = new ControllableLookup();
        svc.fail = true;

        for (int i = 0; i < 3; i++) {
            assertThrows(LookupUnavailableException.class, () -> executor.verify(svc, request()));
        }
        int callsWhenOpened = svc.calls.get();
        assertEquals(3, callsWhenOpened);

        // Circuit is now open: the next call must short-circuit without touching the backend.
        assertThrows(LookupUnavailableException.class, () -> executor.verify(svc, request()));
        assertEquals(callsWhenOpened, svc.calls.get());
    }

    @Test
    void circuitRecoversAfterCooldownWindow() throws InterruptedException {
        LookupExecutor executor = new LookupExecutor(1000, 4, 2, 1); // 1s cooldown
        ControllableLookup svc = new ControllableLookup();
        svc.fail = true;

        for (int i = 0; i < 2; i++) {
            assertThrows(LookupUnavailableException.class, () -> executor.verify(svc, request()));
        }

        // Wait out the open window, then let the backend recover.
        Thread.sleep(1100);
        svc.fail = false;

        // Half-open trial call should be allowed through and succeed, closing the breaker.
        assertTrue(executor.verify(svc, request()).isVerified());
    }
}
