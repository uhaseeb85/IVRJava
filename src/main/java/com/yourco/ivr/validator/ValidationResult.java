package com.yourco.ivr.validator;

/**
 * Outcome of a token or brand-config validation.
 *
 * <p>Constructed via the static factory methods {@link #ok()}, {@link #fail(ValidationErrorCode)}
 * and {@link #error(String)}. Used as the return type of {@link TokenValidator#validate} and of
 * the two-stage validation pipeline in {@link com.yourco.ivr.engine.AuthEngine} — format results
 * and backend results are both expressed as a {@code ValidationResult} before being merged — and
 * by {@link com.yourco.ivr.service.BrandService#validate} for brand-config checks.
 *
 * @see com.yourco.ivr.lookup.LookupResult for the analogous backend-verification outcome
 */
public class ValidationResult {

    private final boolean valid;
    private final ValidationErrorCode errorCode;
    private final String message;

    private ValidationResult(boolean valid, ValidationErrorCode errorCode, String message) {
        this.valid = valid;
        this.errorCode = errorCode;
        this.message = message;
    }

    /** Returns a successful result. */
    public static ValidationResult ok() {
        return new ValidationResult(true, null, null);
    }

    /**
     * Returns a failed result with the given error code.
     *
     * @param code reason for failure; must not be {@code null}
     */
    public static ValidationResult fail(ValidationErrorCode code) {
        return new ValidationResult(false, code, null);
    }

    /**
     * Returns a failed result with a human-readable error message (e.g. brand-config validation).
     *
     * @param message reason for failure; must not be {@code null}
     */
    public static ValidationResult error(String message) {
        return new ValidationResult(false, null, message);
    }

    /** {@code true} if the token passed validation. */
    public boolean isValid() {
        return valid;
    }

    /** The failure reason code; {@code null} when {@link #isValid()} is {@code true}. */
    public ValidationErrorCode getErrorCode() {
        return errorCode;
    }

    /** The failure message; {@code null} when {@link #isValid()} is {@code true}. */
    public String getMessage() {
        return message;
    }
}
