package com.yourco.ivr.api;

import com.yourco.ivr.api.dto.LookupServiceDescriptor;
import com.yourco.ivr.lookup.LookupServiceRegistry;
import com.yourco.ivr.lookup.TokenLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/lookup-services")
@Tag(name = "Lookup Services", description = "Discovery of available backend token verification services")
public class LookupServiceController {

    private final LookupServiceRegistry registry;

    public LookupServiceController(LookupServiceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    @Operation(summary = "List available lookup services",
        description = "Returns all registered backend verification services so the Brand Editor can "
            + "offer them per token type.")
    public ResponseEntity<List<LookupServiceDescriptor>> listAll() {
        List<LookupServiceDescriptor> result = new ArrayList<>();
        for (TokenLookupService svc : registry.all()) {
            result.add(new LookupServiceDescriptor(
                svc.id(), svc.displayName(), svc.description(),
                new ArrayList<>(svc.supportedTokens())));
        }
        return ResponseEntity.ok(result);
    }
}
