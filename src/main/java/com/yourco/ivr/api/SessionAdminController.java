package com.yourco.ivr.api;

import com.yourco.ivr.domain.IvrSession;
import com.yourco.ivr.engine.AuthEngine;
import com.yourco.ivr.repository.SessionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/sessions")
@Tag(name = "Session Admin", description = "Admin operations for inspecting active IVR sessions")
public class SessionAdminController {

    private final SessionRepository sessionRepo;
    private final AuthEngine authEngine;

    public SessionAdminController(SessionRepository sessionRepo, AuthEngine authEngine) {
        this.sessionRepo = sessionRepo;
        this.authEngine = authEngine;
    }

    @GetMapping
    @Operation(summary = "List active sessions", description = "Returns all non-expired sessions")
    public ResponseEntity<List<IvrSession>> listAll() {
        return ResponseEntity.ok(sessionRepo.listAll());
    }

    @GetMapping("/search")
    @Operation(summary = "Search sessions", description = "Search sessions by brand, status, or caller ID")
    public ResponseEntity<List<IvrSession>> search(
            @RequestParam(required = false) String brandId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String callerId) {
        return ResponseEntity.ok(sessionRepo.search(brandId, status, callerId));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get session detail", description = "Returns a single session's full state")
    public ResponseEntity<IvrSession> get(@PathVariable String sessionId) {
        return ResponseEntity.ok(sessionRepo.getOrThrow(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Force-end a session", description = "Admin-only: force-delete an active session")
    public ResponseEntity<Void> end(@PathVariable String sessionId) {
        sessionRepo.delete(sessionId);
        return ResponseEntity.noContent().build();
    }
}
