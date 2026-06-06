package com.yourco.ivr.lookup;

import com.yourco.ivr.validator.ValidationErrorCode;

/**
 * Outcome of a backend verification. Mirrors the spirit of
 * {@link com.yourco.ivr.validator.ValidationResult} but carries an optional safe detail string.
 */
public final class LookupResult {

    private final boolean verified;
    private final ValidationErrorCode code;
    private final String detail;

    private LookupResult(boolean verified, ValidationErrorCode code, String detail) {
        this.verified = verified;
        this.code = code;
        this.detail = detail;
    }

    public static LookupResult ok() {
        return new LookupResult(true, null, null);
    }

    public static LookupResult fail(ValidationErrorCode code) {
        return new LookupResult(false, code, null);
    }

    /** {@code detail} must be non-sensitive (no token values). */
    public static LookupResult fail(ValidationErrorCode code, String detail) {
        return new LookupResult(false, code, detail);
    }

    public boolean isVerified() { return verified; }

    public ValidationErrorCode getCode() { return code; }

    public String getDetail() { return detail; }
}
