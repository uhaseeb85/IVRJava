package com.yourco.ivr.api.dto;

import com.yourco.ivr.domain.AuthLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@Schema(description = "Request to escalate to a higher authentication level")
public class EscalateRequest {
    @Schema(description = "Higher authentication level to reach", example = "ELEVATED")
    @NotNull
    private AuthLevel targetLevel;
}