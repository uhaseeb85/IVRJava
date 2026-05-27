package com.yourco.ivr.api.dto;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.CompositeRiskAssessment;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.RiskAssessment;
import com.yourco.ivr.domain.RiskLevel;
import com.yourco.ivr.domain.SessionPhase;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response returned by all IVR authentication endpoints")
public class AuthenticateResponse {
    @Schema(description = "Unique session identifier")
    private String sessionId;

    @Schema(description = "Current session status")
    private SessionStatus status;

    @Schema(description = "Highest auth level achieved so far")
    private AuthLevel currentLevel;

    @Schema(description = "Auth level the session is trying to reach")
    private AuthLevel targetLevel;

    @Schema(description = "Next token type the caller needs to provide (null when AUTHENTICATED or FAILED)")
    private TokenType nextRequiredToken;

    @Schema(description = "Remaining retry attempts for the current token")
    private Integer remainingAttempts;

    @Schema(description = "Human-readable IVR prompt text")
    private String prompt;

    @Schema(description = "When locked, the time until which the session is locked")
    private Instant lockedUntil;

    @Schema(description = "List of token types the client is allowed to submit at this step (includes the nextRequiredToken plus any backup alternatives)")
    private List<TokenType> acceptedTokens;

    @Schema(description = "Current session phase (DISAMBIGUATION or AUTHENTICATING)")
    private SessionPhase phase;

    @Schema(description = "ID of the matched party once disambiguation resolves (null otherwise)")
    private String matchedPartyId;

    @Schema(description = "Caller risk level assessed at session start (LOW / MEDIUM / HIGH / CRITICAL). "
        + "Null if risk assessment was not performed for this session.")
    private RiskLevel riskLevel;

    @Schema(description = "Per-provider risk signal breakdown, e.g. {\"PHONE\": \"HIGH\", \"DEVICE\": \"MEDIUM\"}. "
        + "Null when only a single provider is registered or the session predates composite risk.")
    private Map<String, RiskLevel> riskSignals;

    @Schema(description = "Step-by-step processing log (only present on token-submission requests). "
        + "Shows validation outcome, backup resolution, attempt counts, and path transitions. "
        + "Token values are never included.")
    private List<ProcessingEvent> processingLog;

    public static AuthenticateResponse fromSession(IvrSession session) {
        RiskAssessment risk = session.getRiskAssessment();
        Map<String, RiskLevel> signals = null;
        if (risk instanceof CompositeRiskAssessment) {
            signals = ((CompositeRiskAssessment) risk).getSignals();
        }
        return AuthenticateResponse.builder()
            .sessionId(session.getSessionId())
            .status(session.getStatus())
            .phase(session.getPhase())
            .currentLevel(session.getCurrentLevel())
            .targetLevel(session.getTargetLevel())
            .matchedPartyId(session.getMatchedParty() != null
                ? session.getMatchedParty().getPartyId() : null)
            .riskLevel(risk != null ? risk.getLevel() : null)
            .riskSignals(signals)
            .build();
    }
}
