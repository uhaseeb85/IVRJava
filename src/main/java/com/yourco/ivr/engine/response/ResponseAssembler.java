package com.yourco.ivr.engine.response;

import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.Party;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;

import java.time.Instant;
import java.util.List;

/**
 * Builds pre-configured {@link AuthenticateResponse} objects from a session.
 *
 * <p>Centralises the repeated {@code baseResponse(session).status(...)...build()}
 * pattern that appears across {@link com.yourco.ivr.engine.AuthEngine} methods.
 */
public final class ResponseAssembler {

    private ResponseAssembler() {
    }

    public static AuthenticateResponse.AuthenticateResponseBuilder base(IvrSession session) {
        return AuthenticateResponse.builder()
            .sessionId(session.getSessionId())
            .phase(session.getPhase())
            .currentLevel(session.getCurrentLevel())
            .targetLevel(session.getTargetLevel())
            .matchedPartyId(matchedPartyId(session));
    }

    public static AuthenticateResponse collecting(IvrSession session,
                                                   TokenType nextRequired,
                                                   List<TokenType> acceptedTokens,
                                                   int remainingAttempts,
                                                   String prompt) {
        return base(session)
            .status(SessionStatus.COLLECTING)
            .nextRequiredToken(nextRequired)
            .remainingAttempts(remainingAttempts)
            .acceptedTokens(acceptedTokens)
            .prompt(prompt)
            .build();
    }

    public static AuthenticateResponse authenticated(IvrSession session, String message) {
        return base(session)
            .status(SessionStatus.AUTHENTICATED)
            .prompt(message)
            .build();
    }

    public static AuthenticateResponse failed(IvrSession session, String message) {
        return base(session)
            .status(SessionStatus.FAILED)
            .prompt(message)
            .build();
    }

    public static AuthenticateResponse redirectToAgent(IvrSession session,
                                                        Instant lockedUntil,
                                                        String message) {
        return base(session)
            .status(SessionStatus.REDIRECT_TO_AGENT)
            .lockedUntil(lockedUntil)
            .prompt(message)
            .build();
    }

    private static String matchedPartyId(IvrSession session) {
        Party party = session.getMatchedParty();
        return party != null ? party.getPartyId() : null;
    }
}