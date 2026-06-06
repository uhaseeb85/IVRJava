package com.yourco.ivr.validator;

/**
 * Outcome of a {@link TokenValidator#validate} call.
 *
 * <p>Constructed via the static factory methods {@link #ok()} and {@link #fail(ValidationErrorCode)}.
 * This class is also used as the return type of the two-stage validation pipeline in
 * {@link com.yourco.ivr.engine.AuthEngine} — format results and backend results are both
 * expressed as a {@code ValidationResult} before being merged.
 *
 * @see com.yourco.ivr.lookup.LookupResult for the analogous backend-verification outcome
 */
public class ValidationResult {

    private final boolean valid;
    private final ValidationErrorCode errorCode;

    private ValidationResult(boolean valid, ValidationErrorCode errorCode) {
        this.valid = valid;
        this.errorCode = errorCode;
    }

    /** Returns a successful result. */
    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }

    /**
     * Returns a failed result with the given error code.
     *
     * @param code reason for failure; must not be {@code null}
     */
    public static ValidationResult fail(ValidationErrorCode code) {
        return new ValidationResult(false, code);
    }

    /** {@code true} if the token passed validation. */
    public boolean isValid() {
        return valid;
    }

    /** The failure reason; {@code null} when {@link #isValid()} is {@code true}. */
    public ValidationErrorCode getErrorCode() {
        return errorCode;
    }
}