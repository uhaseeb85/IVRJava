package com.yourco.ivr.validator;

public class BrandTokenValidatorOverride {

    private final String brandId;
    private final TokenValidator validator;

    public BrandTokenValidatorOverride(String brandId, TokenValidator validator) {
        this.brandId = brandId;
        this.validator = validator;
    }

    public String getBrandId() {
        return brandId;
    }

    public TokenValidator getValidator() {
        return validator;
    }
}