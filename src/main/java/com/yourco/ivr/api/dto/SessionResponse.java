package com.yourco.ivr.api.dto;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.domain.SessionStatus;
import com.yourco.ivr.domain.TokenType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response returned by all IVR session endpoints")
public class SessionResponse {
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

    @Schema(description = "List of token types the client is allowed to submit at this step (includes the nextRequiredToken plus any backup alternatives)")
    private List<TokenType> acceptedTokens;

    public static SessionResponse fromSession(IvrSession session) {
        return SessionResponse.builder()
            .sessionId(session.getSessionId())
            .status(session.getStatus())
            .currentLevel(session.getCurrentLevel())
            .targetLevel(session.getTargetLevel())
            .build();
    }
}