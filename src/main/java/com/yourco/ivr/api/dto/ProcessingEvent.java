package com.yourco.ivr.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in the per-request processing log attached to every token submission
 * response.  The log narrates what the auth engine actually did so operators and
 * developers can understand the flow without touching a debugger.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single entry in the processing log for a token submission")
public class ProcessingEvent {

    /**
     * Severity level.  One of: {@code INFO}, {@code PASS}, {@code FAIL}, {@code WARN}.
     */
    @Schema(description = "Severity level: INFO | PASS | FAIL | WARN")
    private String level;

    /**
     * Human-readable description of what happened at this processing step.
     * Token <em>values</em> are never included — only token types and outcomes.
     */
    @Schema(description = "Human-readable description of the processing step. Token values are never logged.")
    private String message;
}
