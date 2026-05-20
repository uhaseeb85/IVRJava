package com.yourco.ivr.api.dto;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.TokenType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Unified request for all IVR authentication actions. Discriminated by which fields are present.")
public class AuthenticateRequest {
    @Schema(description = "Session ID (absent for start/transfer, required for token/escalate)")
    private String sessionId;

    @Schema(description = "Brand identifier (required for start/transfer)", example = "BRAND_A")
    private String brandId;

    @Schema(description = "Caller phone number or identifier (required for start/transfer)", example = "5551234567")
    private String callerId;

    @Schema(description = "Target authentication level (required for start/transfer/escalate)", example = "STANDARD")
    private AuthLevel targetLevel;

    @Schema(description = "Source system identifier — presence triggers transfer action", example = "LEGACY_IVR")
    private String sourceSystemId;

    @Schema(description = "Highest auth level reached in source system (transfer only)", example = "BASIC")
    private AuthLevel currentLevel;

    @Schema(description = "Token types already validated in the source system (transfer only)")
    private List<TokenType> validatedTokens;

    @Schema(description = "Type of token being submitted — presence triggers token validation", example = "PIN")
    private TokenType tokenType;

    @Schema(description = "Raw token value entered by the caller", example = "1234")
    private String tokenValue;

    @Schema(description = "Optional pre-collected tokens submitted at session start")
    private Map<TokenType, String> initialTokens;
}
