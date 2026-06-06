package com.yourco.ivr.api.dto;

import com.yourco.ivr.domain.TokenType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * UI-facing metadata describing an available lookup service. Returned by
 * {@code GET /api/lookup-services} to populate the Brand Editor dropdown.
 */
@Schema(description = "Metadata for an available backend lookup/verification service")
public class LookupServiceDescriptor {

    @Schema(description = "Stable service id stored in brand config", example = "stub-verify")
    private final String id;

    @Schema(description = "Human-readable label", example = "Stub Verifier (configurable)")
    private final String displayName;

    @Schema(description = "Helptext describing what the service verifies")
    private final String description;

    @Schema(description = "Token types this service can verify")
    private final List<TokenType> supportedTokens;

    public LookupServiceDescriptor(String id, String displayName, String description,
                                   List<TokenType> supportedTokens) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.supportedTokens = supportedTokens;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public List<TokenType> getSupportedTokens() { return supportedTokens; }
}
