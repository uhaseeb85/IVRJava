package com.yourco.ivr.api;

import com.yourco.ivr.domain.config.TransferPolicy;
import com.yourco.ivr.registry.TransferPoliciesRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transfer Policies", description = "CRUD operations for call transfer policy configuration")
public class TransferPoliciesController {

    private final TransferPoliciesRegistry registry;

    public TransferPoliciesController(TransferPoliciesRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    @Operation(summary = "List all transfer policies", description = "Returns all configured transfer policies")
    public ResponseEntity<List<TransferPolicy>> listAll() {
        return ResponseEntity.ok(registry.listAll());
    }

    @GetMapping("/{sourceSystemId}")
    @Operation(summary = "Get a transfer policy", description = "Returns a single transfer policy by source system ID")
    public ResponseEntity<TransferPolicy> get(@PathVariable String sourceSystemId) {
        TransferPolicy policy = registry.get(sourceSystemId);
        if (policy == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(policy);
    }

    @PutMapping
    @Operation(summary = "Save all transfer policies", description = "Replaces all transfer policies with the provided list")
    public ResponseEntity<List<TransferPolicy>> saveAll(@RequestBody List<TransferPolicy> policies) {
        registry.saveAll(policies);
        return ResponseEntity.ok(registry.listAll());
    }
}
