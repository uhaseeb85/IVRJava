package com.yourco.ivr.domain;

import lombok.Data;

import java.time.Instant;
import java.util.*;

/**
 * Mutable runtime state of a single IVR authentication session.
 *
 * <p>This is the central object passed through the engine. It is a plain POJO — no JPA
 * annotations, no transactions. Every mutating operation in
 * {@link com.yourco.ivr.engine.AuthEngine} or
 * {@link com.yourco.ivr.engine.DisambiguationEngine} ends with an explicit
 * {@code sessionRepo.save(session)} call.
 *
 * <p>Sessions are persisted as a mix of scalar columns and JSON-encoded columns in SQLite by
 * {@link com.yourco.ivr.repository.SqliteSessionRepository}. The {@code version} field is used
 * for optimistic locking on updates — a concurrent modification throws
 * {@link com.yourco.ivr.exception.SessionConflictException}.
 *
 * <p><strong>Security note:</strong> {@code collectedTokens} holds raw token values in memory
 * only and is intentionally <em>never</em> written to the database column (the column is always
 * stored as {@code null}). Only {@code validatedTokens} (the set of successfully verified
 * {@link TokenType}s) is persisted.
 */
@Data
public class IvrSession {
    /** Globally unique session identifier (UUID). */
    private String sessionId;

    /** Brand this session belongs to; keys into {@link com.yourco.ivr.registry.BrandRulesRegistry}. */
    private String brandId;

    /** Caller ANI (Automatic Number Identification) used for party lookup. */
    private String callerId;

    /** Highest authentication level actually achieved so far; starts at {@link AuthLevel#NONE}. */
    private AuthLevel currentLevel;

    /** The authentication level the session is trying to reach. */
    private AuthLevel targetLevel;

    /** Current lifecycle state; see {@link SessionStatus} for valid transitions. */
    private SessionStatus status;

    /** Whether the session is in party disambiguation or credential collection. */
    private SessionPhase phase;

    /**
     * Raw token values submitted this session, keyed by type. Sensitive — never persisted.
     * Used transiently so validators can cross-reference other tokens (e.g. confirm an OTP
     * matches the account number provided earlier).
     */
    private Map<TokenType, String> collectedTokens;

    /** Token types that have passed both format and backend verification. Persisted. */
    private Set<TokenType> validatedTokens;

    /**
     * Per-token failure counts for the current path and target level. Keyed by the
     * <em>required</em> token slot (not the submitted backup type) to prevent retry-budget
     * bypass by cycling across backup alternatives.
     */
    private Map<TokenType, Integer> attemptCounts;

    /**
     * Tracks which fallback path index is active per authentication level. When the engine
     * exhausts retries on the current path it increments the index here to try the next
     * configured fallback path. Starts at 0 (first path) for each level.
     */
    private Map<AuthLevel, Integer> activePathIndexByLevel;

    /** Parties identified by ANI lookup; narrowed down during disambiguation. */
    private List<Party> candidateParties;

    /** The single party resolved after disambiguation completes; null during DISAMBIGUATION phase. */
    private Party matchedParty;

    /** Caller preferences loaded once the party is resolved; may restrict which tokens are offered. */
    private CustomerPreference customerPreferences;

    /** Number of disambiguation rounds already attempted; bounded by {@code DisambiguationConfig.maxDisambiguationTokens}. */
    private int disambiguationAttemptCount;

    /**
     * Optimistic-locking version counter. The repository {@code INSERT} sets this to 1;
     * each {@code UPDATE} increments it and asserts the previous version still matches.
     * A mismatch throws {@link com.yourco.ivr.exception.SessionConflictException}.
     */
    private int version;

    /** Source system ID when the session was created via a call transfer; null for direct sessions. */
    private String transferredFrom;

    /** Time until which the session is locked after exhausting retry attempts; null when not locked. */
    private Instant lockedUntil;

    /** Wall-clock time when the session was first created. */
    private Instant createdAt;

    /** Wall-clock time of the most recent activity; used to enforce session TTL. */
    private Instant lastActivityAt;

    public IvrSession() {
        this.currentLevel = AuthLevel.NONE;
        this.status = SessionStatus.COLLECTING;
        this.phase = SessionPhase.AUTHENTICATING;
        this.collectedTokens = new EnumMap<>(TokenType.class);
        this.validatedTokens = EnumSet.noneOf(TokenType.class);
        this.attemptCounts = new EnumMap<>(TokenType.class);
        this.activePathIndexByLevel = new EnumMap<>(AuthLevel.class);
        this.candidateParties = new ArrayList<>();
    }
}
