package com.yourco.ivr.service;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.CallTransferRequest;
import com.yourco.ivr.api.dto.StartAuthenticateRequest;
import com.yourco.ivr.domain.*;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.domain.config.TransferPolicy;
import com.yourco.ivr.engine.AuthEngine;
import com.yourco.ivr.engine.DisambiguationEngine;
import com.yourco.ivr.exception.TransferNotAllowedException;
import com.yourco.ivr.exception.UnknownCallerException;
import com.yourco.ivr.partylookup.PartyLookupProvider;
import com.yourco.ivr.preference.CustomerPreferenceProvider;
import com.yourco.ivr.registry.BrandRulesRegistry;
import com.yourco.ivr.registry.TransferPoliciesRegistry;
import com.yourco.ivr.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application-layer orchestrator for the IVR authentication lifecycle.
 *
 * <p>This service is the single entry point called by {@link com.yourco.ivr.api.AuthenticateController}.
 * It owns session creation and delegates token evaluation to {@link AuthEngine}.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@link #start} — creates a new session, runs party lookup and optional disambiguation,
 *       processes any initial tokens, and returns the first prompt.</li>
 *   <li>{@link #transfer} — creates a session pre-loaded with tokens and an auth level carried
 *       over from another IVR system, subject to the configured {@link TransferPolicy}.</li>
 *   <li>{@link #submitToken} / {@link #submitTokenWithCaller} — delegates to the engine for
 *       format + backend validation and progress evaluation.</li>
 *   <li>{@link #escalate} — requests a higher auth level mid-session.</li>
 *   <li>{@link #getStatus} — returns the current session snapshot without mutating state.</li>
 *   <li>{@link #end} — deletes the session on hang-up.</li>
 * </ul>
 */
@Service
public class AuthenticateService {

    private final AuthEngine engine;
    private final SessionRepository sessionRepo;
    private final BrandRulesRegistry rulesRegistry;
    private final TransferPoliciesRegistry transferRegistry;
    private final PartyLookupProvider partyLookup;
    private final CustomerPreferenceProvider preferenceProvider;
    private final DisambiguationEngine disambiguationEngine;
    public AuthenticateService(AuthEngine engine, SessionRepository sessionRepo,
                               BrandRulesRegistry rulesRegistry,
                               TransferPoliciesRegistry transferRegistry,
                               PartyLookupProvider partyLookup,
                               CustomerPreferenceProvider preferenceProvider,
                               DisambiguationEngine disambiguationEngine) {
        this.engine = engine;
        this.sessionRepo = sessionRepo;
        this.rulesRegistry = rulesRegistry;
        this.transferRegistry = transferRegistry;
        this.partyLookup = partyLookup;
        this.preferenceProvider = preferenceProvider;
        this.disambiguationEngine = disambiguationEngine;
    }

    /**
     * Starts a new authentication session.
     *
     * <ol>
     *   <li>Looks up parties by ANI — throws {@link com.yourco.ivr.exception.UnknownCallerException}
     *       if the list is empty.</li>
     *   <li>If more than one party is found, the session enters
     *       {@link com.yourco.ivr.domain.SessionPhase#DISAMBIGUATION} and
     *       {@link DisambiguationEngine#start} is invoked.</li>
     *   <li>For a single-party result, customer preferences are loaded and the engine
     *       evaluates progress (possibly collecting any {@code initialTokens} first).</li>
     * </ol>
     *
     * @throws com.yourco.ivr.exception.UnknownBrandException if {@code req.getBrandId()} is not loaded
     * @throws com.yourco.ivr.exception.UnknownCallerException if no parties match the caller's ANI
     */
    public AuthenticateResponse start(StartAuthenticateRequest req) {
        BrandAuthConfig config = rulesRegistry.get(req.getBrandId());

        // ── Session creation ─────────────────────────────────────────────────
        IvrSession session = new IvrSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setBrandId(req.getBrandId());
        session.setCallerId(req.getCallerId());
        session.setCurrentLevel(AuthLevel.NONE);
        session.setTargetLevel(req.getTargetLevel());
        session.setStatus(SessionStatus.COLLECTING);
        session.setCreatedAt(Instant.now());
        session.setLastActivityAt(Instant.now());

        if (config.isIdentificationOnly()) {
            session.setTargetLevel(AuthLevel.NONE);
        }

        // ── Party lookup and disambiguation (always-on) ──────────────────────
        List<Party> parties = partyLookup.lookupByAni(req.getCallerId());

        if (parties.isEmpty()) {
            throw new UnknownCallerException(req.getCallerId());
        }

        session.setPhase(parties.size() > 1
            ? SessionPhase.DISAMBIGUATION : SessionPhase.AUTHENTICATING);
        session.setCandidateParties(parties);
        sessionRepo.save(session);

        if (parties.size() > 1) {
            AuthenticateResponse disResp = disambiguationEngine.start(session);
            if (session.getPhase() != SessionPhase.AUTHENTICATING) {
                return disResp;  // still narrowing parties — return the disambiguation prompt
            }
            // Disambiguation resolved to a single party. Identification-only brands with
            // NONE-level rules collect the provided identity tokens; everything else proceeds
            // straight to progress evaluation. (Normal brands do not consume initialTokens here.)
            if (config.isIdentificationOnly() && hasNoneRule(config) && hasInitialTokens(req)) {
                return processInitialTokens(session, config, req.getInitialTokens());
            }
            return engine.onPartyResolved(session, config);
        }

        // Single party — load preferences
        CustomerPreference prefs = preferenceProvider.getPreferences(
            parties.get(0).getPartyId(), session.getBrandId());
        session.setMatchedParty(parties.get(0));
        session.setCustomerPreferences(prefs);
        sessionRepo.save(session);

        // Identification-only brands collect tokens only when NONE-level rules define them.
        if (config.isIdentificationOnly()) {
            return hasNoneRule(config) && hasInitialTokens(req)
                ? processInitialTokens(session, config, req.getInitialTokens())
                : engine.onPartyResolved(session, config);
        }

        // Standard brands process any initial tokens provided at session start.
        return hasInitialTokens(req)
            ? processInitialTokens(session, config, req.getInitialTokens())
            : engine.onPartyResolved(session, config);
    }

    /** True if the brand defines NONE-level rules (identification-token collection). */
    private static boolean hasNoneRule(BrandAuthConfig config) {
        return config.getLevelRules() != null
            && config.getLevelRules().containsKey(AuthLevel.NONE);
    }

    /** True if the start request carried any initial tokens to pre-submit. */
    private static boolean hasInitialTokens(StartAuthenticateRequest req) {
        return req.getInitialTokens() != null && !req.getInitialTokens().isEmpty();
    }

    /**
     * Submits each initial token through the engine, short-circuiting if any submission
     * drives the session to a terminal {@link SessionStatus#FAILED} or
     * {@link SessionStatus#REDIRECT_TO_AGENT} state. Once all tokens are accepted, the
     * (re-read) session is evaluated for progress toward its target level.
     */
    private AuthenticateResponse processInitialTokens(IvrSession session, BrandAuthConfig config,
                                                      Map<TokenType, String> initialTokens) {
        for (Map.Entry<TokenType, String> entry : initialTokens.entrySet()) {
            AuthenticateResponse tokenResponse = engine.submitToken(
                session.getSessionId(), entry.getKey(), entry.getValue());
            if (tokenResponse.getStatus() == SessionStatus.FAILED
                    || tokenResponse.getStatus() == SessionStatus.REDIRECT_TO_AGENT) {
                return tokenResponse;
            }
        }
        IvrSession updatedSession = sessionRepo.getOrThrow(session.getSessionId());
        return engine.evaluateProgress(updatedSession, config);
    }

    /**
     * Creates a session from an inbound call transfer, importing pre-validated tokens from
     * the source system subject to its {@link com.yourco.ivr.domain.config.TransferPolicy}.
     *
     * <p>Steps:
     * <ol>
     *   <li>Validates that the source system is configured and enabled.</li>
     *   <li>Filters the presented tokens to the subset allowed by the policy's
     *       {@code honoredTokens} list.</li>
     *   <li>Caps the carried-over auth level at the policy's {@code maxHonoredLevel}.</li>
     *   <li>Creates a new session pre-populated with the filtered tokens and delegates to
     *       {@link AuthEngine#transferSession} for progress evaluation.</li>
     * </ol>
     *
     * @throws com.yourco.ivr.exception.TransferNotAllowedException if the source system is
     *         not configured or is disabled
     */
    public AuthenticateResponse transfer(CallTransferRequest req) {
        TransferPolicy policy = transferRegistry.get(req.getSourceSystemId());
        if (policy == null) {
            throw new TransferNotAllowedException(
                "Source system not configured: " + req.getSourceSystemId());
        }
        if (!policy.isEnabled()) {
            throw new TransferNotAllowedException(
                "Source system is disabled: " + req.getSourceSystemId());
        }

        // 2. Get target brand config
        BrandAuthConfig config = rulesRegistry.get(req.getBrandId());

        // 3. Filter validated tokens to only those honored by the policy
        List<TokenType> honoredTokens = new ArrayList<>();
        if (req.getValidatedTokens() != null) {
            for (TokenType tokenType : req.getValidatedTokens()) {
                if (transferRegistry.isTokenHonored(req.getSourceSystemId(), tokenType)) {
                    honoredTokens.add(tokenType);
                }
            }
        }

        // 4. Cap currentLevel at the policy's maxHonoredLevel
        AuthLevel transferredLevel = req.getCurrentLevel() != null ? req.getCurrentLevel() : AuthLevel.NONE;
        AuthLevel maxHonored = transferRegistry.getMaxHonoredLevel(req.getSourceSystemId());
        if (transferredLevel.getRank() > maxHonored.getRank()) {
            transferredLevel = maxHonored;
        }

        // 5. Create session
        IvrSession session = new IvrSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setBrandId(req.getBrandId());
        session.setCallerId(req.getCallerId());
        session.setCurrentLevel(transferredLevel);
        session.setTargetLevel(req.getTargetLevel());
        session.setStatus(SessionStatus.COLLECTING);
        session.setTransferredFrom(req.getSourceSystemId());
        session.setCreatedAt(Instant.now());
        session.setLastActivityAt(Instant.now());

        // 6. Hand off to engine to populate tokens and evaluate
        return engine.transferSession(session, config, honoredTokens);
    }

    /** Submits a token value for validation; delegates entirely to {@link AuthEngine#submitToken}. */
    public AuthenticateResponse submitToken(String sessionId, TokenType tokenType, String tokenValue) {
        return engine.submitToken(sessionId, tokenType, tokenValue);
    }

    /**
     * Submits a token value with optional caller verification.
     * The engine checks that {@code callerId} matches the session's registered caller before
     * accepting the token, protecting against session-ID enumeration attacks.
     */
    public AuthenticateResponse submitTokenWithCaller(String sessionId, TokenType tokenType,
                                                       String tokenValue, String callerId) {
        return engine.submitTokenWithCaller(sessionId, tokenType, tokenValue, callerId);
    }

    /**
     * Requests a mid-session upgrade to a higher auth level.
     *
     * @throws IllegalArgumentException if {@code targetLevel} is not higher than the current level
     */
    public AuthenticateResponse escalate(String sessionId, AuthLevel targetLevel) {
        return engine.escalate(sessionId, targetLevel);
    }

    /**
     * Returns a snapshot of the current session state without modifying it.
     * Used by the polling {@code GET /ivr/authenticate/{id}/status} endpoint.
     *
     * @throws com.yourco.ivr.exception.SessionNotFoundException if the session does not exist or is expired
     */
    public AuthenticateResponse getStatus(String sessionId) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        return AuthenticateResponse.fromSession(session);
    }

    /** Deletes the session record; called on hang-up via {@code DELETE /ivr/authenticate/{id}}. */
    public void end(String sessionId) {
        sessionRepo.delete(sessionId);
    }
}
