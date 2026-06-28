package com.yourco.ivr.api;

import com.yourco.ivr.domain.ValidationResult;
import com.yourco.ivr.domain.config.BrandAuthConfig;
import com.yourco.ivr.service.BrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/brands")
@Tag(name = "Brand Config", description = "CRUD operations for brand configuration JSON files")
public class BrandController {

    private final BrandService brandService;

    public BrandController(BrandService brandService) {
        this.brandService = brandService;
    }

    @GetMapping
    @Operation(summary = "List all brands", description = "Returns all brand configurations from the external config directory")
    public ResponseEntity<List<BrandAuthConfig>> listAll() {
        return ResponseEntity.ok(brandService.listAll());
    }

    @GetMapping("/{brandId}")
    @Operation(summary = "Get a brand config", description = "Returns a single brand configuration by brand ID")
    public ResponseEntity<BrandAuthConfig> get(@PathVariable String brandId) {
        return ResponseEntity.ok(brandService.get(brandId));
    }

    @PostMapping
    @Operation(summary = "Create a new brand config", description = "Creates a new brand configuration JSON file")
    public ResponseEntity<BrandAuthConfig> create(@RequestBody BrandAuthConfig config) {
        return ResponseEntity.ok(brandService.save(config));
    }

    @PutMapping("/{brandId}")
    @Operation(summary = "Update a brand config", description = "Updates an existing brand configuration JSON file")
    public ResponseEntity<BrandAuthConfig> update(@PathVariable String brandId, @RequestBody BrandAuthConfig config) {
        return ResponseEntity.ok(brandService.update(brandId, config));
    }

    @DeleteMapping("/{brandId}")
    @Operation(summary = "Delete a brand config", description = "Deletes a brand configuration JSON file")
    public ResponseEntity<Void> delete(@PathVariable String brandId) {
        brandService.delete(brandId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate a brand config", description = "Validates a brand configuration without saving")
    public ResponseEntity<ValidationResult> validate(@RequestBody BrandAuthConfig config) {
        return ResponseEntity.ok(brandService.validate(config));
    }

    @PostMapping("/{brandId}/clone")
    @Operation(summary = "Clone a brand", description = "Clones an existing brand config under a new brand ID")
    public ResponseEntity<BrandAuthConfig> clone(@PathVariable String brandId, @RequestBody CloneRequest request) {
        return ResponseEntity.ok(brandService.clone(brandId, request.getNewBrandId()));
    }

    @GetMapping("/{brandId}/export")
    @Operation(summary = "Export a brand as JSON", description = "Returns the brand config as a downloadable JSON string")
    public ResponseEntity<String> export(@PathVariable String brandId) {
        return ResponseEntity.ok()
            .header("Content-Type", "application/json")
            .header("Content-Disposition", "attachment; filename=\"" + brandId.toLowerCase() + ".json\"")
            .body(brandService.exportAsJson(brandId));
    }

    @PostMapping("/import")
    @Operation(summary = "Import a brand from JSON", description = "Parses and saves a brand config from raw JSON string")
    public ResponseEntity<BrandAuthConfig> importBrand(@RequestBody ImportRequest request) {
        return ResponseEntity.ok(brandService.importFromJson(request.getJsonContent()));
    }

    // ── Inner DTOs ──────────────────────────────────────────────────────────

    static class CloneRequest {
        private String newBrandId;
        public String getNewBrandId() { return newBrandId; }
        public void setNewBrandId(String newBrandId) { this.newBrandId = newBrandId; }
    }

    static class ImportRequest {
        private String jsonContent;
        public String getJsonContent() { return jsonContent; }
        public void setJsonContent(String jsonContent) { this.jsonContent = jsonContent; }
    }
}