package com.yourco.ivr.api;

import com.yourco.ivr.api.dto.CallTransferRequest;
import com.yourco.ivr.api.dto.EscalateRequest;
import com.yourco.ivr.api.dto.SessionResponse;
import com.yourco.ivr.api.dto.StartSessionRequest;
import com.yourco.ivr.api.dto.TokenSubmitRequest;
import com.yourco.ivr.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/ivr/session")
@Tag(name = "IVR Session", description = "Endpoints for managing IVR authentication sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Operation(summary = "Start a new session", description = "Create a new IVR authentication session for a given brand and target auth level. Optionally accepts pre-validated cross-brand tokens.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Session created; first token prompt returned"),
        @ApiResponse(responseCode = "400", description = "Unknown brand or invalid request")
    })
    @PostMapping("/start")
    public ResponseEntity<SessionResponse> start(@RequestBody @Valid StartSessionRequest req) {
        return ResponseEntity.ok(sessionService.start(req));
    }

    @Operation(summary = "Submit a token", description = "Submit a collected token value (PIN, OTP, etc.) for validation against the current session's active path.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token processed; next prompt or authentication result returned"),
        @ApiResponse(responseCode = "404", description = "Session not found")
    })
    @PostMapping("/{sessionId}/token")
    public ResponseEntity<SessionResponse> submitToken(
            @Parameter(description = "Session ID returned from /start") @PathVariable String sessionId,
            @RequestBody @Valid TokenSubmitRequest req) {
        return ResponseEntity.ok(
            sessionService.submitToken(sessionId, req.getTokenType(), req.getTokenValue())
        );
    }

    @Operation(summary = "Escalate auth level", description = "Request a higher authentication level mid-session. Already-validated tokens are reused.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Target level updated; next token prompt returned"),
        @ApiResponse(responseCode = "400", description = "Target level must exceed current level")
    })
    @PostMapping("/{sessionId}/escalate")
    public ResponseEntity<SessionResponse> escalate(
            @Parameter(description = "Session ID") @PathVariable String sessionId,
            @RequestBody @Valid EscalateRequest req) {
        return ResponseEntity.ok(
            sessionService.escalate(sessionId, req.getTargetLevel())
        );
    }

    @Operation(summary = "Get session status", description = "Poll the current state of an authentication session.")
    @GetMapping("/{sessionId}/status")
    public ResponseEntity<SessionResponse> status(
            @Parameter(description = "Session ID") @PathVariable String sessionId) {
        return ResponseEntity.ok(sessionService.getStatus(sessionId));
    }

    @Operation(summary = "Transfer a call", description = "Accept a caller transferred from an external system with pre-validated tokens. Tokens are filtered per source policy and the caller's auth level is capped at the policy's max level.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transfer accepted; next token prompt returned"),
        @ApiResponse(responseCode = "400", description = "Unknown brand or invalid request"),
        @ApiResponse(responseCode = "403", description = "Source system not allowed or disabled")
    })
    @PostMapping("/transfer")
    public ResponseEntity<SessionResponse> transfer(@RequestBody @Valid CallTransferRequest req) {
        return ResponseEntity.ok(sessionService.transfer(req));
    }

    @Operation(summary = "End session", description = "End / hang up an IVR session and clean up stored data.")
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> end(
            @Parameter(description = "Session ID") @PathVariable String sessionId) {
        sessionService.end(sessionId);
        return ResponseEntity.noContent().build();
    }
}