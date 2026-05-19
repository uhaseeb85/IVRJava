package com.yourco.ivr.api.dto;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.CrossBrandTokenRecord;
import com.yourco.ivr.domain.TokenType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Map;

@Data
@Schema(description = "Request to start a new IVR authentication session")
public class StartAuthenticateRequest {
    @Schema(description = "Brand identifier (e.g. BRAND_A, BRAND_B)", example = "BRAND_A")
    @NotBlank
    private String brandId;

    @Schema(description = "Caller phone number or identifier", example = "5551234567")
    @NotBlank
    private String callerId;

    @Schema(description = "Target authentication level to reach", example = "STANDARD")
    @NotNull
    private AuthLevel targetLevel;

    @Schema(description = "Optional pre-validated tokens from another brand context")
    private Map<TokenType, CrossBrandTokenRecord> crossBrandTokens;

    @Schema(description = "Optional pre-collected token values submitted at session start (e.g. caller already entered account number before session began)")
    private Map<TokenType, String> initialTokens;
}
