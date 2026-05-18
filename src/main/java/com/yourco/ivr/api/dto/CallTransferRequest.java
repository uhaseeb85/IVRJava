package com.yourco.ivr.api.dto;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.TokenType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@Schema(description = "Request to transfer a caller from an external system with pre-validated tokens")
public class CallTransferRequest {
    @Schema(description = "Identifier of the transferring system (e.g. LEGACY_IVR)", example = "LEGACY_IVR")
    @NotBlank
    private String sourceSystemId;

    @Schema(description = "Target brand in this system", example = "BRAND_A")
    @NotBlank
    private String brandId;

    @Schema(description = "Caller phone number or identifier", example = "5551234567")
    @NotBlank
    private String callerId;

    @Schema(description = "Highest auth level caller reached in the source system", example = "BASIC")
    private AuthLevel currentLevel;

    @Schema(description = "Auth level the caller needs to reach", example = "STANDARD")
    @NotNull
    private AuthLevel targetLevel;

    @Schema(description = "Token types already validated in the source system (no values needed)")
    private List<TokenType> validatedTokens;
}
