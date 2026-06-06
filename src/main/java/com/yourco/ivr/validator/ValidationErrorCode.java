package com.yourco.ivr.validator;

/**
 * Reason codes returned by {@link ValidationResult} and {@link com.yourco.ivr.lookup.LookupResult}
 * when validation or backend verification fails.
 *
 * <p>The engine logs the code name (never the token value) to the processing event log and
 * uses it to drive retry / path-switch decisions.
 */
public enum ValidationErrorCode {
    /** Token value does not meet format requirements (wrong length, unparseable, blank). */
    INVALID,
    /** Token has expired (e.g. OTP past its validity window). */
    EXPIRED,
    /** Token value was not found in the backend system of record. */
    NOT_FOUND,
    /** Backend rejected the request due to too many attempts from this caller. */
    RATE_LIMITED,
    /** An unexpected error occurred calling the backend; distinct from VERIFICATION_UNAVAILABLE. */
    EXTERNAL_ERROR,
    /** Backend call succeeded but the token did not match the customer record. */
    VERIFICATION_FAILED,
    /** Backend was unreachable (timeout or exception); behaviour depends on {@code failClosed} flag. */
    VERIFICATION_UNAVAILABLE
}