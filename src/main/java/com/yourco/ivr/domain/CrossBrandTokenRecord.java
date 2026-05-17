package com.yourco.ivr.domain;

import java.time.Instant;

public class CrossBrandTokenRecord {

    private String sourceBrandId;
    private Instant validatedAt;

    public CrossBrandTokenRecord() {
    }

    public CrossBrandTokenRecord(String sourceBrandId, Instant validatedAt) {
        this.sourceBrandId = sourceBrandId;
        this.validatedAt = validatedAt;
    }

    public String getSourceBrandId() {
        return sourceBrandId;
    }

    public void setSourceBrandId(String sourceBrandId) {
        this.sourceBrandId = sourceBrandId;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public void setValidatedAt(Instant validatedAt) {
        this.validatedAt = validatedAt;
    }
}
