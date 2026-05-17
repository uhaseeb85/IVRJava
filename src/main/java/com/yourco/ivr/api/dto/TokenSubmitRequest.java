package com.yourco.ivr.api.dto;

import com.yourco.ivr.domain.TokenType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@Schema(description = "Request to submit a collected token value for validation")
public class TokenSubmitRequest {
    @Schema(description = "Type of token being submitted", example = "PIN")
    @NotNull
    private TokenType tokenType;

    @Schema(description = "Raw token value entered by the caller", example = "1234")
    @NotBlank
    private String tokenValue;
}