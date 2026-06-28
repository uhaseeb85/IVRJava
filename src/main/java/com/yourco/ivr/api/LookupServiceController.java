package com.yourco.ivr.api;

import com.yourco.ivr.lookup.LookupServiceRegistry;
import com.yourco.ivr.lookup.TokenLookupService;
import com.yourco.ivr.lookup.VerificationBindings;
import com.yourco.ivr.lookup.VerificationBinding;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/lookup-services")
@Tag(name = "Lookup Services", description = "Admin dashboard for backend token verification services")
public class LookupServiceController {

    private final LookupServiceRegistry registry;
    private final VerificationBindings verificationBindings;

    public LookupServiceController(LookupServiceRegistry registry,
                                   VerificationBindings verificationBindings) {
        this.registry = registry;
        this.verificationBindings = verificationBindings;
    }

    @GetMapping
    @Operation(summary = "List all lookup services", description = "Returns all registered backend verification services with their supported token types")
    public ResponseEntity<List<LookupServiceStatus>> listAll() {
        List<LookupServiceStatus> result = new ArrayList<>();
        for (TokenLookupService svc : registry.all()) {
            result.add(new LookupServiceStatus(
                svc.id(),
                svc.supportedTokens().stream().map(Enum::name).collect(Collectors.toList()),
                "REGISTERED"
            ));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/bindings")
    @Operation(summary = "List active bindings", description = "Returns which token types are currently bound to which lookup services")
    public ResponseEntity<Map<String, Object>> listBindings() {
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (com.yourco.ivr.domain.TokenType tokenType : com.yourco.ivr.domain.TokenType.values()) {
            VerificationBinding binding = verificationBindings.bindingFor("*", tokenType);
            if (binding != null) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("serviceId", binding.getServiceId());
                info.put("failClosed", binding.isFailClosed());
                info.put("params", binding.getParams());
                bindings.put(tokenType.name(), info);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bindings", bindings);
        result.put("totalBound", bindings.size());
        return ResponseEntity.ok(result);
    }

    // ── DTO ─────────────────────────────────────────────────────────────────

    static class LookupServiceStatus {
        private final String id;
        private final List<String> supportedTokens;
        private final String status;

        public LookupServiceStatus(String id, List<String> supportedTokens, String status) {
            this.id = id;
            this.supportedTokens = supportedTokens;
            this.status = status;
        }

        public String getId() { return id; }
        public List<String> getSupportedTokens() { return supportedTokens; }
        public String getStatus() { return status; }
    }
}
