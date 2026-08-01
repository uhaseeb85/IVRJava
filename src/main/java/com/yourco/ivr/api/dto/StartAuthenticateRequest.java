package com.yourco.ivr.api.dto;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.TokenType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
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

    @Schema(description = "Optional target authentication level. Ignored when the brand defines a levelDetermination section (the level is derived from call conditions); used as-is otherwise (legacy behavior).", example = "STANDARD")
    private AuthLevel targetLevel;

    @Schema(description = "Optional pre-collected token values submitted at session start (e.g. caller already entered account number before session began)")
    private Map<TokenType, String> initialTokens;
}
