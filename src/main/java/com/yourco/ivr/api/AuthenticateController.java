package com.yourco.ivr.api;

import com.yourco.ivr.api.dto.AuthenticateRequest;
import com.yourco.ivr.api.dto.AuthenticateResponse;
import com.yourco.ivr.api.dto.CallTransferRequest;
import com.yourco.ivr.api.dto.StartAuthenticateRequest;
import com.yourco.ivr.service.AuthenticateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ivr/authenticate")
@Tag(name = "IVR Authentication", description = "Unified endpoint for IVR authentication sessions")
public class AuthenticateController {

    private final AuthenticateService authenticateService;

    public AuthenticateController(AuthenticateService authenticateService) {
        this.authenticateService = authenticateService;
    }

    @Operation(summary = "Unified authentication action", description = "Handles all authentication actions. Discriminated by payload fields: no sessionId + no sourceSystemId = START, no sessionId + sourceSystemId = TRANSFER, sessionId + tokenType = TOKEN, sessionId + no tokenType + targetLevel = ESCALATE")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Action processed"),
        @ApiResponse(responseCode = "400", description = "Invalid request, unknown brand, or unknown caller"),
        @ApiResponse(responseCode = "403", description = "Transfer source not allowed or disabled"),
        @ApiResponse(responseCode = "404", description = "Session not found")
    })
    @PostMapping
    public ResponseEntity<AuthenticateResponse> handle(@RequestBody AuthenticateRequest req) {
        if (req.getSessionId() == null) {
            if (req.getSourceSystemId() != null) {
                CallTransferRequest transfer = new CallTransferRequest();
                transfer.setSourceSystemId(req.getSourceSystemId());
                transfer.setBrandId(req.getBrandId());
                transfer.setCallerId(req.getCallerId());
                transfer.setCurrentLevel(req.getCurrentLevel());
                transfer.setTargetLevel(req.getTargetLevel());
                transfer.setValidatedTokens(req.getValidatedTokens());
                return ResponseEntity.ok(authenticateService.transfer(transfer));
            }
            StartAuthenticateRequest start = new StartAuthenticateRequest();
            start.setBrandId(req.getBrandId());
            start.setCallerId(req.getCallerId());
            start.setTargetLevel(req.getTargetLevel());
            start.setInitialTokens(req.getInitialTokens());
            return ResponseEntity.ok(authenticateService.start(start));
        }
        if (req.getTokenType() != null) {
            return ResponseEntity.ok(
                authenticateService.submitToken(req.getSessionId(), req.getTokenType(), req.getTokenValue())
            );
        }
        return ResponseEntity.ok(
            authenticateService.escalate(req.getSessionId(), req.getTargetLevel())
        );
    }

    @Operation(summary = "Get session status", description = "Poll the current state of an authentication session.")
    @GetMapping("/{sessionId}/status")
    public ResponseEntity<AuthenticateResponse> status(
            @Parameter(description = "Session ID") @PathVariable String sessionId) {
        return ResponseEntity.ok(authenticateService.getStatus(sessionId));
    }

    @Operation(summary = "End session", description = "End / hang up an IVR session and clean up stored data.")
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> end(
            @Parameter(description = "Session ID") @PathVariable String sessionId) {
        authenticateService.end(sessionId);
        return ResponseEntity.noContent().build();
    }
}
